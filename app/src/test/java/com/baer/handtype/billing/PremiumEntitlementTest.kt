package com.baer.handtype.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumEntitlementTest {

    @Test
    fun emptyListIsLocked() {
        val evaluation = PremiumEntitlement.evaluate(emptyList())
        assertFalse(evaluation.unlocked)
        assertFalse(evaluation.pending)
        assertTrue(evaluation.tokensToAcknowledge.isEmpty())
    }

    @Test
    fun purchasedAndAcknowledgedUnlocks() {
        val evaluation = PremiumEntitlement.evaluate(
            listOf(purchase(purchased = true, acknowledged = true, token = "tok1")),
        )
        assertTrue(evaluation.unlocked)
        assertFalse(evaluation.pending)
        assertTrue(evaluation.tokensToAcknowledge.isEmpty())
    }

    @Test
    fun purchasedAndUnacknowledgedUnlocksAndNeedsAcknowledgement() {
        val evaluation = PremiumEntitlement.evaluate(
            listOf(purchase(purchased = true, acknowledged = false, token = "tok1")),
        )
        assertTrue(evaluation.unlocked)
        assertEquals(listOf("tok1"), evaluation.tokensToAcknowledge)
    }

    @Test
    fun pendingOnlyDoesNotUnlock() {
        val evaluation = PremiumEntitlement.evaluate(
            listOf(purchase(purchased = false, pending = true, token = "tok1")),
        )
        assertFalse(evaluation.unlocked)
        assertTrue(evaluation.pending)
        assertTrue(evaluation.tokensToAcknowledge.isEmpty())
    }

    @Test
    fun wrongProductIdIsIgnored() {
        val evaluation = PremiumEntitlement.evaluate(
            listOf(
                purchase(purchased = true, acknowledged = true, token = "tok1", products = listOf("other_product")),
            ),
        )
        assertFalse(evaluation.unlocked)
        assertFalse(evaluation.pending)
        assertTrue(evaluation.tokensToAcknowledge.isEmpty())
    }

    @Test
    fun mixedListUnlocksAndIgnoresUnrelatedPending() {
        val evaluation = PremiumEntitlement.evaluate(
            listOf(
                purchase(purchased = false, pending = true, token = "tokPending", products = listOf("other_product")),
                purchase(purchased = true, acknowledged = false, token = "tokUnlock"),
            ),
        )
        assertTrue(evaluation.unlocked)
        assertFalse(evaluation.pending)
        assertEquals(listOf("tokUnlock"), evaluation.tokensToAcknowledge)
    }

    private fun purchase(
        purchased: Boolean,
        token: String,
        acknowledged: Boolean = false,
        pending: Boolean = false,
        products: List<String> = listOf(PremiumEntitlement.PRODUCT_ID),
    ): PurchaseInfo = PurchaseInfo(
        products = products,
        purchased = purchased,
        pending = pending,
        acknowledged = acknowledged,
        token = token,
    )
}
