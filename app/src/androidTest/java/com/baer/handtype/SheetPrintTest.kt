package com.baer.handtype

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baer.handtype.feature.capture.SheetPrint
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SheetPrintTest {
    /** Runs only on a phone without the Print Spooler (the RTE device); elsewhere it would open the print dialog. */
    @Test fun printWithoutAPrintSpoolerReportsFailureInsteadOfCrashing() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeFalse("needs a phone without the Print Spooler", printSpoolerInstalled(context))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> assertFalse(SheetPrint.print(activity, "HandType test sheet")) }
        }
    }

    private fun printSpoolerInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo("com.android.printspooler", 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
