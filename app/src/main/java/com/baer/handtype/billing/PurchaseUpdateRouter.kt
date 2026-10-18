package com.baer.handtype.billing

/**
 * Hands a Play purchase update to the purchase() call that waits for it; any other update
 * (pending payment completed, promo code, purchase on another device) goes to [onUnsolicited].
 */
internal class PurchaseUpdateRouter<T>(private val onUnsolicited: (T) -> Unit) {
    private var waiting: ((T) -> Unit)? = null

    fun expect(callback: (T) -> Unit) {
        waiting = callback
    }

    fun cancel() {
        waiting = null
    }

    fun deliver(update: T) {
        val callback = waiting
        waiting = null
        if (callback != null) callback(update) else onUnsolicited(update)
    }
}
