package com.baer.handtype.feature.intake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedTextIntakeTest {

    @Test fun plainTextShareIsTrimmed() {
        assertEquals(
            "Dear Anna,\nsee you on Sunday!",
            SharedTextIntake.textFrom(SEND, "text/plain", "  Dear Anna,\nsee you on Sunday!\n "),
        )
    }

    @Test fun blankShareIsIgnored() {
        assertNull(SharedTextIntake.textFrom(SEND, "text/plain", " \n "))
        assertNull(SharedTextIntake.textFrom(SEND, "text/plain", null))
    }

    @Test fun launcherStartAndOtherTypesAreIgnored() {
        assertNull(SharedTextIntake.textFrom("android.intent.action.MAIN", null, null))
        assertNull(SharedTextIntake.textFrom(SEND, "image/png", "caption"))
    }

    private companion object {
        const val SEND = "android.intent.action.SEND"
    }
}
