package com.baer.handtype.feature.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Generates the printable practice sheet that users fill out to calibrate their handwriting font.
 *
 * The sheet uses four solid black square fiducial markers (one per corner of the writable area)
 * plus a uniform 8×8 cell grid covering 62 alphanumeric characters (A-Z, a-z, 0-9). The character
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
    private const val GRID_ROWS = 8
    private const val LABEL_COLOR = 0xFFB7B0A3.toInt()
    private const val GRID_COLOR = 0xFFCCC4B4.toInt()
    private const val GUIDE_COLOR = 0xFFD8D1C3.toInt()
    private const val GUIDE_SEGMENT_LENGTH = 26f
    internal const val GUIDE_UPPER_RATIO = 0.32f
    internal const val GUIDE_BASELINE_RATIO = 0.78f
    private const val GUIDE_MIDDLE_RATIO = (GUIDE_UPPER_RATIO + GUIDE_BASELINE_RATIO) / 2f

    /** Characters laid out left-to-right, top-to-bottom into the 8×8 grid. */
    val cellCharacters: List<Char> = buildList {
        addAll(('A'..'Z').toList())
        addAll(('a'..'z').toList())
        addAll(('0'..'9').toList())
        // Fill remaining 2 cells with placeholders so the sheet stays a uniform grid.
        add(' ')
        add(' ')
    }

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

    /** Cell rectangles in **rectified** coordinates (0..1 over the grid bounds defined by fiducials). */
    val normalizedCells: List<CellRect> = buildList {
        // Inset the grid well inside the fiducial bounding box so the writable cells never overlap
        // the printed black corner squares (each fiducial extends FIDUCIAL_SIZE/2 from its centre,
        // which is ~3.8% of the grid width on the canonical sheet).
        val gridInset = 0.06f
        val gridLeft = gridInset
        val gridTop = gridInset
        val gridRight = 1f - gridInset
        val gridBottom = 1f - gridInset
        val cellWidth = (gridRight - gridLeft) / GRID_COLS
        val cellHeight = (gridBottom - gridTop) / GRID_ROWS

        cellCharacters.forEachIndexed { index, character ->
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
        val bitmap = renderBitmap()
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
    fun renderBitmap(): Bitmap {
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
            "Write each character inside its cell. Keep the four black squares visible.",
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

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LABEL_COLOR
            textSize = 28f
            typeface = Typeface.DEFAULT
        }
        val gridLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GRID_COLOR
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GUIDE_COLOR
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }

        normalizedCells.forEach { cell ->
            val rect = RectF(
                gridLeftPx + cell.left * gridWidthPx,
                gridTopPx + cell.top * gridHeightPx,
                gridLeftPx + cell.right * gridWidthPx,
                gridTopPx + cell.bottom * gridHeightPx,
            )
            canvas.drawRect(rect, gridLinePaint)
            drawGuideTicks(canvas, rect, guidePaint)
            if (cell.character != ' ') {
                canvas.drawText(
                    cell.character.toString(),
                    rect.left + 8f,
                    rect.top + 32f,
                    labelPaint,
                )
            }
        }

        return bitmap
    }

    private fun drawGuideTicks(canvas: Canvas, rect: RectF, paint: Paint) {
        val upperGuideY = rect.top + rect.height() * GUIDE_UPPER_RATIO
        val middleGuideY = rect.top + rect.height() * GUIDE_MIDDLE_RATIO
        val baselineY = rect.top + rect.height() * GUIDE_BASELINE_RATIO
        val centerX = rect.centerX()
        val halfSegment = GUIDE_SEGMENT_LENGTH / 2f

        canvas.drawLine(centerX - halfSegment, upperGuideY, centerX + halfSegment, upperGuideY, paint)
        canvas.drawLine(centerX - halfSegment, middleGuideY, centerX + halfSegment, middleGuideY, paint)
        canvas.drawLine(centerX - halfSegment, baselineY, centerX + halfSegment, baselineY, paint)
    }
}
