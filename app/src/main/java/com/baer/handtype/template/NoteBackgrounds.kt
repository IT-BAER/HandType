package com.baer.handtype.template

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.annotation.StringRes
import com.baer.handtype.R
import kotlin.math.max
import kotlin.random.Random

data class NoteBackgroundPreset(
    val id: String,
    @StringRes val labelRes: Int,
    val premium: Boolean,
    val previewColors: List<Int>,
    val previewPattern: NoteBackgroundPreviewPattern = NoteBackgroundPreviewPattern.Solid,
    private val style: NoteBackgroundStyle,
) {
    fun render(
        canvas: Canvas,
        width: Int,
        height: Int,
        seed: Long,
        lineSpacingPx: Int,
        firstBaselinePx: Int,
        marginPx: Int,
    ) {
        style.render(
            canvas = canvas,
            width = width,
            height = height,
            seed = seed xor id.hashCode().toLong(),
            lineSpacingPx = lineSpacingPx,
            firstBaselinePx = firstBaselinePx,
            marginPx = marginPx,
        )
    }
}

enum class NoteBackgroundPreviewPattern {
    Solid,
    Texture,
    Ruled,
    Graph,
}

sealed interface NoteBackgroundStyle {
    fun render(
        canvas: Canvas,
        width: Int,
        height: Int,
        seed: Long,
        lineSpacingPx: Int,
        firstBaselinePx: Int,
        marginPx: Int,
    )
}

private data class SolidBackgroundStyle(
    val color: Int,
) : NoteBackgroundStyle {
    override fun render(
        canvas: Canvas,
        width: Int,
        height: Int,
        seed: Long,
        lineSpacingPx: Int,
        firstBaselinePx: Int,
        marginPx: Int,
    ) {
        canvas.drawColor(color)
    }
}

private data object TransparentBackgroundStyle : NoteBackgroundStyle {
    override fun render(
        canvas: Canvas,
        width: Int,
        height: Int,
        seed: Long,
        lineSpacingPx: Int,
        firstBaselinePx: Int,
        marginPx: Int,
    ) = Unit
}

private data class TexturedPaperBackgroundStyle(
    val baseColor: Int,
    val lightColor: Int,
    val shadowColor: Int,
    val fleckColor: Int,
    val fiberColor: Int,
) : NoteBackgroundStyle {
    override fun render(
        canvas: Canvas,
        width: Int,
        height: Int,
        seed: Long,
        lineSpacingPx: Int,
        firstBaselinePx: Int,
        marginPx: Int,
    ) {
        canvas.drawColor(baseColor)

        val washPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                lightColor,
                shadowColor,
                Shader.TileMode.CLAMP,
            )
            alpha = 120
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), washPaint)

        val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                width * 0.5f,
                height * 0.35f,
                max(width, height) * 0.95f,
                withAlpha(lightColor, 8),
                withAlpha(shadowColor, 34),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vignettePaint)

        val rng = Random(seed)
        val fleckPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val fleckCount = (width * height / 12_000).coerceIn(90, 520)
        repeat(fleckCount) {
            fleckPaint.color = if (rng.nextFloat() < 0.65f) fleckColor else lightColor
            fleckPaint.alpha = rng.nextInt(10, 32)
            val radius = rng.nextFloat() * 2.8f + 0.5f
            canvas.drawCircle(
                rng.nextFloat() * width,
                rng.nextFloat() * height,
                radius,
                fleckPaint,
            )
        }

        val fiberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        val fiberCount = (width * height / 24_000).coerceIn(50, 260)
        repeat(fiberCount) {
            val startX = rng.nextFloat() * width
            val startY = rng.nextFloat() * height
            val length = rng.nextFloat() * 30f + 10f
            val angle = rng.nextFloat() * 0.8f - 0.4f
            val endX = startX + length
            val endY = startY + length * angle
            fiberPaint.color = if (rng.nextBoolean()) fiberColor else shadowColor
            fiberPaint.alpha = rng.nextInt(10, 28)
            fiberPaint.strokeWidth = rng.nextFloat() * 1.2f + 0.3f
            canvas.drawLine(startX, startY, endX, endY, fiberPaint)
        }
    }
}

private data class GuidedPaperBackgroundStyle(
    val baseColor: Int,
    val lightColor: Int,
    val shadowColor: Int,
    val guideColor: Int,
    val accentColor: Int,
    val graph: Boolean,
) : NoteBackgroundStyle {
    override fun render(
        canvas: Canvas,
        width: Int,
        height: Int,
        seed: Long,
        lineSpacingPx: Int,
        firstBaselinePx: Int,
        marginPx: Int,
    ) {
        canvas.drawColor(baseColor)

        val washPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                lightColor,
                shadowColor,
                Shader.TileMode.CLAMP,
            )
            alpha = 58
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), washPaint)

        val fleckPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rng = Random(seed)
        repeat((width * height / 30_000).coerceIn(24, 120)) {
            fleckPaint.color = withAlpha(shadowColor, rng.nextInt(8, 18))
            canvas.drawCircle(
                rng.nextFloat() * width,
                rng.nextFloat() * height,
                rng.nextFloat() * 1.6f + 0.4f,
                fleckPaint,
            )
        }

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        if (graph) {
            val majorStep = lineSpacingPx.coerceAtLeast(34)
            val minorStep = (majorStep / 2f).toInt().coerceAtLeast(18)
            linePaint.color = withAlpha(guideColor, 46)
            linePaint.strokeWidth = 1f
            var x = 0f
            while (x <= width) {
                canvas.drawLine(x, 0f, x, height.toFloat(), linePaint)
                x += minorStep
            }
            var y = 0f
            while (y <= height) {
                canvas.drawLine(0f, y, width.toFloat(), y, linePaint)
                y += minorStep
            }
            linePaint.color = withAlpha(accentColor, 64)
            linePaint.strokeWidth = 1.2f
            x = marginPx.toFloat()
            while (x > 0f) {
                canvas.drawLine(x, 0f, x, height.toFloat(), linePaint)
                x -= majorStep
            }
            x = marginPx.toFloat() + majorStep
            while (x <= width) {
                canvas.drawLine(x, 0f, x, height.toFloat(), linePaint)
                x += majorStep
            }

            val baselineY = firstBaselinePx.toFloat().coerceAtLeast(0f)
            y = baselineY
            while (y > 0f) {
                canvas.drawLine(0f, y, width.toFloat(), y, linePaint)
                y -= majorStep
            }
            y = baselineY + majorStep
            while (y <= height) {
                canvas.drawLine(0f, y, width.toFloat(), y, linePaint)
                y += majorStep
            }
        } else {
            val step = lineSpacingPx.coerceAtLeast(34)
            val baselineY = firstBaselinePx.toFloat().coerceAtLeast(0f)
            linePaint.color = withAlpha(guideColor, 74)
            linePaint.strokeWidth = 1.2f
            var y = baselineY
            while (y > 0f) {
                canvas.drawLine(0f, y, width.toFloat(), y, linePaint)
                y -= step
            }
            y = baselineY + step
            while (y <= height) {
                canvas.drawLine(0f, y, width.toFloat(), y, linePaint)
                y += step
            }
            linePaint.color = withAlpha(accentColor, 96)
            linePaint.strokeWidth = 1.6f
            val marginLineX = (marginPx * 1.75f).coerceAtLeast(48f)
            canvas.drawLine(marginLineX, 0f, marginLineX, height.toFloat(), linePaint)
        }
    }
}

object NoteBackgroundCatalog {
    private const val DEFAULT_ID = "warm_cream"

    val transparentPreset = NoteBackgroundPreset(
        id = "transparent",
        labelRes = R.string.note_background_plain_white,
        premium = false,
        previewColors = listOf(0x00FFFFFF),
        previewPattern = NoteBackgroundPreviewPattern.Solid,
        style = TransparentBackgroundStyle,
    )

    val selectablePresets: List<NoteBackgroundPreset> = listOf(
        NoteBackgroundPreset(
            id = "plain_white",
            labelRes = R.string.note_background_plain_white,
            premium = false,
            previewColors = listOf(0xFFFFFFFF.toInt()),
            previewPattern = NoteBackgroundPreviewPattern.Solid,
            style = SolidBackgroundStyle(0xFFFFFFFF.toInt()),
        ),
        NoteBackgroundPreset(
            id = "warm_cream",
            labelRes = R.string.note_background_warm_cream,
            premium = false,
            previewColors = listOf(0xFFF8F1E4.toInt()),
            previewPattern = NoteBackgroundPreviewPattern.Solid,
            style = SolidBackgroundStyle(0xFFF8F1E4.toInt()),
        ),
        NoteBackgroundPreset(
            id = "soft_butter",
            labelRes = R.string.note_background_soft_butter,
            premium = false,
            previewColors = listOf(0xFFFFF3C6.toInt()),
            previewPattern = NoteBackgroundPreviewPattern.Solid,
            style = SolidBackgroundStyle(0xFFFFF3C6.toInt()),
        ),
        NoteBackgroundPreset(
            id = "rose_blush",
            labelRes = R.string.note_background_rose_blush,
            premium = false,
            previewColors = listOf(0xFFFBE7EA.toInt()),
            previewPattern = NoteBackgroundPreviewPattern.Solid,
            style = SolidBackgroundStyle(0xFFFBE7EA.toInt()),
        ),
        NoteBackgroundPreset(
            id = "cool_mist",
            labelRes = R.string.note_background_cool_mist,
            premium = false,
            previewColors = listOf(0xFFE9F1F7.toInt()),
            previewPattern = NoteBackgroundPreviewPattern.Solid,
            style = SolidBackgroundStyle(0xFFE9F1F7.toInt()),
        ),
        NoteBackgroundPreset(
            id = "soft_sage",
            labelRes = R.string.note_background_soft_sage,
            premium = false,
            previewColors = listOf(0xFFE8F0E7.toInt()),
            previewPattern = NoteBackgroundPreviewPattern.Solid,
            style = SolidBackgroundStyle(0xFFE8F0E7.toInt()),
        ),
        NoteBackgroundPreset(
            id = "parchment_sheet",
            labelRes = R.string.note_background_parchment_sheet,
            premium = true,
            previewColors = listOf(0xFFF8ECD3.toInt(), 0xFFE6D1A7.toInt()),
            previewPattern = NoteBackgroundPreviewPattern.Texture,
            style = TexturedPaperBackgroundStyle(
                baseColor = 0xFFF3E4C6.toInt(),
                lightColor = 0xFFFFF5E1.toInt(),
                shadowColor = 0xFFD3B98A.toInt(),
                fleckColor = 0xFFE0C89E.toInt(),
                fiberColor = 0xFFD9BE90.toInt(),
            ),
        ),
        NoteBackgroundPreset(
            id = "recycled_sheet",
            labelRes = R.string.note_background_recycled_sheet,
            premium = true,
            previewColors = listOf(0xFFF0EBE0.toInt(), 0xFFD8D1C4.toInt()),
            previewPattern = NoteBackgroundPreviewPattern.Texture,
            style = TexturedPaperBackgroundStyle(
                baseColor = 0xFFE8E1D4.toInt(),
                lightColor = 0xFFF7F1E8.toInt(),
                shadowColor = 0xFFC8C0B1.toInt(),
                fleckColor = 0xFFBFB29E.toInt(),
                fiberColor = 0xFFD4CABB.toInt(),
            ),
        ),
        NoteBackgroundPreset(
            id = "kraft_sheet",
            labelRes = R.string.note_background_kraft_sheet,
            premium = true,
            previewColors = listOf(0xFFE3CDA8.toInt(), 0xFFC5A879.toInt()),
            previewPattern = NoteBackgroundPreviewPattern.Texture,
            style = TexturedPaperBackgroundStyle(
                baseColor = 0xFFD8BC92.toInt(),
                lightColor = 0xFFE8D1AA.toInt(),
                shadowColor = 0xFFB08957.toInt(),
                fleckColor = 0xFFC29A69.toInt(),
                fiberColor = 0xFFE0C395.toInt(),
            ),
        ),
        NoteBackgroundPreset(
            id = "ruled_sheet",
            labelRes = R.string.note_background_ruled_sheet,
            premium = true,
            previewColors = listOf(0xFFFBFAF5.toInt(), 0xFFE7EEF8.toInt()),
            previewPattern = NoteBackgroundPreviewPattern.Ruled,
            style = GuidedPaperBackgroundStyle(
                baseColor = 0xFFFBFAF5.toInt(),
                lightColor = 0xFFFFFFFF.toInt(),
                shadowColor = 0xFFE7E3D8.toInt(),
                guideColor = 0xFF9BB7DB.toInt(),
                accentColor = 0xFFD58F8F.toInt(),
                graph = false,
            ),
        ),
        NoteBackgroundPreset(
            id = "graph_sheet",
            labelRes = R.string.note_background_graph_sheet,
            premium = true,
            previewColors = listOf(0xFFF8FBFF.toInt(), 0xFFE0ECF7.toInt()),
            previewPattern = NoteBackgroundPreviewPattern.Graph,
            style = GuidedPaperBackgroundStyle(
                baseColor = 0xFFF9FBFE.toInt(),
                lightColor = 0xFFFFFFFF.toInt(),
                shadowColor = 0xFFE2EBF3.toInt(),
                guideColor = 0xFF8DB2D2.toInt(),
                accentColor = 0xFF6D99C2.toInt(),
                graph = true,
            ),
        ),
    )

    fun defaultPreset(): NoteBackgroundPreset = presetOrDefault(DEFAULT_ID)

    fun presetOrDefault(id: String?): NoteBackgroundPreset {
        return selectablePresets.firstOrNull { it.id == id } ?: selectablePresets.first { it.id == DEFAULT_ID }
    }
}

class NoteBackgroundPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getDefaultBackgroundId(): String {
        return prefs.getString(KEY_DEFAULT_BACKGROUND_ID, NoteBackgroundCatalog.defaultPreset().id)
            ?: NoteBackgroundCatalog.defaultPreset().id
    }

    fun setDefaultBackgroundId(backgroundId: String) {
        val safeId = NoteBackgroundCatalog.presetOrDefault(backgroundId).id
        prefs.edit().putString(KEY_DEFAULT_BACKGROUND_ID, safeId).apply()
    }

    private companion object {
        private const val PREFS_NAME = "note_background_prefs"
        private const val KEY_DEFAULT_BACKGROUND_ID = "default_background_id"
    }
}

private fun withAlpha(color: Int, alpha: Int): Int {
    return (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)
}