package com.baer.handtype.ml

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Represents one recognized handwritten glyph and the bitmap crop produced for it.
 */
data class ExtractedGlyph(
    val character: Char,
    val bitmap: Bitmap,
    val boundingBox: Rect,
)

/**
 * Output of the handwriting extraction pass.
 *
 * [glyphMap] keeps the first crop found for each recognized character, which matches the
 * initial alphabet-calibration flow where one bitmap per character is eventually persisted.
 */
data class ExtractionResult(
    val glyphs: List<ExtractedGlyph>,
    val glyphMap: Map<Char, Bitmap>,
    val recognizedText: String,
)

/**
 * Runs ML Kit text recognition against a captured handwriting sample and crops each symbol.
 *
 * ML Kit exposes symbol-level bounding boxes through `Text.Element.symbols`. When symbol boxes
 * are unavailable, this class falls back to slicing the element box into equal-width regions so
 * the calibration pipeline can still proceed with a best-effort crop per character.
 */
class HandwritingExtractor(
    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
) : AutoCloseable {

    /**
     * Extracts individual alphanumeric glyph crops from [capturedBitmap].
     *
     * @param capturedBitmap Upright handwriting sample bitmap.
     * @param rotationDegrees Rotation passed through to ML Kit. Use `0` when the bitmap is already upright.
     * @param paddingPx Extra pixels added around each crop to avoid clipping ascenders and descenders.
     */
    suspend fun extract(
        capturedBitmap: Bitmap,
        rotationDegrees: Int = 0,
        paddingPx: Int = 12,
    ): ExtractionResult {
        val inputImage = InputImage.fromBitmap(capturedBitmap, rotationDegrees)
        val visionText = recognizer.process(inputImage).await()
        val glyphs = buildList {
            collectSymbolCandidates(visionText).forEach { candidate ->
                val cropRect = candidate.boundingBox.expandedWithin(
                    bitmapWidth = capturedBitmap.width,
                    bitmapHeight = capturedBitmap.height,
                    paddingPx = paddingPx,
                )

                if (cropRect.width() <= 0 || cropRect.height() <= 0) {
                    return@forEach
                }

                val rawCrop = Bitmap.createBitmap(
                    capturedBitmap,
                    cropRect.left,
                    cropRect.top,
                    cropRect.width(),
                    cropRect.height(),
                )
                val crop = GlyphPostProcessor.cleanGlyph(rawCrop)
                add(
                    ExtractedGlyph(
                        character = candidate.character,
                        bitmap = crop,
                        boundingBox = cropRect,
                    ),
                )
            }
        }

        val glyphMap = LinkedHashMap<Char, Bitmap>()
        glyphs.forEach { glyph ->
            glyphMap.putIfAbsent(glyph.character, glyph.bitmap)
        }

        return ExtractionResult(
            glyphs = glyphs,
            glyphMap = glyphMap,
            recognizedText = visionText.text,
        )
    }

    override fun close() {
        recognizer.close()
    }
}

private data class SymbolCandidate(
    val character: Char,
    val boundingBox: Rect,
)

private fun collectSymbolCandidates(visionText: Text): List<SymbolCandidate> {
    return buildList {
        visionText.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                line.elements.forEach { element ->
                    if (element.symbols.isNotEmpty()) {
                        element.symbols.forEach symbolLoop@ { symbol ->
                            val character = symbol.text.singleOrNull() ?: return@symbolLoop
                            val boundingBox = symbol.boundingBox ?: return@symbolLoop
                            if (character.isLetterOrDigit()) {
                                add(SymbolCandidate(character, boundingBox))
                            }
                        }
                    } else {
                        addAll(element.toFallbackCandidates())
                    }
                }
            }
        }
    }
}

private fun Text.Element.toFallbackCandidates(): List<SymbolCandidate> {
    val normalizedText = text.filter(Char::isLetterOrDigit)
    val boundingBox = boundingBox ?: return emptyList()
    if (normalizedText.isEmpty()) {
        return emptyList()
    }
    if (normalizedText.length == 1) {
        return listOf(SymbolCandidate(normalizedText.first(), boundingBox))
    }

    val sliceWidth = boundingBox.width().toFloat() / normalizedText.length.toFloat()
    return normalizedText.mapIndexed { index, character ->
        val left = (boundingBox.left + (sliceWidth * index)).toInt()
        val right = if (index == normalizedText.lastIndex) {
            boundingBox.right
        } else {
            (boundingBox.left + (sliceWidth * (index + 1))).toInt()
        }

        SymbolCandidate(
            character = character,
            boundingBox = Rect(left, boundingBox.top, right.coerceAtLeast(left + 1), boundingBox.bottom),
        )
    }
}

private fun Rect.expandedWithin(
    bitmapWidth: Int,
    bitmapHeight: Int,
    paddingPx: Int,
): Rect {
    val safePadding = paddingPx.coerceAtLeast(0)
    val left = (this.left - safePadding).coerceIn(0, bitmapWidth.coerceAtLeast(1) - 1)
    val top = (this.top - safePadding).coerceIn(0, bitmapHeight.coerceAtLeast(1) - 1)
    val right = (this.right + safePadding).coerceIn(left + 1, bitmapWidth.coerceAtLeast(left + 1))
    val bottom = (this.bottom + safePadding).coerceIn(top + 1, bitmapHeight.coerceAtLeast(top + 1))
    return Rect(left, top, right, bottom)
}

private suspend fun <T> Task<T>.await(): T {
    return suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result ->
            if (continuation.isActive) {
                continuation.resume(result)
            }
        }
        addOnFailureListener { throwable ->
            if (continuation.isActive) {
                continuation.resumeWithException(throwable)
            }
        }
        addOnCanceledListener {
            continuation.cancel(CancellationException("ML Kit text recognition task was cancelled."))
        }
    }
}