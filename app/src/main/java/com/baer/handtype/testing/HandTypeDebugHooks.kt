package com.baer.handtype.testing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.baer.handtype.BuildConfig
import java.io.File

/**
 * Debug-only hooks used by instrumentation tests to bypass external scanner UI with a fixed
 * local bitmap. Release builds ignore these fields entirely.
 */
object HandTypeDebugHooks {
    @Volatile
    var importedScanPath: String? = null

    @Volatile
    var forcedBackgroundId: String? = null

    fun loadImportedScanBitmap(): Bitmap? {
        if (!BuildConfig.DEBUG) return null
        val path = importedScanPath?.takeIf { it.isNotBlank() } ?: return null
        val file = File(path)
        if (!file.isFile) return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    fun clear() {
        importedScanPath = null
        forcedBackgroundId = null
    }
}
