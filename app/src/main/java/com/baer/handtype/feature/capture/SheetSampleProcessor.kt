package com.baer.handtype.feature.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import com.baer.handtype.ml.GlyphPostProcessor
import java.io.File
import java.io.FileOutputStream

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
     */
    fun process(
        captured: Bitmap,
        debugContext: Context? = null,
        skipPageDetection: Boolean = false,
    ): SheetExtractionResult {
        debugContext?.let { clearDebugDir(it); dumpDebugCaptured(it, captured) }
        val initial = processAttempt(
            captured = captured,
            debugContext = debugContext,
            skipPageDetection = skipPageDetection,
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
            )
        }
        return initial
    }

    private fun processAttempt(
        captured: Bitmap,
        debugContext: Context? = null,
        skipPageDetection: Boolean,
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
            Log.i(TAG, "Page detection skipped — using full image bounds (${working.width}x${working.height})")
        } else {
            val pageInfo = locatePage(luma, working.width, working.height)
            pageRect = pageInfo.rect
            paperMask = pageInfo.paperMask
            Log.i(TAG, "Detected page rectangle (working coords): $pageRect")
        }
        debugContext?.let { dumpDebugPageRect(it, working, pageRect) }

        val mask = thresholdMaskInRect(luma, working.width, working.height, pageRect, paperMask)
        debugContext?.let { dumpDebugMask(it, working, mask) }

        val fiducials = findFiducials(mask, working.width, working.height, pageRect)
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

        PracticeSheetGenerator.normalizedCells.forEachIndexed { index, cell ->
            if (cell.character == ' ') return@forEachIndexed
            val cellLeft = (gridLeftPx + cell.left * gridWidthPx).toInt().coerceIn(0, RECTIFIED_WIDTH - 2)
            val cellTop = (gridTopPx + cell.top * gridHeightPx).toInt().coerceIn(0, RECTIFIED_HEIGHT - 2)
            val cellRight = (gridLeftPx + cell.right * gridWidthPx).toInt().coerceIn(cellLeft + 1, RECTIFIED_WIDTH)
            val cellBottom = (gridTopPx + cell.bottom * gridHeightPx).toInt().coerceIn(cellTop + 1, RECTIFIED_HEIGHT)

            val cellWidthPx = cellRight - cellLeft
            val cellHeightPx = cellBottom - cellTop
            // Inset to skip the printed grid border. Use a larger inset on top (where the
            // printed character label lives) and a smaller inset on bottom/right so that
            // descenders (g, y, p, q) and wide letters (E bottom arm, k arm) are not clipped.
            // suppressPrintedCellLabel() blanks the top-left label zone after crop, so the top
            // inset only needs to clear the cell border line on left/right/bottom.
            val topInset = (cellHeightPx * 0.08f).toInt().coerceAtLeast(4)
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
            val sanitizedCellCrop = suppressPrintedCellLabel(cellCrop)
            val guideFreeCellCrop = suppressGuideLinesInCrop(sanitizedCellCrop, topInset, cellHeightPx)
            val cleaned = GlyphPostProcessor.cleanGlyph(guideFreeCellCrop)

            debugContext?.let { dumpCellCrop(it, index, cell.character, guideFreeCellCrop, cleaned) }

            // Detect empty cells: require enough strongly-opaque pixels (real ink, not faint
            // residue from the printed cell border / label).
            if (hasInk(cleaned)) {
                glyphs[cell.character] = cleaned
            } else {
                missing += cell.character
            }
        }

        Log.i(TAG, "Sheet processing complete: ${glyphs.size} glyphs extracted, ${missing.size} cells empty")
        return SheetExtractionResult(
            glyphs = glyphs,
            missing = missing,
            rectifiedDebugBitmap = rectified,
            detectionFailed = false,
        )
    }

    private fun suppressPrintedCellLabel(cellCrop: Bitmap): Bitmap {
        val masked = cellCrop.copy(Bitmap.Config.ARGB_8888, true)
        val labelMaskWidth = (masked.width * 0.22f).toInt().coerceAtLeast(18).coerceAtMost(masked.width)
        val labelMaskHeight = (masked.height * 0.24f).toInt().coerceAtLeast(24).coerceAtMost(masked.height)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        Canvas(masked).drawRect(0f, 0f, labelMaskWidth.toFloat(), labelMaskHeight.toFloat(), paint)
        return masked
    }

    /**
     * Blanks thin horizontal bands at the three known guide-line positions (upper 32%, middle 55%,
     * baseline 78% of full cell height) in the already-cropped bitmap so that surviving guide-line
     * ink is removed before [GlyphPostProcessor.cleanGlyph] runs component extraction.
     *
     * [topInset] is the number of pixels removed from the cell top before cropping, so the guide
     * positions in the crop coordinate system are shifted by that amount.
     * [cellHeightPx] is the full cell height (before inset) used to compute absolute guide Y.
     */
    private fun suppressGuideLinesInCrop(cellCrop: Bitmap, topInset: Int, cellHeightPx: Int): Bitmap {
        val masked = cellCrop.copy(Bitmap.Config.ARGB_8888, true)
        val cropHeight = masked.height
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val canvas = Canvas(masked)
        // Guide ratios from PracticeSheetGenerator (upper, middle, baseline).
        val guideRatios = floatArrayOf(0.32f, 0.55f, 0.78f)
        // Widen the mask band slightly to catch any scan/enhancement spread (±4px each side).
        val halfBand = 4
        for (ratio in guideRatios) {
            val guideCellY = (cellHeightPx * ratio).toInt()
            val guideCropY = guideCellY - topInset
            if (guideCropY < 0 || guideCropY >= cropHeight) continue
            val top = (guideCropY - halfBand).coerceAtLeast(0).toFloat()
            val bottom = (guideCropY + halfBand).coerceAtMost(cropHeight - 1).toFloat()
            canvas.drawRect(0f, top, masked.width.toFloat(), bottom, paint)
        }
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
        // Find the brightest dominant peak in the histogram — that's the paper. Use it as the
        // anchor for the page-detection cutoff so wood-grain (a noticeably darker peak) is
        // rejected even when Otsu sits low. Falls back to (otsu+60) if no clear bright peak.
        var brightPeak = -1
        var brightPeakCount = 0
        for (t in 200..255) {
            if (histogram[t] > brightPeakCount) {
                brightPeakCount = histogram[t]
                brightPeak = t
            }
        }
        val brightCutoff = if (brightPeak >= 0 && brightPeakCount > total / 200) {
            (brightPeak - 60).coerceAtLeast(threshold + 30).coerceAtMost(245)
        } else {
            (threshold + 30).coerceAtMost(245)
        }
        Log.i(TAG, "Page-detection bright cutoff: $brightCutoff (otsu=$threshold, brightPeak=$brightPeak)")

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

        // Build the actual paper mask = the largest bright connected component (NOT just its AABB).
        val paperMask = BooleanArray(luma.size) { componentId[it] == bestId }

        // Tiny inward margin so we don't include the page edge halo when thresholding.
        val margin = (minOf(width, height) * 0.005f).toInt().coerceAtLeast(1)
        val pageRect = PageRect(
            left = (bestLeft + margin).coerceAtMost(bestRight - 1),
            top = (bestTop + margin).coerceAtMost(bestBottom - 1),
            right = (bestRight - margin).coerceAtLeast(bestLeft + 1),
            bottom = (bestBottom - margin).coerceAtLeast(bestTop + 1),
        )
        Log.i(TAG, "Largest bright region size=$bestSize, rect=$pageRect")
        return PageInfo(pageRect, paperMask)
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
        Log.i(TAG, "In-page Otsu threshold: $threshold over $pixelCount pixels")

        val mask = BooleanArray(luma.size)
        for (y in pageRect.top..pageRect.bottom) {
            val rowOffset = y * width
            for (x in pageRect.left..pageRect.right) {
                val idx = rowOffset + x
                if (paperMask != null && !paperMask[idx]) continue
                mask[idx] = luma[idx] <= threshold
            }
        }
        return mask
    }

    // ---------------------------------------------------------------------------------------------
    // Fiducial localisation
    // ---------------------------------------------------------------------------------------------

    private data class Fiducials(
        val tl: Pair<Float, Float>,
        val tr: Pair<Float, Float>,
        val br: Pair<Float, Float>,
        val bl: Pair<Float, Float>,
    )

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
        Log.i(TAG, "Found ${components.size} dark components in mask (after size filter). Centroids: " + components.map { it.centroid })

        val tl = pickFiducial(components, 0, 0, midX, midY, 0f, 0f) ?: return null
        val tr = pickFiducial(components, midX, 0, width, midY, width.toFloat(), 0f) ?: return null
        val br = pickFiducial(components, midX, midY, width, height, width.toFloat(), height.toFloat()) ?: return null
        val bl = pickFiducial(components, 0, midY, midX, height, 0f, height.toFloat()) ?: return null
        return Fiducials(tl.centroid, tr.centroid, br.centroid, bl.centroid)
    }

    private data class Component(
        val size: Int,
        val bboxW: Int,
        val bboxH: Int,
        val centroid: Pair<Float, Float>,
        val fillDensity: Float,
        val aspectRatio: Float,
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
                    Log.v(TAG, "  Component filtered: size=$size (min=$minSize,max=$maxSize) centroid=(${sumX/size},${sumY/size})")
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
