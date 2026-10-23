package com.baer.handtype

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baer.handtype.template.BundledTemplateRepository
import com.baer.handtype.template.HandwritingBitmapRenderer
import com.baer.handtype.template.HandwritingRenderConfig
import com.baer.handtype.template.HandwritingTemplate
import com.baer.handtype.template.NoteBackgroundCatalog
import com.baer.handtype.ui.animation.NoteWriteOn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/** Ink Script layout at the render screen's size on a 450 dpi phone (990 px wide, 76 dp lines). */
@RunWith(AndroidJUnit4::class)
class RenderLayoutTest {
    @Test fun wordsWiderThanALineBreakWithAHyphen() {
        val texts = listOf(
            "Liebe Anna,\ndanke! Es war ein\nwunderbarer Abend.\nBis Sonntag!\nDeine Mia",
            "Alles Gute zum\nGeburtstag, Tom!",
            "Einkaufsliste\n- Milch\n- Eier",
            "Donaudampfschifffahrtsgesellschaft",
        )
        val failures = mutableListOf<String>()
        for (text in texts) for (seed in 1L..SEEDS) {
            val result = render(text, seed)
            val width = result.bitmap.width
            result.bitmap.recycle()
            val problem = hyphenationProblem(text.split(' ', '\n'), result.lines)
                ?: if (width > WIDTH_PX) "note is $width px wide" else null
            if (problem != null) failures += "seed $seed: $problem in ${result.lines}"
        }
        assertTrue("${failures.size} bad renders:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    @Test fun wordsThatFitAreNeverBroken() {
        val text = "Dear Anna,\nthank you for the lovely evening!\nSee you on Sunday.\nLove, Mia"
        for (seed in 1L..SEEDS) {
            val result = render(text, seed)
            result.bitmap.recycle()
            val laidOut = result.lines.flatMap { it.trim().split(' ') }.filter(String::isNotEmpty)
            assertTrue("seed $seed broke a word: ${result.lines}", laidOut == text.split(' ', '\n'))
        }
    }

    @Test fun exclamationMarkStandsApartFromThePreviousLetter() {
        val failures = mutableListOf<String>()
        for (word in listOf("Tom", "bald", "everything", "Sunday")) for (seed in 1L..SEEDS) {
            val gap = gapBeforeLastMark(render("$word!", seed).bitmap)
            if (gap < MIN_GAP_PX) failures += "$word!/$seed gap=$gap"
        }
        assertTrue("${failures.size} renders glue '!' to the letter: $failures", failures.isEmpty())
    }

    @Test fun inkRowsHoldEveryInkPixelAndThePaperHasNone() {
        val text = "Dear Anna,\nthank you for the lovely evening!\n\nSee you on Sunday, gladly.\nLove, Mia"
        val failures = mutableListOf<String>()
        for (seed in 1L..SEEDS) for (cursive in listOf(false, true)) {
            val config = HandwritingRenderConfig(
                maxWidthPx = WIDTH_PX,
                targetLineHeightPx = 214,
                noteBackground = NoteBackgroundCatalog.transparentPreset,
            )
            val result = if (cursive) {
                HandwritingBitmapRenderer.renderCursive(text, emptyMap(), config, seed, keepPaper = true)
            } else {
                HandwritingBitmapRenderer.render(text, template, config, seed, keepPaper = true)
            }
            val bmp = result.bitmap
            val rows = result.inkRows
            val label = "seed $seed cursive=$cursive"
            if (rows.size < 4) failures += "$label: ${rows.size} rows"
            if (rows.firstOrNull()?.top != 0 || rows.lastOrNull()?.bottom != bmp.height ||
                rows.zipWithNext().any { (a, b) -> a.bottom != b.top }
            ) failures += "$label: rows do not tile the height: $rows"
            val pixels = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
            val written = NoteWriteOn.revealBoxes(rows, NoteWriteOn.totalMillis(rows.size).toLong())
            var outside = 0
            for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
                if ((pixels[y * bmp.width + x] ushr 24) == 0) continue
                if (written.none { x >= it.left && x < it.right && y >= it.top && y < it.bottom }) outside++
            }
            if (outside > 0) failures += "$label: $outside ink pixels never written"
            val paper = result.paper!!
            val paperPixels = IntArray(paper.width * paper.height).also { paper.getPixels(it, 0, paper.width, 0, 0, paper.width, paper.height) }
            if (paperPixels.any { (it ushr 24) != 0 }) failures += "$label: paper has ink"
            bmp.recycle()
            paper.recycle()
        }
        assertTrue("${failures.size} bad renders:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    /** Null when [lines] spell [words] in order, any word split only as "ab-" + "cde" pieces. */
    private fun hyphenationProblem(words: List<String>, lines: List<String>): String? {
        val tokens = ArrayDeque(lines.flatMap { it.trim().split(' ') }.filter(String::isNotEmpty))
        for (word in words) {
            var rest = word
            while (true) {
                val token = tokens.removeFirstOrNull() ?: return "missing '$rest'"
                if (token == rest) break
                if (!token.endsWith('-')) return "'$token' is not '$rest'"
                val piece = token.dropLast(1)
                if (piece.length < 2 || !rest.startsWith(piece)) return "bad piece '$token' of '$word'"
                rest = rest.drop(piece.length)
                if (rest.count(Char::isLetterOrDigit) < 3) return "'$word' leaves '$rest' after the hyphen"
            }
        }
        return if (tokens.isEmpty()) null else "extra tokens $tokens"
    }

    private fun render(text: String, seed: Long) = HandwritingBitmapRenderer.render(
        text = text,
        template = template,
        config = HandwritingRenderConfig(
            maxWidthPx = WIDTH_PX,
            targetLineHeightPx = 214,
            noteBackground = NoteBackgroundCatalog.transparentPreset,
        ),
        seed = seed,
    )

    /** Empty columns between the rightmost ink run (the mark) and the ink left of it. */
    private fun gapBeforeLastMark(bitmap: Bitmap): Int {
        try {
            val inked = BooleanArray(bitmap.width) { x ->
                (0 until bitmap.height).any { y -> (bitmap.getPixel(x, y) ushr 24) > 128 }
            }
            var x = inked.lastIndexOf(true)
            while (x >= 0 && inked[x]) x--
            var gap = 0
            while (x >= 0 && !inked[x]) { gap++; x-- }
            return if (x < 0) 0 else gap
        } finally {
            bitmap.recycle()
        }
    }

    companion object {
        private const val SEEDS = 20L
        private const val WIDTH_PX = 990
        private const val MIN_GAP_PX = 4
        private lateinit var template: HandwritingTemplate

        @JvmStatic @BeforeClass fun loadTemplate() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            template = runBlocking { BundledTemplateRepository(context).loadTemplate("classic_script") }
        }
    }
}
