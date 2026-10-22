package com.baer.handtype

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baer.handtype.billing.PremiumBilling
import com.baer.handtype.billing.PremiumState
import com.baer.handtype.billing.PurchaseResult
import com.baer.handtype.feature.billing.PremiumPaywallSheet
import com.baer.handtype.testing.HandTypeTestTags
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PremiumPaywallStatesTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun buyIsDisabledWhilePlayIsUnavailable() {
        composeRule.setContent {
            PremiumPaywallSheet(StubBilling(PremiumState.Unavailable, price = null), onDismiss = {}, onUnlocked = {})
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HandTypeTestTags.PREMIUM_BUY_BUTTON).assertIsNotEnabled()
    }

    @Test
    fun buyIsEnabledOnceThePriceLoads() {
        composeRule.setContent {
            PremiumPaywallSheet(StubBilling(PremiumState.Locked, price = "€ 2,99"), onDismiss = {}, onUnlocked = {})
        }
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodes(hasTestTag(HandTypeTestTags.PREMIUM_BUY_BUTTON) and isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(HandTypeTestTags.PREMIUM_BUY_BUTTON).assertIsEnabled()
    }

    private class StubBilling(initial: PremiumState, private val price: String?) : PremiumBilling {
        override val state = MutableStateFlow(initial)
        override suspend fun refresh() = Unit
        override suspend fun formattedPrice(): String? = price
        override suspend fun purchase(): PurchaseResult = PurchaseResult.Cancelled
        override fun close() = Unit
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
