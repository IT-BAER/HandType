package com.baer.handtype.ml

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Cleans raw OCR glyph crops (paper background + ink) into renderable glyph bitmaps.
 *
 * Pipeline:
 * 1. Grayscale + Otsu threshold to separate ink from paper.
 * 2. Trim to the ink pixel bounding box (drop OCR padding + paper rectangles).
 * 3. Re-emit as ARGB_8888 where paper = transparent and ink = solid black with
 *    intensity-derived alpha (so anti-aliased ink edges survive).
 * 4. Normalize onto a uniform [TARGET_CANVAS] square with the glyph bottom
 *    aligned to a fixed baseline so the bitmap renderer's averaged metrics work.
 */
object GlyphPostProcessor {

    private data class GuideBand(
        val upperY: Int,
        val baselineY: Int,
    ) {
        val height: Int get() = baselineY - upperY
    }

    private data class InkComponent(
        val pixels: IntArray,
        val area: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width: Int get() = right - left + 1
        val height: Int get() = bottom - top + 1
        val centerX: Float get() = (left + right) / 2f
    }

    private const val CLOSE_RADIUS = 1

    // Reference size for the baseline, guide band and width; older saved templates are this tall.
    private const val TARGET_CANVAS = 220
    // 2 x-heights above the baseline, past the top of the cell, and 1.5 below it, for descenders followed into the cell below.
    private const val CANVAS_HEIGHT = 353
    // Canvas heights of templates saved before the canvas grew to [CANVAS_HEIGHT]; their baseline is [TARGET_BASELINE_Y].
    private const val LEGACY_CANVAS_HEIGHT = 313
    private const val OLDER_CANVAS_HEIGHT = 250
    private const val TARGET_GLYPH_HEIGHT = 199
    private const val TARGET_BASELINE_Y = (TARGET_CANVAS * 0.74f).toInt()
    private const val CANVAS_BASELINE_Y = TARGET_BASELINE_Y + 40
    private const val TARGET_GUIDE_BAND_HEIGHT = (TARGET_CANVAS * 0.46f).toInt()
    private const val INK_COLOR = 0xFF1A1410.toInt()
    private const val SIDE_PAD = 8
    private const val OUTER_GUIDE_DOT_MAX_AREA = 12
    // Lightest luma the faint-dot scan accepts as ink.
    private const val MARK_PAPER_LUMA = 250

    private val DIACRITIC_CHARS = setOf(
        'ä', 'ö', 'ü', 'Ä', 'Ö', 'Ü',
        'á', 'é', 'í', 'ó', 'ú', 'Á', 'É', 'Í', 'Ó', 'Ú',
        'à', 'è', 'ì', 'ò', 'ù', 'À', 'È', 'Ì', 'Ò', 'Ù',
        'â', 'ê', 'î', 'ô', 'û', 'Â', 'Ê', 'Î', 'Ô', 'Û',
        'ñ', 'Ñ', 'ç', 'Ç',
    )

    /**
     * Whether [c] carries a separate mark that [cleanGlyph] must keep. Uppercase 'I' and 'J' have
     * no dot, so the diacritic-recovery passes must not run on them.
     */
    fun expectsDiacritic(c: Char): Boolean = c == 'i' || c == 'j' || c in DIACRITIC_CHARS

    /**
     * Returns a cleaned, baseline-aligned 350×350 ARGB glyph bitmap.
     *
     * Falls back to the original [crop] if the glyph cannot be processed (e.g. blank crop).
     */
    fun cleanGlyph(
        crop: Bitmap,
        guideYsInCrop: IntArray = intArrayOf(),
        expectsDiacritic: Boolean = false,
        // A crop without printed sheet furniture must keep rule-shaped ink: a tall I or a wide W.
        rejectPrintedRules: Boolean = true,
        // Dotted outer guides; they never set the glyph scale.
        outerGuideYsInCrop: IntArray = intArrayOf(),
        // Per crop column: ink just above the crop, where a stroke of the row above crosses in.
        inkAboveCrop: BooleanArray = BooleanArray(0),
    ): Bitmap {
        val width = crop.width
        val height = crop.height
        if (width <= 0 || height <= 0) return crop
        val guideBand = resolveGuideBand(guideYsInCrop)?.let { GuideBand(it.first, it.last) }

        val pixels = IntArray(width * height)
        crop.getPixels(pixels, 0, width, 0, 0, width, height)

        val luma = IntArray(pixels.size)
        var lumaMin = 255
        var lumaMax = 0
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            // Standard luminance approximation (Rec. 601).
            val y = (r * 299 + g * 587 + b * 114) / 1000
            luma[i] = y
            if (y < lumaMin) lumaMin = y
            if (y > lumaMax) lumaMax = y
        }

        if (lumaMax - lumaMin < MIN_CONTRAST) {
            // Uniform/blank crop — return transparent canvas so hasInk() correctly returns false.
            return Bitmap.createBitmap(TARGET_CANVAS, CANVAS_HEIGHT, Bitmap.Config.ARGB_8888)
        }

        val threshold = otsuThreshold(luma)
        // Use a permissive threshold (Otsu × 1.2, capped at 210) so lightly-written strokes
        // such as i/j dots are classified as ink rather than paper. The component filter in
        // shouldKeepComponent() handles any resulting noise (guide residue, paper texture) via
        // area and proximity checks. Background paper is typically luma ≥ 225 so the cap of
        // 210 keeps a comfortable margin.
        val permissiveThreshold = (threshold * 1.2f).toInt().coerceAtMost(210)
        // Thin pens leave strokes that Otsu breaks into dashes; the close bridges short gaps.
        val inkMask = closeGaps(BooleanArray(pixels.size) { index -> luma[index] <= permissiveThreshold }, width, height)

        // Secondary diacritic scan: search the top 30% of the crop for small connected groups
        // of pixels that are darker than paper (luma < 220) but lighter than the Otsu threshold.
        // These pixels represent lightly drawn i/j dots that global Otsu misses when strong body
        // ink pulls the threshold below the dot's light grey. Only accept groups with area < 150
        // to ensure we never pull in large paper-texture regions.
        val diacriticZoneEnd = guideBand?.baselineY?.coerceIn(0, height) ?: (height * 0.40f).toInt()
        // Pixels of the kept dots/accents, darkened later when fainter than the pen. Set only for
        // marks, because the scans below also pick up the faint anti-aliased edge of every stroke.
        val diacriticPixelMask = BooleanArray(pixels.size)
        // Pass 1: gentle scan (luma < 240, area 3..149) — picks up clearly faint dots without
        // pulling in paper grain.
        if (diacriticZoneEnd > 0) {
            val diacriticCandidates = BooleanArray(pixels.size) { index ->
                val y = index / width
                y < diacriticZoneEnd && !inkMask[index] && luma[index] < 240
            }
            val candidateComponents = extractInkComponents(diacriticCandidates, width, height)
            for (comp in candidateComponents) {
                if (comp.area in 3..149) comp.pixels.forEach { inkMask[it] = true }
            }
        }
        // Pass 2 (aggressive, only for chars known to carry a diacritic): always runs additively
        // for expectsDiacritic to catch barely-visible i/j dots (luma 240..249 vs ~252 paper).
        // Restricted to expectsDiacritic so we never invent dots on letters that shouldn't have
        // them. Picks at most the single most-compact upper-zone blob to avoid paper smudges.
        if (expectsDiacritic && diacriticZoneEnd > 0) {
            val diacriticCandidates = BooleanArray(pixels.size) { index ->
                val y = index / width
                y < diacriticZoneEnd && !inkMask[index] && luma[index] < 250
            }
            val candidateComponents = extractInkComponents(diacriticCandidates, width, height)
            val best = candidateComponents
                .filter { it.area in 1..149 }
                .minByOrNull { it.area }
            best?.pixels?.forEach { inkMask[it] = true }
        }
        val components = extractInkComponents(inkMask, width, height)
            .filterNot { rejectPrintedRules && isPrintedRule(it, width, height) }
            .filterNot { isOuterGuideDot(it.top, it.bottom, it.area, outerGuideYsInCrop) }
            // A mark may be cut by the cell's top edge, so only letters without one drop spill.
            .filterNot { c ->
                !expectsDiacritic && isSpillFromCellAbove(
                    c.top, c.bottom, guideYsInCrop,
                    continuesAboveCrop = c.pixels.any { it / width <= c.top + 1 && inkAboveCrop.getOrElse(it % width) { false } },
                )
            }
        if (components.isEmpty()) {
            return Bitmap.createBitmap(TARGET_CANVAS, CANVAS_HEIGHT, Bitmap.Config.ARGB_8888)
        }
        val primary = components.maxByOrNull { it.area } ?: return crop
        val keptComponents = components
            .filter { shouldKeepComponent(it, primary, guideYsInCrop, expectsDiacritic) }
            .toMutableList()
        // Dots and accents of the characters known to carry them (i/j, umlauts, accented vowels):
        // recovered when shouldKeepComponent rejects them, then placed near the body. Only for
        // these characters, so paper noise never becomes a dot above other letters.
        // Map from shifted dot pixel index → original pixel index (used to source luma for alpha
        // calc when the dot is relocated below for visual proximity to the body).
        val diacriticSourceMap = HashMap<Int, Int>()
        if (expectsDiacritic) {
            // The letter body is the lowest substantial kept piece: an i dot can outweigh its
            // stem, so the largest component is not always the body.
            val body = keptComponents.filter { it.area >= primary.area / 2 }.maxBy { it.bottom }
            val primaryHeight = body.bottom - body.top + 1
            val diacriticReach = maxOf(primaryHeight * 3, 60)
            // Top body row per column: an umlaut dot may sit between the arms of a U or beside the
            // crown of an O, below the body's overall top.
            val columnTop = IntArray(width) { Int.MAX_VALUE }
            for (p in body.pixels) columnTop[p % width] = minOf(columnTop[p % width], p / width)
            fun bodyTopUnder(c: InkComponent): Int =
                (c.left..c.right).minOf { columnTop[it] }.takeIf { it != Int.MAX_VALUE } ?: body.top
            // A marker dot can outweigh 200 px; a stroke piece of the letter is far larger.
            val markMaxArea = maxOf(200, body.area / 4)
            val matchesDiacritic: (InkComponent) -> Boolean = { c ->
                c !== body &&
                    c.area in 2..markMaxArea &&
                    !isGuideSliver(c.top, c.bottom, guideYsInCrop) &&
                    c.bottom < bodyTopUnder(c) &&
                    c.bottom < body.top + primaryHeight / 3 &&
                    run {
                        val w = c.right - c.left + 1
                        val h = c.bottom - c.top + 1
                        val ar = w.toFloat() / h.coerceAtLeast(1)
                        // i/j dots from handwriting are often a horizontal smear (AR up to ~5),
                        // accents like acute/grave are slanted lines (AR ~0.4..2.5). Be permissive.
                        ar in 0.3f..5.0f
                    } &&
                    (bodyTopUnder(c) - c.bottom - 1) <= diacriticReach
                // No horizontal alignment check: the cell contains a single letter, so any
                // small mark in the upper portion of the cell that matches dot/accent shape
                // belongs to this letter. Users routinely place i/j dots slightly off-center
                // and sometimes far to the left of the body.
            }

            // 1) Safety net: likely marks that shouldKeepComponent rejected; an umlaut has two.
            val safetyNet = components.filter { it !in keptComponents && matchesDiacritic(it) }
            keptComponents += safetyNet
            safetyNet.forEach { mark -> mark.pixels.forEach { diacriticPixelMask[it] = true } }

            // 2) Marks are level with the largest one (umlaut dots, even when one sits higher);
            //    fragments far smaller than it are guide or label residue.
            val found = keptComponents.filter(matchesDiacritic)
            val largest = found.maxByOrNull { it.area }
            val marks = if (largest == null) emptyList() else found.filter {
                it.area * 4 >= largest.area &&
                    it.bottom >= largest.top - largest.height / 2 && it.top <= largest.bottom + largest.height / 2
            }
            keptComponents.removeAll { it in found && it !in marks }
            if (largest != null) {
                keptComponents.removeAll { it !== body && it.bottom < body.top && it.area * 4 < largest.area }
            }

            // 3) Move the marks as one group, so umlaut dots keep their spacing, and only as far
            //    as needed: the writer's dot height and offset are part of the hand. Up when one
            //    nearly touches the ink below it (scaling would fuse them), down when far above
            //    the body (the renderer fits all ink to the line, so the letter would shrink),
            //    sideways only into the body's width.
            val minGap = maxOf(2, (primaryHeight * 0.06f).toInt())
            val maxGap = maxOf(minGap, (primaryHeight * 0.3f).toInt())
            val shiftY = if (marks.isEmpty()) 0 else {
                val closest = marks.minOf { bodyTopUnder(it) - it.bottom - 1 }
                val aboveBody = marks.minOf { body.top - it.bottom - 1 }
                when {
                    closest < minGap -> closest - minGap
                    aboveBody > maxGap -> aboveBody - maxGap
                    else -> 0
                }.coerceAtLeast(-marks.minOf { it.top })
            }
            val shiftX = if (marks.isEmpty()) 0 else {
                val left = marks.minOf { it.left }
                val right = marks.maxOf { it.right }
                when {
                    right - left > body.right - body.left -> (body.left + body.right - left - right) / 2
                    left < body.left -> body.left - left
                    right > body.right -> body.right - right
                    else -> 0
                }
            }
            val toRemove = mutableListOf<InkComponent>()
            val toAdd = mutableListOf<InkComponent>()
            for (dot in marks) {
                if (shiftY == 0 && shiftX == 0) {
                    // Already in position — just ensure it gets diacritic alpha treatment.
                    dot.pixels.forEach { diacriticPixelMask[it] = true }
                    continue
                }
                val shifted = IntArray(dot.pixels.size)
                var n = 0
                for (origIdx in dot.pixels) {
                    val oy = origIdx / width
                    val ox = origIdx % width
                    val ny = oy + shiftY
                    val nx = ox + shiftX
                    if (ny in 0 until height && nx in 0 until width) {
                        val newIdx = ny * width + nx
                        shifted[n++] = newIdx
                        diacriticPixelMask[newIdx] = true
                        diacriticSourceMap[newIdx] = origIdx
                    }
                }
                // Clear the diacritic flag from original positions (the relocated pixels carry it
                // now). The original positions are also removed from the kept set below.
                dot.pixels.forEach { diacriticPixelMask[it] = false }
                // Re-mark shifted positions (the loop above may have cleared overlapping ones).
                for (i in 0 until n) diacriticPixelMask[shifted[i]] = true
                if (n > 0) {
                    toRemove += dot
                    toAdd += InkComponent(
                        pixels = shifted.copyOf(n),
                        area = n,
                        left = dot.left + shiftX,
                        top = dot.top + shiftY,
                        right = dot.right + shiftX,
                        bottom = dot.bottom + shiftY,
                    )
                }
            }
            keptComponents.removeAll(toRemove)
            keptComponents.addAll(toAdd)
        }
        val filteredInkMask = BooleanArray(inkMask.size)
        keptComponents.forEach { component ->
            component.pixels.forEach { pixelIndex ->
                filteredInkMask[pixelIndex] = true
            }
        }

        // Bounding box of ink pixels.
        var top = height
        var bottom = -1
        var left = width
        var right = -1
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                if (filteredInkMask[rowOffset + x]) {
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
        }
        if (bottom < 0) return crop // no ink found

        // A mark fainter than the pen (a light pencil dot) is darkened until its darkest pixel
        // matches the body's median ink, keeping its own shading; other marks map like the stroke.
        var markLumaMin = MARK_PAPER_LUMA
        val bodyLumas = ArrayList<Int>()
        for (i in diacriticPixelMask.indices) {
            if (!filteredInkMask[i]) continue
            if (diacriticPixelMask[i]) {
                markLumaMin = minOf(markLumaMin, luma[diacriticSourceMap[i] ?: i])
            } else if (luma[i] <= permissiveThreshold) {
                bodyLumas += luma[i]
            }
        }
        val bodyMedianLuma = bodyLumas.sorted().getOrElse(bodyLumas.size / 2) { lumaMin }
        val markStretch = ((MARK_PAPER_LUMA - bodyMedianLuma).toFloat() / (MARK_PAPER_LUMA - markLumaMin).coerceAtLeast(1))
            .coerceAtLeast(1f)

        val padding = 4
        left = (left - padding).coerceAtLeast(0)
        top = (top - padding).coerceAtLeast(0)
        right = (right + padding).coerceAtMost(width - 1)
        bottom = (bottom + padding).coerceAtMost(height - 1)

        val inkWidth = right - left + 1
        val inkHeight = bottom - top + 1
        if (inkWidth <= 0 || inkHeight <= 0) return crop

        // Build alpha-masked glyph (paper transparent, ink near-black).
        // Anything brighter than the Otsu threshold is treated as paper and emitted fully transparent;
        // pixels at-or-below the threshold are mapped to opacity proportional to their darkness.
        val masked = Bitmap.createBitmap(inkWidth, inkHeight, Bitmap.Config.ARGB_8888)
        val maskedPixels = IntArray(inkWidth * inkHeight)
        val darkSpan = (permissiveThreshold - lumaMin).coerceAtLeast(1)
        for (y in 0 until inkHeight) {
            val srcRow = (top + y) * width
            val dstRow = y * inkWidth
            for (x in 0 until inkWidth) {
                val srcIdx = srcRow + (left + x)
                // Relocated mark pixels take the luma of their original position.
                val l = if (diacriticPixelMask[srcIdx]) {
                    (MARK_PAPER_LUMA - (MARK_PAPER_LUMA - luma[diacriticSourceMap[srcIdx] ?: srcIdx]) * markStretch).toInt()
                } else {
                    luma[srcIdx]
                }
                val alpha = if (!filteredInkMask[srcIdx]) {
                    0
                } else if (l > permissiveThreshold) {
                    0
                } else {
                    // l <= permissiveThreshold: darker means more ink. Clamp at 255.
                    (((permissiveThreshold - l).toFloat() / darkSpan) * 255f)
                        .toInt()
                        .coerceIn(0, 255)
                }
                maskedPixels[dstRow + x] = if (alpha == 0) {
                    Color.TRANSPARENT
                } else {
                    (alpha shl 24) or (INK_COLOR and 0x00FFFFFF)
                }
            }
        }
        masked.setPixels(maskedPixels, 0, inkWidth, 0, 0, inkWidth, inkHeight)

        val guideBandInMasked = guideBand?.let { band ->
            GuideBand(
                upperY = band.upperY - top,
                baselineY = band.baselineY - top,
            )
        }

        return placeOnCanvas(glyph = masked, guideBand = guideBandInMasked)
    }

    private fun extractInkComponents(mask: BooleanArray, width: Int, height: Int): List<InkComponent> {
        val visited = BooleanArray(mask.size)
        val stack = IntArray(mask.size)
        val components = mutableListOf<InkComponent>()

        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue

            var stackSize = 0
            stack[stackSize++] = start
            visited[start] = true

            val pixels = ArrayList<Int>()
            var left = width
            var top = height
            var right = -1
            var bottom = -1

            while (stackSize > 0) {
                val index = stack[--stackSize]
                pixels += index

                val x = index % width
                val y = index / width
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y

                val minY = maxOf(0, y - 1)
                val maxY = minOf(height - 1, y + 1)
                val minX = maxOf(0, x - 1)
                val maxX = minOf(width - 1, x + 1)
                for (ny in minY..maxY) {
                    val rowOffset = ny * width
                    for (nx in minX..maxX) {
                        if (nx == x && ny == y) continue
                        val neighbor = rowOffset + nx
                        if (!mask[neighbor] || visited[neighbor]) continue
                        visited[neighbor] = true
                        stack[stackSize++] = neighbor
                    }
                }
            }

            components += InkComponent(
                pixels = pixels.toIntArray(),
                area = pixels.size,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
            )
        }

        return components
    }

    /**
     * Printed sheet furniture — the guide lines and the cell border — spans nearly the full crop
     * on one axis at a few pixels on the other. A cell holds one alphanumeric character, so no
     * handwritten stroke has that shape. Rejecting these before the primary component is picked
     * stops a printed rule from becoming the glyph and pushing the real ink out through the
     * proximity rules.
     */
    private fun isPrintedRule(component: InkComponent, cropWidth: Int, cropHeight: Int): Boolean {
        val width = component.right - component.left + 1
        val height = component.bottom - component.top + 1
        val horizontalRule = height <= RULE_MAX_THICKNESS && width >= cropWidth * RULE_MIN_SPAN
        val verticalRule = width <= RULE_MAX_THICKNESS && height >= cropHeight * RULE_MIN_SPAN
        return horizontalRule || verticalRule || with(component) {
            isCellBorderFrame(pixels, left, top, right, bottom, cropWidth, cropHeight)
        }
    }

    /**
     * The four cell borders meet at the corners, so they reach the component labeller as one
     * hollow rectangle that spans the crop yet encloses almost nothing. Left alone it is the
     * largest component and is picked as the glyph. [pixels] index a crop [cropWidth] wide.
     */
    internal fun isCellBorderFrame(
        pixels: IntArray,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        cropWidth: Int,
        cropHeight: Int,
    ): Boolean {
        val width = right - left + 1
        val height = bottom - top + 1
        if (width < cropWidth * RULE_MIN_SPAN || height < cropHeight * RULE_MIN_SPAN) return false
        if (pixels.size >= width.toLong() * height * FRAME_MAX_FILL) return false
        // A tall cursive capital is just as sparse, but only a frame keeps its ink on the box edges.
        val onEdge = pixels.count { index ->
            val x = index % cropWidth
            val y = index / cropWidth
            x - left < RULE_MAX_THICKNESS || right - x < RULE_MAX_THICKNESS ||
                y - top < RULE_MAX_THICKNESS || bottom - y < RULE_MAX_THICKNESS
        }
        return onEdge >= pixels.size * FRAME_MIN_EDGE_SHARE
    }

    private fun shouldKeepComponent(
        component: InkComponent,
        primary: InkComponent,
        guideYsInCrop: IntArray = intArrayOf(),
        expectsDiacritic: Boolean = true,
    ): Boolean {
        if (component == primary) return true

        return shouldKeepComponentBounds(
            componentLeft = component.left,
            componentTop = component.top,
            componentRight = component.right,
            componentBottom = component.bottom,
            componentArea = component.area,
            primaryLeft = primary.left,
            primaryTop = primary.top,
            primaryRight = primary.right,
            primaryBottom = primary.bottom,
            primaryArea = primary.area,
            guideYsInCrop = guideYsInCrop,
            expectsDiacritic = expectsDiacritic,
        )
    }

    internal fun shouldKeepComponentBounds(
        componentLeft: Int,
        componentTop: Int,
        componentRight: Int,
        componentBottom: Int,
        componentArea: Int,
        primaryLeft: Int,
        primaryTop: Int,
        primaryRight: Int,
        primaryBottom: Int,
        primaryArea: Int,
        guideYsInCrop: IntArray = intArrayOf(),
        expectsDiacritic: Boolean = true,
    ): Boolean {
        val componentWidth = componentRight - componentLeft + 1
        val componentHeight = componentBottom - componentTop + 1
        val primaryWidth = primaryRight - primaryLeft + 1
        val primaryHeight = primaryBottom - primaryTop + 1

        val componentAspectRatio = componentWidth.toFloat() / componentHeight.coerceAtLeast(1)
        val horizontalGap = axisGap(componentLeft, componentRight, primaryLeft, primaryRight)
        val verticalGap = axisGap(componentTop, componentBottom, primaryTop, primaryBottom)
        val componentCenterX = (componentLeft + componentRight) / 2f
        val alignedWithPrimary = componentCenterX >= (primaryLeft - 12) && componentCenterX <= (primaryRight + 12)

        // Reject wide, short components that look like printed guide-line residue.
        // Guide lines are 26px long × ~1.5px stroke → area ≈ 40-120px² in rectified space.
        // Horizontal letter arms (e.g. the bottom arm of 'E') are as wide as the letter
        // (~50-80px) and as thick as the pen stroke (~4-8px) → area ≈ 200-640px².
        // Only reject if the component is also small in absolute pixel count so letter
        // arms are not caught by the same filter.
        val touchingPrimary = horizontalGap == 0 && verticalGap == 0
        val nearPrimaryEdge = componentTop <= primaryTop + maxOf(primaryHeight / 5, 8) ||
            componentBottom >= primaryBottom - maxOf(primaryHeight / 5, 8)
        val nearPrimaryArm = horizontalGap == 0 &&
            verticalGap <= maxOf(primaryHeight / 6, 8) &&
            componentArea >= maxOf(primaryArea / 8, 24) &&
            nearPrimaryEdge
        if (componentAspectRatio > 3.4f && componentHeight < maxOf(primaryHeight / 3, 10)
            && componentArea < 150 && !(touchingPrimary && nearPrimaryEdge) && !nearPrimaryArm) {
            return false
        }

        // Reject components that lie entirely within a known guide-line band.
        // A component whose vertical span [componentTop, componentBottom] is fully contained
        // within [guideY−4, guideY+4] is almost certainly guide-line residue: printed tick marks
        // are at most 1.5px thick (print) → ~3-5px after scan. Handwritten strokes that CROSS
        // a guide position have ink above AND below the band, so their span exceeds the band.
        // Note: if guide ink is fused with the handwritten letter (same component), both pixels
        // belong to the primary and this check is never reached for them.
        // Reject components that lie entirely within a known guide-line band AND are small.
        // After centered pixel blanking, any surviving guide remnant is partial (≤13px wide)
        // and thin (≤3px), so area ≤ ~40px². Letter arms at this position are ≥50px wide
        // and ≥3px tall, so area ≥ 150px². Upper bound of 80 gives safe separation.
        // A printed guide measures ~5 rows after a photographed sheet is rectified, so the band
        // has to be wider than the line itself to contain a dash completely.
        val guideBandHalf = 6
        if (componentArea < 80 && guideYsInCrop.any { gy ->
                componentTop >= gy - guideBandHalf && componentBottom <= gy + guideBandHalf
            }) {
            return false
        }

        val sitsAbovePrimary = componentBottom < primaryTop
        if (sitsAbovePrimary) {
            // Diacritics (i-dot, j-dot, accents) are tiny components that sit above the letter
            // body. They can have a large vertical gap (dot at 32% zone, body at 78%) and may
            // be slightly shifted horizontally from the body centre in the user's handwriting.
            // Allow a 3× larger vertical gap AND a relaxed horizontal alignment for such marks.
            val isDiacritic = expectsDiacritic
                && componentArea < maxOf(primaryArea / 5, 30)
                && componentWidth <= maxOf(primaryWidth + 6, 20)
                && componentAspectRatio >= 0.3f  // tall narrow lines are residue, not diacritics
            val maxGap = if (isDiacritic) maxOf(primaryHeight * 3, 60) else maxOf(primaryHeight / 2, 24)
            val alignOk = if (isDiacritic) {
                // Diacritics may be placed anywhere above the letter body in the cell.
                // Don't require horizontal alignment — the cell belongs to one letter only.
                true
            } else {
                alignedWithPrimary
            }
            // Allow large components above the primary — they can be letter body parts (e.g. the
            // main stem of an 'E' when the bottom arm is the dominant component, or the bowl of
            // 'g' above its descender). Only reject components that are clearly larger than any
            // reasonable letter part could be (> 3× primary area).
            val maxArea = maxOf(primaryArea * 3, 24)
            // Tall narrow components above the primary are almost certainly printed label/guide
            // residue (a single stroke of the printed cell label). Reject them outright — even if
            // horizontally aligned — unless they look like a diacritic.
            if (!isDiacritic && componentAspectRatio < 0.25f) return false
            // Paper grain and print residue land above the letter as a speck far smaller than any
            // real letter part. Characters that carry a dot reach this point as isDiacritic.
            if (!isDiacritic && componentArea < maxOf(primaryArea / 10, 24)) return false
            return alignOk && verticalGap <= maxGap && componentArea <= maxArea
        }

        val closeHorizontally = horizontalGap <= maxOf(primaryWidth / 2, 12)
        val closeVertically = verticalGap <= maxOf(primaryHeight / 3, 14)
        val minArea = maxOf(primaryArea / 10, 8)
        // For components directly touching the primary (gap = 0 on both axes), skip the area
        // upper bound — they are almost certainly part of the same letter (e.g. the diagonal
        // arms of 'k' or 'K' touching the central stem). For components with a gap, apply a
        // relaxed upper bound (2× primary) to exclude large unrelated blobs.
        val touching = touchingPrimary
        val maxArea = if (touching) Int.MAX_VALUE else maxOf((primaryArea * 2.0f).toInt(), 24)
        return closeHorizontally && closeVertically && componentArea >= minArea && componentArea <= maxArea
    }

    private fun axisGap(startA: Int, endA: Int, startB: Int, endB: Int): Int {
        return when {
            endA < startB -> startB - endA
            endB < startA -> startA - endB
            else -> 0
        }
    }

    /** Renders the trimmed alpha glyph onto a canvas with baseline alignment.
     *
     * Canvas height is [CANVAS_HEIGHT]; the baseline sits at [CANVAS_BASELINE_Y].
     * Canvas width is trimmed to the actual ink width + [SIDE_PAD] on each side, so
     * [HandwritingBitmapRenderer] advances by the real character width rather than the full
     * square canvas — eliminating the excessive inter-character gap in user-captured templates.
     */
    private fun placeOnCanvas(
        glyph: Bitmap,
        guideBand: GuideBand? = null,
    ): Bitmap {
        val srcH = glyph.height.toFloat()
        val srcW = glyph.width.toFloat()
        val targetH = TARGET_GLYPH_HEIGHT.toFloat()
        val guideScale = guideBand
            ?.takeIf { it.height >= 8 && it.baselineY > 0 }
            ?.let { TARGET_GUIDE_BAND_HEIGHT.toFloat() / it.height.toFloat() }
        val scale = guideScale ?: (targetH / srcH)
        val scaledW = (srcW * scale).roundToInt().coerceAtLeast(1).coerceAtMost(TARGET_CANVAS)
        // Not capped: ink deeper than the canvas is cut at its edge, while the baseline stays put.
        val scaledH = (srcH * scale).roundToInt().coerceAtLeast(1)

        // Width-trim: use actual ink width + small side padding so the renderer's
        // glyph.width advance reflects the true character width.
        val canvasWidth = (scaledW + 2 * SIDE_PAD).coerceAtMost(TARGET_CANVAS)
        val canvasBmp = Bitmap.createBitmap(canvasWidth, CANVAS_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBmp)

        val left = SIDE_PAD.toFloat()
        val top = guideBand
            ?.takeIf { it.baselineY > 0 }
            ?.let { CANVAS_BASELINE_Y - it.baselineY * scale }
            ?: (CANVAS_BASELINE_Y - scaledH).toFloat()
        val dst = RectF(left, top, left + scaledW, top + scaledH)
        val src = Rect(0, 0, glyph.width, glyph.height)

        // Filtered for every glyph: the gap under a dot is at least 2 px before upscaling, so it stays open.
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        canvas.drawBitmap(glyph, src, dst, paint)
        return canvasBmp
    }

    /** Morphological close (dilate, then erode) with a (2r+1)-square window; bridges gaps up to 2r px. */
    internal fun closeGaps(mask: BooleanArray, width: Int, height: Int, r: Int = CLOSE_RADIUS): BooleanArray {
        fun apply(src: BooleanArray, any: Boolean): BooleanArray = BooleanArray(src.size) { idx ->
            val cx = idx % width
            val cy = idx / width
            var hit = !any
            for (dy in -r..r) for (dx in -r..r) {
                val nx = cx + dx
                val ny = cy + dy
                val v = if (nx in 0 until width && ny in 0 until height) src[ny * width + nx] else false
                if (any && v) hit = true
                if (!any && !v) hit = false
            }
            hit
        }
        return apply(apply(mask, any = true), any = false)
    }

    internal fun normalizedCanvasSize(): Int = TARGET_CANVAS

    /** A cleaned glyph canvas: the current height, or the height of older templates. */
    internal fun isNormalizedCanvas(height: Int): Boolean =
        height == CANVAS_HEIGHT || height == LEGACY_CANVAS_HEIGHT || height == OLDER_CANVAS_HEIGHT || height == TARGET_CANVAS

    /** Baseline row of the reference canvas, [normalizedCanvasSize] tall. */
    internal fun normalizedBaselineRow(): Int = TARGET_BASELINE_Y

    /** Baseline row of a cleaned glyph canvas [height] rows tall: the current one sits lower than the older ones. */
    internal fun canvasBaselineRow(height: Int): Int = if (height == CANVAS_HEIGHT) CANVAS_BASELINE_Y else TARGET_BASELINE_Y

    internal fun normalizedGuideBandHeight(): Int = TARGET_GUIDE_BAND_HEIGHT

    /**
     * A printed dot of a dotted guide that survived thresholding. Faint writing lowers the Otsu
     * threshold enough to keep them, and the i/j dot search would take the top-most one.
     */
    internal fun isOuterGuideDot(top: Int, bottom: Int, area: Int, outerGuideYsInCrop: IntArray): Boolean =
        area <= OUTER_GUIDE_DOT_MAX_AREA && outerGuideYsInCrop.any { top >= it - 2 && bottom <= it + 2 }

    /**
     * A descender of the row above that crossed the cell border: it enters at the crop's top edge
     * (the scanner clears the first rows of border bleed) from ink above the cell, and ends before
     * the x-height guide, where every stroke of this cell's letter reaches. A cursive F or T bar
     * that only touches the edge stays.
     */
    internal fun isSpillFromCellAbove(top: Int, bottom: Int, guideYsInCrop: IntArray, continuesAboveCrop: Boolean): Boolean =
        continuesAboveCrop && top <= 4 && guideYsInCrop.isNotEmpty() && bottom < guideYsInCrop.min()

    /**
     * A piece of a dashed guide that the faint-mark scan picked up: no taller than the printed line
     * and lying on it. Taken for a mark, it is the one nearest the body and evicts the real dots.
     */
    internal fun isGuideSliver(top: Int, bottom: Int, guideYsInCrop: IntArray): Boolean =
        bottom - top <= 2 && guideYsInCrop.any { top >= it - 3 && bottom <= it + 3 }

    internal fun resolveGuideBand(guideYsInCrop: IntArray): IntRange? {
        if (guideYsInCrop.size < 2) return null
        val upper = guideYsInCrop.minOrNull() ?: return null
        val baseline = guideYsInCrop.maxOrNull() ?: return null
        return if (baseline - upper >= 8) upper..baseline else null
    }

    /**
     * Otsu's method for automatic luminance thresholding.
     *
     * Returns a luma value such that pixels at-or-below it are treated as ink.
     */
    private fun otsuThreshold(luma: IntArray): Int {
        val histogram = IntArray(256)
        luma.forEach { histogram[it]++ }
        val total = luma.size
        var sum = 0L
        for (t in 0..255) sum += (t * histogram[t]).toLong()

        var sumB = 0L
        var wB = 0
        var maxBetween = 0.0
        var threshold = 127
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
        return threshold
    }

    private const val MIN_CONTRAST = 30

    // A printed rule measures ~2 px across after rectification; the thickest pen stroke on a
    // scanned sheet measures ~8. Spanning 70% of the crop separates a border from a letter.
    private const val RULE_MAX_THICKNESS = 8
    private const val RULE_MIN_SPAN = 0.7f

    // A cell border encloses its own bounding box at ~3% fill. The most open letter that spans
    // this much of a cell still fills over a quarter of its box, so the gap is wide.
    private const val FRAME_MAX_FILL = 0.15f
    // Letters measured 0.12..0.55 of their ink within RULE_MAX_THICKNESS of the box edge.
    private const val FRAME_MIN_EDGE_SHARE = 0.8f
}
