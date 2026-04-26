package com.baer.handtype.feature.template

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue
import com.baer.handtype.template.HandwritingBitmapRenderer
import com.baer.handtype.template.HandwritingRenderConfig
import com.baer.handtype.template.HandwritingTemplate
import com.baer.handtype.template.OutputExporter
import com.baer.handtype.template.TemplateDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun Modifier.pressScale(): Modifier {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "pressScale",
    )
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                pressed = true
                waitForUpOrCancellation()
                pressed = false
            }
        }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TemplateChooserScreen(
    templates: List<TemplateDescriptor>,
    premiumTemplate: TemplateDescriptor,
    onTemplateSelected: (TemplateDescriptor) -> Unit,
    onPremiumSelected: () -> Unit,
    onHistorySelected: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        containerColor = Color(0xFFF5EFE4),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Choose a handwriting style",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                text = "Built-in templates are the default path. Personal handwriting capture stays available as an optional premium feature.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            TextButton(
                onClick = onHistorySelected,
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                Text(text = "View history")
            }

            val pagerState = rememberPagerState(pageCount = { templates.size })
            val pagerScope = rememberCoroutineScope()
            val haptic = LocalHapticFeedback.current

            HorizontalPager(
                state = pagerState,
                contentPadding = PaddingValues(horizontal = 32.dp),
                pageSpacing = 12.dp,
                modifier = Modifier.fillMaxWidth(),
                flingBehavior = PagerDefaults.flingBehavior(
                    state = pagerState,
                    snapAnimationSpec = spring(stiffness = Spring.StiffnessMedium),
                ),
            ) { page ->
                val pageOffset = (
                    (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                ).absoluteValue
                val overlayAlpha = (pageOffset * 0.5f).coerceIn(0f, 0.5f)

                Box(
                    modifier = Modifier.pointerInput(page) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            val up = waitForUpOrCancellation()
                            if (up != null) {
                                up.consume()
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                pagerScope.launch {
                                    if (pagerState.currentPage != page) {
                                        pagerState.animateScrollToPage(page)
                                    }
                                    onTemplateSelected(templates[page])
                                }
                            }
                        }
                    },
                ) {
                    TemplateOptionCard(
                        descriptor = templates[page],
                        accentColor = Color(0xFF23443A),
                        buttonText = "Use template",
                    )
                    if (overlayAlpha > 0.01f) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clip(RoundedCornerShape(28.dp))
                                .background(Color(0xFFF5EFE4).copy(alpha = overlayAlpha)),
                        )
                    }
                }
            }

            if (templates.size > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(templates.size) { index ->
                        val selected = pagerState.currentPage == index
                        val dotSize by animateDpAsState(
                            targetValue = if (selected) 8.dp else 6.dp,
                            animationSpec = spring(),
                            label = "dotSize",
                        )
                        val dotColor by animateColorAsState(
                            targetValue = if (selected) Color(0xFF23443A)
                                else Color(0xFF23443A).copy(alpha = 0.3f),
                            animationSpec = tween(300),
                            label = "dotColor",
                        )
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(dotSize)
                                .clip(CircleShape)
                                .background(dotColor),
                        )
                    }
                }
            }

            Card(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onPremiumSelected()
                },
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .pressScale(),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF9F2)),
                border = BorderStroke(1.dp, Color(0xFF7C4D11).copy(alpha = 0.18f)),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = premiumTemplate.displayName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Surface(
                            color = Color(0xFF7C4D11).copy(alpha = 0.14f),
                            shape = RoundedCornerShape(999.dp),
                        ) {
                            Text(
                                text = "Premium",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                color = Color(0xFF7C4D11),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    Surface(
                        color = Color(0xFF7C4D11).copy(alpha = 0.08f),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = premiumTemplate.sampleText,
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color(0xFF7C4D11),
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = premiumTemplate.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Surface(
                        color = Color(0xFF7C4D11),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text(
                            text = "Premium capture",
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

private enum class RenderPhase { Compose, Generating, Result }

@Composable
fun HandwritingRenderScreen(
    template: HandwritingTemplate,
    onBackToTemplates: () -> Unit,
    onPremiumSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var phase by rememberSaveable { mutableStateOf(RenderPhase.Compose.name) }
    var resultBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var missingChars by remember { mutableStateOf<Set<Char>>(emptySet()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Scaffold(
        modifier = modifier,
        containerColor = Color(0xFFF5EFE4),
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val density = LocalDensity.current
            val renderWidthPx = remember(maxWidth, density) {
                with(density) { (maxWidth - 32.dp).roundToPx() }
            }

            fun generate() {
                phase = RenderPhase.Generating.name
                scope.launch {
                    val inputText = text.ifBlank {
                        "The quick brown fox jumps over the lazy dog. " +
                        "Every morning she would sit by the window, watching " +
                        "the rain trace patterns on the glass. It was a quiet " +
                        "kind of beauty, the sort that only patience could reveal."
                    }
                    val result = withContext(Dispatchers.Default) {
                        val config = HandwritingRenderConfig(maxWidthPx = renderWidthPx)
                        if (template.isStrokeBased) {
                            HandwritingBitmapRenderer.renderCursive(
                                text = inputText,
                                strokeData = template.strokeData!!,
                                config = config,
                            )
                        } else {
                            HandwritingBitmapRenderer.render(
                                text = inputText,
                                template = template,
                                config = config,
                            )
                        }
                    }
                    resultBitmap = result.bitmap
                    missingChars = result.missingCharacters
                    phase = RenderPhase.Result.name
                    // Auto-archive into history (best effort, non-blocking).
                    withContext(Dispatchers.IO) {
                        OutputExporter.archiveBitmap(
                            context = context,
                            bitmap = result.bitmap,
                            templateName = template.descriptor.displayName,
                            sourceText = inputText,
                        )
                    }
                }
            }

            AnimatedContent(
                targetState = phase,
                transitionSpec = {
                    (fadeIn(tween(300)) + slideInVertically { it / 8 })
                        .togetherWith(fadeOut(tween(200)))
                },
                label = "phaseTransition",
            ) { currentPhase ->
                when (currentPhase) {
                    RenderPhase.Compose.name -> ComposePhaseContent(
                        text = text,
                        onTextChange = { text = it },
                        templateName = template.descriptor.displayName,
                        onGenerate = ::generate,
                        onBackToTemplates = onBackToTemplates,
                        onPremiumSelected = onPremiumSelected,
                    )

                    RenderPhase.Generating.name -> GeneratingOverlay()

                    RenderPhase.Result.name -> ResultPhaseContent(
                        bitmap = resultBitmap,
                        templateName = template.descriptor.displayName,
                        missingCharacters = missingChars,
                        onNewNote = {
                            resultBitmap = null
                            phase = RenderPhase.Compose.name
                        },
                        onRegenerate = ::generate,
                        onBackToTemplates = onBackToTemplates,
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposePhaseContent(
    text: String,
    onTextChange: (String) -> Unit,
    templateName: String,
    onGenerate: () -> Unit,
    onBackToTemplates: () -> Unit,
    onPremiumSelected: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = templateName,
                style = MaterialTheme.typography.headlineSmall,
            )
            TextButton(onClick = onBackToTemplates) {
                Text(text = "Templates")
            }
        }

        Text(
            text = "Type your message below, then tap Generate to create a unique handwritten note.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text("Your message") },
            placeholder = { Text("Hello, world!") },
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
            maxLines = 20,
        )

        val haptic = LocalHapticFeedback.current

        Button(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onGenerate()
            },
            modifier = Modifier
                .fillMaxWidth()
                .pressScale(),
            shape = RoundedCornerShape(18.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF23443A),
            ),
        ) {
            Text(
                text = "Generate",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        OutlinedButton(
            onClick = onPremiumSelected,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            contentPadding = PaddingValues(vertical = 14.dp),
        ) {
            Text(text = "Premium: Use My Handwriting")
        }
    }
}

@Composable
private fun GeneratingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5EFE4)),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = true,
            enter = scaleIn(
                initialScale = 0.85f,
                animationSpec = spring(dampingRatio = 0.7f),
            ) + fadeIn(tween(300)),
        ) {
            Card(
                shape = RoundedCornerShape(28.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = Color(0xFF23443A),
                    )
                    Text(
                        text = "Generating your note...",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Each generation is unique",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultPhaseContent(
    bitmap: Bitmap?,
    templateName: String,
    missingCharacters: Set<Char>,
    onNewNote: () -> Unit,
    onRegenerate: () -> Unit,
    onBackToTemplates: () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    var pendingTransparent by remember { mutableStateOf(false) }

    fun doSave(transparent: Boolean) {
        val bmp = bitmap ?: return
        val result = if (transparent) {
            OutputExporter.saveTransparentImageToGallery(context, bmp, templateName)
        } else {
            OutputExporter.saveImageToGallery(context, bmp, templateName)
        }
        result
            .onSuccess {
                toast(
                    if (transparent) "Saved transparent PNG to Pictures/HandType"
                    else "Saved to Pictures/HandType",
                )
            }
            .onFailure { toast("Save failed: ${it.message ?: "unknown"}") }
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) doSave(pendingTransparent) else toast("Storage permission denied")
    }

    fun saveWithPermission(transparent: Boolean = false) {
        if (bitmap == null) return
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        pendingTransparent = transparent
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
        }
        doSave(transparent)
    }

    fun shareImage() {
        val bmp = bitmap ?: return
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        runCatching {
            val uri = OutputExporter.stageBitmapForShare(context, bmp, templateName)
            context.startActivity(OutputExporter.buildShareIntent(uri))
        }.onFailure { toast("Share failed: ${it.message ?: "unknown"}") }
    }

    fun exportPdf() {
        val bmp = bitmap ?: return
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        OutputExporter.exportPdf(context, bmp, templateName)
            .onSuccess { toast("PDF saved to Download/HandType") }
            .onFailure { toast("PDF failed: ${it.message ?: "unknown"}") }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Your Note",
                style = MaterialTheme.typography.headlineSmall,
            )
            TextButton(onClick = onBackToTemplates) {
                Text(text = "Templates")
            }
        }

        if (bitmap != null) {
            Card(
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Generated handwriting",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    contentScale = ContentScale.FillWidth,
                )
            }
        }

        if (missingCharacters.isNotEmpty()) {
            Text(
                text = "Missing glyphs: ${missingCharacters.joinToString(" ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Primary export actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = { saveWithPermission(false) },
                enabled = bitmap != null,
                modifier = Modifier
                    .weight(1f)
                    .pressScale(),
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(vertical = 14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF23443A),
                ),
            ) {
                Text(text = "Save")
            }
            Button(
                onClick = ::shareImage,
                enabled = bitmap != null,
                modifier = Modifier
                    .weight(1f)
                    .pressScale(),
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(vertical = 14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF7C4D11),
                ),
            ) {
                Text(text = "Share")
            }
        }

        OutlinedButton(
            onClick = ::exportPdf,
            enabled = bitmap != null,
            modifier = Modifier
                .fillMaxWidth()
                .pressScale(),
            shape = RoundedCornerShape(18.dp),
            contentPadding = PaddingValues(vertical = 14.dp),
        ) {
            Text(text = "Export as PDF")
        }

        TextButton(
            onClick = { saveWithPermission(true) },
            enabled = bitmap != null,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(text = "Save without background (transparent PNG)")
        }

        // Secondary navigation actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onRegenerate()
                },
                modifier = Modifier
                    .weight(1f)
                    .pressScale(),
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(vertical = 14.dp),
            ) {
                Text(text = "Regenerate")
            }

            OutlinedButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onNewNote()
                },
                modifier = Modifier
                    .weight(1f)
                    .pressScale(),
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(vertical = 14.dp),
            ) {
                Text(text = "New Note")
            }
        }
    }
}

@Composable
fun LoadingTemplateScreen(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF5EFE4)),
        contentAlignment = Alignment.Center,
    ) {
        Card(shape = RoundedCornerShape(28.dp)) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CircularProgressIndicator()
                Text(text = title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TemplateOptionCard(
    descriptor: TemplateDescriptor,
    accentColor: Color,
    buttonText: String,
    badgeText: String? = null,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = descriptor.displayName,
                    style = MaterialTheme.typography.titleLarge,
                )
                if (badgeText != null) {
                    Surface(
                        color = accentColor.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Text(
                            text = badgeText,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            color = accentColor,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }

            Surface(
                color = accentColor.copy(alpha = 0.08f),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = descriptor.sampleText,
                        style = MaterialTheme.typography.headlineSmall,
                        color = accentColor,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = descriptor.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Surface(
                color = accentColor,
                shape = RoundedCornerShape(18.dp),
            ) {
                Text(
                    text = buttonText,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
