package com.baer.handtype.feature.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreFeedbackTest {
    @Test
    fun listingUrlUsesReleaseId() {
        assertEquals(
            "https://play.google.com/store/apps/details?id=com.baer.handtype",
            StoreLinks.listingUrl(),
        )
    }

    @Test
    fun shareTextIsMessagePlusNewlinePlusUrl() {
        assertEquals("Hello\n" + StoreLinks.listingUrl(), StoreLinks.shareText("Hello"))
    }

    @Test
    fun reviewPolicyThresholdAndShownFlag() {
        assertFalse(ReviewPromptPolicy.shouldAsk(2, false))
        assertTrue(ReviewPromptPolicy.shouldAsk(3, false))
        assertFalse(ReviewPromptPolicy.shouldAsk(3, true))
    }
}
