package com.baer.handtype.feature.capture

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baer.handtype.R
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_BASE
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

/**
 * Camera capture screen for the handwriting calibration flow.
 *
 * Launches the ML Kit Document Scanner activity which handles camera permission,
 * perspective correction, and auto-rotation internally. Returns an upright, perspective-
 * corrected [Bitmap] ready for [SheetSampleProcessor].
 */
@Composable
fun CameraCaptureScreen(
    onImageCaptured: (Bitmap) -> Unit,
    onCaptureError: (Throwable) -> Unit,
    modifier: Modifier = Modifier,
    instructionText: String = "Print the practice sheet, fill every cell with your handwriting, then tap Scan to photograph the whole page.",
    isProcessing: Boolean = false,
) {
    val context = LocalContext.current
    val sectionTitle = stringResource(R.string.camera_section_title)
    val guideTitle = stringResource(R.string.camera_pre_scan_title)
    val guideSteps = listOf(
        stringResource(R.string.camera_pre_scan_step_1),
        stringResource(R.string.camera_pre_scan_step_2),
        stringResource(R.string.camera_pre_scan_step_3),
        stringResource(R.string.camera_pre_scan_step_4),
    )
    val tipText = stringResource(R.string.camera_tip)
    val sheetButtonText = stringResource(R.string.camera_sheet_button)
    val scanButtonText = stringResource(R.string.camera_scan_button)
    val openingText = stringResource(R.string.camera_opening_scanner)
    val processingText = stringResource(R.string.camera_processing_button)
    val currentOnImageCaptured by rememberUpdatedState(onImageCaptured)
    val currentOnCaptureError by rememberUpdatedState(onCaptureError)
    var isLaunching by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val scannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { activityResult ->
        isLaunching = false
        if (activityResult.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(activityResult.data)
            val imageUri = scanResult?.pages?.firstOrNull()?.imageUri
            if (imageUri != null) {
                runCatching {
                    context.contentResolver.openInputStream(imageUri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                            ?: error("Failed to decode scanned image")
                    } ?: error("Failed to open scanned image URI")
                }.onSuccess { bitmap ->
                    errorMessage = null
                    currentOnImageCaptured(bitmap)
                }.onFailure { throwable ->
                    errorMessage = throwable.message
                    currentOnCaptureError(throwable)
                }
            }
        }
    }

    fun launchScanner() {
        if (isLaunching || isProcessing) return
        val activity = context.findActivity() ?: run {
            currentOnCaptureError(IllegalStateException("Cannot launch scanner: no Activity context"))
            return
        }
        isLaunching = true
        errorMessage = null
        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(SCANNER_MODE_BASE)
            .setPageLimit(1)
            .setGalleryImportAllowed(false)
            .setResultFormats(RESULT_FORMAT_JPEG)
            .build()
        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(activity)
            .addOnSuccessListener { intentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener { throwable ->
                isLaunching = false
                errorMessage = throwable.message
                currentOnCaptureError(throwable)
            }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = sectionTitle,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = instructionText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                        ),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = guideTitle,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            guideSteps.forEachIndexed { index, step ->
                                GuideStepRow(
                                    number = index + 1,
                                    text = step,
                                )
                                if (index != guideSteps.lastIndex) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = tipText,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(
                        onClick = { context.shareTemplateSheet() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = sheetButtonText)
                    }
                }
            }

            if (errorMessage != null) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        text = errorMessage!!,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = { launchScanner() },
                enabled = !isLaunching && !isProcessing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                shape = RoundedCornerShape(22.dp),
            ) {
                if (isLaunching || isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.size(10.dp))
                    Text(
                        text = if (isProcessing) processingText else openingText,
                        style = MaterialTheme.typography.titleMedium,
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = scanButtonText,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideStepRow(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "$number.",
            modifier = Modifier.width(20.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

private fun Context.shareTemplateSheet() {
    val uri = runCatching { PracticeSheetGenerator.renderToShareableUri(this) }.getOrNull() ?: return
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "HandType practice sheet")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(sendIntent, "Send practice sheet").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(chooser)
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
