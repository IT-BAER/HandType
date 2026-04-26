package com.baer.handtype.template

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists generated handwriting bitmaps to public storage and exposes them via
 * Android's share sheet. Three exit paths:
 *  - [saveImageToGallery]: writes a PNG to Pictures/HandType (visible in Photos app)
 *  - [shareImage]: copies a PNG into cache + returns a content:// Uri to fire ACTION_SEND
 *  - [exportPdf]: rasterises the bitmap into a single A4 PDF page, saved to Download/HandType
 *
 * On API 23-28 saving requires WRITE_EXTERNAL_STORAGE; on API 29+ MediaStore handles it.
 */
object OutputExporter {

    private const val ALBUM = "HandType"
    private const val PNG_MIME = "image/png"
    private const val PDF_MIME = "application/pdf"

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun fileName(template: String, ext: String): String {
        val safe = template.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_')
        val tag = if (safe.isEmpty()) "note" else safe
        return "HandType_${tag}_${timestamp()}.$ext"
    }

    private fun safeTag(template: String): String {
        val safe = template.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_')
        return if (safe.isEmpty()) "note" else safe
    }

    /**
     * Writes a copy of [bitmap] into the app's private history directory and an
     * optional [sourceText] sidecar. Files use `<timestamp>__<safeTemplate>.png`
     * so they can be ordered chronologically and remain easy to parse.
     */
    fun archiveBitmap(
        context: Context,
        bitmap: Bitmap,
        templateName: String,
        sourceText: String? = null,
    ): Result<File> = runCatching {
        val dir = File(context.filesDir, "history").apply { mkdirs() }
        val stamp = timestamp()
        val tag = safeTag(templateName)
        val png = File(dir, "${stamp}__${tag}.png")
        FileOutputStream(png).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                error("Bitmap.compress failed")
            }
        }
        if (!sourceText.isNullOrBlank()) {
            val meta = File(dir, "${stamp}__${tag}.txt")
            meta.writeText(sourceText)
        }
        png
    }

    /** Writes the bitmap as PNG to the public Pictures/HandType folder. */
    fun saveImageToGallery(context: Context, bitmap: Bitmap, templateName: String): Result<Uri> =
        runCatching {
            val name = fileName(templateName, "png")
            writePngToGallery(context, bitmap, name)
        }

    /**
     * Variant of [saveImageToGallery] that keys out the paper colour, producing
     * a transparent PNG with only the ink pixels. Useful for overlaying on real
     * paper or other backgrounds.
     */
    fun saveTransparentImageToGallery(
        context: Context,
        bitmap: Bitmap,
        templateName: String,
        paperColor: Int = 0xFFF8F1E4.toInt(),
    ): Result<Uri> = runCatching {
        val name = fileName(templateName + "_transparent", "png")
        val transparent = keyOutPaper(bitmap, paperColor)
        try {
            writePngToGallery(context, transparent, name)
        } finally {
            if (transparent !== bitmap) transparent.recycle()
        }
    }

    private fun writePngToGallery(context: Context, bitmap: Bitmap, name: String): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, PNG_MIME)
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/$ALBUM",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert returned null")
            resolver.openOutputStream(uri).use { out ->
                requireNotNull(out) { "Cannot open output stream for $uri" }
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    error("Bitmap.compress failed")
                }
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            val picturesDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES,
            )
            val albumDir = File(picturesDir, ALBUM).apply { mkdirs() }
            val file = File(albumDir, name)
            FileOutputStream(file).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    error("Bitmap.compress failed")
                }
            }
            Uri.fromFile(file)
        }
    }

    /**
     * Returns a list of vertical cut indices [0, c1, c2, ..., bitmap.height]
     * such that each segment fits within [maxSliceHeight] pixels and the cuts
     * snap to the most paper-empty (whitespace) row inside a search window.
     * This avoids slicing through a line of handwriting.
     */
    private fun computePageCuts(bitmap: Bitmap, maxSliceHeight: Int): List<Int> {
        val h = bitmap.height
        if (h <= maxSliceHeight) return listOf(0, h)
        val w = bitmap.width
        // Build per-row "ink density" by sampling every Nth column and N rows together
        // for speed. Lower value = more ink.
        val sampleStride = (w / 64).coerceAtLeast(1)
        val rowDarkness = IntArray(h)
        val rowBuf = IntArray((w + sampleStride - 1) / sampleStride)
        val sampleCount = rowBuf.size
        for (y in 0 until h) {
            var idx = 0
            var x = 0
            while (x < w && idx < sampleCount) {
                rowBuf[idx++] = bitmap.getPixel(x, y)
                x += sampleStride
            }
            var sum = 0
            for (j in 0 until idx) {
                val c = rowBuf[j]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                // perceived brightness; lower = darker (ink)
                sum += (r * 30 + g * 59 + b * 11) / 100
            }
            rowDarkness[y] = sum / idx
        }

        val cuts = mutableListOf(0)
        var pos = 0
        // Search window: 12% of page height around the ideal cut.
        val windowRadius = (maxSliceHeight * 0.12f).toInt().coerceAtLeast(8)
        while (h - pos > maxSliceHeight) {
            val ideal = pos + maxSliceHeight
            val from = (ideal - windowRadius).coerceAtLeast(pos + maxSliceHeight / 2)
            val to = ideal.coerceAtMost(h - 1)
            // Pick the brightest (most paper) row in [from, to].
            var bestRow = ideal
            var bestVal = -1
            for (y in from..to) {
                if (rowDarkness[y] > bestVal) {
                    bestVal = rowDarkness[y]
                    bestRow = y
                }
            }
            cuts.add(bestRow)
            pos = bestRow
        }
        cuts.add(h)
        return cuts
    }

    /**
     * Returns a new ARGB_8888 bitmap where pixels close to [paperColor] become
     * transparent. Uses a simple per-channel distance with a soft edge so anti-aliased
     * ink pixels keep partial alpha and edges stay smooth.
     */
    private fun keyOutPaper(src: Bitmap, paperColor: Int): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val pr = (paperColor shr 16) and 0xFF
        val pg = (paperColor shr 8) and 0xFF
        val pb = paperColor and 0xFF
        // distance threshold in 0..441 range (sqrt(3*255^2)); tuned so cream paper drops fully
        // but ink anti-aliased edges keep partial alpha.
        val hard = 30
        val soft = 110
        val span = (soft - hard).toFloat()
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val dr = r - pr
            val dg = g - pg
            val db = b - pb
            val dist = kotlin.math.sqrt((dr * dr + dg * dg + db * db).toDouble()).toFloat()
            val alpha = when {
                dist <= hard -> 0
                dist >= soft -> 255
                else -> (((dist - hard) / span) * 255f).toInt().coerceIn(0, 255)
            }
            pixels[i] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /** Writes a temp PNG into cache/shared and returns a FileProvider Uri ready for ACTION_SEND. */
    fun stageBitmapForShare(context: Context, bitmap: Bitmap, templateName: String): Uri {
        val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(sharedDir, fileName(templateName, "png"))
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }

    /** Builds a system share-sheet intent for an image content uri. */
    fun buildShareIntent(uri: Uri, mime: String = PNG_MIME): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share handwritten note")
    }

    /**
     * Rasterises [bitmap] across one or more A4 portrait PDF pages (595x842pt @72dpi),
     * scaling to a 32pt margin and slicing tall bitmaps across pages.
     * Saves to Download/HandType on API 29+, app-private cache on older devices.
     */
    fun exportPdf(context: Context, bitmap: Bitmap, templateName: String): Result<Uri> =
        runCatching {
            val name = fileName(templateName, "pdf")
            val pdf = PdfDocument()
            val pageWidth = 595
            val pageHeight = 842
            val margin = 32
            val available = pageWidth - margin * 2
            val scale = available.toFloat() / bitmap.width
            val pageContentHeight = pageHeight - margin * 2
            // How many bitmap pixels fit on one page after scaling.
            val sliceHeightPx = (pageContentHeight / scale).toInt().coerceAtLeast(1)
            val cutPoints = computePageCuts(bitmap, sliceHeightPx)
            val pageCount = cutPoints.size - 1

            for (i in 0 until pageCount) {
                val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, i + 1).create()
                val page = pdf.startPage(info)
                val canvas = page.canvas
                val srcTop = cutPoints[i]
                val srcBottom = cutPoints[i + 1]
                val srcRect = android.graphics.Rect(0, srcTop, bitmap.width, srcBottom)
                val sliceTargetH = (srcBottom - srcTop) * scale
                val left = margin.toFloat()
                val top = margin.toFloat()
                val dstRect = android.graphics.RectF(left, top, left + available, top + sliceTargetH)
                canvas.drawBitmap(bitmap, srcRect, dstRect, null)
                pdf.finishPage(page)
            }

            val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, PDF_MIME)
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/$ALBUM",
                    )
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore insert returned null")
                resolver.openOutputStream(target).use { out ->
                    requireNotNull(out) { "Cannot open output stream for $target" }
                    pdf.writeTo(out)
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(target, values, null, null)
                target
            } else {
                val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                val file = File(dir, name)
                FileOutputStream(file).use { out -> pdf.writeTo(out) }
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
            }
            pdf.close()
            uri
        }
}
