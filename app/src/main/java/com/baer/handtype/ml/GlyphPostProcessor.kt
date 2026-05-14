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

    private const val TARGET_CANVAS = 220
    private const val TARGET_GLYPH_HEIGHT = 199
    private const val TARGET_BASELINE_Y = (TARGET_CANVAS * 0.74f).toInt()
    private const val TARGET_GUIDE_BAND_HEIGHT = (TARGET_CANVAS * 0.46f).toInt()
    private const val INK_COLOR = 0xFF1A1410.toInt()
    private const val SIDE_PAD = 8

    /**
     * Returns a cleaned, baseline-aligned 350×350 ARGB glyph bitmap.
     *
     * Falls back to the original [crop] if the glyph cannot be processed (e.g. blank crop).
     */
    fun cleanGlyph(
        crop: Bitmap,
        guideYsInCrop: IntArray = intArrayOf(),
        expectsDiacritic: Boolean = false,
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
            return Bitmap.createBitmap(TARGET_CANVAS, TARGET_CANVAS, Bitmap.Config.ARGB_8888)
        }

        val threshold = otsuThreshold(luma)
        // Use a permissive threshold (Otsu × 1.2, capped at 210) so lightly-written strokes
        // such as i/j dots are classified as ink rather than paper. The component filter in
        // shouldKeepComponent() handles any resulting noise (guide residue, paper texture) via
        // area and proximity checks. Background paper is typically luma ≥ 225 so the cap of
        // 210 keeps a comfortable margin.
        val permissiveThreshold = (threshold * 1.2f).toInt().coerceAtMost(210)
        val inkMask = BooleanArray(pixels.size) { index -> luma[index] <= permissiveThreshold }

        // Secondary diacritic scan: search the top 30% of the crop for small connected groups
        // of pixels that are darker than paper (luma < 220) but lighter than the Otsu threshold.
        // These pixels represent lightly drawn i/j dots that global Otsu misses when strong body
        // ink pulls the threshold below the dot's light grey. Only accept groups with area < 150
        // to ensure we never pull in large paper-texture regions.
        val diacriticZoneEnd = guideBand?.baselineY?.coerceIn(0, height) ?: (height * 0.40f).toInt()
        // Track pixels recovered by the diacritic scan so we can force them to full opacity
        // later — faint dots/accents otherwise render at low alpha and disappear when scaled.
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
                if (comp.area in 3..149) {
                    comp.pixels.forEach {
                        inkMask[it] = true
                        diacriticPixelMask[it] = true
                    }
                }
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
            if (best != null) {
                best.pixels.forEach {
                    inkMask[it] = true
                    diacriticPixelMask[it] = true
                }
            }
        }
        val components = extractInkComponents(inkMask, width, height)
        if (components.isEmpty()) return crop
        val primary = components.maxByOrNull { it.area } ?: return crop
        val keptComponents = components.filter { shouldKeepComponent(it, primary, guideYsInCrop) }
            .toMutableList()
        // Char-aware diacritic safety net: i/j and accented vowels can have small dots/accents
        // that the strict shouldKeepComponent rules reject. Only for characters we KNOW carry a
        // diacritic, pick the single best candidate sitting above the primary that looks like a
        // dot/accent (compact area, AR in 0.3..3.0, centered horizontally on the body, within
        // 3× the primary height). Avoids re-introducing paper-noise dots above all letters.
        // Map from shifted dot pixel index → original pixel index (used to source luma for alpha
        // calc when the dot is relocated below for visual proximity to the body).
        val diacriticSourceMap = HashMap<Int, Int>()
        if (expectsDiacritic) {
            val primaryHeight = primary.bottom - primary.top + 1
            val primaryWidth = primary.right - primary.left + 1
            val diacriticReach = maxOf(primaryHeight * 3, 60)
            // True dots/accents always sit in the upper portion of the cell, above the body.
            // Pick the TOP-MOST candidate (smallest .top) in the upper half of the canvas that
            // is centered roughly above the primary. Selecting by area was unreliable when stray
            // ink fragments between dot and body had similar pixel counts to the real dot.
            val matchesDiacritic: (InkComponent) -> Boolean = { c ->
                c !== primary &&
                    c.area in 2..200 &&
                    c.bottom < primary.top &&
                    c.top < height / 2 &&
                    run {
                        val w = c.right - c.left + 1
                        val h = c.bottom - c.top + 1
                        val ar = w.toFloat() / h.coerceAtLeast(1)
                        // i/j dots from handwriting are often a horizontal smear (AR up to ~5),
                        // accents like acute/grave are slanted lines (AR ~0.4..2.5). Be permissive.
                        ar in 0.3f..5.0f
                    } &&
                    (primary.top - c.bottom - 1) <= diacriticReach
                // No horizontal alignment check: the cell contains a single letter, so any
                // small mark in the upper portion of the cell that matches dot/accent shape
                // belongs to this letter. Users routinely place i/j dots slightly off-center
                // and sometimes far to the left of the body.
            }

            // 1) Safety-net: if a likely dot was rejected by shouldKeepComponent, add it.
            val safetyNet = components.asSequence()
                .filter { it !in keptComponents }
                .filter(matchesDiacritic)
                .minByOrNull { it.top }
            if (safetyNet != null) {
                keptComponents += safetyNet
                safetyNet.pixels.forEach { diacriticPixelMask[it] = true }
            }

            // 2) Relocate ANY kept component that looks like a dot/accent so it sits close to
            //    the body AND horizontally centered above the primary's body. The user may have
            //    drawn it far above (near the cell top) or off to the side (j dots especially);
            //    after normalization that places it well outside the ascender zone or visually
            //    detached from the letter.
            //    Target gap = 18% of primary height (e.g. ~14px for an ~80px-tall i stem).
            val targetGap = maxOf(2, (primaryHeight * 0.18f).toInt())
            val primaryCenterX = (primary.left + primary.right) / 2
            val toRemove = mutableListOf<InkComponent>()
            val toAdd = mutableListOf<InkComponent>()
            for (dot in keptComponents) {
                if (!matchesDiacritic(dot)) continue
                val currentGap = primary.top - dot.bottom - 1
                val dotCenterX = (dot.left + dot.right) / 2
                val shiftY = (currentGap - targetGap).coerceAtLeast(0)
                val shiftX = primaryCenterX - dotCenterX
                if (shiftY <= 0 && shiftX == 0) {
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

        // Dilate diacritic pixels by 3x3 — ONLY for characters known to carry a diacritic, and
        // only seeds that already survived component filtering. This avoids promoting paper
        // grain into visible dots above letters that shouldn't have any.
        if (expectsDiacritic) {
            val dilated = BooleanArray(diacriticPixelMask.size)
            for (i in diacriticPixelMask.indices) {
                if (!diacriticPixelMask[i] || !filteredInkMask[i]) continue
                val cy = i / width
                val cx = i % width
                for (dy in -1..1) {
                    val ny = cy + dy
                    if (ny < 0 || ny >= height) continue
                    for (dx in -1..1) {
                        val nx = cx + dx
                        if (nx < 0 || nx >= width) continue
                        dilated[ny * width + nx] = true
                    }
                }
            }
            for (i in dilated.indices) {
                if (dilated[i]) {
                    diacriticPixelMask[i] = true
                    filteredInkMask[i] = true
                }
            }
            // Recompute bounding box including dilated dot pixels.
            for (i in filteredInkMask.indices) {
                if (!filteredInkMask[i]) continue
                val y = i / width
                val x = i % width
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }

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
                val l = luma[srcIdx]
                val alpha = if (!filteredInkMask[srcIdx]) {
                    0
                } else if (diacriticPixelMask[srcIdx]) {
                    // Diacritic pixels may have been relocated (see expectsDiacritic block);
                    // source the luma from their original position. Boost alpha so faint dots
                    // remain visible after downscale, but don't force pure black — that creates
                    // harsher dots than the rest of the stroke.
                    val sourceIdx = diacriticSourceMap[srcIdx] ?: srcIdx
                    val srcLuma = luma[sourceIdx]
                    if (srcLuma > permissiveThreshold) {
                        240
                    } else {
                        (((permissiveThreshold - srcLuma).toFloat() / darkSpan) * 255f)
                            .toInt()
                            .coerceIn(230, 255)
                    }
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

        return placeOnCanvas(
            glyph = masked,
            preserveComponentSeparation = keptComponents.size > 1,
            guideBand = guideBandInMasked,
        )
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

    private fun shouldKeepComponent(
        component: InkComponent,
        primary: InkComponent,
        guideYsInCrop: IntArray = intArrayOf(),
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
        val guideBandHalf = 4
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
            val isDiacritic = componentArea < maxOf(primaryArea / 5, 30)
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
     * Canvas height is [TARGET_CANVAS] (for consistent baseline math in the renderer).
     * Canvas width is trimmed to the actual ink width + [SIDE_PAD] on each side, so
     * [HandwritingBitmapRenderer] advances by the real character width rather than the full
     * square canvas — eliminating the excessive inter-character gap in user-captured templates.
     */
    private fun placeOnCanvas(
        glyph: Bitmap,
        preserveComponentSeparation: Boolean = false,
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
        val scaledH = (srcH * scale).roundToInt().coerceAtLeast(1).coerceAtMost(TARGET_CANVAS)

        // Width-trim: use actual ink width + small side padding so the renderer's
        // glyph.width advance reflects the true character width.
        val canvasWidth = (scaledW + 2 * SIDE_PAD).coerceAtMost(TARGET_CANVAS)
        val canvasBmp = Bitmap.createBitmap(canvasWidth, TARGET_CANVAS, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBmp)

        val left = SIDE_PAD.toFloat()
        val top = guideBand
            ?.takeIf { it.baselineY > 0 }
            ?.let { TARGET_BASELINE_Y - it.baselineY * scale }
            ?: (TARGET_BASELINE_Y - scaledH).toFloat()
        val dst = RectF(left, top, left + scaledW, top + scaledH)
        val src = Rect(0, 0, glyph.width, glyph.height)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = !preserveComponentSeparation
        }
        canvas.drawBitmap(glyph, src, dst, paint)
        return canvasBmp
    }

    internal fun normalizedCanvasSize(): Int = TARGET_CANVAS

    internal fun normalizedBaselineRow(): Int = TARGET_BASELINE_Y

    internal fun normalizedGuideBandHeight(): Int = TARGET_GUIDE_BAND_HEIGHT

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
}
