package com.baer.handtype.feature.capture

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.Settings
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * Camera capture screen for the handwriting calibration flow.
 *
 * The composable requests runtime camera permission, shows a framed CameraX preview,
 * and returns an upright [Bitmap] once the user snaps the handwritten alphabet sheet.
 */
@Composable
fun CameraCaptureScreen(
    onImageCaptured: (Bitmap) -> Unit,
    onCaptureError: (Throwable) -> Unit,
    modifier: Modifier = Modifier,
    instructionText: String = "Write A-Z, a-z, and 0-9 on blank paper, then center the full page inside the frame.",
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnImageCaptured by rememberUpdatedState(onImageCaptured)
    val currentOnCaptureError by rememberUpdatedState(onCaptureError)
    val cameraExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val imageCaptureState = remember { mutableStateOf<ImageCapture?>(null) }
    var hasPermission by remember { mutableStateOf(context.hasCameraPermission()) }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    var isCapturing by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
    }

    DisposableEffect(hasPermission, lifecycleOwner, previewView) {
        if (!hasPermission) {
            imageCaptureState.value = null
            onDispose { }
        } else {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            val listener = Runnable {
                runCatching {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setJpegQuality(100)
                        .build()

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture,
                    )
                    imageCaptureState.value = imageCapture
                }.onFailure(currentOnCaptureError)
            }

            cameraProviderFuture.addListener(listener, cameraExecutor)

            onDispose {
                imageCaptureState.value = null
                if (cameraProviderFuture.isDone) {
                    runCatching { cameraProviderFuture.get().unbindAll() }
                }
            }
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
    ) { innerPadding ->
        if (hasPermission) {
            CameraPreviewContent(
                innerPadding = innerPadding,
                previewView = previewView,
                instructionText = instructionText,
                isCapturing = isCapturing,
                onCaptureClick = {
                    val captureUseCase = imageCaptureState.value ?: return@CameraPreviewContent
                    isCapturing = true
                    captureUseCase.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
                    capturePhoto(
                        context = context,
                        imageCapture = captureUseCase,
                        onPhotoReady = { bitmap ->
                            isCapturing = false
                            currentOnImageCaptured(bitmap)
                        },
                        onError = { throwable ->
                            isCapturing = false
                            currentOnCaptureError(throwable)
                        },
                    )
                },
            )
        } else {
            PermissionContent(
                innerPadding = innerPadding,
                permissionRequested = permissionRequested,
                shouldShowRationale = context.shouldShowCameraPermissionRationale(),
                onRequestPermission = {
                    permissionRequested = true
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                },
                onOpenSettings = { context.openAppSettings() },
            )
        }
    }
}

@Composable
private fun CameraPreviewContent(
    innerPadding: PaddingValues,
    previewView: PreviewView,
    instructionText: String,
    isCapturing: Boolean,
    onCaptureClick: () -> Unit,
) {
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
                    text = "Capture your handwriting sheet",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = instructionText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Tip: use even lighting and keep all rows of letters visible.",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(28.dp))
                .background(Color.Black)
                .aspectRatio(3f / 4f),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { previewView },
            )
            CameraGuideOverlay(modifier = Modifier.fillMaxSize())
            M3Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(18.dp),
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
                tonalElevation = 0.dp,
            ) {
                Text(
                    text = "Align the page edges with the guide",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        Button(
            onClick = onCaptureClick,
            enabled = !isCapturing,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
        ) {
            if (isCapturing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.size(10.dp))
                Text(text = "Capturing...")
            } else {
                Text(text = "Capture sample")
            }
        }
    }
}

@Composable
private fun PermissionContent(
    innerPadding: PaddingValues,
    permissionRequested: Boolean,
    shouldShowRationale: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val bodyText = when {
        shouldShowRationale -> "HandType needs camera access to photograph your handwritten calibration sheet."
        permissionRequested -> "Camera access is still blocked. You can grant it from system settings if Android is no longer showing the permission dialog."
        else -> "Allow camera access to capture the alphabet sheet used for handwriting extraction."
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(shape = RoundedCornerShape(28.dp)) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "Camera permission required",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = bodyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text(text = "Grant camera access")
                }
                if (permissionRequested && !shouldShowRationale) {
                    TextButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = "Open app settings")
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraGuideOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokeWidth = 4.dp.toPx()
        val insetX = size.width * 0.08f
        val insetY = size.height * 0.10f
        val frameLeft = insetX
        val frameTop = insetY
        val frameWidth = size.width - (insetX * 2)
        val frameHeight = size.height - (insetY * 2)
        val lineColor = Color.White.copy(alpha = 0.92f)
        val gridColor = Color.White.copy(alpha = 0.30f)

        drawRoundRect(
            color = lineColor,
            topLeft = androidx.compose.ui.geometry.Offset(frameLeft, frameTop),
            size = androidx.compose.ui.geometry.Size(frameWidth, frameHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(36.dp.toPx()),
            style = Stroke(width = strokeWidth),
        )

        val thirdHeight = frameHeight / 3f
        for (index in 1..2) {
            val y = frameTop + (thirdHeight * index)
            drawLine(
                color = gridColor,
                start = androidx.compose.ui.geometry.Offset(frameLeft + 24.dp.toPx(), y),
                end = androidx.compose.ui.geometry.Offset(frameLeft + frameWidth - 24.dp.toPx(), y),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

private fun capturePhoto(
    context: Context,
    imageCapture: ImageCapture,
    onPhotoReady: (Bitmap) -> Unit,
    onError: (Throwable) -> Unit,
) {
    val outputFile = runCatching {
        File.createTempFile("handtype_capture_", ".jpg", context.cacheDir)
    }.getOrElse { throwable ->
        onError(throwable)
        return
    }
    val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

    imageCapture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                runCatching {
                    outputFile.toUprightBitmap()
                }.onSuccess { bitmap ->
                    onPhotoReady(bitmap)
                }.onFailure(onError)

                outputFile.delete()
            }

            override fun onError(exception: ImageCaptureException) {
                outputFile.delete()
                onError(exception)
            }
        },
    )
}

private fun File.toUprightBitmap(): Bitmap {
    val decodedBitmap = BitmapFactory.decodeFile(absolutePath)
        ?: error("Unable to decode captured bitmap from $absolutePath")
    val orientation = ExifInterface(absolutePath).getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL,
    )
    val rotation = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    return if (rotation == 0f) {
        decodedBitmap
    } else {
        val matrix = Matrix().apply { postRotate(rotation) }
        Bitmap.createBitmap(
            decodedBitmap,
            0,
            0,
            decodedBitmap.width,
            decodedBitmap.height,
            matrix,
            true,
        )
    }
}

private fun Context.hasCameraPermission(): Boolean {
    return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
}

private fun Context.shouldShowCameraPermissionRationale(): Boolean {
    val activity = findActivity() ?: return false
    return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}