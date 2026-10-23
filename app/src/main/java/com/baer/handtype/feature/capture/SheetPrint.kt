package com.baer.handtype.feature.capture

import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import java.io.FileOutputStream

/** Sends the practice sheet to the Android print framework as one A4 portrait page. */
object SheetPrint {

    /** Printed margin around the sheet, in PDF points (1/72 in), on top of the printer's own margins. */
    private const val MARGIN_POINTS = 18f

    data class Fit(val left: Float, val top: Float, val width: Float, val height: Float) {
        val right: Float get() = left + width
        val bottom: Float get() = top + height
    }

    /** Largest uniformly scaled, centred placement of a [srcWidth] x [srcHeight] image in the page minus [margin]. */
    fun fitRect(pageWidth: Float, pageHeight: Float, srcWidth: Int, srcHeight: Int, margin: Float): Fit {
        val availableWidth = pageWidth - 2 * margin
        val availableHeight = pageHeight - 2 * margin
        val scale = minOf(availableWidth / srcWidth, availableHeight / srcHeight)
        val width = srcWidth * scale
        val height = srcHeight * scale
        return Fit(margin + (availableWidth - width) / 2f, margin + (availableHeight - height) / 2f, width, height)
    }

    /** False when the phone has no print framework, e.g. a Print Spooler removed by the vendor or user. */
    fun print(context: Context, jobName: String): Boolean {
        val manager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return false
        val attributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .build()
        return try {
            manager.print(jobName, SheetAdapter(context, jobName), attributes)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    private class SheetAdapter(private val context: Context, private val name: String) : PrintDocumentAdapter() {
        private var attributes: PrintAttributes? = null

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal,
            callback: LayoutResultCallback,
            extras: Bundle?,
        ) {
            if (cancellationSignal.isCanceled) {
                callback.onLayoutCancelled()
                return
            }
            attributes = newAttributes
            val info = PrintDocumentInfo.Builder("$name.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(1)
                .build()
            callback.onLayoutFinished(info, oldAttributes != newAttributes)
        }

        override fun onWrite(
            pages: Array<out PageRange>,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal,
            callback: WriteResultCallback,
        ) {
            val printAttributes = attributes
            if (printAttributes == null || cancellationSignal.isCanceled) {
                callback.onWriteCancelled()
                return
            }
            val document = PrintedPdfDocument(context, printAttributes)
            var bitmap: Bitmap? = null
            try {
                bitmap = PracticeSheetGenerator.renderBitmap(PracticeSheetGenerator.cellCharacters(context))
                val page = document.startPage(0)
                val fit = fitRect(
                    page.info.pageWidth.toFloat(),
                    page.info.pageHeight.toFloat(),
                    bitmap.width,
                    bitmap.height,
                    MARGIN_POINTS,
                )
                page.canvas.drawBitmap(bitmap, null, RectF(fit.left, fit.top, fit.right, fit.bottom), null)
                document.finishPage(page)
                FileOutputStream(destination.fileDescriptor).use { document.writeTo(it) }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            } finally {
                document.close()
                bitmap?.recycle()
            }
        }
    }
}
