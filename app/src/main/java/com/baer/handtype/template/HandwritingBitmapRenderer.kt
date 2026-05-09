package com.baer.handtype.template

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Typeface
import kotlin.math.max
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
)

data class HandwritingRenderResult(
    val bitmap: Bitmap,
    val missingCharacters: Set<Char>,
)

object HandwritingBitmapRenderer {

    private val descenderChars = setOf('g', 'j', 'p', 'q', 'y')
    private val dottedChars = setOf('i', 'j')
    private val punctuationChars = setOf('.', ',', ';', ':', '!', '?', '\'', '"', '`')

    private data class InkBounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width: Int get() = right - left + 1
        val height: Int get() = bottom - top + 1
    }

    private data class PreparedGlyph(
        val bitmap: Bitmap,
        val baselineRow: Int,
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
            InkBounds(minX, minY, maxX, maxY)
        } else {
            InkBounds(0, 0, w - 1, h - 1)
        }
    }

    private fun prepareGlyph(
        bitmap: Bitmap,
        character: Char,
        templateBaselineRow: Int,
        isUserTemplate: Boolean,
    ): PreparedGlyph {
        val bounds = measureInkBounds(bitmap)
        val cropped = Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width, bounds.height)
        val lower = character.lowercaseChar()
        val baselineInSource = when {
            // Only lowercase descenders get the fraction-based baseline.
            // Uppercase G/J/P/Q/Y have no descender — their baseline is at bounds.bottom.
            isUserTemplate && character.isLowerCase() && lower in descenderChars -> {
                val baselineFraction = when (lower) {
                    'g', 'j' -> 0.50f
                    else -> 0.58f
                }
                (bounds.top + (bounds.height * baselineFraction).toInt()).coerceIn(bounds.top, bounds.bottom)
            }
            lower in descenderChars -> templateBaselineRow.coerceIn(bounds.top, bounds.bottom)
            else -> bounds.bottom
        }
        return PreparedGlyph(
            bitmap = cropped,
            baselineRow = (baselineInSource - bounds.top).coerceIn(0, cropped.height - 1),
        )
    }

    internal fun targetInkHeightRatio(character: Char, isUserTemplate: Boolean): Float {
        val lower = character.lowercaseChar()
        val baseRatio = when {
            character in punctuationChars -> 0.24f
            character.isDigit() -> 0.72f
            character.isUpperCase() -> 0.80f
            lower in descenderChars -> 0.64f
            lower in setOf('b', 'd', 'f', 'h', 'k', 'l', 't') -> 0.66f
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

    private fun glyphAdvancePx(
        glyph: PreparedGlyph,
        character: Char,
        scaleX: Float,
        lineHeightPx: Int,
    ): Int {
        val rawAdvance = (glyph.bitmap.width * scaleX * 0.90f).toInt() +
            (lineHeightPx * 0.04f).toInt()
        val minAdvance = when {
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
        )

        val placements = mutableListOf<WordPlacement>()
        var cursorX = config.marginPx.toFloat()
        var cursorY = config.marginPx.toFloat() + charSize
        var charIndexInLine = 0
        var lineBaselinePhase = rng.nextFloat() * 6.2832f
        var lineDriftSlope = (rng.nextFloat() * 2f - 1f) * config.lineDriftPx
        var maxUsedX = cursorX

        fun newLine() {
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
            val words = line.split(' ')
            val totalCharsInLine = line.length.coerceAtLeast(1)

            for ((wordIndex, word) in words.withIndex()) {
                if (word.isEmpty()) continue

                val wordSizeJitter = 1f + (rng.nextFloat() * 2f - 1f) * 0.02f  // ±2%
                val wordCharSize = charSize * wordSizeJitter
                val wordWidth = estimateWordWidth(word)

                if (wordIndex > 0) {
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

        val placementPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val matrix = Matrix()

        placements.forEach { p ->
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

        return HandwritingRenderResult(bitmap = output, missingCharacters = missingCharacters)
    }

    fun render(
        text: String,
        template: HandwritingTemplate,
        config: HandwritingRenderConfig,
        seed: Long = System.nanoTime(),
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
        fun preparedGlyph(character: Char, bitmap: Bitmap): PreparedGlyph {
            return preparedGlyphs.getOrPut(bitmap) {
                prepareGlyph(bitmap, character, templateBaselineRow, isUserTemplate)
            }
        }

        val averageInkWidth = rawBounds.values.map(InkBounds::width).average().toInt().coerceAtLeast(24)
        val averageInkHeight = rawBounds.values.map(InkBounds::height).average().toInt().coerceAtLeast(48)
        val lineHeightPx = if (config.targetLineHeightPx > 0) {
            config.targetLineHeightPx
        } else {
            (averageInkHeight * 1.35f).toInt().coerceAtLeast(72)
        }
        val effectiveVerticalSpacingPx = if (isUserTemplate) {
            (config.verticalSpacingPx / 2).coerceAtLeast(4)
        } else {
            config.verticalSpacingPx
        }
        val baselineOffsetPx = (lineHeightPx * 0.74f).toInt().coerceAtLeast(1)
        val spaceWidth = max((lineHeightPx * 0.20f).toInt(), (averageInkWidth * 0.45f).toInt()).coerceAtLeast(14)
        val missingCharacters = linkedSetOf<Char>()
        val placements = mutableListOf<GlyphPlacement>()

        var cursorX = config.marginPx
        var cursorY = config.marginPx
        var lineHeight = lineHeightPx
        var maxUsedX = config.marginPx
        var charIndexInLine = 0
        var lineBaselinePhase = rng.nextFloat() * 6.2832f
        var lineDriftSlope = (rng.nextFloat() * 2f - 1f) * config.lineDriftPx

        fun moveToNextLine() {
            cursorX = config.marginPx
            cursorY += lineHeight + effectiveVerticalSpacingPx
            lineHeight = lineHeightPx
            charIndexInLine = 0
            lineBaselinePhase = rng.nextFloat() * 6.2832f
            lineDriftSlope = (rng.nextFloat() * 2f - 1f) * config.lineDriftPx
        }

        fun wordWidth(word: String): Int {
            var w = 0
            word.forEach { ch ->
                val variants = template.glyphs[ch]
                    ?: template.glyphs[ch.lowercaseChar()]
                    ?: template.glyphs[ch.uppercaseChar()]
                val glyph = variants?.firstOrNull()
                if (glyph == null) {
                    w += spaceWidth
                } else {
                    val prepared = preparedGlyph(ch, glyph)
                    val targetInkHeight = (lineHeightPx * targetInkHeightRatio(ch, isUserTemplate)).toInt().coerceAtLeast(12)
                    val (glyphScaleX, _) = UserGlyphWidthNormalizer.scales(
                        character = ch,
                        templateId = template.descriptor.id,
                        glyphWidthPx = prepared.bitmap.width,
                        glyphHeightPx = prepared.bitmap.height,
                        targetInkHeightPx = targetInkHeight,
                    )
                    w += glyphAdvancePx(prepared, ch, glyphScaleX, lineHeightPx) + config.horizontalSpacingPx
                }
            }
            return w
        }

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
                    val neededWidth = spaceWidth + wordWidth(word)
                    if (cursorX + neededWidth > safeWidth - config.marginPx && cursorX > config.marginPx) {
                        moveToNextLine()
                    } else {
                        cursorX += spaceWidth + rng.nextInt(-config.spacingJitter, config.spacingJitter + 1)
                        maxUsedX = max(maxUsedX, cursorX)
                    }
                }

                for (character in word) {
                    val variants = template.glyphs[character]
                        ?: template.glyphs[character.lowercaseChar()]
                        ?: template.glyphs[character.uppercaseChar()]

                    val glyph = variants?.let { it[rng.nextInt(it.size)] }

                    if (glyph == null) {
                        missingCharacters += character
                        cursorX += spaceWidth
                        maxUsedX = max(maxUsedX, cursorX)
                        charIndexInLine++
                        continue
                    }

                    val prepared = preparedGlyph(character, glyph)
                    val targetInkHeight = (lineHeightPx * targetInkHeightRatio(character, isUserTemplate)).toInt().coerceAtLeast(12)
                    val (glyphScaleX, glyphScaleY) = UserGlyphWidthNormalizer.scales(
                        character = character,
                        templateId = template.descriptor.id,
                        glyphWidthPx = prepared.bitmap.width,
                        glyphHeightPx = prepared.bitmap.height,
                        targetInkHeightPx = targetInkHeight,
                    )
                    val scaledGlyphW = (prepared.bitmap.width * glyphScaleX).toInt().coerceAtLeast(1)
                    val scaledGlyphH = (prepared.bitmap.height * glyphScaleY).toInt().coerceAtLeast(1)
                    val advance = glyphAdvancePx(prepared, character, glyphScaleX, lineHeightPx)

                    if (cursorX + scaledGlyphW > safeWidth - config.marginPx && cursorX > config.marginPx) {
                        moveToNextLine()
                    }

                    val jx = rng.nextInt(-config.jitterX, config.jitterX + 1)
                    val jy = rng.nextInt(-config.jitterY, config.jitterY + 1)
                    val waveOffset = 6f *
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

                    placements += GlyphPlacement(
                        bitmap = prepared.bitmap,
                        left = cursorX + jx,
                        top = drawTop + jy + waveOffset.toInt() + driftOffset.toInt(),
                        rotationDeg = rotation,
                        alpha = alpha,
                        drawScaleX = glyphScaleX,
                        drawScaleY = glyphScaleY,
                    )

                    cursorX += (advance + config.horizontalSpacingPx +
                        rng.nextInt(-config.spacingJitter, config.spacingJitter + 1))
                    lineHeight = max(lineHeight, max(lineHeightPx, scaledGlyphH + effectiveVerticalSpacingPx / 2))
                    maxUsedX = max(maxUsedX, cursorX)
                    charIndexInLine++
                }
            }
            moveToNextLine()
        }

        val extraBottom = (config.baselineWaveAmplitude + config.lineDriftPx + config.jitterY).toInt()
        val bitmapHeight = (cursorY + lineHeight + config.marginPx + extraBottom).coerceAtLeast(1)
        val bitmapWidth = max(maxUsedX + config.marginPx, safeWidth)
        val output = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val lineSpacingPx = lineHeightPx + effectiveVerticalSpacingPx
        val firstBaselinePx = config.marginPx + baselineOffsetPx
        drawBackground(
            canvas = canvas,
            width = bitmapWidth,
            height = bitmapHeight,
            config = config,
            seed = seed,
            lineSpacingPx = lineSpacingPx,
            firstBaselinePx = firstBaselinePx,
        )

        val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            isDither = true
        }
        val matrix = Matrix()

        placements.forEach { placement ->
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

            if (placement.rotationDeg != 0f) {
                matrix.reset()
                matrix.postScale(scaleX, scaleY)
                matrix.postTranslate(placement.left.toFloat(), placement.top.toFloat())
                val cx = bmp.width * scaleX / 2f
                val cy = bmp.height * scaleY / 2f
                matrix.postRotate(placement.rotationDeg, placement.left + cx, placement.top + cy)
                canvas.save()
                canvas.concat(matrix)
                canvas.drawBitmapMesh(bmp, meshW, meshH, verts, 0, null, 0, glyphPaint)
                canvas.restore()
            } else {
                canvas.save()
                canvas.translate(placement.left.toFloat(), placement.top.toFloat())
                canvas.scale(scaleX, scaleY)
                canvas.drawBitmapMesh(bmp, meshW, meshH, verts, 0, null, 0, glyphPaint)
                canvas.restore()
            }
        }

        return HandwritingRenderResult(bitmap = output, missingCharacters = missingCharacters)
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
)
