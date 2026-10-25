package com.baer.handtype.feature.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.baer.handtype.BuildConfig
import com.baer.handtype.ml.GlyphPostProcessor
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Detects the four black corner fiducials on a printed [PracticeSheetGenerator] sheet, rectifies
 * the photographed page back to a known canonical layout, and crops one glyph bitmap per cell.
 *
 * The processor is tolerant to perspective distortion as long as all four fiducials are visible
 * and remain the dominant dark blobs in their respective quadrants of the captured photo.
 *
 * Pipeline:
 * 1. Downscale the input bitmap to a working size for fast pixel scans.
 * 2. Otsu threshold the working image to get a binary ink/paper mask.
 * 3. In each quadrant (TL/TR/BR/BL) flood-fill the largest dark connected component; treat its
 *    centroid as the corresponding fiducial centre.
 * 4. Map those four centroids to the canonical sheet corners via `Matrix.setPolyToPoly` and draw
 *    the rectified bitmap onto a uniform canvas.
 * 5. Crop each cell rect from the rectified bitmap and run [GlyphPostProcessor.cleanGlyph].
 */
object SheetSampleProcessor {

    private const val TAG = "SheetProcessor"
    private const val MAX_WORKING_DIM = 900
    private const val RECTIFIED_WIDTH = 1240
    private const val RECTIFIED_HEIGHT = 1754

    // Printed furniture (cell border, label) is erased with this margin around its known geometry
    // to absorb the residual bow the four-point rectification leaves inside a cell.
    private const val FURNITURE_MARGIN = 3

    // Paper at or above this luma is already effectively white; normalizing it would only
    // amplify sensor noise.
    private const val WHITE_POINT_MIN = 245

    // Guides print lighter than ink but clearly darker than paper; after white-point
    // normalization they measure ~200 against paper at ~253.
    private const val GUIDE_LUMA_MAX = 215
    private const val GUIDE_SEARCH_RADIUS = 20

    // Covers the ~15 px bow near the page bottom but stays below the 19 px that separates the
    // nearest 8-row and 9-row borders.
    private const val BORDER_SEARCH_RADIUS = 15

    // snapLine(): a printed rule darkens at least 5 of 6 segments of its span, by at least this
    // much below the window's paper level.
    private const val LINE_SEGMENTS = 6
    private const val LINE_SEGMENTS_MIN = 5
    private const val LINE_CONTRAST = 15
    // Pen ink below the paper level; a printed guide (luma ~198 on ~250 paper) stays above.
    private const val INK_ABOVE_CONTRAST = 60

    // detectGridLines(): a solid border darkens nearly all of the central band (a dashed guide
    // about 60%), and printed cells are equal within this fraction of the mean gap.
    private const val GRID_LINE_COVERAGE = 0.8f
    private const val GRID_GAP_TOLERANCE = 0.1f

    // plausibleFiducials(): opposite sides of the fiducial quad may differ by this factor (phone
    // tilt), and its height/width may deviate this much from the printed layout.
    private const val FIDUCIAL_SIDE_RATIO_MAX = 1.5f
    private const val FIDUCIAL_ASPECT_TOLERANCE = 0.35f

    data class SheetExtractionResult(
        val glyphs: Map<Char, Bitmap>,
        val missing: Set<Char>,
        val rectifiedDebugBitmap: Bitmap?,
        val detectionFailed: Boolean = false,
    )

    /**
     * Runs the full pipeline against [captured]. Throws if the sheet cannot be located.
     *
     * @param skipPageDetection When `true` the page-detection flood-fill is skipped and the full
     *   working-image bounds are used as the page region. Set this when [captured] is already a
     *   clean, perspective-corrected document scan (e.g. from ML Kit Document Scanner) so that
     *   the fiducial search spans the entire image rather than a potentially incomplete sub-rect.
     * @param localeCharacters The language row's letters ([PracticeSheetGenerator.localeCharacters])
     *   of the sheet the app printed; ignored when the photo shows a legacy 8-row sheet.
     */
    fun process(
        captured: Bitmap,
        debugContext: Context? = null,
        skipPageDetection: Boolean = false,
        localeCharacters: List<Char> = emptyList(),
    ): SheetExtractionResult {
        debugContext?.let { clearDebugDir(it); dumpDebugCaptured(it, captured) }
        val initial = processAttempt(
            captured = captured,
            debugContext = debugContext,
            skipPageDetection = skipPageDetection,
            localeCharacters = localeCharacters,
        )
        if (skipPageDetection && initial.glyphs.isEmpty()) {
            Log.w(
                TAG,
                "Skip-page-detection path produced no glyphs; retrying with page detection enabled",
            )
            return processAttempt(
                captured = captured,
                debugContext = debugContext,
                skipPageDetection = false,
                localeCharacters = localeCharacters,
            )
        }
        return initial
    }

    private fun processAttempt(
        captured: Bitmap,
        debugContext: Context? = null,
        skipPageDetection: Boolean,
        localeCharacters: List<Char>,
    ): SheetExtractionResult {
        val (working, scaleToOriginal) = downscale(captured, MAX_WORKING_DIM)
        val luma = computeLuma(working)

        // Step 1: locate the page rectangle.
        // For pre-corrected ML Kit images the flood-fill can fail to cover the whole sheet, so
        // we allow callers to skip it and use the full image bounds instead.
        val pageRect: PageRect
        val paperMask: BooleanArray?
        if (skipPageDetection) {
            pageRect = PageRect(0, 0, working.width - 1, working.height - 1)
            paperMask = null
            if (BuildConfig.DEBUG) Log.i(TAG, "Page detection skipped — using full image bounds (${working.width}x${working.height})")
        } else {
            val pageInfo = locatePage(luma, working.width, working.height)
            pageRect = pageInfo.rect
            paperMask = pageInfo.paperMask
            if (BuildConfig.DEBUG) Log.i(TAG, "Detected page rectangle (working coords): $pageRect")
        }
        debugContext?.let { dumpDebugPageRect(it, working, pageRect) }

        val mask = thresholdMaskInRect(luma, working.width, working.height, pageRect, paperMask)
        debugContext?.let { dumpDebugMask(it, working, mask) }

        val fiducials = findFiducials(mask, working.width, working.height, pageRect)?.takeIf(::plausibleFiducials)
        if (fiducials == null) {
            Log.e(TAG, "Failed to locate 4 fiducials.")
            return SheetExtractionResult(
                glyphs = emptyMap(),
                missing = emptySet(),
                rectifiedDebugBitmap = null,
                detectionFailed = true,
            )
        }
        val rectified = rectify(captured, fiducials, scaleToOriginal)
        debugContext?.let { dumpDebugRectified(it, rectified) }

        val glyphs = linkedMapOf<Char, Bitmap>()
        val missing = linkedSetOf<Char>()

        // Cell coords in PracticeSheetGenerator.normalizedCells are 0..1 over the *grid bounds*
        // (rectangle defined by the four fiducial CENTRES) — NOT over the full rectified bitmap.
        // rectify() maps fiducial centroids onto fiducialLayout, so the grid bounds in rectified
        // pixels are exactly the fiducial centre rectangle.
        val fl = PracticeSheetGenerator.fiducialLayout
        val gridLeftPx = fl.topLeft.first
        val gridTopPx = fl.topLeft.second
        val gridRightPx = fl.topRight.first
        val gridBottomPx = fl.bottomLeft.second
        val gridWidthPx = gridRightPx - gridLeftPx
        val gridHeightPx = gridBottomPx - gridTopPx

        val rectifiedLuma = computeLuma(rectified)
        val gridLines = detectGridLines(rectifiedLuma)
        val rows = gridLines?.let { it.second.size - 1 } ?: detectGridRows(rectifiedLuma)
        Log.i(TAG, "Detected $rows grid rows, printed lines ${if (gridLines != null) "found" else "not found"}")
        val cells = PracticeSheetGenerator.normalizedCells(PracticeSheetGenerator.cellCharacters(localeCharacters, rows))
        cells.forEachIndexed { index, cell ->
            if (cell.character == ' ') return@forEachIndexed
            // The nominal cell from the printed lines (or the grid geometry), then snapped onto the
            // printed borders: rectification bow moves a border by up to ~15 px near the page bottom.
            val col = index % PracticeSheetGenerator.GRID_COLS
            val row = index / PracticeSheetGenerator.GRID_COLS
            val nominalLeft = (gridLines?.first?.get(col) ?: (gridLeftPx + cell.left * gridWidthPx).toInt()).coerceIn(0, RECTIFIED_WIDTH - 2)
            val nominalTop = (gridLines?.second?.get(row) ?: (gridTopPx + cell.top * gridHeightPx).toInt()).coerceIn(0, RECTIFIED_HEIGHT - 2)
            val nominalRight = (gridLines?.first?.get(col + 1) ?: (gridLeftPx + cell.right * gridWidthPx).toInt()).coerceIn(nominalLeft + 1, RECTIFIED_WIDTH)
            val nominalBottom = (gridLines?.second?.get(row + 1) ?: (gridTopPx + cell.bottom * gridHeightPx).toInt()).coerceIn(nominalTop + 1, RECTIFIED_HEIGHT)
            val cellTop = snapBorderRow(rectifiedLuma, nominalTop, nominalLeft, nominalRight)
            val cellBottom = snapBorderRow(rectifiedLuma, nominalBottom, nominalLeft, nominalRight)
            val cellLeft = snapBorderColumn(rectifiedLuma, nominalLeft, cellTop, cellBottom)
            val cellRight = snapBorderColumn(rectifiedLuma, nominalRight, cellTop, cellBottom)

            val cellWidthPx = cellRight - cellLeft
            val cellHeightPx = cellBottom - cellTop
            // Inset to skip the printed grid border line only; eraseSheetFurniture() clears the
            // printed label and any border bleed that is left inside the crop.
            val topInset = (cellHeightPx * 0.03f).toInt().coerceAtLeast(3)
            val sideInset = (minOf(cellWidthPx, cellHeightPx) * 0.03f).toInt().coerceAtLeast(3)

            val cropLeft = cellLeft + sideInset
            val cropTop = cellTop + topInset
            val cropRight = cellRight - sideInset
            val cropBottom = cellBottom - sideInset

            val cellCrop = Bitmap.createBitmap(
                rectified,
                cropLeft,
                cropTop,
                cropRight - cropLeft,
                cropBottom - cropTop,
            )
            // Guides are full-width dashed lines light enough (luma 198) that per-cell Otsu keeps
            // them on the paper side. No pixel-level guide erasure: it severed strokes like the
            // 'H' crossbar into isolated components that the component filter then also rejected.
            val edgeCleanCellCrop = eraseSheetFurniture(
                normalizeWhitePoint(cellCrop),
                PracticeSheetGenerator.labelBounds(cell.character).apply { offset(-sideInset, -topInset) },
            )
            // The x-height guide and the baseline set the glyph scale; the dotted outer guides
            // are light enough for Otsu to keep on the paper side.
            val guideRatios = floatArrayOf(
                PracticeSheetGenerator.GUIDE_UPPER_RATIO,
                PracticeSheetGenerator.GUIDE_BASELINE_RATIO,
            )
            fun snappedGuideRows(ratios: FloatArray) = ratios
                .map { ratio -> (cellHeightPx * ratio).toInt() - topInset }
                .filter { it >= 0 && it < edgeCleanCellCrop.height }
                .map { snapGuideRow(edgeCleanCellCrop, it, guideSnapRadius(cellHeightPx)) }
                .toIntArray()
            val guideYsInCrop = snappedGuideRows(guideRatios)
            val outerGuideYsInCrop = snappedGuideRows(
                floatArrayOf(PracticeSheetGenerator.GUIDE_CAP_RATIO, PracticeSheetGenerator.GUIDE_DESCENDER_RATIO),
            )
            val cleaned = GlyphPostProcessor.cleanGlyph(
                edgeCleanCellCrop,
                guideYsInCrop,
                expectsDiacritic = GlyphPostProcessor.expectsDiacritic(cell.character),
                outerGuideYsInCrop = outerGuideYsInCrop,
                inkAboveCrop = inkAboveBorder(rectifiedLuma, RECTIFIED_WIDTH, cellTop, cropLeft, cropRight),
            )

            debugContext?.let { dumpCellCrop(it, index, cell.character, edgeCleanCellCrop, cleaned) }

            // Detect empty cells: require enough strongly-opaque pixels (real ink, not faint
            // residue from the printed cell border / label).
            if (hasInk(cleaned)) {
                glyphs[cell.character] = cleaned
            } else {
                missing += cell.character
            }
        }

        if (BuildConfig.DEBUG) Log.i(TAG, "Sheet processing complete: ${glyphs.size} glyphs extracted, ${missing.size} cells empty")
        return SheetExtractionResult(
            glyphs = glyphs,
            missing = missing,
            rectifiedDebugBitmap = rectified,
            detectionFailed = false,
        )
    }

    /**
     * Snaps a nominal guide row onto the printed line actually present in [crop]. The four-point
     * rectification leaves a few pixels of bow on a photographed sheet, so a guide can sit up to
     * [GUIDE_SEARCH_RADIUS] rows away from where the grid geometry puts it. Returns [nominalY]
     * unchanged when no row in range looks like a printed line.
     */
    private fun snapGuideRow(crop: Bitmap, nominalY: Int, radius: Int): Int =
        snapLine(computeLuma(crop), crop.width, nominalY, crop.height, 0 until crop.width, horizontal = true, radius = radius)

    /** Half the gap from a dashed guide to its dotted neighbour, so a snap never lands on the dotted line. */
    internal fun guideSnapRadius(cellHeightPx: Int): Int {
        val gap = minOf(
            PracticeSheetGenerator.GUIDE_UPPER_RATIO - PracticeSheetGenerator.GUIDE_CAP_RATIO,
            PracticeSheetGenerator.GUIDE_DESCENDER_RATIO - PracticeSheetGenerator.GUIDE_BASELINE_RATIO,
        )
        return minOf(GUIDE_SEARCH_RADIUS, (cellHeightPx * gap / 2).toInt())
    }

    /**
     * Rescales [cellCrop] so its own paper level maps to luma 255. A photographed sheet has an
     * arbitrary white point (a real scan measured paper at 178), while every ink threshold from
     * here on is calibrated against the generated sheet's white paper.
     */
    private fun normalizeWhitePoint(cellCrop: Bitmap): Bitmap {
        val width = cellCrop.width
        val height = cellCrop.height
        val pixels = IntArray(width * height)
        cellCrop.getPixels(pixels, 0, width, 0, 0, width, height)

        val histogram = IntArray(256)
        for (p in pixels) {
            val luma = (
                ((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114
                ) / 1000
            histogram[luma]++
        }
        // A cell is mostly paper, so the 90th percentile sits above the printed label and guides
        // but below any specular highlight.
        val target = (pixels.size * 0.90f).toInt()
        var seen = 0
        var paperLuma = 255
        for (l in 0..255) {
            seen += histogram[l]
            if (seen >= target) {
                paperLuma = l
                break
            }
        }
        if (paperLuma >= WHITE_POINT_MIN) return cellCrop

        val gain = 255f / paperLuma.coerceAtLeast(1)
        val lut = IntArray(256) { (it * gain).toInt().coerceAtMost(255) }
        for (i in pixels.indices) {
            val p = pixels[i]
            pixels[i] = (p.toLong() and 0xFF000000L).toInt() or
                (lut[(p shr 16) and 0xFF] shl 16) or
                (lut[(p shr 8) and 0xFF] shl 8) or
                lut[p and 0xFF]
        }
        val normalized = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        normalized.setPixels(pixels, 0, width, 0, 0, width, height)
        if (BuildConfig.DEBUG) Log.i(TAG, "White point normalized: paper luma $paperLuma -> 255")
        return normalized
    }

    /**
     * Cell rows of the rectified sheet in [luma]: the layout whose interior borders land on darker
     * grid-wide rows wins. Sheets printed before the language row have 8 rows, current ones 9.
     */
    internal fun detectGridRows(luma: IntArray): Int =
        listOf(PracticeSheetGenerator.GRID_ROWS, PracticeSheetGenerator.LEGACY_GRID_ROWS)
            .minBy { rows -> interiorBorderLuma(luma, rows) }

    /**
     * The printed cell borders of the rectified sheet in [luma] as (column xs, row ys), or null
     * when they do not form a clean, evenly spaced 8-column grid of a known row count. Sheets
     * printed by other generators put the grid elsewhere relative to the corner squares, beyond
     * what snapping a nominal border can reach. Coverage is measured over the central half of
     * the page only, where rectification bow cannot tilt a line out of one pixel column or row.
     */
    internal fun detectGridLines(luma: IntArray): Pair<List<Int>, List<Int>>? {
        val w = RECTIFIED_WIDTH
        val h = RECTIFIED_HEIGHT
        val bandX = w / 4 until w * 3 / 4
        val bandY = h / 4 until h * 3 / 4
        val sample = IntArray(bandX.count() * bandY.count() / 16)
        var i = 0
        for (y in bandY step 4) for (x in bandX step 4) if (i < sample.size) sample[i++] = luma[y * w + x]
        sample.sort()
        val darkMax = minOf(sample[i / 2] - LINE_CONTRAST, GUIDE_LUMA_MAX)

        fun lines(count: Int, coverage: (Int) -> Float): List<Int> {
            val centres = mutableListOf<Int>()
            var runStart = -1
            for (p in 0..count) {
                val dark = p < count && coverage(p) >= GRID_LINE_COVERAGE
                if (dark && runStart < 0) runStart = p
                if (!dark && runStart >= 0) {
                    centres += (runStart + p - 1) / 2
                    runStart = -1
                }
            }
            return centres
        }
        fun evenlySpaced(lines: List<Int>): Boolean {
            val gaps = lines.zipWithNext { a, b -> b - a }
            val mean = gaps.average()
            return gaps.all { abs(it - mean) <= mean * GRID_GAP_TOLERANCE }
        }

        val xs = lines(w) { x -> bandY.count { y -> luma[y * w + x] <= darkMax }.toFloat() / bandY.count() }
        val ys = lines(h) { y -> bandX.count { x -> luma[y * w + x] <= darkMax }.toFloat() / bandX.count() }
        if (xs.size != PracticeSheetGenerator.GRID_COLS + 1 || !evenlySpaced(xs)) return null
        if (ys.size - 1 !in setOf(PracticeSheetGenerator.GRID_ROWS, PracticeSheetGenerator.LEGACY_GRID_ROWS) || !evenlySpaced(ys)) {
            return null
        }
        return xs to ys
    }

    /** Mean, over the interior horizontal borders of a [rows]-row grid, of the darkest row near each. */
    private fun interiorBorderLuma(luma: IntArray, rows: Int): Float {
        val fl = PracticeSheetGenerator.fiducialLayout
        val gridWidthPx = fl.topRight.first - fl.topLeft.first
        val gridHeightPx = fl.bottomLeft.second - fl.topLeft.second
        val cells = PracticeSheetGenerator.normalizedCells(PracticeSheetGenerator.cellCharacters(rows = rows))
        val x0 = (fl.topLeft.first + cells.first().left * gridWidthPx).toInt()
        val x1 = (fl.topLeft.first + cells.last().right * gridWidthPx).toInt()
        return cells.map { it.top }.distinct().drop(1).map { top ->
            val nominalY = (fl.topLeft.second + top * gridHeightPx).toInt()
            val from = (nominalY - BORDER_SEARCH_RADIUS).coerceAtLeast(0)
            val to = (nominalY + BORDER_SEARCH_RADIUS).coerceAtMost(RECTIFIED_HEIGHT - 1)
            (from..to).minOf { y ->
                var sum = 0L
                for (x in x0 until x1) sum += luma[y * RECTIFIED_WIDTH + x]
                sum.toFloat() / (x1 - x0)
            }
        }.average().toFloat()
    }

    /**
     * Snaps a nominal line (a row when [horizontal], else a column, of an image with row [stride])
     * onto the printed rule within [GUIDE_SEARCH_RADIUS], measured along [span]. A rule, solid or
     * dashed, darkens every part of the span; a pen stroke only part of it, however dark, so a
     * candidate must reach [LINE_SEGMENTS_MIN] of [LINE_SEGMENTS] segments. Among those the line
     * with the most dark pixels wins; with none, [nominal] is the better guess.
     */
    internal fun snapLine(
        luma: IntArray,
        stride: Int,
        nominal: Int,
        limit: Int,
        span: IntRange,
        horizontal: Boolean,
        radius: Int = GUIDE_SEARCH_RADIUS,
    ): Int {
        val lines = (nominal - radius).coerceAtLeast(0)..(nominal + radius).coerceAtMost(limit - 1)
        if (lines.isEmpty() || span.isEmpty()) return nominal
        fun at(line: Int, p: Int) = luma[if (horizontal) line * stride + p else p * stride + line]
        // Paper level from the window itself: a dim photo has paper well below a printed sheet's.
        val window = IntArray(lines.count() * span.count())
        var i = 0
        for (line in lines) for (p in span) window[i++] = at(line, p)
        window.sort()
        val darkMax = minOf(window[window.size / 2] - LINE_CONTRAST, GUIDE_LUMA_MAX)
        val segmentLength = span.count().toFloat() / LINE_SEGMENTS
        var best = nominal
        var bestCount = 0
        for (line in lines) {
            var count = 0
            val hit = BooleanArray(LINE_SEGMENTS)
            for (p in span) {
                if (at(line, p) > darkMax) continue
                count++
                hit[((p - span.first) / segmentLength).toInt().coerceAtMost(LINE_SEGMENTS - 1)] = true
            }
            if (hit.count { it } >= LINE_SEGMENTS_MIN && count > bestCount) {
                bestCount = count
                best = line
            }
        }
        return best
    }

    /**
     * Columns of [x0] until [x1] with ink in the rows just above the border at [borderY]: a stroke
     * of the row above crossing into the cell. Printed guides are far lighter than pen ink.
     */
    internal fun inkAboveBorder(luma: IntArray, stride: Int, borderY: Int, x0: Int, x1: Int): BooleanArray {
        val rows = (borderY - 6).coerceAtLeast(0)..(borderY - 3)
        if (rows.isEmpty() || x1 <= x0) return BooleanArray(maxOf(0, x1 - x0))
        val window = IntArray(rows.count() * (x1 - x0))
        var i = 0
        for (y in rows) for (x in x0 until x1) window[i++] = luma[y * stride + x]
        window.sort()
        val inkMax = window[window.size / 2] - INK_ABOVE_CONTRAST
        return BooleanArray(x1 - x0) { dx -> rows.any { luma[it * stride + x0 + dx] < inkMax } }
    }

    private fun snapBorderRow(luma: IntArray, nominalY: Int, x0: Int, x1: Int): Int =
        snapLine(luma, RECTIFIED_WIDTH, nominalY, RECTIFIED_HEIGHT, x0 until x1, horizontal = true)

    private fun snapBorderColumn(luma: IntArray, nominalX: Int, y0: Int, y1: Int): Int =
        snapLine(luma, RECTIFIED_WIDTH, nominalX, RECTIFIED_WIDTH, y0 until y1, horizontal = false)

    /**
     * Whites out the printed furniture the crop still contains: the cell label at [labelBox]
     * (crop coordinates) and the border bleed along the crop edges, each grown by
     * [FURNITURE_MARGIN]. The geometry comes from the sheet generator, not from pixel luma, so a
     * dark label core can no longer survive as a stray component.
     */
    private fun eraseSheetFurniture(cellCrop: Bitmap, labelBox: Rect): Bitmap {
        val masked = cellCrop.copy(Bitmap.Config.ARGB_8888, true)
        val paint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val canvas = Canvas(masked)
        val w = masked.width.toFloat()
        val h = masked.height.toFloat()
        val m = FURNITURE_MARGIN.toFloat()
        canvas.drawRect(0f, 0f, m, h, paint)
        canvas.drawRect(w - m, 0f, w, h, paint)
        canvas.drawRect(0f, 0f, w, m, paint)
        canvas.drawRect(0f, h - m, w, h, paint)
        canvas.drawRect(labelBox.left - m, labelBox.top - m, labelBox.right + m, labelBox.bottom + m, paint)
        return masked
    }

    // ---------------------------------------------------------------------------------------------
    // Downscale & threshold helpers
    // ---------------------------------------------------------------------------------------------

    private fun downscale(src: Bitmap, maxDim: Int): Pair<Bitmap, Float> {
        val largest = maxOf(src.width, src.height)
        if (largest <= maxDim) return src to 1f
        val scale = maxDim.toFloat() / largest
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        return scaled to (1f / scale)
    }

    /** Returns a binary mask (`true` = ink) using Otsu thresholding on luminance. */
    private fun computeLuma(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val luma = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            luma[i] = (r * 299 + g * 587 + b * 114) / 1000
        }
        return luma
    }

    /**
     * Finds the largest bright region using a row/column projection scan. We assume the printed
     * sheet is the largest contiguous "bright" cluster in the photo, even with a dark background
     * like wood grain. The returned rectangle is in working-image coordinates and is expanded by
     * a small margin so the fiducial markers near the page edges are not clipped.
     */
    private data class PageRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private data class PageInfo(val rect: PageRect, val paperMask: BooleanArray?)

    private fun locatePage(luma: IntArray, width: Int, height: Int): PageInfo {
        // Decide a "bright" cutoff using a global Otsu over the whole image. Pixels above the
        // cutoff are treated as paper.
        val histogram = IntArray(256)
        for (l in luma) histogram[l]++
        val total = luma.size
        var sum = 0L
        for (t in 0..255) sum += (t * histogram[t]).toLong()
        var sumB = 0L
        var wB = 0
        var maxBetween = 0.0
        var threshold = 200
        for (t in 0..255) {
            wB += histogram[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += (t * histogram[t]).toLong()
            val mB = sumB.toDouble() / wB
            val mF = (sum - sumB).toDouble() / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > maxBetween) {
                maxBetween = between
                threshold = t
            }
        }
        val (brightCutoff, brightPeak) = pageBrightCutoff(histogram, threshold)
        if (BuildConfig.DEBUG) Log.i(TAG, "Page-detection bright cutoff: $brightCutoff (otsu=$threshold, brightPeak=$brightPeak)")

        // Build a "bright" mask and find the largest connected bright component on a 4-connected
        // grid. That component IS the paper (largest contiguous bright patch in the photo).
        val bright = BooleanArray(luma.size) { luma[it] >= brightCutoff }
        val visited = BooleanArray(luma.size)
        // Component-id per pixel (-1 = not in any component). We rebuild the page mask from
        // whichever id ended up being the largest, so it can be used to gate the in-page Otsu.
        val componentId = IntArray(luma.size) { -1 }
        var nextId = 0
        var bestId = -1
        var bestSize = 0
        var bestLeft = 0
        var bestRight = width - 1
        var bestTop = 0
        var bestBottom = height - 1
        val queueX = IntArray(luma.size)
        val queueY = IntArray(luma.size)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                if (visited[idx] || !bright[idx]) continue
                var head = 0
                var tail = 0
                queueX[tail] = x; queueY[tail] = y; tail++
                visited[idx] = true
                componentId[idx] = nextId
                var size = 0
                var minX = x; var maxX = x; var minY = y; var maxY = y
                while (head < tail) {
                    val cx = queueX[head]; val cy = queueY[head]; head++
                    size++
                    componentId[cy * width + cx] = nextId
                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy
                    if (cx > 0) {
                        val ni = cy * width + (cx - 1)
                        if (!visited[ni] && bright[ni]) { visited[ni] = true; queueX[tail] = cx - 1; queueY[tail] = cy; tail++ }
                    }
                    if (cx < width - 1) {
                        val ni = cy * width + (cx + 1)
                        if (!visited[ni] && bright[ni]) { visited[ni] = true; queueX[tail] = cx + 1; queueY[tail] = cy; tail++ }
                    }
                    if (cy > 0) {
                        val ni = (cy - 1) * width + cx
                        if (!visited[ni] && bright[ni]) { visited[ni] = true; queueX[tail] = cx; queueY[tail] = cy - 1; tail++ }
                    }
                    if (cy < height - 1) {
                        val ni = (cy + 1) * width + cx
                        if (!visited[ni] && bright[ni]) { visited[ni] = true; queueX[tail] = cx; queueY[tail] = cy + 1; tail++ }
                    }
                }
                if (size > bestSize) {
                    bestSize = size
                    bestId = nextId
                    bestLeft = minX; bestRight = maxX; bestTop = minY; bestBottom = maxY
                }
                nextId++
            }
        }

        if (bestSize < total * 0.10) {
            Log.w(TAG, "Largest bright region too small ($bestSize / $total); using full frame")
            return PageInfo(PageRect(0, 0, width - 1, height - 1), null)
        }

        // Paper mask = the largest bright component plus everything it encloses, so fiducials
        // and ink on the page are thresholded instead of dropped with the background.
        val paperMask = fillEnclosedHoles(BooleanArray(luma.size) { componentId[it] == bestId }, width, height)

        // Tiny inward margin so we don't include the page edge halo when thresholding.
        val margin = (minOf(width, height) * 0.005f).toInt().coerceAtLeast(1)
        val pageRect = PageRect(
            left = (bestLeft + margin).coerceAtMost(bestRight - 1),
            top = (bestTop + margin).coerceAtMost(bestBottom - 1),
            right = (bestRight - margin).coerceAtLeast(bestLeft + 1),
            bottom = (bestBottom - margin).coerceAtLeast(bestTop + 1),
        )
        if (BuildConfig.DEBUG) Log.i(TAG, "Largest bright region size=$bestSize, rect=$pageRect")
        return PageInfo(pageRect, paperMask)
    }

    /**
     * Cutoff above which a pixel counts as paper, plus the paper peak it was derived from.
     * Halfway between Otsu and the paper peak: a dim or shadowed sheet stays one component
     * while wood grain, which sits below Otsu, stays out.
     */
    internal fun pageBrightCutoff(histogram: IntArray, otsu: Int): Pair<Int, Int> {
        var brightPeak = otsu + 1
        for (t in (otsu + 1)..255) if (histogram[t] > histogram[brightPeak]) brightPeak = t
        val cutoff = ((otsu + brightPeak) / 2).coerceAtLeast(otsu + 10).coerceAtMost(245)
        return cutoff to brightPeak
    }

    /** Returns [mask] with every false region not connected to the frame border set to true. */
    internal fun fillEnclosedHoles(mask: BooleanArray, width: Int, height: Int): BooleanArray {
        val outside = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        var tail = 0
        fun seed(idx: Int) {
            if (!mask[idx] && !outside[idx]) { outside[idx] = true; queue[tail++] = idx }
        }
        for (x in 0 until width) { seed(x); seed((height - 1) * width + x) }
        for (y in 0 until height) { seed(y * width); seed(y * width + width - 1) }
        var head = 0
        while (head < tail) {
            val idx = queue[head++]
            val x = idx % width
            val y = idx / width
            if (x > 0) seed(idx - 1)
            if (x < width - 1) seed(idx + 1)
            if (y > 0) seed(idx - width)
            if (y < height - 1) seed(idx + width)
        }
        return BooleanArray(mask.size) { !outside[it] }
    }

    /**
     * Otsu threshold restricted to pixels inside [pageRect]. Pixels outside the rectangle are
     * marked as `false` so background clutter never participates in fiducial detection.
     */
    private fun thresholdMaskInRect(
        luma: IntArray,
        width: Int,
        height: Int,
        pageRect: PageRect,
        paperMask: BooleanArray?,
    ): BooleanArray {
        val histogram = IntArray(256)
        var pixelCount = 0
        for (y in pageRect.top..pageRect.bottom) {
            val rowOffset = y * width
            for (x in pageRect.left..pageRect.right) {
                val idx = rowOffset + x
                if (paperMask != null && !paperMask[idx]) continue
                histogram[luma[idx]]++
                pixelCount++
            }
        }
        var sum = 0L
        for (t in 0..255) sum += (t * histogram[t]).toLong()
        var sumB = 0L
        var wB = 0
        var maxBetween = 0.0
        var threshold = 127
        for (t in 0..255) {
            wB += histogram[t]
            if (wB == 0) continue
            val wF = pixelCount - wB
            if (wF == 0) break
            sumB += (t * histogram[t]).toLong()
            val mB = sumB.toDouble() / wB
            val mF = (sum - sumB).toDouble() / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > maxBetween) {
                maxBetween = between
                threshold = t
            }
        }
        if (BuildConfig.DEBUG) Log.i(TAG, "In-page Otsu threshold: $threshold over $pixelCount pixels")

        // The paper mask only gates the histogram: a fiducial at a shadowed page edge is not
        // enclosed by bright paper, so the mask is applied to the whole page rectangle and the
        // fiducial shape tiers reject what lies beyond the page.
        val mask = BooleanArray(luma.size)
        for (y in pageRect.top..pageRect.bottom) {
            val rowOffset = y * width
            for (x in pageRect.left..pageRect.right) {
                val idx = rowOffset + x
                mask[idx] = luma[idx] <= threshold
            }
        }
        return mask
    }

    // ---------------------------------------------------------------------------------------------
    // Fiducial localisation
    // ---------------------------------------------------------------------------------------------

    internal data class Fiducials(
        val tl: Pair<Float, Float>,
        val tr: Pair<Float, Float>,
        val br: Pair<Float, Float>,
        val bl: Pair<Float, Float>,
    )

    /**
     * Whether the four picked centres outline the printed sheet. On a photo without the squares
     * (a scan cropped to the grid) the picks are pen strokes, and rectifying onto them yields
     * nothing but rule pieces and smeared ink.
     */
    internal fun plausibleFiducials(f: Fiducials): Boolean {
        fun dist(a: Pair<Float, Float>, b: Pair<Float, Float>) = hypot(a.first - b.first, a.second - b.second)
        fun similar(a: Float, b: Float) = a > 0f && b > 0f && maxOf(a, b) / minOf(a, b) <= FIDUCIAL_SIDE_RATIO_MAX
        val top = dist(f.tl, f.tr)
        val bottom = dist(f.bl, f.br)
        val left = dist(f.tl, f.bl)
        val right = dist(f.tr, f.br)
        if (!similar(top, bottom) || !similar(left, right)) return false
        val fl = PracticeSheetGenerator.fiducialLayout
        val expected = (fl.bottomLeft.second - fl.topLeft.second) / (fl.topRight.first - fl.topLeft.first)
        val aspect = (left + right) / (top + bottom)
        return abs(aspect / expected - 1f) <= FIDUCIAL_ASPECT_TOLERANCE
    }

    /** Locate the best fiducial-shaped dark component in each quadrant; return their centroids. */
    private fun findFiducials(mask: BooleanArray, width: Int, height: Int, pageRect: PageRect? = null): Fiducials? {
        // Split quadrants at the PAPER centre, not the full-frame centre.  This handles sheets
        // that are off-centre in the photo (e.g. user held the phone slightly to one side) so
        // the four fiducials still land in the correct quadrant.
        val midX = if (pageRect != null) (pageRect.left + pageRect.right) / 2 else width / 2
        val midY = if (pageRect != null) (pageRect.top + pageRect.bottom) / 2 else height / 2
        // Run a single connected-component scan over the whole mask, then dispatch each component
        // to whichever quadrant its centroid falls into. This is more robust than scanning each
        // quadrant in isolation when a fiducial straddles the midpoint.
        val components = collectComponents(mask, width, height)
        if (BuildConfig.DEBUG) Log.i(TAG, "Found ${components.size} dark components in mask (after size filter). Centroids: " + components.map { it.centroid })

        val tl = pickFiducial(components, 0, 0, midX, midY, 0f, 0f) ?: return null
        val tr = pickFiducial(components, midX, 0, width, midY, width.toFloat(), 0f) ?: return null
        val br = pickFiducial(components, midX, midY, width, height, width.toFloat(), height.toFloat()) ?: return null
        val bl = pickFiducial(components, 0, midY, midX, height, 0f, height.toFloat()) ?: return null
        // A tight scanner crop can cut a square at the frame edge; its centroid then sits off the
        // true centre, which skews rectification. Rebuild such centres from the uncut side.
        val picked = listOf(tl, tr, br, bl)
        val sides = picked.flatMap { c ->
            listOfNotNull(
                c.bboxW.takeIf { c.minX > 0 && c.maxX < width - 1 },
                c.bboxH.takeIf { c.minY > 0 && c.maxY < height - 1 },
            )
        }.sorted()
        if (sides.isEmpty()) return Fiducials(tl.centroid, tr.centroid, br.centroid, bl.centroid)
        val half = sides[sides.size / 2] / 2f
        fun centre(c: Component): Pair<Float, Float> {
            val x = when {
                c.minX == 0 && c.maxX < width - 1 -> c.maxX + 1 - half
                c.maxX == width - 1 && c.minX > 0 -> c.minX + half
                else -> c.centroid.first
            }
            val y = when {
                c.minY == 0 && c.maxY < height - 1 -> c.maxY + 1 - half
                c.maxY == height - 1 && c.minY > 0 -> c.minY + half
                else -> c.centroid.second
            }
            return x to y
        }
        return Fiducials(centre(tl), centre(tr), centre(br), centre(bl))
    }

    private data class Component(
        val size: Int,
        val bboxW: Int,
        val bboxH: Int,
        val centroid: Pair<Float, Float>,
        val fillDensity: Float,
        val aspectRatio: Float,
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
    )

    private fun collectComponents(mask: BooleanArray, width: Int, height: Int): List<Component> {
        val visited = BooleanArray(width * height)
        val results = mutableListOf<Component>()
        // Fiducial in working image (~6.5% of long side); be permissive on the lower bound.
        val totalArea = width * height
        val minSize = (totalArea * 0.0003f).toInt().coerceAtLeast(40)
        val maxSize = (totalArea * 0.15f).toInt()
        val queueX = IntArray(width * height)
        val queueY = IntArray(width * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                if (visited[idx] || !mask[idx]) continue

                var head = 0
                var tail = 0
                queueX[tail] = x; queueY[tail] = y; tail++
                visited[idx] = true
                var size = 0
                var sumX = 0L
                var sumY = 0L
                var minBX = Int.MAX_VALUE
                var maxBX = Int.MIN_VALUE
                var minBY = Int.MAX_VALUE
                var maxBY = Int.MIN_VALUE
                while (head < tail) {
                    val cx = queueX[head]; val cy = queueY[head]; head++
                    size++; sumX += cx; sumY += cy
                    if (cx < minBX) minBX = cx
                    if (cx > maxBX) maxBX = cx
                    if (cy < minBY) minBY = cy
                    if (cy > maxBY) maxBY = cy
                    if (cx > 0) {
                        val ni = cy * width + (cx - 1)
                        if (!visited[ni] && mask[ni]) { visited[ni] = true; queueX[tail] = cx - 1; queueY[tail] = cy; tail++ }
                    }
                    if (cx < width - 1) {
                        val ni = cy * width + (cx + 1)
                        if (!visited[ni] && mask[ni]) { visited[ni] = true; queueX[tail] = cx + 1; queueY[tail] = cy; tail++ }
                    }
                    if (cy > 0) {
                        val ni = (cy - 1) * width + cx
                        if (!visited[ni] && mask[ni]) { visited[ni] = true; queueX[tail] = cx; queueY[tail] = cy - 1; tail++ }
                    }
                    if (cy < height - 1) {
                        val ni = (cy + 1) * width + cx
                        if (!visited[ni] && mask[ni]) { visited[ni] = true; queueX[tail] = cx; queueY[tail] = cy + 1; tail++ }
                    }
                }
                if (size < minSize || size > maxSize) {
                    if (BuildConfig.DEBUG) Log.v(TAG, "  Component filtered: size=$size (min=$minSize,max=$maxSize) centroid=(${sumX/size},${sumY/size})")
                    continue
                }
                val bboxW = maxBX - minBX + 1
                val bboxH = maxBY - minBY + 1
                val bboxArea = bboxW.toFloat() * bboxH
                if (bboxArea <= 0f) continue
                val fillDensity = size / bboxArea
                val aspectRatio = if (bboxW < bboxH) bboxW.toFloat() / bboxH else bboxH.toFloat() / bboxW
                results += Component(
                    size = size,
                    bboxW = bboxW,
                    bboxH = bboxH,
                    centroid = (sumX.toDouble() / size).toFloat() to (sumY.toDouble() / size).toFloat(),
                    fillDensity = fillDensity,
                    aspectRatio = aspectRatio,
                    minX = minBX,
                    minY = minBY,
                    maxX = maxBX,
                    maxY = maxBY,
                )
            }
        }
        return results
    }

    /**
     * Picks the most fiducial-like component whose centroid lies in the requested quadrant.
     * Among components that look like solid squares (fiducials are large filled squares), prefers
     * the one whose centroid is *closest to the actual corner* — handwriting blobs in the centre
     * of the page can have similar density/aspect to a fiducial, so corner proximity is the most
     * reliable disambiguator.
     */
    private fun pickFiducial(
        components: List<Component>,
        regionLeft: Int,
        regionTop: Int,
        regionRight: Int,
        regionBottom: Int,
        cornerX: Float,
        cornerY: Float,
    ): Component? {
        val inRegion = components.filter { c ->
            val (cx, cy) = c.centroid
            cx in regionLeft.toFloat()..regionRight.toFloat() && cy in regionTop.toFloat()..regionBottom.toFloat()
        }
        if (inRegion.isEmpty()) return null

        fun distToCorner(c: Component): Float {
            val dx = c.centroid.first - cornerX
            val dy = c.centroid.second - cornerY
            return dx * dx + dy * dy
        }

        // Tier 1: solid square-ish fiducial under good light. Pick CLOSEST to corner.
        val tier1 = inRegion.filter { it.fillDensity >= 0.55f && it.aspectRatio >= 0.7f }
        if (tier1.isNotEmpty()) return tier1.minByOrNull { distToCorner(it) }

        // Tier 2: relaxed (poorly inked print, perspective skew).
        val tier2 = inRegion.filter { it.fillDensity >= 0.4f && it.aspectRatio >= 0.55f }
        if (tier2.isNotEmpty()) return tier2.minByOrNull { distToCorner(it) }

        // Tier 3: any reasonably compact blob, still preferring corner proximity.
        val tier3 = inRegion.filter { it.fillDensity >= 0.25f && it.aspectRatio >= 0.4f }
        if (tier3.isNotEmpty()) return tier3.minByOrNull { distToCorner(it) }

        // Last resort: blob closest to the corner regardless of shape.
        return inRegion.minByOrNull { distToCorner(it) }
    }

    // ---------------------------------------------------------------------------------------------
    // Paper-corner localisation (primary path) and perspective rectification
    // ---------------------------------------------------------------------------------------------

    /**
     * Rectifies the captured photo so that the four detected paper corners map to the four
     * corners of the canonical practice-sheet bitmap (0,0 .. PAGE_WIDTH,PAGE_HEIGHT). After
     * this, every cell on the practice sheet sits at its known canonical pixel position.
     */
    private fun rectifyPage(original: Bitmap, paper: PaperCornerDetector.Corners, scaleToOriginal: Float): Bitmap {
        val srcPoints = floatArrayOf(
            paper.topLeft.x * scaleToOriginal, paper.topLeft.y * scaleToOriginal,
            paper.topRight.x * scaleToOriginal, paper.topRight.y * scaleToOriginal,
            paper.bottomRight.x * scaleToOriginal, paper.bottomRight.y * scaleToOriginal,
            paper.bottomLeft.x * scaleToOriginal, paper.bottomLeft.y * scaleToOriginal,
        )
        val dstPoints = floatArrayOf(
            0f, 0f,
            RECTIFIED_WIDTH.toFloat(), 0f,
            RECTIFIED_WIDTH.toFloat(), RECTIFIED_HEIGHT.toFloat(),
            0f, RECTIFIED_HEIGHT.toFloat(),
        )

        val matrix = Matrix()
        if (!matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)) {
            error("Could not compute perspective transform from paper corner points.")
        }

        val rectified = Bitmap.createBitmap(RECTIFIED_WIDTH, RECTIFIED_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(rectified)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        canvas.drawBitmap(original, matrix, paint)
        return rectified
    }

    // ---------------------------------------------------------------------------------------------
    // Legacy fiducial-based perspective rectification (kept but unused)
    // ---------------------------------------------------------------------------------------------

    private fun rectify(original: Bitmap, fiducials: Fiducials, scaleToOriginal: Float): Bitmap {
        val srcPoints = floatArrayOf(
            fiducials.tl.first * scaleToOriginal, fiducials.tl.second * scaleToOriginal,
            fiducials.tr.first * scaleToOriginal, fiducials.tr.second * scaleToOriginal,
            fiducials.br.first * scaleToOriginal, fiducials.br.second * scaleToOriginal,
            fiducials.bl.first * scaleToOriginal, fiducials.bl.second * scaleToOriginal,
        )
        // Destination corners are the centres of the four fiducial squares on the canonical sheet.
        val dst = PracticeSheetGenerator.fiducialLayout
        val dstPoints = floatArrayOf(
            dst.topLeft.first, dst.topLeft.second,
            dst.topRight.first, dst.topRight.second,
            dst.bottomRight.first, dst.bottomRight.second,
            dst.bottomLeft.first, dst.bottomLeft.second,
        )

        val matrix = Matrix()
        if (!matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)) {
            error("Could not compute perspective transform from fiducial points.")
        }

        val rectified = Bitmap.createBitmap(RECTIFIED_WIDTH, RECTIFIED_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(rectified)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        canvas.drawBitmap(original, matrix, paint)
        return rectified
    }

    // ---------------------------------------------------------------------------------------------
    // Empty-cell detection
    // ---------------------------------------------------------------------------------------------

    private fun hasInk(glyph: Bitmap): Boolean {
        val w = glyph.width
        val h = glyph.height
        if (w <= 0 || h <= 0) return false
        val pixels = IntArray(w * h)
        glyph.getPixels(pixels, 0, w, 0, 0, w, h)
        // Sum total alpha energy across the canvas. Thin strokes produce fewer pixels but at
        // meaningful alpha; bold strokes produce more. A threshold of 50 fully-opaque pixel
        // equivalents (50×255=12750) catches even the thinnest characters ('i', '1', 'l')
        // while excluding blank cells (alphaSum=0 after cleanGlyph fix) and faint label residue.
        var alphaSum = 0L
        for (p in pixels) {
            alphaSum += (p ushr 24)
        }
        return alphaSum > 50L * 255
    }

    private const val MIN_OPACITY_FOR_INK = 200

    // ---------------------------------------------------------------------------------------------
    // Debug helpers (write artifacts to filesDir/sheet_debug for adb pull inspection)
    // ---------------------------------------------------------------------------------------------

    private fun debugDir(context: Context): File =
        File(context.externalCacheDir, "sheet_debug").apply { if (!exists()) mkdirs() }

    private fun clearDebugDir(context: Context) {
        try {
            val dir = File(context.externalCacheDir, "sheet_debug")
            dir.deleteRecursively()
            dir.mkdirs()
            Log.i(TAG, "Cleared debug dir")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to clear debug dir", t)
        }
    }

    private fun dumpDebugCaptured(context: Context, captured: Bitmap) {
        try {
            FileOutputStream(File(debugDir(context), "00_captured.png")).use {
                captured.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            Log.i(TAG, "Wrote captured bitmap (${captured.width}x${captured.height})")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to dump captured bitmap", t)
        }
    }

    private fun dumpDebugMask(context: Context, working: Bitmap, mask: BooleanArray) {
        try {
            val w = working.width
            val h = working.height
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(w * h)
            for (i in mask.indices) pixels[i] = if (mask[i]) Color.BLACK else Color.WHITE
            out.setPixels(pixels, 0, w, 0, 0, w, h)
            FileOutputStream(File(debugDir(context), "01_mask.png")).use {
                out.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            FileOutputStream(File(debugDir(context), "00_working.png")).use {
                working.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            Log.i(TAG, "Wrote debug mask + working bitmap (${w}x${h})")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to dump debug mask", t)
        }
    }

    private fun dumpDebugRectified(context: Context, rectified: Bitmap) {
        try {
            FileOutputStream(File(debugDir(context), "02_rectified.png")).use {
                rectified.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            Log.i(TAG, "Wrote debug rectified bitmap")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to dump rectified bitmap", t)
        }
    }

    private fun dumpDebugPageRect(context: Context, working: Bitmap, rect: PageRect) {
        try {
            val out = working.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.RED
                style = Paint.Style.STROKE
                strokeWidth = 4f
            }
            canvas.drawRect(
                rect.left.toFloat(),
                rect.top.toFloat(),
                rect.right.toFloat(),
                rect.bottom.toFloat(),
                paint,
            )
            FileOutputStream(File(debugDir(context), "00b_pagerect.png")).use {
                out.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            Log.i(TAG, "Wrote debug page-rect overlay")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to dump page rect", t)
        }
    }

    private fun dumpCellCrop(
        context: Context,
        index: Int,
        character: Char,
        raw: Bitmap,
        cleaned: Bitmap,
    ) {
        try {
            val cellsDir = File(debugDir(context), "cells").apply { if (!exists()) mkdirs() }
            val safeChar = if (character.isLetterOrDigit()) character.toString() else "x"
            FileOutputStream(File(cellsDir, "%02d_%s_raw.png".format(index, safeChar))).use {
                raw.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            FileOutputStream(File(cellsDir, "%02d_%s_cleaned.png".format(index, safeChar))).use {
                cleaned.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to dump cell crop $index/$character", t)
        }
    }
}
