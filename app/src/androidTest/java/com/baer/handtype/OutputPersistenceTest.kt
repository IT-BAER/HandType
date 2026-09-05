package com.baer.handtype

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baer.handtype.template.OutputExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OutputPersistenceTest {
    @Test fun privateHandwritingIsNotOptedIntoCloudBackup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse("Private notes must follow the on-device-only promise",
            context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)
    }

    @Test fun rapidNotesNeverOverwriteEarlierHistory() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(base.cacheDir, "history-regression-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir(): File = dir
        }
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        try {
            val files = (1..5).map { index ->
                OutputExporter.archiveBitmap(isolated, bitmap, "Same template", "Note $index").getOrThrow()
            }
            assertEquals("Each render must retain its own history entry", 5, files.toSet().size)
            files.forEachIndexed { index, file ->
                assertEquals("Note ${index + 1}", File(file.parentFile, "${file.nameWithoutExtension}.txt").readText())
            }
        } finally {
            bitmap.recycle()
            dir.deleteRecursively()
        }
    }
}
