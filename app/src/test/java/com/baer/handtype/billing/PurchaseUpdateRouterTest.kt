package com.baer.handtype.billing

import org.junit.Assert.assertEquals
import org.junit.Test

class PurchaseUpdateRouterTest {

    @Test
    fun updateGoesToWaitingPurchaseOnly() {
        val unsolicited = mutableListOf<String>()
        val router = PurchaseUpdateRouter<String> { unsolicited += it }
        val received = mutableListOf<String>()

        router.expect { received += it }
        router.deliver("flow-result")

        assertEquals(listOf("flow-result"), received)
        assertEquals(emptyList<String>(), unsolicited)
    }

    @Test
    fun updateWithoutWaitingPurchaseIsUnsolicited() {
        val unsolicited = mutableListOf<String>()
        val router = PurchaseUpdateRouter<String> { unsolicited += it }

        router.deliver("pending-payment-completed")

        assertEquals(listOf("pending-payment-completed"), unsolicited)
    }

    @Test
    fun waitingPurchaseReceivesOneUpdateThenLaterUpdatesAreUnsolicited() {
        val unsolicited = mutableListOf<String>()
        val router = PurchaseUpdateRouter<String> { unsolicited += it }
        val received = mutableListOf<String>()

        router.expect { received += it }
        router.deliver("first")
        router.deliver("second")

        assertEquals(listOf("first"), received)
        assertEquals(listOf("second"), unsolicited)
    }

    @Test
    fun cancelledWaitRoutesUpdateToUnsolicited() {
        val unsolicited = mutableListOf<String>()
        val router = PurchaseUpdateRouter<String> { unsolicited += it }
        val received = mutableListOf<String>()

        router.expect { received += it }
        router.cancel()
        router.deliver("late")

        assertEquals(emptyList<String>(), received)
        assertEquals(listOf("late"), unsolicited)
    }
}
