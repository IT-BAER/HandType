package com.baer.handtype.feature.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Generates the printable practice sheet that users fill out to calibrate their handwriting font.
 *
 * The sheet uses four solid black square fiducial markers (one per corner of the writable area)
 * plus a uniform 8×9 cell grid: 62 alphanumeric characters (A-Z, a-z, 0-9), then the letters of the
 * app language (German: ÄÖÜäöüß). Sheets printed with the older 8×8 grid still scan. The character
 * label printed in the corner of each cell is intentionally faint so it does not bias the user's
 * stroke shape but still tells them which character to write.
 *
 * Layout in pixels (A4 at 150 dpi ≈ 1240×1754):
 * - Outer margins: 60px
 * - Fiducial size: 60×60px (solid black squares at each corner of the inner page area)
 * - Grid origin: aligned to the inner edges of the fiducials so post-capture rectification
 *   maps the four detected fiducials directly onto the cell grid.
 */
object PracticeSheetGenerator {

    private const val PAGE_WIDTH = 1240
    private const val PAGE_HEIGHT = 1754
    private const val OUTER_MARGIN = 60
    const val FIDUCIAL_SIZE = 80
    private const val GRID_COLS = 8
    const val GRID_ROWS = 9

    /** Sheets printed before the language row existed; the scan still reads them. */
    const val LEGACY_GRID_ROWS = 8
    private const val LABEL_COLOR = 0xFFB7B0A3.toInt()
    private const val GRID_COLOR = 0xFFCCC4B4.toInt()
    // Faint full-width dashed guide lines: light enough that per-cell Otsu thresholding discards
    // them as paper, so a line running under the letter aids writing without contaminating ink.
    private const val GUIDE_COLOR = 0xFFCEC6B4.toInt()
    internal const val GUIDE_UPPER_RATIO = 0.32f
    internal const val GUIDE_BASELINE_RATIO = 0.78f

    private val baseCharacters: List<Char> = ('A'..'Z') + ('a'..'z') + ('0'..'9')

    /** Letters of [locale]'s language that the last row adds; at most one row (8 cells plus the 2 spare). */
    fun localeCharacters(locale: Locale): List<Char> = when (locale.language) {
        "de" -> "ÄÖÜäöüß".toList()
        else -> emptyList()
    }

    /**
     * Characters laid out left-to-right, top-to-bottom; blank (' ') cells fill the grid. The legacy
     * 8-row layout has no room for [extras].
     */
    fun cellCharacters(extras: List<Char> = emptyList(), rows: Int = GRID_ROWS): List<Char> {
        val characters = if (rows == LEGACY_GRID_ROWS) baseCharacters else baseCharacters + extras
        return characters + List(GRID_COLS * rows - characters.size) { ' ' }
    }

    /** [cellCharacters] for the language [context] shows, which follows the in-app language override. */
    fun cellCharacters(context: Context): List<Char> =
        cellCharacters(localeCharacters(context.resources.configuration.locales[0]))

    /** Rectangle (left, top, right, bottom) of every fiducial centre point on the generated sheet. */
    data class FiducialLayout(
        val topLeft: Pair<Float, Float>,
        val topRight: Pair<Float, Float>,
        val bottomRight: Pair<Float, Float>,
        val bottomLeft: Pair<Float, Float>,
    )

    /**
     * Cell positions within the rectified sheet space, expressed as fractions of the grid area
     * defined by the fiducial centres. Used by the capture post-processor to crop each cell
     * directly without re-running the layout maths.
     */
    data class CellRect(val character: Char, val left: Float, val top: Float, val right: Float, val bottom: Float)

    val fiducialLayout: FiducialLayout = run {
        val half = FIDUCIAL_SIZE / 2f
        val left = OUTER_MARGIN + half
        val top = OUTER_MARGIN + half
        val right = PAGE_WIDTH - OUTER_MARGIN - half
        val bottom = PAGE_HEIGHT - OUTER_MARGIN - half
        FiducialLayout(
            topLeft = left to top,
            topRight = right to top,
            bottomRight = right to bottom,
            bottomLeft = left to bottom,
        )
    }

    /**
     * Cell rectangles in **rectified** coordinates (0..1 over the grid bounds defined by fiducials),
     * one per entry of [characters]; the row count follows from its size.
     */
    fun normalizedCells(characters: List<Char>): List<CellRect> = buildList {
        // Inset the grid well inside the fiducial bounding box so the writable cells never overlap
        // the printed black corner squares (each fiducial extends FIDUCIAL_SIZE/2 from its centre,
        // which is ~3.8% of the grid width on the canonical sheet).
        val gridInset = 0.06f
        val gridLeft = gridInset
        val gridTop = gridInset
        val gridRight = 1f - gridInset
        val gridBottom = 1f - gridInset
        val rows = (characters.size + GRID_COLS - 1) / GRID_COLS
        val cellWidth = (gridRight - gridLeft) / GRID_COLS
        val cellHeight = (gridBottom - gridTop) / rows

        characters.forEachIndexed { index, character ->
            val col = index % GRID_COLS
            val row = index / GRID_COLS
            val left = gridLeft + cellWidth * col
            val top = gridTop + cellHeight * row
            add(
                CellRect(
                    character = character,
                    left = left,
                    top = top,
                    right = left + cellWidth,
                    bottom = top + cellHeight,
                ),
            )
        }
    }

    /**
     * Renders the sheet to [Bitmap], saves it to [Context.getCacheDir] as a PNG and returns the
     * shareable content URI exposed via the app's FileProvider.
     */
    fun renderToShareableUri(context: Context): android.net.Uri {
        val bitmap = renderBitmap(cellCharacters(context))
        val sharedDir = File(context.cacheDir, "shared").apply { if (!exists()) mkdirs() }
        val file = File(sharedDir, "handtype_practice_sheet.png").apply {
            FileOutputStream(this).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }

    /** Builds an in-memory [Bitmap] of the practice sheet. */
    private const val LABEL_X = 8f
    private const val LABEL_BASELINE_Y = 32f
    // Lazy so the layout functions above stay usable in JVM unit tests, where Paint is a stub.
    private val labelPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LABEL_COLOR
            textSize = 28f
            typeface = Typeface.DEFAULT
        }
    }

    /** Ink box of the printed cell label for [character], in pixels relative to the cell's top-left. */
    fun labelBounds(character: Char): Rect = Rect().also {
        labelPaint.getTextBounds(character.toString(), 0, 1, it)
        it.offset(LABEL_X.toInt(), LABEL_BASELINE_Y.toInt())
    }

    fun renderBitmap(characters: List<Char> = cellCharacters()): Bitmap {
        val bitmap = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Title.
        paint.color = 0xFF1A1410.toInt()
        paint.textSize = 36f
        paint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(
            "HandType practice sheet",
            OUTER_MARGIN.toFloat(),
            OUTER_MARGIN - 18f,
            paint,
        )

        // Footer instructions.
        paint.textSize = 22f
        paint.typeface = Typeface.DEFAULT
        canvas.drawText(
            "Write each letter resting on the lower guide line, filling up to the upper line. Keep the four black squares visible.",
            OUTER_MARGIN.toFloat(),
            PAGE_HEIGHT - OUTER_MARGIN + 32f,
            paint,
        )

        // Fiducials.
        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        listOf(
            fiducialLayout.topLeft,
            fiducialLayout.topRight,
            fiducialLayout.bottomRight,
            fiducialLayout.bottomLeft,
        ).forEach { (cx, cy) ->
            val half = FIDUCIAL_SIZE / 2f
            canvas.drawRect(cx - half, cy - half, cx + half, cy + half, paint)
        }

        // Grid + labels.
        val gridLeftPx = fiducialLayout.topLeft.first
        val gridTopPx = fiducialLayout.topLeft.second
        val gridRightPx = fiducialLayout.topRight.first
        val gridBottomPx = fiducialLayout.bottomLeft.second
        val gridWidthPx = gridRightPx - gridLeftPx
        val gridHeightPx = gridBottomPx - gridTopPx

        val gridLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GRID_COLOR
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GUIDE_COLOR
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            pathEffect = DashPathEffect(floatArrayOf(7f, 5f), 0f)
        }

        normalizedCells(characters).forEach { cell ->
            val rect = RectF(
                gridLeftPx + cell.left * gridWidthPx,
                gridTopPx + cell.top * gridHeightPx,
                gridLeftPx + cell.right * gridWidthPx,
                gridTopPx + cell.bottom * gridHeightPx,
            )
            canvas.drawRect(rect, gridLinePaint)
            drawGuideLines(canvas, rect, guidePaint)
            if (cell.character != ' ') {
                canvas.drawText(
                    cell.character.toString(),
                    rect.left + LABEL_X,
                    rect.top + LABEL_BASELINE_Y,
                    labelPaint,
                )
            }
        }

        return bitmap
    }

    private fun drawGuideLines(canvas: Canvas, rect: RectF, paint: Paint) {
        val xHeightY = rect.top + rect.height() * GUIDE_UPPER_RATIO
        val baselineY = rect.top + rect.height() * GUIDE_BASELINE_RATIO
        canvas.drawLine(rect.left, xHeightY, rect.right, xHeightY, paint)
        canvas.drawLine(rect.left, baselineY, rect.right, baselineY, paint)
    }
}
