package com.baer.handtype

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baer.handtype.template.HandwritingBitmapRenderer
import com.baer.handtype.template.HandwritingRenderConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RenderBoundaryTest {
    @Test fun longCursiveWordWrapsWithinThePage() {
        val result = HandwritingBitmapRenderer.renderCursive(
            text = "Donaudampfschifffahrtsgesellschaftskapitaen",
            strokeData = emptyMap(),
            config = HandwritingRenderConfig(maxWidthPx = 600),
            seed = 42,
        )
        try {
            assertTrue("Long words must wrap instead of widening the entire note: ${result.bitmap.width}",
                result.bitmap.width <= 600)
            assertTrue("The wrapped note must contain several lines", result.bitmap.height > 300)
        } finally {
            result.bitmap.recycle()
        }
    }
}
