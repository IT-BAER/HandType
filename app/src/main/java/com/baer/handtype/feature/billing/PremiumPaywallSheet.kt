package com.baer.handtype.feature.billing

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baer.handtype.R
import com.baer.handtype.billing.PremiumBilling
import com.baer.handtype.billing.PremiumState
import com.baer.handtype.billing.PurchaseResult
import com.baer.handtype.testing.HandTypeTestTags
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PremiumPaywallSheet(
    billing: PremiumBilling,
    onDismiss: () -> Unit,
    onUnlocked: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    var price by remember { mutableStateOf<String?>(null) }
    var isPurchasing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(billing) {
        price = billing.formattedPrice()
    }

    fun startPurchase() {
        isPurchasing = true
        errorMessage = null
        scope.launch {
            when (val result = billing.purchase()) {
                PurchaseResult.Success -> {
                    isPurchasing = false
                    onUnlocked()
                }
                PurchaseResult.Cancelled -> {
                    isPurchasing = false
                }
                PurchaseResult.Pending -> {
                    isPurchasing = false
                }
                is PurchaseResult.Error -> {
                    isPurchasing = false
                    errorMessage = result.message
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag(HandTypeTestTags.PREMIUM_PAYWALL_SHEET),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.premium_paywall_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.premium_paywall_benefit_capture),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.premium_paywall_benefit_papers),
                style = MaterialTheme.typography.bodyMedium,
            )

            val currentState by billing.state.collectAsState()
            when (currentState) {
                PremiumState.Pending -> Text(
                    text = stringResource(R.string.premium_paywall_pending),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                PremiumState.Unavailable -> Text(
                    text = stringResource(R.string.premium_paywall_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Unit
            }

            errorMessage?.let {
                Text(
                    text = stringResource(R.string.premium_paywall_error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = ::startPurchase,
                enabled = !isPurchasing,
                modifier = Modifier
                    .testTag(HandTypeTestTags.PREMIUM_BUY_BUTTON)
                    .fillMaxWidth(),
            ) {
                if (isPurchasing) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp).size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Text(text = stringResource(R.string.premium_paywall_buy, price ?: "…"))
            }

            TextButton(
                onClick = {
                    scope.launch {
                        billing.refresh()
                        val restoredState = billing.state.value
                        val messageRes = when (restoredState) {
                            PremiumState.Unlocked -> R.string.premium_restore_success
                            PremiumState.Unavailable -> R.string.premium_paywall_unavailable
                            else -> R.string.premium_restore_none
                        }
                        Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_SHORT).show()
                        if (restoredState == PremiumState.Unlocked) onUnlocked()
                    }
                },
                enabled = !isPurchasing,
                modifier = Modifier
                    .testTag(HandTypeTestTags.PREMIUM_RESTORE_BUTTON)
                    .fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.premium_paywall_restore))
            }
        }
    }
}
