package com.baer.handtype.feature.capture.stylus

import android.graphics.Bitmap
import com.baer.handtype.feature.capture.PracticeSheetGenerator
import com.baer.handtype.ml.GlyphPostProcessor
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One printed practice-sheet cell at the scan's rectified resolution. Insets and guide rows use
 * the same formulas as the cell crop in `SheetSampleProcessor`, so a written glyph reaches
 * [GlyphPostProcessor.cleanGlyph] with the geometry a scanned glyph has.
 */
data class CellGeometry(val cellWidthPx: Int, val cellHeightPx: Int) {
    val topInset: Int = (cellHeightPx * 0.03f).toInt().coerceAtLeast(3)
    val sideInset: Int = (min(cellWidthPx, cellHeightPx) * 0.03f).toInt().coerceAtLeast(3)
    val cropWidth: Int = cellWidthPx - 2 * sideInset
    val cropHeight: Int = cellHeightPx - topInset - sideInset
    val guideYsInCrop: IntArray = floatArrayOf(
        PracticeSheetGenerator.GUIDE_UPPER_RATIO,
        PracticeSheetGenerator.GUIDE_BASELINE_RATIO,
    ).map { (cellHeightPx * it).toInt() - topInset }.toIntArray()

    /** Width over height of the cell; the on-screen writing box uses the same ratio. */
    val aspectRatio: Float get() = cellWidthPx.toFloat() / cellHeightPx

    companion object {
        /** The first cell of the printed sheet, measured like the scan path measures it. */
        fun printed(): CellGeometry {
            val layout = PracticeSheetGenerator.fiducialLayout
            val gridLeft = layout.topLeft.first
            val gridTop = layout.topLeft.second
            val gridWidth = layout.topRight.first - gridLeft
            val gridHeight = layout.bottomLeft.second - gridTop
            val cell = PracticeSheetGenerator.normalizedCells(PracticeSheetGenerator.cellCharacters()).first()
            val left = (gridLeft + cell.left * gridWidth).toInt()
            val top = (gridTop + cell.top * gridHeight).toInt()
            val right = (gridLeft + cell.right * gridWidth).toInt()
            val bottom = (gridTop + cell.bottom * gridHeight).toInt()
            return CellGeometry(right - left, bottom - top)
        }
    }
}

object InkRasterizer {

    // A 0.5 mm ballpoint line at the sheet's 150 dpi.
    const val PEN_WIDTH_PX = 3f

    private const val WHITE = 0xFFFFFFFF.toInt()

    /**
     * Draws [strokes] black on white paper into a crop-sized ARGB pixel array with anti-aliased
     * round pens. Ink outside the crop is clipped.
     */
    fun rasterize(
        strokes: List<InkStroke>,
        geometry: CellGeometry,
        penWidthPx: Float = PEN_WIDTH_PX,
    ): IntArray {
        val w = geometry.cropWidth
        val h = geometry.cropHeight
        val coverage = FloatArray(w * h)
        val radius = penWidthPx / 2f
        fun px(p: InkPoint) = p.x * geometry.cellWidthPx - geometry.sideInset
        fun py(p: InkPoint) = p.y * geometry.cellHeightPx - geometry.topInset

        for (stroke in strokes) {
            val pts = stroke.points
            if (pts.isEmpty()) continue
            if (pts.size == 1) {
                stamp(coverage, w, h, px(pts[0]), py(pts[0]), px(pts[0]), py(pts[0]), radius)
                continue
            }
            for (i in 1 until pts.size) {
                stamp(coverage, w, h, px(pts[i - 1]), py(pts[i - 1]), px(pts[i]), py(pts[i]), radius)
            }
        }
        return IntArray(w * h) { i ->
            val c = coverage[i]
            if (c <= 0f) {
                WHITE
            } else {
                val l = 255 - (c * 255f).roundToInt()
                (0xFF shl 24) or (l shl 16) or (l shl 8) or l
            }
        }
    }

    fun toBitmap(pixels: IntArray, geometry: CellGeometry): Bitmap =
        Bitmap.createBitmap(pixels, geometry.cropWidth, geometry.cropHeight, Bitmap.Config.ARGB_8888)

    /**
     * Rasterizes every non-empty glyph and cleans it with the scan path's [GlyphPostProcessor.cleanGlyph]
     * call. Guides are passed at their nominal rows: the synthetic crop has no printed lines to snap to
     * or remove.
     */
    fun buildGlyphs(
        characters: List<Char>,
        glyphStrokes: List<List<InkStroke>>,
        geometry: CellGeometry = CellGeometry.printed(),
    ): Map<Char, Bitmap> {
        val glyphs = linkedMapOf<Char, Bitmap>()
        characters.forEachIndexed { index, c ->
            val strokes = glyphStrokes.getOrNull(index).orEmpty()
            if (strokes.none { it.points.isNotEmpty() }) return@forEachIndexed
            val crop = toBitmap(rasterize(strokes, geometry), geometry)
            glyphs[c] = GlyphPostProcessor.cleanGlyph(
                crop,
                geometry.guideYsInCrop,
                expectsDiacritic = GlyphPostProcessor.expectsDiacritic(c),
                rejectPrintedRules = false,
            )
        }
        return glyphs
    }

    /** Max-blends the coverage of a round pen swept from (x0, y0) to (x1, y1). */
    private fun stamp(
        coverage: FloatArray,
        w: Int,
        h: Int,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        radius: Float,
    ) {
        val reach = radius + 0.5f
        val minX = floor(minOf(x0, x1) - reach).toInt().coerceAtLeast(0)
        val maxX = ceil(maxOf(x0, x1) + reach).toInt().coerceAtMost(w - 1)
        val minY = floor(minOf(y0, y1) - reach).toInt().coerceAtLeast(0)
        val maxY = ceil(maxOf(y0, y1) + reach).toInt().coerceAtMost(h - 1)
        if (minX > maxX || minY > maxY) return
        val dx = x1 - x0
        val dy = y1 - y0
        val lengthSq = dx * dx + dy * dy
        for (y in minY..maxY) {
            val cy = y + 0.5f
            for (x in minX..maxX) {
                val cx = x + 0.5f
                val t = if (lengthSq == 0f) 0f else (((cx - x0) * dx + (cy - y0) * dy) / lengthSq).coerceIn(0f, 1f)
                val ex = cx - (x0 + t * dx)
                val ey = cy - (y0 + t * dy)
                val c = (reach - sqrt(ex * ex + ey * ey)).coerceIn(0f, 1f)
                val i = y * w + x
                if (c > coverage[i]) coverage[i] = c
            }
        }
    }
}
