package com.baer.handtype.feature.capture.stylus

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Handwriting recognition behind the letter precheck; swapped for a fake in tests. */
interface InkRecognizer {
    /** Makes the model available (may download it once). Returns false when recognition cannot run. */
    suspend fun prepare(): Boolean

    /** Candidate texts best-first, or null when recognition is unavailable. Never throws. */
    suspend fun recognize(strokes: List<InkStroke>, aspectRatio: Float): List<String>?

    fun close()
}

/** On-device ML Kit Digital Ink recognition with the en-US model; only the model download uses the network. */
class MlKitInkRecognizer : InkRecognizer {

    private val model: DigitalInkRecognitionModel? = runCatching {
        DigitalInkRecognitionModelIdentifier.fromLanguageTag(LANGUAGE_TAG)
            ?.let { DigitalInkRecognitionModel.builder(it).build() }
    }.getOrNull()

    private var recognizer: DigitalInkRecognizer? = null
    private var modelReady = false

    override suspend fun prepare(): Boolean {
        val model = model ?: return false
        if (modelReady) return true
        modelReady = runCatching {
            val manager = RemoteModelManager.getInstance()
            if (manager.isModelDownloaded(model).await() != true) {
                manager.download(model, DownloadConditions.Builder().build()).await()
            }
            true
        }.onFailure { Log.w(TAG, "Ink model unavailable", it) }.getOrDefault(false)
        return modelReady
    }

    override suspend fun recognize(strokes: List<InkStroke>, aspectRatio: Float): List<String>? {
        val model = model ?: return null
        if (!modelReady) return null
        val client = recognizer
            ?: DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
                .also { recognizer = it }
        val width = WRITING_HEIGHT * aspectRatio
        val ink = Ink.builder().apply {
            for (stroke in strokes) {
                if (stroke.points.isEmpty()) continue
                val builder = Ink.Stroke.builder()
                stroke.points.forEach {
                    builder.addPoint(Ink.Point.create(it.x * width, it.y * WRITING_HEIGHT, it.timeMillis))
                }
                addStroke(builder.build())
            }
        }.build()
        val context = RecognitionContext.builder()
            .setPreContext("")
            .setWritingArea(WritingArea(width, WRITING_HEIGHT))
            .build()
        return runCatching { client.recognize(ink, context).await().candidates.map { it.text } }
            .onFailure { Log.w(TAG, "Ink recognition failed", it) }
            .getOrNull()
    }

    override fun close() {
        recognizer?.close()
        recognizer = null
    }

    private companion object {
        const val TAG = "InkRecognizer"
        const val LANGUAGE_TAG = "en-US"
        const val WRITING_HEIGHT = 1000f
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
