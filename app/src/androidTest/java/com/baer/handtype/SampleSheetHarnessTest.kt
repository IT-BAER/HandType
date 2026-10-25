package com.baer.handtype

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baer.handtype.feature.capture.PracticeSheetGenerator
import com.baer.handtype.feature.capture.SheetSampleProcessor
import com.baer.handtype.template.HandwritingBitmapRenderer
import com.baer.handtype.template.HandwritingRenderConfig
import com.baer.handtype.template.HandwritingTemplate
import com.baer.handtype.template.TemplateDescriptor
import java.util.Locale
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the scan extraction on every sheet PNG pushed to `files/handtype_test/inbox` and writes a
 * glyph montage, a rendered note and a report per sheet to `files/handtype_test/outbox`.
 */
@RunWith(AndroidJUnit4::class)
class SampleSheetHarnessTest {

    private val german = PracticeSheetGenerator.localeCharacters(Locale.GERMANY)
    private val root = InstrumentationRegistry.getInstrumentation().targetContext
        .getExternalFilesDir(null)!!.resolve("handtype_test")

    @Test
    fun extractEverySheetInTheInbox() {
        val sheets = root.resolve("inbox").listFiles { f -> f.extension == "png" }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no sheets in inbox", sheets.isNotEmpty())
        val out = root.resolve("outbox").apply { mkdirs() }
        val expected = PracticeSheetGenerator.cellCharacters(german).filter { it != ' ' }
        val report = StringBuilder()
        for (file in sheets) {
            val bitmap = BitmapFactory.decodeFile(file.path)
            val result = SheetSampleProcessor.process(bitmap, skipPageDetection = true, localeCharacters = german)
            val missing = expected.filter { it !in result.glyphs.keys }
            report.append("${file.name}: detectionFailed=${result.detectionFailed} kept=${result.glyphs.size}/${expected.size} missing=${missing.joinToString("")}\n")
            if (result.glyphs.isEmpty()) continue
            save(out, "${file.nameWithoutExtension}_glyphs", montage(expected, result.glyphs))
            val template = HandwritingTemplate(
                descriptor = TemplateDescriptor("user_u1", "Test", "", "", premium = false),
                glyphs = result.glyphs.mapValues { listOf(it.value) },
            )
            val note = HandwritingBitmapRenderer.render(
                "Hello Jenny, Quick Brown Foxes jump.\nBig and small letters: Aa Bb Gg Hh Ll Yy\nGrüße aus Österreich, 2026!",
                template,
                HandwritingRenderConfig(maxWidthPx = 1000),
                seed = 42,
            )
            save(out, "${file.nameWithoutExtension}_note", note.bitmap)
        }
        out.resolve("report.txt").writeText(report.toString())
    }

    /** Extracted glyphs in sheet order on a white grid; an empty cell marks a missing glyph. */
    private fun montage(chars: List<Char>, glyphs: Map<Char, Bitmap>): Bitmap {
        val cell = 120
        val cols = PracticeSheetGenerator.GRID_COLS
        val rows = (chars.size + cols - 1) / cols
        val bitmap = Bitmap.createBitmap(cols * cell, rows * cell, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val border = Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; textSize = 18f }
        chars.forEachIndexed { i, c ->
            val x = (i % cols) * cell
            val y = (i / cols) * cell
            canvas.drawRect(x.toFloat(), y.toFloat(), (x + cell).toFloat(), (y + cell).toFloat(), border)
            canvas.drawText(c.toString(), x + 4f, y + 18f, label)
            glyphs[c]?.let { g ->
                val s = minOf(cell.toFloat() / g.width, cell.toFloat() / g.height)
                canvas.drawBitmap(Bitmap.createScaledBitmap(g, (g.width * s).toInt().coerceAtLeast(1), (g.height * s).toInt().coerceAtLeast(1), true), x.toFloat(), y.toFloat(), null)
            }
        }
        return bitmap
    }

    private fun save(dir: java.io.File, name: String, bitmap: Bitmap) {
        dir.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
