package com.baer.handtype

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baer.handtype.feature.capture.stylus.InkRecognizer
import com.baer.handtype.feature.capture.stylus.InkStroke
import com.baer.handtype.feature.capture.stylus.StylusCaptureScreen
import com.baer.handtype.feature.capture.stylus.StylusSession
import com.baer.handtype.testing.HandTypeTestTags
import com.baer.handtype.ui.theme.HandTypeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StylusCaptureTest {
    @get:Rule val rule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private class FakeRecognizer(var candidates: List<String>?) : InkRecognizer {
        var calls = 0
        override suspend fun prepare(): Boolean = candidates != null
        override suspend fun recognize(strokes: List<InkStroke>, aspectRatio: Float): List<String>? {
            calls++
            return candidates
        }
        override fun close() {}
    }

    private fun progress(n: Int, total: Int) = context.getString(R.string.stylus_progress, n, total)

    private fun setScreen(session: StylusSession, recognizer: InkRecognizer) {
        rule.setContent {
            HandTypeTheme {
                StylusCaptureScreen(
                    session = session,
                    recognizer = recognizer,
                    onFinished = {},
                    onBack = {},
                )
            }
        }
    }

    // A tall vertical stroke from above the x-height guide down to the baseline: passes the shape check for 'A'.
    private fun drawTallStroke() {
        rule.onNodeWithTag(HandTypeTestTags.STYLUS_CANVAS).performTouchInput {
            down(Offset(width * 0.3f, height * 0.15f))
            moveTo(Offset(width * 0.4f, height * 0.45f))
            moveTo(Offset(width * 0.5f, height * 0.75f))
            up()
        }
    }

    @Test fun acceptedLetterAdvancesProgress() {
        val session = StylusSession(StylusSession.sheetCharacters())
        val total = session.characters.size
        setScreen(session, FakeRecognizer(listOf("A")))

        rule.onNodeWithTag(HandTypeTestTags.STYLUS_PROGRESS).assertTextEquals(progress(0, total))
        drawTallStroke()
        rule.runOnIdle { assertEquals(1, session.glyphs[0].size) }
        rule.onNodeWithTag(HandTypeTestTags.STYLUS_NEXT).assertIsEnabled().performClick()
        rule.waitForIdle()

        rule.onNodeWithTag(HandTypeTestTags.STYLUS_PROGRESS).assertTextEquals(progress(1, total))
        rule.runOnIdle { assertEquals(1, session.index) }
    }

    @Test fun wrongLetterShowsHintThenSecondNextKeepsIt() {
        val session = StylusSession(StylusSession.sheetCharacters())
        val total = session.characters.size
        val recognizer = FakeRecognizer(listOf("H"))
        setScreen(session, recognizer)

        drawTallStroke()
        rule.onNodeWithTag(HandTypeTestTags.STYLUS_NEXT).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(HandTypeTestTags.STYLUS_HINT)
            .assertTextEquals(context.getString(R.string.stylus_hint_wrong, "H", "A"))
        rule.onNodeWithTag(HandTypeTestTags.STYLUS_PROGRESS).assertTextEquals(progress(0, total))

        rule.onNodeWithTag(HandTypeTestTags.STYLUS_NEXT).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(HandTypeTestTags.STYLUS_PROGRESS).assertTextEquals(progress(1, total))
        rule.runOnIdle { assertEquals(1, recognizer.calls) }
    }

    @Test fun emptyCellIsRejectedWithoutRecognition() {
        val session = StylusSession(StylusSession.sheetCharacters())
        val recognizer = FakeRecognizer(listOf("A"))
        setScreen(session, recognizer)

        rule.onNodeWithTag(HandTypeTestTags.STYLUS_NEXT).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(HandTypeTestTags.STYLUS_HINT)
            .assertTextEquals(context.getString(R.string.stylus_hint_empty))
        rule.runOnIdle {
            assertEquals(0, session.index)
            assertEquals(0, recognizer.calls)
        }
    }

    @Test fun unavailableRecognizerFallsBackToShapeChecks() {
        val session = StylusSession(StylusSession.sheetCharacters())
        setScreen(session, FakeRecognizer(null))

        drawTallStroke()
        rule.onNodeWithTag(HandTypeTestTags.STYLUS_NEXT).performClick()
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(session.index == 1) }
        rule.onNodeWithTag(HandTypeTestTags.STYLUS_HINT)
            .assertTextEquals(context.getString(R.string.stylus_note_shape_only))
    }
}
