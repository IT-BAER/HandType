package com.baer.handtype.feature.capture.stylus

import org.junit.Assert.assertEquals
import org.junit.Test

class InkStrokeCodecTest {

    @Test
    fun roundTripKeepsPointsAndRelativeTimes() {
        val strokes = listOf(
            InkStroke(listOf(InkPoint(0.1f, 0.2f, 5_000L), InkPoint(0.3f, 0.4f, 5_016L))),
            InkStroke(listOf(InkPoint(0.5f, 0.6f, 5_400L))),
        )
        val decoded = InkStrokeCodec.decode(InkStrokeCodec.encode(strokes))
        assertEquals(
            listOf(
                InkStroke(listOf(InkPoint(0.1f, 0.2f, 0L), InkPoint(0.3f, 0.4f, 16L))),
                InkStroke(listOf(InkPoint(0.5f, 0.6f, 400L))),
            ),
            decoded,
        )
    }

    @Test
    fun emptyAndTruncatedInputDecodeSafely() {
        assertEquals(emptyList<InkStroke>(), InkStrokeCodec.decode(InkStrokeCodec.encode(emptyList())))
        assertEquals(emptyList<InkStroke>(), InkStrokeCodec.decode(floatArrayOf()))
        assertEquals(emptyList<InkStroke>(), InkStrokeCodec.decode(floatArrayOf(2f, 3f, 0.1f)))
    }
}
