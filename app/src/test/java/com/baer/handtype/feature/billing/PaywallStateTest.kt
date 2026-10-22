package com.baer.handtype.feature.billing

import com.baer.handtype.R
import com.baer.handtype.billing.PremiumState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaywallStateTest {
    @Test
    fun buyIsEnabledOnlyWithPriceAndReachablePlay() {
        assertTrue(PaywallState.isBuyEnabled(PremiumState.Locked, "€ 2,99", isPurchasing = false))
        assertFalse(PaywallState.isBuyEnabled(PremiumState.Locked, null, isPurchasing = false))
        assertFalse(PaywallState.isBuyEnabled(PremiumState.Unavailable, "€ 2,99", isPurchasing = false))
        assertFalse(PaywallState.isBuyEnabled(PremiumState.Unavailable, null, isPurchasing = false))
        assertFalse(PaywallState.isBuyEnabled(PremiumState.Locked, "€ 2,99", isPurchasing = true))
    }

    @Test
    fun restoreMessageMatchesState() {
        assertEquals(R.string.premium_restore_success, PaywallState.restoreMessageRes(PremiumState.Unlocked))
        assertEquals(R.string.premium_paywall_pending, PaywallState.restoreMessageRes(PremiumState.Pending))
        assertEquals(R.string.premium_paywall_unavailable, PaywallState.restoreMessageRes(PremiumState.Unavailable))
        assertEquals(R.string.premium_restore_none, PaywallState.restoreMessageRes(PremiumState.Locked))
    }
}
