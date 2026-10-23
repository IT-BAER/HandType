package com.baer.handtype

import android.content.Context
import android.provider.Settings
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baer.handtype.feature.capture.stylus.InkParticleField
import com.baer.handtype.feature.capture.stylus.InkRecognizer
import com.baer.handtype.feature.capture.stylus.InkStroke
import com.baer.handtype.feature.capture.stylus.StylusCaptureScreen
import com.baer.handtype.feature.capture.stylus.StylusSession
import com.baer.handtype.testing.HandTypeTestTags
import com.baer.handtype.ui.theme.HandTypeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class StylusCaptureTest {
    @get:Rule val rule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** [answer] gets the 0-based recognition call number; the screen recognizes cells in order. */
    private class FakeRecognizer(val answer: (Int) -> List<String>?) : InkRecognizer {
        var calls = 0
        override suspend fun prepare(): Boolean = answer(-1) != null
        override suspend fun recognize(strokes: List<InkStroke>, aspectRatio: Float): List<String>? =
            answer(calls++)
        override fun close() {}
    }

    private val characters = StylusSession.sheetCharacters()

    /** Recognizes call n as sheet character n, so a fully written first page passes. */
    private fun inOrder(wrongAt: Int = -1, seen: String = "H") = FakeRecognizer { call ->
        when {
            call < 0 -> emptyList()
            call == wrongAt -> listOf(seen)
            else -> listOf(characters[call].toString())
        }
    }

    private fun progress(n: Int, total: Int) = context.getString(R.string.stylus_progress, n, total)

    private fun setScreen(
        session: StylusSession,
        recognizer: InkRecognizer,
        particles: InkParticleField = InkParticleField(Random(1), context.resources.displayMetrics.density),
    ) {
        rule.setContent {
            HandTypeTheme {
                StylusCaptureScreen(
                    session = session,
                    recognizer = recognizer,
                    onFinished = {},
                    onBack = {},
                    particles = particles,
                )
            }
        }
    }

    /** Sheet indices of the cells shown on the current page (9, or 6 on a short screen). */
    private fun pageCells(start: Int): List<Int> = (start until characters.size).takeWhile {
        rule.onAllNodesWithTag(HandTypeTestTags.stylusCell(it)).fetchSemanticsNodes().isNotEmpty()
    }

    // A tall vertical stroke from above the x-height guide down to the baseline: passes the shape check for any character.
    private fun drawTallStroke(cell: Int) {
        rule.onNodeWithTag(HandTypeTestTags.stylusCell(cell)).performTouchInput {
            down(Offset(width * 0.3f, height * 0.15f))
            moveTo(Offset(width * 0.4f, height * 0.45f))
            moveTo(Offset(width * 0.5f, height * 0.75f))
            up()
        }
    }

    private fun writePage(start: Int = 0, skip: Int = -1): List<Int> {
        val cells = pageCells(start)
        cells.filter { it != skip }.forEach { drawTallStroke(it) }
        return cells
    }

    private fun next() {
        rule.onNodeWithTag(HandTypeTestTags.STYLUS_NEXT).assertIsEnabled().performClick()
        rule.waitForIdle()
    }

    @Test fun acceptedPageAdvancesProgress() {
        val session = StylusSession(characters)
        val total = session.characters.size
        val recognizer = inOrder()
        setScreen(session, recognizer)

        rule.onNodeWithTag(HandTypeTestTags.STYLUS_PROGRESS).assertTextEquals(progress(0, total))
        val cells = writePage()
        assertTrue("a page shows 6 or 9 cells, got ${cells.size}", cells.size == 9 || cells.size == 6)
        rule.runOnIdle { cells.forEach { assertEquals(1, session.glyphs[it].size) } }
        next()

        rule.onNodeWithTag(HandTypeTestTags.STYLUS_PROGRESS).assertTextEquals(progress(cells.size, total))
        rule.runOnIdle {
            assertEquals(cells.size, session.index)
            assertEquals(cells.size, recognizer.calls)
        }
        rule.onNodeWithTag(HandTypeTestTags.stylusCell(cells.size)).assertExists()
    }

    @Test fun wrongLetterShowsHintThenSecondNextKeepsIt() {
        val session = StylusSession(characters)
        val total = session.characters.size
        val recognizer = inOrder(wrongAt = 0)
        setScreen(session, recognizer)

        val cells = writePage()
        next()
        rule.onNodeWithTag(HandTypeTestTags.STYLUS_HINT)
            .assertTextEquals(context.getString(R.string.stylus_hint_wrong, "H", "A"))
        rule.onNodeWithTag(HandTypeTestTags.STYLUS_PROGRESS).assertTextEquals(progress(0, total))

        next()
        rule.onNodeWithTag(HandTypeTestTags.STYLUS_PROGRESS).assertTextEquals(progress(cells.size, total))
        // The kept cell and the cells that already passed are not recognized again.
        rule.runOnIdle { assertEquals(cells.size, recognizer.calls) }
    }

    @Test fun emptyPageIsRejectedWithoutRecognition() {
        val session = StylusSession(characters)
        val recognizer = inOrder()
        setScreen(session, recognizer)

        next()
        rule.onNodeWithTag(HandTypeTestTags.STYLUS_HINT).assertTextEquals(
            context.getString(R.string.stylus_hint_cell, "A", context.getString(R.string.stylus_hint_empty)),
        )
        rule.runOnIdle {
            assertEquals(0, session.index)
            assertEquals(0, recognizer.calls)
        }
    }

    @Test fun oneEmptyCellBlocksThePageAndIsNamed() {
        val session = StylusSession(characters)
        val recognizer = FakeRecognizer { null }
        setScreen(session, recognizer)

        val cells = writePage(skip = 4)
        next()
        rule.onNodeWithTag(HandTypeTestTags.STYLUS_HINT).assertTextEquals(
            context.getString(R.string.stylus_hint_cell, "E", context.getString(R.string.stylus_hint_empty)),
        )
        rule.runOnIdle {
            assertEquals(0, session.index)
            assertEquals(cells.size - 1, recognizer.calls)
        }

        drawTallStroke(4)
        next()
        rule.runOnIdle {
            assertEquals(cells.size, session.index)
            assertEquals(cells.size, recognizer.calls)
        }
    }

    @Test fun undoRemovesTheLastStrokeOnThePage() {
        val session = StylusSession(characters)
        setScreen(session, inOrder())

        drawTallStroke(0)
        drawTallStroke(1)
        drawTallStroke(0)
        rule.onNodeWithTag(HandTypeTestTags.STYLUS_UNDO).performClick()
        rule.runOnIdle {
            assertEquals(1, session.glyphs[0].size)
            assertEquals(1, session.glyphs[1].size)
        }
        rule.onNodeWithTag(HandTypeTestTags.STYLUS_UNDO).performClick()
        rule.runOnIdle {
            assertEquals(1, session.glyphs[0].size)
            assertEquals(0, session.glyphs[1].size)
        }
    }

    // The pen moves again in the frame in which the last speck expired; the specks must still fade.
    @Test fun specksFadeWhenThePenMovesRightAfterTheLastOneExpired() {
        val scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        assumeTrue("specks are off with animations off", scale > 0f)
        val particles = InkParticleField(Random(7), context.resources.displayMetrics.density)
        rule.mainClock.autoAdvance = false
        setScreen(StylusSession(characters), inOrder(), particles)
        val cell = rule.onNodeWithTag(HandTypeTestTags.stylusCell(0))

        cell.performTouchInput {
            down(Offset(width * 0.3f, height * 0.2f))
            moveTo(Offset(width * 0.6f, height * 0.6f))
        }
        assertTrue("the first move spawns specks", rule.runOnIdle { particles.count } > 0)
        var frames = 0
        while (rule.runOnIdle { particles.count } > 0 && frames++ < 200) rule.mainClock.advanceTimeByFrame()
        // delayMillis = 0: the default 16 ms would run a frame, and its recomposition, before the move.
        cell.performTouchInput { moveTo(Offset(width * 0.3f, height * 0.8f), delayMillis = 0) }
        assertTrue("the second move spawns specks", rule.runOnIdle { particles.count } > 0)

        rule.mainClock.advanceTimeBy(1_000)
        rule.runOnIdle { assertEquals("specks left on screen", 0, particles.count) }
        cell.performTouchInput { up() }
    }

    @Test fun clearEmptiesTheCellWrittenInLast() {
        val session = StylusSession(characters)
        setScreen(session, inOrder())

        drawTallStroke(1)
        drawTallStroke(2)
        drawTallStroke(2)
        rule.onNodeWithTag(HandTypeTestTags.STYLUS_CLEAR).performClick()
        rule.runOnIdle {
            assertEquals(1, session.glyphs[1].size)
            assertEquals(0, session.glyphs[2].size)
        }
    }

    @Test fun previousGoesBackOnePage() {
        val session = StylusSession(characters)
        setScreen(session, inOrder())

        val cells = writePage()
        next()
        rule.runOnIdle { assertEquals(cells.size, session.index) }
        rule.onNodeWithTag(HandTypeTestTags.STYLUS_BACK).performClick()
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(0, session.index) }
        rule.onNodeWithTag(HandTypeTestTags.stylusCell(0)).assertExists()
    }

    @Test fun unavailableRecognizerFallsBackToShapeChecks() {
        val session = StylusSession(characters)
        setScreen(session, FakeRecognizer { null })

        val cells = writePage()
        next()
        rule.runOnIdle { assertEquals(cells.size, session.index) }
        rule.onNodeWithTag(HandTypeTestTags.STYLUS_HINT)
            .assertTextEquals(context.getString(R.string.stylus_note_shape_only))
    }
}
