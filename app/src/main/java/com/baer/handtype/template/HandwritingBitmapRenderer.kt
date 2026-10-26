package com.baer.handtype.template

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import com.baer.handtype.ml.GlyphPostProcessor
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

data class HandwritingRenderConfig(
    val maxWidthPx: Int,
    val marginPx: Int = 28,
    val horizontalSpacingPx: Int = 4,
    val verticalSpacingPx: Int = 10,
    val noteBackground: NoteBackgroundPreset = NoteBackgroundCatalog.defaultPreset(),
    val inkColor: Int = 0xFF1A1410.toInt(),
    // --- Organic parameters (tuned for subtle realism) ---
    val baseFontSizePx: Float = 120f,
    /** Target line height in device pixels. 0 = use raw bitmap height (legacy). */
    val targetLineHeightPx: Int = 0,
    val jitterX: Int = 2,
    val jitterY: Int = 2,
    val spacingJitter: Int = 3,
    val wordRotationDeg: Float = 1.8f,
    val minInkAlpha: Float = 0.85f,
    val baselineWaveAmplitude: Float = 3f,
    val baselineWavePeriod: Float = 20f,
    val lineDriftPx: Float = 4f,
    val fontSizeJitterPct: Float = 0.06f,
    val wordSkewRange: Float = 0.06f,
    /** Off renders a cursive hand's letters unjoined with the same layout, for join tests. */
    internal val cursiveJoins: Boolean = true,
)

/** Pixel box in the note bitmap; right and bottom are exclusive. [inkTop]/[inkBottom] bound the line's ink, which can reach into a neighbour band. */
data class InkRow(val left: Int, val top: Int, val right: Int, val bottom: Int, val inkTop: Int = top, val inkBottom: Int = bottom)

data class HandwritingRenderResult(
    val bitmap: Bitmap,
    val missingCharacters: Set<Char>,
    /** Text of each laid-out line of the glyph path, for layout tests. Empty for the cursive path. */
    internal val lines: List<String> = emptyList(),
    /** Each join of a cursive hand as (previous exit, next entry) in output px, for join tests. */
    internal val joins: List<Pair<Pair<Float, Float>, Pair<Float, Float>>> = emptyList(),
    /** Per join, px the joined letter and the rest of its line moved right so its lead-in meets the tail. */
    internal val joinShifts: List<Int> = emptyList(),
    /** One band per line with ink, top to bottom; the bands tile the bitmap height. */
    val inkRows: List<InkRow> = emptyList(),
    /** The note's paper without ink, only when rendered with `keepPaper`. */
    val paper: Bitmap? = null,
)

object HandwritingBitmapRenderer {

    /** Stretches line ink boxes into bands that tile [height]; neighbours split mid-gap or mid-overlap. */
    internal fun inkRowBands(rows: List<InkRow>, height: Int): List<InkRow> {
        var top = 0
        return rows.mapIndexed { i, row ->
            val bottom = rows.getOrNull(i + 1)?.let { next -> ((row.bottom + next.top) / 2).coerceIn(top, height) } ?: height
            row.copy(top = top, bottom = bottom, inkTop = row.top, inkBottom = row.bottom).also { top = bottom }
        }
    }

    /** Grows [rows] at [line] to hold a box of [w] x [h] at ([x], [y]) rotated [deg] about its centre. */
    private fun growRow(rows: MutableMap<Int, InkRow>, line: Int, x: Float, y: Float, w: Float, h: Float, deg: Float) {
        val rad = Math.toRadians(deg.toDouble())
        val halfW = (abs(w / 2 * cos(rad)) + abs(h / 2 * sin(rad))).toFloat() + 2f
        val halfH = (abs(w / 2 * sin(rad)) + abs(h / 2 * cos(rad))).toFloat() + 2f
        val cx = x + w / 2
        val cy = y + h / 2
        val box = InkRow(floor(cx - halfW).toInt(), floor(cy - halfH).toInt(), ceil(cx + halfW).toInt(), ceil(cy + halfH).toInt())
        rows[line] = rows[line]?.let {
            InkRow(min(it.left, box.left), min(it.top, box.top), max(it.right, box.right), max(it.bottom, box.bottom))
        } ?: box
    }

    private fun clampedBands(rows: Map<Int, InkRow>, width: Int, height: Int): List<InkRow> =
        inkRowBands(
            rows.toSortedMap().values.map { it.copy(left = it.left.coerceIn(0, width), right = it.right.coerceIn(0, width)) },
            height,
        )

    /** Canvas x-height of an average print hand; it renders at about a third of the line height. */
    private const val REFERENCE_X_HEIGHT_PX = 64f

    private val descenderChars = setOf('g', 'j', 'p', 'q', 'y')
    private val dottedChars = setOf('i', 'j')
    private val punctuationChars = setOf('.', ',', ';', ':', '!', '?', '\'', '"', '`')
    /** Marks whose ink floats above the baseline. */
    private val raisedMarks = setOf('\'', '"', '`', '-')

    private data class InkBounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        /** Right edge of the ink below the top 35%: a T bar or f hook overhangs the next letter. */
        val bodyRight: Int = right,
    ) {
        val width: Int get() = right - left + 1
        val height: Int get() = bottom - top + 1
    }

    private data class PreparedGlyph(
        val bitmap: Bitmap,
        val baselineRow: Int,
        val normalizedUserCanvas: Boolean,
        val advanceWidth: Int = bitmap.width,
        /** Size that sets the scale; a composed accent keeps its base letter's size. */
        val sizeWidth: Int = bitmap.width,
        val sizeHeight: Int = bitmap.height,
        /** Columns of mark ink left of the base letter, drawn before the cursor. */
        val leftOverhang: Int = 0,
    )

    private fun drawBackground(
        canvas: Canvas,
        width: Int,
        height: Int,
        config: HandwritingRenderConfig,
        seed: Long,
        lineSpacingPx: Int,
        firstBaselinePx: Int,
    ) {
        config.noteBackground.render(
            canvas = canvas,
            width = width,
            height = height,
            seed = seed,
            lineSpacingPx = lineSpacingPx,
            firstBaselinePx = firstBaselinePx,
            marginPx = config.marginPx,
        )
    }

    /** Placeholder type — stroke data is no longer used; cursive uses system fonts. */
    data class GlyphOutline(val advance: Float = 0f)

    fun parseStrokeData(@Suppress("UNUSED_PARAMETER") json: String): Map<String, GlyphOutline> = emptyMap()

    private fun measureInkBounds(bitmap: Bitmap): InkBounds {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            val rowOffset = y * w
            for (x in 0 until w) {
                val alpha = pixels[rowOffset + x] ushr 24
                if (alpha <= 20) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }

        return if (maxX >= minX && maxY >= minY) {
            var bodyRight = minX
            for (y in (minY + (maxY - minY + 1) * 35 / 100)..maxY) {
                val rowOffset = y * w
                for (x in maxX downTo bodyRight + 1) {
                    if ((pixels[rowOffset + x] ushr 24) > 20) {
                        bodyRight = x
                        break
                    }
                }
            }
            InkBounds(minX, minY, maxX, maxY, bodyRight)
        } else {
            InkBounds(0, 0, w - 1, h - 1)
        }
    }

    private fun prepareGlyph(
        bitmap: Bitmap,
        character: Char,
        templateBaselineRow: Int,
        isUserTemplate: Boolean,
        writerBaseline: Int,
        writerScale: Float,
    ): PreparedGlyph {
        val bounds = measureInkBounds(bitmap)
        if (isUserTemplate && GlyphPostProcessor.isNormalizedCanvas(bitmap.height)) {
            // Trim vertical canvas padding. Raw normalized canvas (220px) has lots of empty
            // space above the cap-top and below the descender — when scaled to lineHeightPx
            // the visible ink ends up only ~27% of line spacing. Crop to a tight ascender/
            // descender window so ink fills the line height.
            val canvasH = bitmap.height
            val canvasBaseline = GlyphPostProcessor.normalizedBaselineRow()
            // Reserve space above baseline for tall ascenders/caps; below for descenders.
            val referenceH = GlyphPostProcessor.normalizedCanvasSize()
            val ascenderReserve = (referenceH * 0.56f).toInt()    // ~123px above baseline
            val descenderReserve = (referenceH * 0.21f).toInt()   // ~46px below baseline
            val rawTop = (canvasBaseline - ascenderReserve).coerceAtLeast(0)
            val rawBot = (canvasBaseline + descenderReserve).coerceAtMost(canvasH - 1)
            // Honor actual ink top in case capture left ink in the diacritic zone (e.g., dots
            // on 'i', 'j'). Never crop below row 0 or above the ink.
            val cropTop = minOf(rawTop, bounds.top).coerceAtLeast(0)
            val cropBot = maxOf(rawBot, bounds.bottom).coerceAtMost(canvasH - 1)
            val cropH = (cropBot - cropTop + 1).coerceAtLeast(1)
            val baseline = userCanvasBaselineRow(character, canvasBaseline, bounds.bottom, writerBaseline)
            return PreparedGlyph(
                bitmap = Bitmap.createBitmap(bitmap, bounds.left, cropTop, bounds.width, cropH),
                baselineRow = (baseline - cropTop).coerceIn(0, cropH - 1),
                normalizedUserCanvas = true,
                // The window, not the ink, sets the scale: one scale for the whole hand, so a
                // capital that overflows the window keeps its size next to the small letters.
                sizeHeight = ((rawBot - rawTop + 1) / writerScale).toInt(),
                // Tall glyphs (T, F, 7, f, t) carry a bar or hook the next letter tucks under.
                // x-height letters keep their full width: an r arm or o tail is the letter.
                advanceWidth = if (bounds.height >= GlyphPostProcessor.normalizedGuideBandHeight() * 6 / 10) {
                    (bounds.bodyRight - bounds.left + 1).coerceAtLeast(bounds.width * 6 / 10)
                } else {
                    bounds.width
                },
            )
        }

        val cropped = Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width, bounds.height)
        return PreparedGlyph(
            bitmap = cropped,
            baselineRow = bundledBaselineRow(character, bounds.top, bounds.bottom, templateBaselineRow, isUserTemplate),
            normalizedUserCanvas = false,
        )
    }

    /** Baseline row inside the ink-cropped glyph. */
    internal fun bundledBaselineRow(
        character: Char,
        inkTop: Int,
        inkBottom: Int,
        templateBaselineRow: Int,
        isUserTemplate: Boolean,
    ): Int {
        val lower = character.lowercaseChar()
        val baselineInSource = when {
            // Only lowercase descenders get the fraction-based baseline.
            // Uppercase G/J/P/Q/Y have no descender — their baseline is at bounds.bottom.
            isUserTemplate && character.isLowerCase() && lower in descenderChars -> {
                val baselineFraction = when (lower) {
                    'g', 'j' -> 0.50f
                    else -> 0.58f
                }
                (inkTop + ((inkBottom - inkTop + 1) * baselineFraction).toInt()).coerceIn(inkTop, inkBottom)
            }
            lower in descenderChars -> templateBaselineRow.coerceIn(inkTop, inkBottom)
            isUserTemplate -> inkBottom
            character in raisedMarks -> return (templateBaselineRow - inkTop).coerceAtLeast(inkBottom - inkTop)
            character == ',' -> templateBaselineRow.coerceIn(inkTop, inkBottom)
            else -> inkBottom
        }
        return (baselineInSource - inkTop).coerceIn(0, inkBottom - inkTop)
    }

    internal fun targetInkHeightRatio(character: Char, isUserTemplate: Boolean): Float {
        val lower = character.lowercaseChar()
        val baseRatio = when {
            // Bundled marks keep their asset height relative to 'l' (0.66 for 100 px of ink).
            character == '!' -> 0.64f
            character == '?' -> 0.59f
            character == ',' -> 0.22f
            character == '\'' -> 0.23f
            character == '.' -> 0.13f
            character == '-' -> 0.08f
            character in punctuationChars -> 0.24f
            character.isDigit() -> 0.72f
            // Ink Free umlaut ink includes the dots: the base letter's ratio times the measured
            // ink-height ratio (Ä/A 126/107, ä/a 93/69), so the body matches the plain letter.
            character in setOf('Ä', 'Ö', 'Ü') -> 0.95f
            character in setOf('ä', 'ö', 'ü') -> 0.74f
            character.isUpperCase() -> 0.80f
            lower in descenderChars -> 0.64f
            lower in setOf('b', 'd', 'f', 'h', 'k', 'l', 't', 'ß') -> 0.66f
            else -> 0.55f
        }
        if (!isUserTemplate) return baseRatio
        return when {
            character in punctuationChars -> 0.28f
            character.isDigit() -> 0.92f
            character.isUpperCase() -> 1.00f
            lower in setOf('g', 'j') -> 0.94f
            lower in setOf('p', 'q', 'y') -> 0.90f
            lower in dottedChars -> 0.62f
            lower in setOf('b', 'd', 'f', 'h', 'k', 'l', 't') -> 0.92f
            else -> 0.52f
        }
    }

    internal fun descenderDropRatio(isUserTemplate: Boolean): Float = if (isUserTemplate) 0.08f else 0.05f

    /**
     * Writers float letters above the printed guide by varying amounts, so a letter's ink bottom
     * is its baseline. Synthesized punctuation is drawn on the canvas baseline. Descenders hang
     * from [writerBaseline], and so does ink reaching far below it: a cursive ß or f has a
     * descender a print one lacks. Capital G J P Q Y have no descender.
     */
    internal fun userCanvasBaselineRow(character: Char, canvasBaseline: Int, inkBottom: Int, writerBaseline: Int = canvasBaseline): Int =
        when {
            !character.isLetterOrDigit() -> canvasBaseline
            character.isLowerCase() && character in descenderChars -> writerBaseline
            inkBottom - writerBaseline > GlyphPostProcessor.normalizedGuideBandHeight() / 5 -> writerBaseline
            else -> inkBottom
        }

    /**
     * Scale that brings a hand's x-height (normalized canvas px) to the reference, so a hand that
     * fills the sheet's guide band as asked still fits the ruled line. A tiny hand grows less than
     * that: scaled up, its fine pen would turn into a marker.
     */
    internal fun writerSizeScale(xHeightInkHeights: List<Int>): Float {
        val median = xHeightInkHeights.sorted().getOrNull(xHeightInkHeights.size / 2) ?: return 1f
        return (REFERENCE_X_HEIGHT_PX / median.coerceAtLeast(1)).coerceIn(0.5f, 1.6f)
    }

    /**
     * How far the next glyph may move left from [distancePx] (left edge to left edge) until its
     * ink comes within [gapPx] of the previous glyph's ink on some shared row. A cursive lead-in
     * slides under the previous exit stroke; round print letters already sit at that gap.
     * [prevRight] and [nextLeft] hold the outermost ink column per bitmap row, -1 for none.
     */
    internal fun kerningPx(
        prevRight: IntArray,
        prevBaselineRow: Int,
        prevScaleX: Float,
        prevScaleY: Float,
        nextLeft: IntArray,
        nextBaselineRow: Int,
        nextScaleX: Float,
        nextScaleY: Float,
        distancePx: Int,
        gapPx: Int,
        maxKernPx: Int,
    ): Int {
        // Rows in px from the baseline, so glyphs of different crops line up.
        val prevEdge = HashMap<Int, Float>()
        prevRight.forEachIndexed { row, col ->
            if (col < 0) return@forEachIndexed
            val y = ((row - prevBaselineRow) * prevScaleY).roundToInt()
            prevEdge[y] = max(prevEdge[y] ?: Float.NEGATIVE_INFINITY, (col + 1) * prevScaleX)
        }
        var required = Float.NEGATIVE_INFINITY
        nextLeft.forEachIndexed { row, col ->
            if (col < 0) return@forEachIndexed
            val edge = prevEdge[((row - nextBaselineRow) * nextScaleY).roundToInt()] ?: return@forEachIndexed
            required = max(required, edge - col * nextScaleX)
        }
        if (required == Float.NEGATIVE_INFINITY) return 0
        return (distancePx - ceil(required).toInt() - gapPx).coerceIn(0, maxKernPx.coerceAtLeast(0))
    }

    /** Where the writer's x-height letters stand: the median of their ink bottoms. */
    internal fun writerBaselineRow(canvasBaseline: Int, xHeightInkBottoms: List<Int>): Int =
        xHeightInkBottoms.sorted().getOrElse(xHeightInkBottoms.size / 2) { canvasBaseline }

    internal fun targetInkHeightPx(
        lineHeightPx: Int,
        character: Char,
        isUserTemplate: Boolean,
        normalizedUserCanvas: Boolean,
    ): Int {
        return if (normalizedUserCanvas) {
            lineHeightPx.coerceAtLeast(12)
        } else {
            (lineHeightPx * targetInkHeightRatio(character, isUserTemplate)).toInt().coerceAtLeast(12)
        }
    }

    /** Median ink top of the x-height letters; the guide-band top when none are present. */
    internal fun xHeightTopRow(tops: List<Int>): Int =
        tops.sorted().getOrElse(tops.size / 2) { PunctuationSynthesizer.InkStyle.DEFAULT.xHeightTop }

    /** Narrow letters slide under a tucked bar; after a round letter they would land on its ink. */
    internal fun leadingPullPx(
        character: Char,
        lineHeightPx: Int,
        normalizedUserCanvas: Boolean,
        previousTucked: Boolean,
    ): Int {
        if (!normalizedUserCanvas) return 0
        return when {
            character.lowercaseChar() in setOf('i', 'j', 'l') || character in setOf('I', '1') -> {
                if (previousTucked) (lineHeightPx * 0.03f).toInt().coerceAtLeast(2) else 0
            }
            character.lowercaseChar() in setOf('f', 't', 'r') -> {
                (lineHeightPx * 0.015f).toInt().coerceAtLeast(1)
            }
            else -> 0
        }
    }

    /** Bundled glyphs overlap their neighbour, so a tall mark after a letter would read as one of its strokes. */
    internal fun markLeadPx(previous: Char?, character: Char, lineHeightPx: Int, normalizedUserCanvas: Boolean): Int =
        if (!normalizedUserCanvas && character in setOf('!', '?') && previous?.isLetterOrDigit() == true) {
            (lineHeightPx * 0.06f).toInt()
        } else {
            0
        }

    private fun glyphAdvancePx(
        glyph: PreparedGlyph,
        character: Char,
        scaleX: Float,
        lineHeightPx: Int,
        normalizedUserCanvas: Boolean,
    ): Int {
        val rawAdvance = if (normalizedUserCanvas) {
            (glyph.advanceWidth * scaleX).toInt() + when {
                character in punctuationChars -> 0
                character.lowercaseChar() in setOf('i', 'l') || character in setOf('I', '1') -> 0
                character.lowercaseChar() in setOf('m', 'w') || character in setOf('M', 'W') -> 3
                else -> 2
            }
        } else {
            (glyph.advanceWidth * scaleX * 0.90f).toInt() + (lineHeightPx * 0.04f).toInt()
        }
        val minAdvance = when {
            normalizedUserCanvas && character in punctuationChars -> (lineHeightPx * 0.02f).toInt()
            normalizedUserCanvas &&
                (character.lowercaseChar() in setOf('i', 'l') || character in setOf('I', '1')) -> {
                (lineHeightPx * 0.035f).toInt()
            }
            normalizedUserCanvas &&
                (character.lowercaseChar() in setOf('m', 'w') || character in setOf('M', 'W')) -> {
                (lineHeightPx * 0.15f).toInt()
            }
            normalizedUserCanvas -> (lineHeightPx * 0.07f).toInt()
            character in punctuationChars -> (lineHeightPx * 0.08f).toInt()
            character.lowercaseChar() in setOf('i', 'l') || character in setOf('I', '1') -> (lineHeightPx * 0.12f).toInt()
            character.lowercaseChar() in setOf('m', 'w') || character in setOf('M', 'W') -> (lineHeightPx * 0.22f).toInt()
            else -> (lineHeightPx * 0.14f).toInt()
        }
        return rawAdvance.coerceAtLeast(minAdvance.coerceAtLeast(1))
    }

    /**
     * Walk along every contour of [src], sampling at [step]-px intervals,
     * and apply position-based displacement using overlapping sine waves.
     * Global offsets ensure both sides of a stroke shift equally, preserving thickness.
     */
    private fun wavyPath(
        src: Path,
        amplitude: Float,
        phase1: Float,
        phase2: Float,
        phase3: Float,
        step: Float = 3.5f,
    ): Path {
        val result = Path()
        val measure = PathMeasure(src, false)
        val pos = FloatArray(2)
        val tan = FloatArray(2)
        do {
            val length = measure.length
            if (length <= 0f) continue
            var d = 0f
            var first = true
            var prevX = 0f
            var prevY = 0f
            while (d <= length) {
                measure.getPosTan(d, pos, tan)
                val ox = amplitude * (
                    0.5f * sin((pos[1] * 0.10f + phase1).toDouble()).toFloat() +
                    0.3f * sin((pos[1] * 0.23f + phase2).toDouble()).toFloat() +
                    0.2f * sin((pos[1] * 0.47f + phase3).toDouble()).toFloat()
                )
                val oy = amplitude * 0.6f * (
                    0.5f * sin((pos[0] * 0.09f + phase2).toDouble()).toFloat() +
                    0.3f * sin((pos[0] * 0.21f + phase3).toDouble()).toFloat() +
                    0.2f * sin((pos[0] * 0.44f + phase1).toDouble()).toFloat()
                )
                val x = pos[0] + ox
                val y = pos[1] + oy
                if (first) {
                    result.moveTo(x, y)
                    first = false
                } else {
                    val mx = (prevX + x) * 0.5f
                    val my = (prevY + y) * 0.5f
                    result.quadTo(prevX, prevY, mx, my)
                }
                prevX = x
                prevY = y
                d += step
            }
            if (!first) result.lineTo(prevX, prevY)
            result.close()
        } while (measure.nextContour())
        return result
    }

    /**
     * Render a word using the Android system cursive font. Each character is
     * extracted as a separate Path via getTextPath and transformed individually
     * (size, rotation, skew, baseline offset) to produce distinctive letterforms.
     */
    private fun renderWordCursive(
        word: String,
        baseSize: Float,
        inkColor: Int,
        rng: Random,
    ): Bitmap? {
        val typeface = Typeface.create("cursive", Typeface.NORMAL)
        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = baseSize
            style = Paint.Style.FILL
            color = inkColor
        }

        data class CharInfo(
            val ch: String,
            val path: Path,
            val measuredWidth: Float,
            val sizeScale: Float,
            val rotation: Float,
            val skewX: Float,
            val baselineOff: Float,
            val alpha: Int,
            val waveAmp: Float,
            val wavePhase1: Float,
            val wavePhase2: Float,
            val wavePhase3: Float,
        )

        val chars = mutableListOf<CharInfo>()
        for (ch in word) {
            val s = ch.toString()
            val sizeScale = 0.95f + rng.nextFloat() * 0.10f          // ±5%
            val rotation = (rng.nextFloat() * 2f - 1f) * 4f          // ±4°
            val skewX    = (rng.nextFloat() * 2f - 1f) * 0.10f       // ±0.10
            val baseOff  = (rng.nextFloat() * 2f - 1f) * 0.5f        // ±0.5px
            val alpha    = 190 + rng.nextInt(61)                      // 190-250
            val waveAmp   = 0.8f + rng.nextFloat() * 0.2f            // 0.8-1.0px
            val wavePhase1 = rng.nextFloat() * 6.2832f
            val wavePhase2 = rng.nextFloat() * 6.2832f
            val wavePhase3 = rng.nextFloat() * 6.2832f

            val charPaint = Paint(basePaint).apply { textSize = baseSize * sizeScale }
            val w = charPaint.measureText(s)
            val path = Path()
            charPaint.getTextPath(s, 0, 1, 0f, 0f, path)

            if (skewX != 0f) {
                val skewMatrix = Matrix()
                skewMatrix.setSkew(skewX, 0f)
                path.transform(skewMatrix)
            }

            chars += CharInfo(
                ch = s, path = path, measuredWidth = w,
                sizeScale = sizeScale, rotation = rotation,
                skewX = skewX, baselineOff = baseOff, alpha = alpha,
                waveAmp = waveAmp, wavePhase1 = wavePhase1,
                wavePhase2 = wavePhase2, wavePhase3 = wavePhase3,
            )
        }

        val margin = 10f
        val totalWidth = chars.sumOf { (it.measuredWidth + 1f).toDouble() }.toFloat() + margin * 2
        val bmpH = (baseSize * 2f + margin).toInt()
        if (totalWidth <= 0 || bmpH <= 0) return null

        val bmp = Bitmap.createBitmap(totalWidth.toInt().coerceAtLeast(1), bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = inkColor
        }

        var cx = margin
        val baselineY = baseSize * 1.3f

        for (ci in chars) {
            val m = Matrix()
            m.postTranslate(cx, baselineY + ci.baselineOff)
            if (ci.rotation != 0f) {
                m.postRotate(ci.rotation, cx + ci.measuredWidth / 2f, baselineY)
            }
            val drawPath = Path(ci.path)
            drawPath.transform(m)
            paint.alpha = ci.alpha
            val wavy = wavyPath(drawPath, ci.waveAmp, ci.wavePhase1, ci.wavePhase2, ci.wavePhase3)
            canvas.drawPath(wavy, paint)

            cx += ci.measuredWidth + (rng.nextFloat() * 2f - 1f) * 3f
        }

        return bmp
    }

    fun renderCursive(
        text: String,
        @Suppress("UNUSED_PARAMETER") strokeData: Map<String, GlyphOutline>,
        config: HandwritingRenderConfig,
        seed: Long = System.nanoTime(),
        keepPaper: Boolean = false,
    ): HandwritingRenderResult {
        val rng = Random(seed)
        val safeWidth = config.maxWidthPx.coerceAtLeast(config.marginPx * 2 + 1)
        val missingCharacters = linkedSetOf<Char>()

        val charSize = config.baseFontSizePx
        val defaultCharWidth = charSize * 0.45f
        val spaceWidth = charSize * 0.35f

        // Use the system cursive font Paint for width estimation
        val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("cursive", Typeface.NORMAL)
            textSize = charSize
        }

        fun estimateWordWidth(word: String): Float {
            return measurePaint.measureText(word)
        }

        data class WordPlacement(
            val bitmap: Bitmap,
            val x: Float,
            val y: Float,
            val rotation: Float,
            val alpha: Float,
            val line: Int,
        )

        val placements = mutableListOf<WordPlacement>()
        var lineIndex = 0
        var cursorX = config.marginPx.toFloat()
        var cursorY = config.marginPx.toFloat() + charSize
        var charIndexInLine = 0
        var lineBaselinePhase = rng.nextFloat() * 6.2832f
        var lineDriftSlope = (rng.nextFloat() * 2f - 1f) * config.lineDriftPx
        var maxUsedX = cursorX

        fun newLine() {
            lineIndex++
            cursorX = config.marginPx.toFloat()
            cursorY += charSize * 1.1f + config.verticalSpacingPx
            charIndexInLine = 0
            lineBaselinePhase = rng.nextFloat() * 6.2832f
            lineDriftSlope = (rng.nextFloat() * 2f - 1f) * config.lineDriftPx
        }

        val lines = (if (text.isBlank()) " " else text).split('\n')
        for (line in lines) {
            if (line.isEmpty()) {
                newLine()
                continue
            }
            // Split oversized words before allocating their bitmaps, allowing for glyph jitter.
            val wordLimit = (safeWidth - config.marginPx * 2 - 24).coerceAtLeast(1).toFloat()
            val words = line.split(' ').flatMap { word ->
                val segments = mutableListOf<Pair<String, Boolean>>()
                val chunk = StringBuilder()
                var width = 0f
                for (character in word) {
                    val advance = measurePaint.measureText(character.toString()) * 1.08f + 1f
                    if (chunk.isNotEmpty() && width + advance > wordLimit) {
                        segments += chunk.toString() to segments.isNotEmpty()
                        chunk.clear()
                        width = 0f
                    }
                    chunk.append(character)
                    width += advance
                }
                if (chunk.isNotEmpty()) segments += chunk.toString() to segments.isNotEmpty()
                segments
            }
            val totalCharsInLine = line.length.coerceAtLeast(1)

            for ((wordIndex, segment) in words.withIndex()) {
                val (word, continuation) = segment
                if (word.isEmpty()) continue
                if (continuation) newLine()

                val wordSizeJitter = 1f + (rng.nextFloat() * 2f - 1f) * 0.02f  // ±2%
                val wordCharSize = charSize * wordSizeJitter
                val wordWidth = estimateWordWidth(word)

                if (wordIndex > 0 && !continuation) {
                    val neededSpace = spaceWidth + wordWidth
                    if (cursorX + neededSpace > safeWidth - config.marginPx && cursorX > config.marginPx + 1) {
                        newLine()
                    } else {
                        cursorX += spaceWidth + rng.nextInt(-config.spacingJitter, config.spacingJitter + 1)
                    }
                }

                if (cursorX + wordWidth > safeWidth - config.marginPx && cursorX > config.marginPx + 1) {
                    newLine()
                }

                val wordBitmap = renderWordCursive(
                    word, wordCharSize, config.inkColor, rng
                )

                val jx = rng.nextInt(-config.jitterX, config.jitterX + 1)
                val jy = rng.nextInt(-config.jitterY, config.jitterY + 1)
                val waveOffset = 14f *
                    sin((charIndexInLine / 12f + lineBaselinePhase).toDouble()).toFloat()
                val driftOffset = lineDriftSlope * (charIndexInLine.toFloat() / totalCharsInLine)
                val rotation = (rng.nextFloat() * 2f - 1f) * config.wordRotationDeg

                if (wordBitmap != null) {
                    placements += WordPlacement(
                        bitmap = wordBitmap,
                        x = cursorX + jx,
                        y = cursorY + jy + waveOffset + driftOffset - charSize * 0.7f,
                        rotation = rotation,
                        alpha = 0.90f + rng.nextFloat() * 0.10f,  // 0.90-1.0
                        line = lineIndex,
                    )
                }

                cursorX += wordWidth + config.horizontalSpacingPx
                maxUsedX = max(maxUsedX, cursorX)
                charIndexInLine += word.length + 1
            }
            newLine()
        }

        val extraBottom = (config.baselineWaveAmplitude + config.lineDriftPx + config.jitterY + charSize * 0.8f).toInt()
        val bitmapHeight = (cursorY + extraBottom).toInt().coerceAtLeast(1)
        val bitmapWidth = max(maxUsedX.toInt() + config.marginPx, safeWidth)
        val output = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val lineSpacingPx = (charSize * 1.1f + config.verticalSpacingPx).toInt().coerceAtLeast(1)
        val firstBaselinePx = (config.marginPx + charSize * 1.6f).toInt()
        drawBackground(
            canvas = canvas,
            width = bitmapWidth,
            height = bitmapHeight,
            config = config,
            seed = seed,
            lineSpacingPx = lineSpacingPx,
            firstBaselinePx = firstBaselinePx,
        )
        val paper = if (keepPaper) output.copy(Bitmap.Config.ARGB_8888, false) else null
        val inkRows = HashMap<Int, InkRow>()

        val placementPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val matrix = Matrix()

        placements.forEach { p ->
            growRow(inkRows, p.line, p.x, p.y, p.bitmap.width.toFloat(), p.bitmap.height.toFloat(), p.rotation)
            placementPaint.alpha = (p.alpha * 255).toInt().coerceIn(0, 255)
            if (p.rotation != 0f) {
                matrix.reset()
                val cx = p.bitmap.width / 2f
                val cy = p.bitmap.height / 2f
                matrix.postRotate(p.rotation, cx, cy)
                matrix.postTranslate(p.x, p.y)
                canvas.drawBitmap(p.bitmap, matrix, placementPaint)
            } else {
                canvas.drawBitmap(p.bitmap, p.x, p.y, placementPaint)
            }
        }

        return HandwritingRenderResult(
            bitmap = output,
            missingCharacters = missingCharacters,
            inkRows = clampedBands(inkRows, bitmapWidth, bitmapHeight),
            paper = paper,
        )
    }

    fun render(
        text: String,
        template: HandwritingTemplate,
        config: HandwritingRenderConfig,
        seed: Long = System.nanoTime(),
        keepPaper: Boolean = false,
    ): HandwritingRenderResult {
        val rng = Random(seed)
        val normalizedText = BundledGlyphTextNormalizer.normalize(text)
        val safeWidth = config.maxWidthPx.coerceAtLeast(config.marginPx * 2 + 1)
        val allGlyphs = template.glyphs.values.flatten()
        val rawBounds = allGlyphs.associateWith { measureInkBounds(it) }
        val baselineRows = rawBounds.values.map(InkBounds::bottom).sorted()
        val templateBaselineRow = baselineRows.getOrElse(baselineRows.size / 2) { 0 }
        val isUserTemplate = template.descriptor.id.startsWith(UserTemplateRepository.USER_ID_PREFIX)
        val preparedGlyphs = HashMap<Bitmap, PreparedGlyph>(allGlyphs.size)
        val synthesizedGlyphCache = HashMap<Char, Bitmap?>()
        val synthesizedInkStyle by lazy {
            if (!isUserTemplate) return@lazy PunctuationSynthesizer.InkStyle.DEFAULT
            val xTops = "aceimnorsuvwxz".mapNotNull { c ->
                template.glyphs[c]?.firstOrNull()?.let { rawBounds[it]?.top }
            }
            val tallTops = "bdhkl0123456789".mapNotNull { c ->
                template.glyphs[c]?.firstOrNull()?.let { rawBounds[it]?.top }
            }.sorted()
            PunctuationSynthesizer.InkStyle.estimate(allGlyphs).copy(
                xHeightTop = xHeightTopRow(xTops),
                ascenderTop = tallTops.getOrElse(tallTops.size / 2) { PunctuationSynthesizer.InkStyle.DEFAULT.ascenderTop },
            )
        }
        val writerMarks by lazy { PunctuationSynthesizer.WriterMarks.from(template.glyphs) }
        val writerBaseline = writerBaselineRow(
            GlyphPostProcessor.normalizedBaselineRow(),
            "acemnorsuvwxz".mapNotNull { c ->
                template.glyphs[c]?.firstOrNull()
                    ?.takeIf { GlyphPostProcessor.isNormalizedCanvas(it.height) }
                    ?.let { rawBounds[it]?.bottom }
            },
        )
        val xHeightInkHeights = "acemnorsuvwxz".mapNotNull { c ->
            template.glyphs[c]?.firstOrNull()
                ?.takeIf { GlyphPostProcessor.isNormalizedCanvas(it.height) }
                ?.let { rawBounds[it]?.height }
        }
        val writerScale = writerSizeScale(xHeightInkHeights)
        val xHeightPx = xHeightInkHeights.sorted().getOrNull(xHeightInkHeights.size / 2)
        fun preparedGlyph(character: Char, bitmap: Bitmap): PreparedGlyph {
            return preparedGlyphs.getOrPut(bitmap) {
                prepareGlyph(bitmap, character, templateBaselineRow, isUserTemplate, writerBaseline, writerScale)
            }
        }
        fun variantsFor(character: Char): List<Bitmap>? =
            template.glyphs[character] ?: template.glyphs[character.lowercaseChar()] ?: template.glyphs[character.uppercaseChar()]
        // Template x-height in glyph pixels sizes the accent marks.
        val markUnitPx by lazy {
            val heights = "acemnorsuvwxz".mapNotNull { c -> template.glyphs[c]?.firstOrNull()?.let { rawBounds[it]?.height } }
            heights.sorted().getOrNull(heights.size / 2)?.toFloat() ?: (rawBounds.values.map(InkBounds::height).average() * 0.6).toFloat()
        }
        val markInkStyle by lazy { PunctuationSynthesizer.InkStyle.estimate(allGlyphs) }
        val composedGlyphs = HashMap<Pair<Char, Bitmap>, PreparedGlyph>()
        fun composedGlyph(character: Char, decomposition: AccentComposer.Decomposition, baseBitmap: Bitmap): PreparedGlyph {
            return composedGlyphs.getOrPut(character to baseBitmap) {
                val base = preparedGlyph(decomposition.base, baseBitmap)
                val composed = AccentComposer.compose(base.bitmap, base.baselineRow, decomposition, markUnitPx, markInkStyle)
                base.copy(
                    bitmap = composed.bitmap,
                    baselineRow = base.baselineRow + composed.topPad,
                    leftOverhang = composed.leftPad,
                )
            }
        }
        /** Accented letter without a glyph of its own whose base letter has one. */
        fun accentFor(character: Char): AccentComposer.Decomposition? =
            if (variantsFor(character) != null) null else AccentComposer.decompose(character)?.takeIf { variantsFor(it.base) != null }

        val averageInkWidth = rawBounds.values.map(InkBounds::width).average().toInt().coerceAtLeast(24)
        val averageInkHeight = rawBounds.values.map(InkBounds::height).average().toInt().coerceAtLeast(48)
        val hasNormalizedUserCanvas = isUserTemplate && allGlyphs.all {
            GlyphPostProcessor.isNormalizedCanvas(it.height)
        }
        val rawLineHeightPx = if (config.targetLineHeightPx > 0) {
            config.targetLineHeightPx
        } else {
            (averageInkHeight * 1.35f).toInt().coerceAtLeast(72)
        }
        val lineHeightPx = if (hasNormalizedUserCanvas && config.targetLineHeightPx <= 0) {
            max(rawLineHeightPx, (GlyphPostProcessor.normalizedGuideBandHeight() * 1.35f).toInt())
        } else {
            rawLineHeightPx
        }
        val effectiveVerticalSpacingPx = if (isUserTemplate) {
            (config.verticalSpacingPx / 2).coerceAtLeast(4)
        } else {
            config.verticalSpacingPx
        }
        val effectiveHorizontalSpacingPx = if (hasNormalizedUserCanvas) {
            config.horizontalSpacingPx.coerceAtMost(1)
        } else {
            config.horizontalSpacingPx
        }
        val effectiveSpacingJitterPx = if (hasNormalizedUserCanvas) 0 else config.spacingJitter
        val effectiveJitterXPx = if (hasNormalizedUserCanvas) config.jitterX.coerceAtMost(1) else config.jitterX
        val effectiveJitterYPx = if (hasNormalizedUserCanvas) config.jitterY.coerceAtMost(1) else config.jitterY
        val effectiveBaselineWaveAmplitude = if (hasNormalizedUserCanvas) {
            config.baselineWaveAmplitude.coerceAtMost(1.5f)
        } else {
            6f
        }
        val effectiveLineDriftPx = if (hasNormalizedUserCanvas) {
            config.lineDriftPx.coerceAtMost(1.5f)
        } else {
            config.lineDriftPx
        }
        val baselineOffsetPx = (lineHeightPx * 0.74f).toInt().coerceAtLeast(1)
        val spaceWidth = max((lineHeightPx * 0.20f).toInt(), (averageInkWidth * 0.45f).toInt()).coerceAtLeast(14)
        val missingCharacters = linkedSetOf<Char>()
        val placements = mutableListOf<GlyphPlacement>()

        var cursorX = config.marginPx
        // Ensure line 1 baseline has enough headroom for tall glyphs (e.g. uppercase at scaleY≈1.35).
        // Without this, drawTop = baselineY - scaledGlyphH can go negative, clipping the top of
        // letters on the first line.
        var cursorY = (lineHeightPx - baselineOffsetPx + config.marginPx).coerceAtLeast(config.marginPx)
        var lineHeight = lineHeightPx
        var maxUsedX = config.marginPx
        var charIndexInLine = 0
        var lineBaselinePhase = rng.nextFloat() * 6.2832f
        var lineDriftSlope = (rng.nextFloat() * 2f - 1f) * effectiveLineDriftPx

        // For normalized user canvas glyphs, the 220x220 canvas has the body confined to ~46%
        // of the height with large transparent zones above (ascender) and below (descender).
        // Advancing cursorY by full lineHeight wastes that empty space and makes lines look
        // sparse. 72% leaves room for a tall ascender under a descender (both reach ~55% of
        // the line height together) without the lines drifting apart.
        val lineAdvancePx = if (hasNormalizedUserCanvas) {
            (lineHeightPx * 0.72f).toInt()
        } else {
            lineHeightPx
        }

        val laidOutLines = mutableListOf(StringBuilder())

        fun moveToNextLine() {
            laidOutLines += StringBuilder()
            cursorX = config.marginPx
            cursorY += (if (hasNormalizedUserCanvas) lineAdvancePx else lineHeight) + effectiveVerticalSpacingPx
            lineHeight = lineHeightPx
            charIndexInLine = 0
            lineBaselinePhase = rng.nextFloat() * 6.2832f
            lineDriftSlope = (rng.nextFloat() * 2f - 1f) * effectiveLineDriftPx
        }

        // Outermost ink column per bitmap row (left, right), for kerning user glyphs.
        val inkProfiles = HashMap<Bitmap, Pair<IntArray, IntArray>>()
        fun inkProfile(bitmap: Bitmap): Pair<IntArray, IntArray> = inkProfiles.getOrPut(bitmap) {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
            val left = IntArray(h) { -1 }
            val right = IntArray(h) { -1 }
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if ((pixels[y * w + x] ushr 24) <= 40) continue
                    if (left[y] < 0) left[y] = x
                    right[y] = x
                }
            }
            left to right
        }
        // Cursive joins: the x-height band of a prepared glyph ends at its baseline row.
        val joinTips = HashMap<Bitmap, CursiveJoins.Tips?>()
        fun alphaOf(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height)
            .also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
            .map { it ushr 24 }.toIntArray()
        fun tipsOf(glyph: PreparedGlyph): CursiveJoins.Tips? = joinTips.getOrPut(glyph.bitmap) {
            xHeightPx?.let { CursiveJoins.tips(alphaOf(glyph.bitmap), glyph.bitmap.width, glyph.baselineRow - it, glyph.baselineRow) }
        }
        val cursiveHand = config.cursiveJoins && hasNormalizedUserCanvas && xHeightPx != null && CursiveJoins.isCursiveHand(
            "acdeimnrsuxz".mapNotNull { c -> template.glyphs[c]?.firstOrNull()?.let { tipsOf(preparedGlyph(c, it))?.hasLeadIn } },
        )
        // Canvas px; the stroke-width estimate of the ink style counts slanted strokes too wide.
        val joinPenPx by lazy {
            "acemnorsuvwxz".mapNotNull { c -> template.glyphs[c]?.firstOrNull()?.let { CursiveJoins.penWidth(alphaOf(it), it.width) } }
                .sorted().let { it.getOrElse(it.size / 2) { synthesizedInkStyle.strokeWidthPx } }
        }
        val tails = HashMap<Bitmap, List<IntArray>>()
        fun tailOf(glyph: PreparedGlyph, tips: CursiveJoins.Tips): List<IntArray> = tails.getOrPut(glyph.bitmap) {
            CursiveJoins.tailRuns(
                alphaOf(glyph.bitmap), glyph.bitmap.width, tips.exitX, tips.exitY, glyph.baselineRow - xHeightPx!!, glyph.baselineRow, joinPenPx * 5f, joinPenPx,
            )
        }
        val leadIns = HashMap<Bitmap, CursiveJoins.LeadIn>()
        fun leadInOf(glyph: PreparedGlyph, tips: CursiveJoins.Tips): CursiveJoins.LeadIn = leadIns.getOrPut(glyph.bitmap) {
            CursiveJoins.leadInRuns(
                alphaOf(glyph.bitmap), glyph.bitmap.width, tips.entryX, tips.entryY, glyph.baselineRow - xHeightPx!!, joinPenPx * 5f,
                settleRows = ceil(joinPenPx).toInt(),
            )
        }
        val connectors = mutableListOf<Connector>()
        val joins = mutableListOf<Pair<Pair<Float, Float>, Pair<Float, Float>>>()
        val joinShifts = mutableListOf<Int>()

        val kernGapPx = max(2, (lineHeightPx * 0.015f).toInt())
        fun kern(prev: PreparedGlyph, prevScaleX: Float, prevScaleY: Float, next: PreparedGlyph, nextScaleX: Float, nextScaleY: Float, distancePx: Int): Int =
            if (!prev.normalizedUserCanvas || !next.normalizedUserCanvas) 0 else kerningPx(
                inkProfile(prev.bitmap).second, prev.baselineRow, prevScaleX, prevScaleY,
                inkProfile(next.bitmap).first, next.baselineRow, nextScaleX, nextScaleY,
                distancePx, kernGapPx, (next.bitmap.width * nextScaleX * 0.5f).toInt(),
            )

        fun wordWidth(word: String): Int {
            var w = 0
            var previous: Triple<PreparedGlyph, Float, Float>? = null
            var previousLeft = 0
            word.forEachIndexed { i, original ->
                val accent = accentFor(original)
                val ch = accent?.base ?: original
                val glyph = variantsFor(ch)?.firstOrNull()
                if (glyph == null) {
                    previous = null
                    w += when {
                        ch in setOf('.', '!', '?') -> (lineHeightPx * 0.14f).toInt().coerceAtLeast(6)
                        ch in punctuationChars -> (lineHeightPx * 0.08f).toInt().coerceAtLeast(4)
                        else -> spaceWidth
                    }
                } else {
                    val prepared = preparedGlyph(ch, glyph)
                    val targetInkHeight = targetInkHeightPx(
                        lineHeightPx = lineHeightPx,
                        character = ch,
                        isUserTemplate = isUserTemplate,
                        normalizedUserCanvas = prepared.normalizedUserCanvas,
                    )
                    val (glyphScaleX, glyphScaleY) = UserGlyphWidthNormalizer.scales(
                        character = ch,
                        templateId = template.descriptor.id,
                        glyphWidthPx = prepared.sizeWidth,
                        glyphHeightPx = prepared.sizeHeight,
                        targetInkHeightPx = targetInkHeight,
                    )
                    previous?.let { (p, sx, sy) -> w -= kern(p, sx, sy, prepared, glyphScaleX, glyphScaleY, w - previousLeft) }
                    previous = Triple(prepared, glyphScaleX, glyphScaleY)
                    previousLeft = w
                    w += glyphAdvancePx(
                        glyph = prepared,
                        character = ch,
                        scaleX = glyphScaleX,
                        lineHeightPx = lineHeightPx,
                        normalizedUserCanvas = prepared.normalizedUserCanvas,
                    ) + effectiveHorizontalSpacingPx +
                        markLeadPx(word.getOrNull(i - 1), ch, lineHeightPx, prepared.normalizedUserCanvas)
                }
            }
            return w
        }

        // Worst case: every gap jitters wide and the last glyph's ink reaches past its advance.
        fun maxWordWidth(word: String): Int =
            wordWidth(word) + word.length * effectiveSpacingJitterPx + effectiveJitterXPx + (lineHeightPx * 0.04f).toInt()

        val lineWidth = safeWidth - 2 * config.marginPx

        fun hyphenatedPieces(word: String): List<String> =
            WordHyphenator.pieces(word) { maxWordWidth(it) <= lineWidth }

        val lines = (if (normalizedText.isBlank()) " " else normalizedText).split('\n')
        for (line in lines) {
            if (line.isEmpty()) {
                moveToNextLine()
                continue
            }
            val words = line.split(' ')
            val totalCharsInLine = line.length.coerceAtLeast(1)

            for ((wordIndex, word) in words.withIndex()) {
                if (wordIndex > 0) {
                    val neededWidth = spaceWidth + maxWordWidth(word)
                    if (cursorX + neededWidth > safeWidth - config.marginPx && cursorX > config.marginPx) {
                        moveToNextLine()
                    } else {
                        cursorX += spaceWidth + if (effectiveSpacingJitterPx > 0) {
                            rng.nextInt(-effectiveSpacingJitterPx, effectiveSpacingJitterPx + 1)
                        } else {
                            0
                        }
                        maxUsedX = max(maxUsedX, cursorX)
                        laidOutLines.last().append(' ')
                    }
                }

                for ((pieceIndex, piece) in hyphenatedPieces(word).withIndex()) {
                    if (pieceIndex > 0) moveToNextLine()
                    var previousTucked = false
                    var previousGlyph: Triple<PreparedGlyph, Float, Float>? = null
                    var previousLeft = 0
                    var previousLine = -1
                    var previousPlaced: Pair<Char, GlyphPlacement>? = null
                    var previousJoin: Pair<PreparedGlyph, CursiveJoins.Tips>? = null
                    for ((charIndex, typed) in piece.withIndex()) {
                        // An accented letter without a glyph is sized and placed as its base letter.
                        val accent = accentFor(typed)
                        val character = accent?.base ?: typed
                        val variants = variantsFor(character)

                        var glyph: Bitmap? = variants?.let { it[rng.nextInt(it.size)] }

                        // Auto-synthesize common punctuation that the user template does not
                        // contain — pen-style approximations of '.', ',', '!', '?', ':', ';',
                        // quotes, and hyphen. Only applies to user templates; bundled templates
                        // already ship complete punctuation sets.
                        if (glyph == null && isUserTemplate && PunctuationSynthesizer.supports(character)) {
                            glyph = synthesizedGlyphCache.getOrPut(character) {
                                PunctuationSynthesizer.synthesize(character, synthesizedInkStyle, writerMarks)
                            }
                        }

                        if (glyph == null) {
                            missingCharacters += character
                            val missingAdvance = when {
                                character in setOf('.', '!', '?') -> (lineHeightPx * 0.14f).toInt().coerceAtLeast(6)
                                character in punctuationChars -> (lineHeightPx * 0.08f).toInt().coerceAtLeast(4)
                                else -> spaceWidth
                            }
                            cursorX += missingAdvance
                            maxUsedX = max(maxUsedX, cursorX)
                            charIndexInLine++
                            previousGlyph = null
                            previousPlaced = null
                            previousJoin = null
                            continue
                        }

                        val prepared = if (accent != null) composedGlyph(typed, accent, glyph) else preparedGlyph(character, glyph)
                        cursorX += markLeadPx(piece.getOrNull(charIndex - 1), character, lineHeightPx, prepared.normalizedUserCanvas)
                        val targetInkHeight = targetInkHeightPx(
                            lineHeightPx = lineHeightPx,
                            character = character,
                            isUserTemplate = isUserTemplate,
                            normalizedUserCanvas = prepared.normalizedUserCanvas,
                        )
                        // User templates hold one sample per letter; vary its size a little so
                        // repeated letters do not read as stamped copies.
                        val sizeJitter = if (isUserTemplate) 1f + (rng.nextFloat() * 2f - 1f) * 0.04f else 1f
                        val (glyphScaleX, glyphScaleY) = UserGlyphWidthNormalizer.scales(
                            character = character,
                            templateId = template.descriptor.id,
                            glyphWidthPx = prepared.sizeWidth,
                            glyphHeightPx = prepared.sizeHeight,
                            targetInkHeightPx = targetInkHeight,
                        ).let { (x, y) -> x * sizeJitter to y * sizeJitter }
                        val scaledGlyphW = (prepared.bitmap.width * glyphScaleX).toInt().coerceAtLeast(1)
                        val scaledGlyphH = (prepared.bitmap.height * glyphScaleY).toInt().coerceAtLeast(1)
                        val advance = glyphAdvancePx(
                            glyph = prepared,
                            character = character,
                            scaleX = glyphScaleX,
                            lineHeightPx = lineHeightPx,
                            normalizedUserCanvas = prepared.normalizedUserCanvas,
                        )

                        if (cursorX + scaledGlyphW > safeWidth - config.marginPx && cursorX > config.marginPx) {
                            moveToNextLine()
                        }

                        val jx = rng.nextInt(-effectiveJitterXPx, effectiveJitterXPx + 1)
                        val jy = rng.nextInt(-effectiveJitterYPx, effectiveJitterYPx + 1)
                        val waveOffset = effectiveBaselineWaveAmplitude *
                            sin((charIndexInLine / config.baselineWavePeriod + lineBaselinePhase).toDouble()).toFloat()
                        val driftOffset = lineDriftSlope * (charIndexInLine.toFloat() / totalCharsInLine)
                        val rotationLimit = if (isUserTemplate) 1.8f else 3.5f
                        val rotation = (rng.nextFloat() * 2f - 1f) * rotationLimit
                        val alpha = 0.90f + rng.nextFloat() * 0.08f  // 230-250 range

                        val baselineY = cursorY + baselineOffsetPx
                        // For user templates the baselineFraction in prepareGlyph() already places
                        // the glyph's ink-baseline at baselineY exactly — no additional drop needed.
                        // For bundled templates the legacy descenderDrop gives the slight optical shift.
                        val descenderDrop = if (!isUserTemplate && character.lowercaseChar() in descenderChars) {
                            (lineHeightPx * descenderDropRatio(isUserTemplate)).toInt()
                        } else {
                            0
                        }
                        val drawTop = baselineY - (prepared.baselineRow * glyphScaleY).toInt() + descenderDrop
                        val unkernedLeft = cursorX + jx - (prepared.leftOverhang * glyphScaleX).toInt() - leadingPullPx(
                            character = character,
                            lineHeightPx = lineHeightPx,
                            normalizedUserCanvas = prepared.normalizedUserCanvas,
                            previousTucked = previousTucked,
                        )
                        val kernPx = previousGlyph?.takeIf { previousLine == laidOutLines.lastIndex }
                            ?.let { (p, sx, sy) -> kern(p, sx, sy, prepared, glyphScaleX, glyphScaleY, unkernedLeft - previousLeft) } ?: 0
                        var drawLeft = unkernedLeft - kernPx
                        cursorX -= kernPx
                        previousGlyph = Triple(prepared, glyphScaleX, glyphScaleY)
                        previousLeft = drawLeft
                        previousLine = laidOutLines.lastIndex
                        previousTucked = prepared.advanceWidth < prepared.sizeWidth

                        laidOutLines.last().append(typed)
                        val placementTop = drawTop + jy + waveOffset.toInt() + driftOffset.toInt()
                        fun toOutput(px: Int, py: Int): Pair<Float, Float> = outputPoint(
                            drawLeft, placementTop, glyphScaleX, glyphScaleY, rotation,
                            prepared.bitmap.width, prepared.bitmap.height, px, py,
                        )
                        val tips = if (cursiveHand) tipsOf(prepared) else null
                        var drawnBitmap = prepared.bitmap
                        val previous = previousPlaced
                            ?.takeIf { (c, p) -> p.line == laidOutLines.lastIndex && CursiveJoins.joins(c, character) && p === placements.lastOrNull() }
                            ?.second
                        val tailSource = previousJoin
                        if (tips != null && tips.hasLeadIn && !tips.inkAboveLeft && previous != null && tailSource != null) {
                            val (tailGlyph, tailTips) = tailSource
                            fun previousOutput(px: Int, py: Int): Pair<Float, Float> = outputPoint(
                                previous.left, previous.top, previous.drawScaleX, previous.drawScaleY, previous.rotationDeg,
                                previous.bitmap.width, previous.bitmap.height, px, py,
                            )
                            val tail = tailOf(tailGlyph, tailTips)
                            val traced = leadInOf(prepared, tips)
                            val leadIn = traced.runs
                            val tailCenters = tail.map { (y, x0, x1) -> previousOutput((x0 + x1) / 2, y) }
                            val placedLeadCenters = leadIn.map { (y, x0, x1) -> toOutput((x0 + x1) / 2, y) }
                            val reach = ceil(joinPenPx).toInt().coerceAtLeast(2)
                            // The pen-down blob is never kept: the lead-in counts from a pen width above its pen-down row.
                            val firstLeadIn = leadIn.indexOfFirst { it[0] <= tips.entryY - reach }
                            // The writer's tail and lead-in carry the join, not drawn ink: the pen would have started this letter
                            // a little nearer or farther, so it moves up to a quarter of the x-height to where the bridge is shortest.
                            // Farther than half the x-height is letter spacing, not a pen break: both letters stay whole.
                            val maxShift = (xHeightPx!! * glyphScaleY * 0.25f).toInt()
                            // Only the lead-in's own ink: past its merge the run centres are those of the letter's stem.
                            val cut = if (tail.isEmpty() || firstLeadIn < 0) null else CursiveJoins.joinCuts(
                                tailCenters, placedLeadCenters.take(traced.ownRuns), firstLeadIn, reach,
                                maxLength = xHeightPx!! * glyphScaleY * 0.5f, shifts = -maxShift..maxShift, penPx = joinPenPx * glyphScaleY,
                            )
                            val shift = cut?.shift ?: 0
                            val cuts = cut?.let { it.tail to it.leadIn }
                            val leadCenters = placedLeadCenters.map { (x, y) -> x + shift to y }
                            val from = cuts?.let { tailCenters[it.first] }
                            val to = cuts?.let { leadCenters[it.second] }
                            if (cuts != null && from != null && to != null) {
                                drawLeft += shift
                                cursorX += shift
                                previousLeft = drawLeft
                                val (i, j) = cuts
                                joins += from to to
                                joinShifts += shift
                                val tailDirection = CursiveJoins.strokeDirection(tailCenters.subList(i, minOf(i + reach, tail.lastIndex) + 1).reversed())!!
                                val leadDirection = CursiveJoins.strokeDirection(leadCenters.subList(j, minOf(j + reach, leadIn.lastIndex) + 1))!!
                                // The tail now ends and the lead-in now starts at the cuts: the stroke beyond a cut goes,
                                // and within a pen width of the cut the ink goes on the far side of a line across the
                                // stroke, because a flat stroke's rows there also hold the edge of the part that stays.
                                val (tipY, tipX0, tipX1) = tail.first()
                                placements[placements.lastIndex] = previous.copy(
                                    bitmap = eraseBeyondCut(
                                        previous.bitmap,
                                        gone = tail.take(maxOf(0, i - reach)) + listOf(1, 2).map { intArrayOf(tipY - it, tipX0, tipX1) },
                                        near = tail.subList(maxOf(0, i - reach), minOf(i + reach + 1, tail.size)),
                                        toOutput = ::previousOutput,
                                    ) { (x, y) -> (x - from.first) * tailDirection.first + (y - from.second) * tailDirection.second > 0f },
                                )
                                // Only the lead-in's own ink: past its merge the stroke is also the letter's stem.
                                val (penDownY, penDownX0, penDownX1) = leadIn.first()
                                val leadCut = minOf(j, traced.ownRuns)
                                drawnBitmap = eraseBeyondCut(
                                    prepared.bitmap,
                                    gone = leadIn.take(maxOf(firstLeadIn, leadCut - reach)) + listOf(1, 2).map { intArrayOf(penDownY + it, penDownX0, penDownX1) },
                                    near = leadIn.subList(maxOf(firstLeadIn, leadCut - reach), minOf(j + reach + 1, traced.ownRuns)),
                                    toOutput = ::toOutput,
                                ) { (x, y) -> (x - to.first) * leadDirection.first + (y - to.second) * leadDirection.second < 0f }
                                // The cross-section of each stroke the bridge joins, over the pen width it keeps past its cut,
                                // as drawn: in output px, with the glyph's alpha; and whether it was a single stroke there.
                                fun drawnSection(
                                    glyph: Bitmap, runs: List<IntArray>, outDirection: Pair<Float, Float>, scaleX: Float, scaleY: Float, glyphAlpha: Float,
                                ): Pair<FloatArray, Boolean> {
                                    val inGlyph = CursiveJoins.direction(0f to 0f, outDirection.first / scaleX to outDirection.second / scaleY)!!
                                    // Up to 2 px of soft edge past the pen: farther ink beside the cut is another stroke.
                                    val section = CursiveJoins.crossSection(
                                        alphaOf(glyph), glyph.width, runs.map { (y, x0, x1) -> (x0 + x1) / 2f to y.toFloat() }, inGlyph,
                                        maxDistance = joinPenPx / 2 + 2f,
                                    )
                                    val scale = hypot(inGlyph.second * scaleX, inGlyph.first * scaleY)
                                    return FloatArray(CursiveJoins.SECTION_SIZE) { k -> CursiveJoins.sectionAt(section, k * CursiveJoins.SECTION_STEP / scale) * glyphAlpha } to
                                        CursiveJoins.isSingleStroke(section, joinPenPx)
                                }
                                val (tailSection, tailSingle) = drawnSection(
                                    tailGlyph.bitmap, tail.subList(i, minOf(i + reach + 1, tail.size)), tailDirection,
                                    previous.drawScaleX, previous.drawScaleY, previous.alpha,
                                )
                                val (leadSection, leadSingle) = drawnSection(
                                    prepared.bitmap, leadIn.subList(j, j + reach + 1), leadDirection, glyphScaleX, glyphScaleY, alpha,
                                )
                                // Where a cut stroke runs into another one, the bridge takes the other end's cross-section.
                                connectors += Connector(
                                    from, to, tailDirection, leadDirection,
                                    if (tailSingle || !leadSingle) tailSection else leadSection,
                                    if (leadSingle || !tailSingle) leadSection else tailSection,
                                    laidOutLines.lastIndex,
                                )
                            }
                        }
                        placements += GlyphPlacement(
                            bitmap = drawnBitmap,
                            left = drawLeft,
                            top = placementTop,
                            rotationDeg = rotation,
                            alpha = alpha,
                            drawScaleX = glyphScaleX,
                            drawScaleY = glyphScaleY,
                            line = laidOutLines.lastIndex,
                        )
                        previousPlaced = character to placements.last()
                        previousJoin = tips?.let { prepared to it }

                        cursorX += advance + effectiveHorizontalSpacingPx + if (effectiveSpacingJitterPx > 0) {
                            rng.nextInt(-effectiveSpacingJitterPx, effectiveSpacingJitterPx + 1)
                        } else {
                            0
                        }
                        lineHeight = max(
                            lineHeight,
                            max(
                                lineHeightPx,
                                // Normalized user canvas glyphs are pre-scaled to lineHeightPx, so
                                // adding effectiveVerticalSpacingPx/2 here would push lineHeight just
                                // above lineHeightPx and cause the text baselines to drift below the
                                // background guide lines line by line.
                                if (prepared.normalizedUserCanvas) {
                                    scaledGlyphH
                                } else {
                                    scaledGlyphH + effectiveVerticalSpacingPx / 2
                                },
                            ),
                        )
                        maxUsedX = max(maxUsedX, cursorX)
                        charIndexInLine++
                    }
                }
            }
            moveToNextLine()
        }

        val extraBottom = (effectiveBaselineWaveAmplitude + effectiveLineDriftPx + effectiveJitterYPx).toInt()
        val bitmapHeight = (cursorY + lineHeight + config.marginPx + extraBottom).coerceAtLeast(1)
        val bitmapWidth = max(maxUsedX + config.marginPx, safeWidth)
        val output = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val lineSpacingPx = if (hasNormalizedUserCanvas) {
            lineAdvancePx + effectiveVerticalSpacingPx
        } else {
            lineHeightPx + effectiveVerticalSpacingPx
        }
        val firstBaselinePx = config.marginPx + lineHeightPx
        drawBackground(
            canvas = canvas,
            width = bitmapWidth,
            height = bitmapHeight,
            config = config,
            seed = seed,
            lineSpacingPx = lineSpacingPx,
            firstBaselinePx = firstBaselinePx,
        )
        val paper = if (keepPaper) output.copy(Bitmap.Config.ARGB_8888, false) else null
        val inkRows = HashMap<Int, InkRow>()

        val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            isDither = true
        }
        val matrix = Matrix()
        // Joined letters go on their own ink layer first, where a bridge can take over the ink it meets.
        val ink = if (connectors.isEmpty()) null else Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)

        placements.forEach { placement ->
            growRow(
                inkRows, placement.line, placement.left.toFloat(), placement.top.toFloat(),
                placement.bitmap.width * placement.drawScaleX, placement.bitmap.height * placement.drawScaleY,
                placement.rotationDeg,
            )
            glyphPaint.alpha = (placement.alpha * 255).toInt().coerceIn(0, 255)

            // Subtle mesh warp for organic wavy strokes
            val bmp = placement.bitmap
            val meshW = 6
            val meshH = 6
            val verts = FloatArray((meshW + 1) * (meshH + 1) * 2)
            val wAmp = if (isUserTemplate) 0.12f else 0.5f + rng.nextFloat() * 0.5f
            val wP1 = rng.nextFloat() * 6.2832f
            val wP2 = rng.nextFloat() * 6.2832f
            for (row in 0..meshH) {
                for (col in 0..meshW) {
                    val idx = (row * (meshW + 1) + col) * 2
                    val fx = col.toFloat() / meshW * bmp.width
                    val fy = row.toFloat() / meshH * bmp.height
                    val ox = wAmp * (
                        0.6f * sin((fy * 0.18f + wP1).toDouble()).toFloat() +
                        0.4f * sin((fy * 0.39f + wP2).toDouble()).toFloat()
                    )
                    val oy = wAmp * 0.5f * (
                        0.6f * sin((fx * 0.15f + wP1).toDouble()).toFloat() +
                        0.4f * sin((fx * 0.33f + wP2).toDouble()).toFloat()
                    )
                    verts[idx] = fx + ox
                    verts[idx + 1] = fy + oy
                }
            }

            val scaleX = placement.drawScaleX
            val scaleY = placement.drawScaleY

            matrix.reset()
            matrix.postScale(scaleX, scaleY)
            matrix.postTranslate(placement.left.toFloat(), placement.top.toFloat())
            if (placement.rotationDeg != 0f) {
                val cx = bmp.width * scaleX / 2f
                val cy = bmp.height * scaleY / 2f
                matrix.postRotate(placement.rotationDeg, placement.left + cx, placement.top + cy)
            }
            if (ink == null) {
                canvas.save()
                canvas.concat(matrix)
                canvas.drawBitmapMesh(bmp, meshW, meshH, verts, 0, null, 0, glyphPaint)
                canvas.restore()
            } else {
                // On the ink layer each pixel keeps the darker ink: where a join's tail and lead-in overlap, one stroke stays one stroke.
                val bounds = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()).also(matrix::mapRect)
                val left = (floor(bounds.left).toInt() - 1).coerceAtLeast(0)
                val top = (floor(bounds.top).toInt() - 1).coerceAtLeast(0)
                val w = (ceil(bounds.right).toInt() + 1).coerceAtMost(bitmapWidth) - left
                val h = (ceil(bounds.bottom).toInt() + 1).coerceAtMost(bitmapHeight) - top
                if (w > 0 && h > 0) {
                    val layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    Canvas(layer).apply {
                        translate(-left.toFloat(), -top.toFloat())
                        concat(matrix)
                        drawBitmapMesh(bmp, meshW, meshH, verts, 0, null, 0, glyphPaint)
                    }
                    val drawn = IntArray(w * h).also { layer.getPixels(it, 0, w, 0, 0, w, h) }
                    val pixels = IntArray(w * h).also { ink.getPixels(it, 0, w, left, top, w, h) }
                    for (i in pixels.indices) if (drawn[i] ushr 24 > pixels[i] ushr 24) pixels[i] = drawn[i]
                    ink.setPixels(pixels, 0, w, left, top, w, h)
                    layer.recycle()
                }
            }
        }

        // Connectors after the glyphs: each pixel takes the darker of the bridge and the ink already there, so the
        // bridge runs into the ends it joins without a seam or doubled ink.
        val inkRgb = synthesizedInkStyle.argb and 0xFFFFFF
        connectors.forEach { c ->
            val (x0, y0) = c.from
            val (x1, y1) = c.to
            // Leaves along the tail and arrives along the lead-in.
            val reach = hypot(x1 - x0, y1 - y0) / 3
            val ax = x0 + c.direction.first * reach
            val ay = y0 + c.direction.second * reach
            val bx = x1 - c.entryDirection.first * reach
            val by = y1 - c.entryDirection.second * reach
            val steps = ceil(reach * 3).toInt().coerceAtLeast(8)
            val path = (0..steps).map { k ->
                val t = k.toFloat() / steps
                val u = 1 - t
                (u * u * u * x0 + 3 * u * u * t * ax + 3 * u * t * t * bx + t * t * t * x1) to
                    (u * u * u * y0 + 3 * u * u * t * ay + 3 * u * t * t * by + t * t * t * y1)
            }
            val pad = CursiveJoins.SECTION_SIZE * CursiveJoins.SECTION_STEP + 1
            val left = floor(minOf(x0, x1, ax, bx) - pad).toInt().coerceAtLeast(0)
            val top = floor(minOf(y0, y1, ay, by) - pad).toInt().coerceAtLeast(0)
            val w = ceil(maxOf(x0, x1, ax, bx) + pad).toInt().coerceAtMost(bitmapWidth) - left
            val h = ceil(maxOf(y0, y1, ay, by) + pad).toInt().coerceAtMost(bitmapHeight) - top
            if (ink == null || w <= 0 || h <= 0) return@forEach
            val coverage = CursiveJoins.bridgeCoverage(path, c.direction, c.entryDirection, c.fromSection, c.toSection, left, top, w, h, extend = 1f)
            val pixels = IntArray(w * h).also { ink.getPixels(it, 0, w, left, top, w, h) }
            var inkLeft = w
            var inkTop = h
            var inkRight = -1
            var inkBottom = -1
            for (i in pixels.indices) {
                val a = coverage[i].roundToInt().coerceIn(0, 255)
                if (a == 0) continue
                inkLeft = minOf(inkLeft, i % w)
                inkRight = maxOf(inkRight, i % w)
                inkTop = minOf(inkTop, i / w)
                inkBottom = maxOf(inkBottom, i / w)
                val existing = pixels[i] ushr 24
                if (a > existing) pixels[i] = (a shl 24) or if (existing > 0) pixels[i] and 0xFFFFFF else inkRgb
            }
            ink.setPixels(pixels, 0, w, left, top, w, h)
            if (inkRight >= 0) {
                growRow(inkRows, c.line, (left + inkLeft).toFloat(), (top + inkTop).toFloat(), (inkRight - inkLeft + 1).toFloat(), (inkBottom - inkTop + 1).toFloat(), 0f)
            }
        }
        ink?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        return HandwritingRenderResult(
            bitmap = output,
            missingCharacters = missingCharacters,
            lines = laidOutLines.map(StringBuilder::toString).filter(String::isNotEmpty),
            joins = joins,
            joinShifts = joinShifts,
            inkRows = clampedBands(inkRows, bitmapWidth, bitmapHeight),
            paper = paper,
        )
    }
}

private data class GlyphPlacement(
    val bitmap: Bitmap,
    val left: Int,
    val top: Int,
    val rotationDeg: Float = 0f,
    val alpha: Float = 1f,
    val drawScaleX: Float = 1f,
    val drawScaleY: Float = 1f,
    val line: Int = 0,
)

/**
 * A copy of [bitmap] without the ink of the runs (row, first x, last x; with a 2 px edge) in [gone],
 * and without the ink of those in [near] whose output position, by [toOutput], lies [beyond] a cut.
 */
private fun eraseBeyondCut(
    bitmap: Bitmap,
    gone: List<IntArray>,
    near: List<IntArray>,
    toOutput: (Int, Int) -> Pair<Float, Float>,
    beyond: (Pair<Float, Float>) -> Boolean,
): Bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true).also { b ->
    fun clear(runs: List<IntArray>, where: (Int, Int) -> Boolean) = runs.filter { it[0] in 0 until b.height }.forEach { (y, x0, x1) ->
        for (x in (x0 - 2).coerceAtLeast(0)..(x1 + 2).coerceAtMost(b.width - 1)) if (where(x, y)) b.setPixel(x, y, 0)
    }
    clear(gone) { _, _ -> true }
    clear(near) { x, y -> beyond(toOutput(x, y)) }
}

/**
 * A pen stroke joining two letters of a cursive hand, in output pixels: it leaves [from] along [direction]
 * and reaches [to] along [entryDirection]; its cross-section (alpha 0..255 in [CursiveJoins.SECTION_STEP]
 * px steps) runs from [fromSection] to [toSection].
 */
private class Connector(
    val from: Pair<Float, Float>,
    val to: Pair<Float, Float>,
    val direction: Pair<Float, Float>,
    val entryDirection: Pair<Float, Float>,
    val fromSection: FloatArray,
    val toSection: FloatArray,
    val line: Int,
)

/** Output position of pixel ([px], [py]) of a glyph drawn as the render loop draws it: scaled, placed, rotated. */
private fun outputPoint(
    left: Int,
    top: Int,
    scaleX: Float,
    scaleY: Float,
    rotationDeg: Float,
    bitmapWidth: Int,
    bitmapHeight: Int,
    px: Int,
    py: Int,
): Pair<Float, Float> {
    val x = left + (px + 0.5f) * scaleX
    val y = top + (py + 0.5f) * scaleY
    if (rotationDeg == 0f) return x to y
    val cx = left + bitmapWidth * scaleX / 2f
    val cy = top + bitmapHeight * scaleY / 2f
    val r = Math.toRadians(rotationDeg.toDouble())
    val c = cos(r).toFloat()
    val s = sin(r).toFloat()
    return cx + (x - cx) * c - (y - cy) * s to cy + (x - cx) * s + (y - cy) * c
}
