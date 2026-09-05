package com.baer.handtype.billing

/** Play-agnostic view of a single purchase, so entitlement logic is testable without Android/Play classes. */
data class PurchaseInfo(
    val products: List<String>,
    val purchased: Boolean,
    val pending: Boolean,
    val acknowledged: Boolean,
    val token: String,
)

data class Evaluation(
    val unlocked: Boolean,
    val pending: Boolean,
    val tokensToAcknowledge: List<String>,
)

object PremiumEntitlement {
    const val PRODUCT_ID = "handtype_premium"

    fun evaluate(purchases: List<PurchaseInfo>): Evaluation {
        val premiumPurchases = purchases.filter { PRODUCT_ID in it.products }
        val unlocked = premiumPurchases.any { it.purchased }
        val tokensToAcknowledge = premiumPurchases
            .filter { it.purchased && !it.acknowledged }
            .map { it.token }
        val pending = !unlocked && premiumPurchases.any { it.pending }
        return Evaluation(unlocked = unlocked, pending = pending, tokensToAcknowledge = tokensToAcknowledge)
    }
}
