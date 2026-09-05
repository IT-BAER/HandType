package com.baer.handtype.billing

import android.app.Activity
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.baer.handtype.BuildConfig
import com.baer.handtype.feature.settings.AppSettingsStore
import com.baer.handtype.testing.HandTypeDebugHooks
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

sealed interface PremiumState {
    data object Locked : PremiumState
    data object Unlocked : PremiumState
    data object Pending : PremiumState
    data object Unavailable : PremiumState
}

sealed interface PurchaseResult {
    data object Success : PurchaseResult
    data object Pending : PurchaseResult
    data object Cancelled : PurchaseResult
    data class Error(val message: String) : PurchaseResult
}

interface PremiumBilling {
    val state: StateFlow<PremiumState>
    suspend fun refresh()
    suspend fun formattedPrice(): String?
    suspend fun purchase(): PurchaseResult
    fun close()

    companion object {
        fun create(activity: Activity, store: AppSettingsStore): PremiumBilling {
            return if (BuildConfig.FAKE_BILLING) {
                FakePremiumBilling(store)
            } else {
                PlayPremiumBilling(activity, store)
            }
        }
    }
}

private class PlayPremiumBilling(
    private val activity: Activity,
    private val store: AppSettingsStore,
) : PremiumBilling {

    private val _state = MutableStateFlow<PremiumState>(
        if (store.read().premiumUnlocked) PremiumState.Unlocked else PremiumState.Locked,
    )
    override val state: StateFlow<PremiumState> = _state.asStateFlow()

    private var pendingPurchaseUpdate: ((BillingResult) -> Unit)? = null

    private val listener = PurchasesUpdatedListener { result, _ ->
        val callback = pendingPurchaseUpdate
        pendingPurchaseUpdate = null
        callback?.invoke(result)
    }

    private val client: BillingClient = BillingClient.newBuilder(activity)
        .setListener(listener)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
        )
        .build()

    private suspend fun connect(): BillingResult {
        if (client.isReady) return BillingResult.newBuilder().setResponseCode(BillingResponseCode.OK).build()
        return suspendCancellableCoroutine { continuation ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    if (continuation.isActive) continuation.resume(billingResult)
                }

                override fun onBillingServiceDisconnected() = Unit
            })
        }
    }

    override suspend fun refresh() {
        val connectResult = connect()
        if (connectResult.responseCode != BillingResponseCode.OK) {
            markUnavailable()
            return
        }

        val purchases = suspendCancellableCoroutine<Pair<BillingResult, List<Purchase>>> { continuation ->
            client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build(),
            ) { billingResult, purchaseList ->
                if (continuation.isActive) continuation.resume(billingResult to purchaseList)
            }
        }
        val (queryResult, purchaseList) = purchases
        if (queryResult.responseCode != BillingResponseCode.OK) {
            markUnavailable()
            return
        }

        val evaluation = PremiumEntitlement.evaluate(purchaseList.map { it.toPurchaseInfo() })
        for (token in evaluation.tokensToAcknowledge) {
            acknowledge(token)
        }
        store.write(store.read().copy(premiumUnlocked = evaluation.unlocked))
        _state.value = when {
            evaluation.unlocked -> PremiumState.Unlocked
            evaluation.pending -> PremiumState.Pending
            else -> PremiumState.Locked
        }
    }

    // A cached unlock survives Play being unreachable; only a successful query may revoke it.
    private fun markUnavailable() {
        if (_state.value != PremiumState.Unlocked) _state.value = PremiumState.Unavailable
    }

    private suspend fun acknowledge(token: String) {
        suspendCancellableCoroutine<Unit> { continuation ->
            client.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build(),
            ) {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }

    private suspend fun queryProductDetails(): ProductDetails? {
        val connectResult = connect()
        if (connectResult.responseCode != BillingResponseCode.OK) return null

        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PremiumEntitlement.PRODUCT_ID)
            .setProductType(ProductType.INAPP)
            .build()
        val result = suspendCancellableCoroutine<List<ProductDetails>> { continuation ->
            client.queryProductDetailsAsync(
                QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build(),
            ) { _, queryResult ->
                if (continuation.isActive) continuation.resume(queryResult.productDetailsList)
            }
        }
        return result.firstOrNull()
    }

    override suspend fun formattedPrice(): String? {
        return queryProductDetails()?.oneTimePurchaseOfferDetails?.formattedPrice
    }

    override suspend fun purchase(): PurchaseResult {
        val productDetails = queryProductDetails() ?: return PurchaseResult.Error("Product unavailable")
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        .build(),
                ),
            )
            .build()
        val billingResult = suspendCancellableCoroutine<BillingResult> { continuation ->
            pendingPurchaseUpdate = { result ->
                if (continuation.isActive) continuation.resume(result)
            }
            val launchResult = client.launchBillingFlow(activity, params)
            if (launchResult.responseCode != BillingResponseCode.OK) {
                pendingPurchaseUpdate = null
                if (continuation.isActive) continuation.resume(launchResult)
            }
        }
        return when (billingResult.responseCode) {
            BillingResponseCode.OK -> {
                refresh()
                when (_state.value) {
                    PremiumState.Unlocked -> PurchaseResult.Success
                    PremiumState.Pending -> PurchaseResult.Pending
                    else -> PurchaseResult.Error(billingResult.debugMessage)
                }
            }
            BillingResponseCode.USER_CANCELED -> PurchaseResult.Cancelled
            BillingResponseCode.ITEM_ALREADY_OWNED -> {
                refresh()
                if (_state.value == PremiumState.Unlocked) {
                    PurchaseResult.Success
                } else {
                    PurchaseResult.Error(billingResult.debugMessage)
                }
            }
            else -> PurchaseResult.Error(billingResult.debugMessage)
        }
    }

    override fun close() {
        client.endConnection()
    }

    private fun Purchase.toPurchaseInfo(): PurchaseInfo = PurchaseInfo(
        products = products,
        purchased = purchaseState == Purchase.PurchaseState.PURCHASED,
        pending = purchaseState == Purchase.PurchaseState.PENDING,
        acknowledged = isAcknowledged,
        token = purchaseToken,
    )
}

private class FakePremiumBilling(private val store: AppSettingsStore) : PremiumBilling {
    private val _state = MutableStateFlow(initialState())
    override val state: StateFlow<PremiumState> = _state.asStateFlow()

    private fun initialState(): PremiumState {
        val unlocked = HandTypeDebugHooks.premiumUnlocked ?: store.read().premiumUnlocked
        return if (unlocked) PremiumState.Unlocked else PremiumState.Locked
    }

    override suspend fun refresh() {
        _state.value = initialState()
    }

    override suspend fun formattedPrice(): String? = "€ 2,99"

    override suspend fun purchase(): PurchaseResult {
        delay(300)
        store.write(store.read().copy(premiumUnlocked = true))
        _state.value = PremiumState.Unlocked
        return PurchaseResult.Success
    }

    override fun close() = Unit
}
