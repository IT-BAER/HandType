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
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.baer.handtype.R
import kotlin.math.absoluteValue
import com.baer.handtype.ui.animation.stripReveal
import com.baer.handtype.ui.animation.PenWritingAnimation
import com.baer.handtype.ui.animation.pressScale
import com.baer.handtype.ui.animation.selectionBounceScale
import com.baer.handtype.template.HandwritingBitmapRenderer
import com.baer.handtype.template.HandwritingRenderConfig
import com.baer.handtype.template.HandwritingTemplate
import com.baer.handtype.template.NoteBackgroundCatalog
import com.baer.handtype.template.NoteBackgroundPreferences
import com.baer.handtype.template.NoteBackgroundPreset
import com.baer.handtype.template.NoteBackgroundPreviewPattern
import com.baer.handtype.template.OutputExporter
import com.baer.handtype.template.TemplateDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TemplateChooserScreen(
    templates: List<TemplateDescriptor>,
    premiumTemplate: TemplateDescriptor,
    onTemplateSelected: (TemplateDescriptor) -> Unit,
    onPremiumSelected: () -> Unit,
    onOpenDrawer: () -> Unit = {},
    onDeleteTemplate: ((TemplateDescriptor) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var pendingDeleteTemplate by remember { mutableStateOf<TemplateDescriptor?>(null) }

    Scaffold(
        modifier = modifier,
        containerColor = Color(0xFFF5EFE4),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.IconButton(onClick = onOpenDrawer) {
                    Column(
                        modifier = Modifier.size(24.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        repeat(3) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.dp)
                                    .background(Color(0xFF1A1410), RoundedCornerShape(1.dp)),
                            )
                        }
                    }
                }
            }
            Text(
                text = stringResource(R.string.template_chooser_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                text = stringResource(R.string.template_chooser_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            val pagerState = rememberPagerState(pageCount = { templates.size })
            val pagerScope = rememberCoroutineScope()
            val haptic = LocalHapticFeedback.current

            HorizontalPager(
                state = pagerState,
                contentPadding = PaddingValues(horizontal = 52.dp),
                pageSpacing = 24.dp,
                modifier = Modifier.fillMaxWidth(),
                flingBehavior = PagerDefaults.flingBehavior(
                    state = pagerState,
                    snapAnimationSpec = spring(
                        dampingRatio = 0.78f,
                        stiffness = 240f,
                    ),
                ),
            ) { page ->
                Box(
                    modifier = Modifier
                        .pointerInput(page) {
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
                        buttonText = stringResource(R.string.template_chooser_use_template),
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Delete button: only for user-captured templates
                    val descriptor = templates[page]
                    if (onDeleteTemplate != null && descriptor.id.startsWith("user_")) {
                        androidx.compose.material3.IconButton(
                            onClick = { pendingDeleteTemplate = descriptor },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp),
                        ) {
                            Text(
                                text = "\u2715",
                                color = Color(0xFFAA3333),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
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
                                text = stringResource(R.string.template_chooser_premium_badge),
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
                            text = stringResource(R.string.template_chooser_premium_button),
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

    // Delete confirmation dialog for user templates
    pendingDeleteTemplate?.let { descriptor ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDeleteTemplate = null },
            title = { Text("Delete template?") },
            text = { Text("\"${descriptor.displayName}\" will be permanently removed from your device.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteTemplate?.invoke(descriptor)
                    pendingDeleteTemplate = null
                }) { Text("Delete", color = Color(0xFFAA3333)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteTemplate = null }) { Text("Cancel") }
            },
        )
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
    val backgroundPreferences = remember(context) { NoteBackgroundPreferences(context) }
    val initialDefaultBackgroundId = remember(backgroundPreferences) { backgroundPreferences.getDefaultBackgroundId() }
    val backgroundPresets = remember { NoteBackgroundCatalog.selectablePresets }
    var defaultBackgroundId by rememberSaveable { mutableStateOf(initialDefaultBackgroundId) }
    var selectedBackgroundId by rememberSaveable { mutableStateOf(initialDefaultBackgroundId) }
    var lastRenderedText by rememberSaveable { mutableStateOf("") }
    var lastRenderSeed by rememberSaveable { mutableStateOf<Long?>(null) }

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
            val targetLineHeightPx = remember(density) {
                with(density) { 34.dp.roundToPx() }
            }

            suspend fun renderNote(inputText: String, backgroundId: String, seed: Long) = withContext(Dispatchers.Default) {
                val config = HandwritingRenderConfig(
                    maxWidthPx = renderWidthPx,
                    targetLineHeightPx = targetLineHeightPx,
                    noteBackground = NoteBackgroundCatalog.presetOrDefault(backgroundId),
                )
                if (template.isStrokeBased) {
                    HandwritingBitmapRenderer.renderCursive(
                        text = inputText,
                        strokeData = template.strokeData!!,
                        config = config,
                        seed = seed,
                    )
                } else {
                    HandwritingBitmapRenderer.render(
                        text = inputText,
                        template = template,
                        config = config,
                        seed = seed,
                    )
                }
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
                    val seed = System.nanoTime()
                    val result = renderNote(inputText, selectedBackgroundId, seed)
                    resultBitmap = result.bitmap
                    missingChars = result.missingCharacters
                    lastRenderedText = inputText
                    lastRenderSeed = seed
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
                    (fadeIn(tween(300)) + slideInVertically { it / 12 })
                        .togetherWith(fadeOut(tween(250)))
                },
                label = "phaseTransition",
            ) { currentPhase ->
                when (currentPhase) {
                    RenderPhase.Compose.name -> ComposePhaseContent(
                        text = text,
                        onTextChange = { text = it },
                        templateName = template.descriptor.displayName,
                        backgroundPresets = backgroundPresets,
                        selectedBackgroundId = selectedBackgroundId,
                        defaultBackgroundId = defaultBackgroundId,
                        onBackgroundSelected = { selectedBackgroundId = it },
                        onSetBackgroundDefault = {
                            backgroundPreferences.setDefaultBackgroundId(selectedBackgroundId)
                            defaultBackgroundId = selectedBackgroundId
                        },
                        onGenerate = ::generate,
                        onBackToTemplates = onBackToTemplates,
                        onPremiumSelected = onPremiumSelected,
                    )

                    RenderPhase.Generating.name -> GeneratingOverlay()

                    RenderPhase.Result.name -> ResultPhaseContent(
                        bitmap = resultBitmap,
                        templateName = template.descriptor.displayName,
                        missingCharacters = missingChars,
                        onSaveTransparent = {
                            val seed = lastRenderSeed ?: return@ResultPhaseContent
                            val inputText = lastRenderedText.ifBlank { return@ResultPhaseContent }
                            scope.launch {
                                val transparentBitmap = renderNote(
                                    inputText = inputText,
                                    backgroundId = NoteBackgroundCatalog.transparentPreset.id,
                                    seed = seed,
                                ).bitmap
                                try {
                                    OutputExporter.saveImageToGallery(
                                        context = context,
                                        bitmap = transparentBitmap,
                                        templateName = "${template.descriptor.displayName}_transparent",
                                    )
                                        .onSuccess {
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.result_saved_transparent),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                        .onFailure {
                                            Toast.makeText(
                                                context,
                                                context.getString(
                                                    R.string.result_save_failed,
                                                    it.message ?: "unknown",
                                                ),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                } finally {
                                    transparentBitmap.recycle()
                                }
                            }
                        },
                        onNewNote = {
                            resultBitmap = null
                            missingChars = emptySet()
                            selectedBackgroundId = defaultBackgroundId
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
    backgroundPresets: List<NoteBackgroundPreset>,
    selectedBackgroundId: String,
    defaultBackgroundId: String,
    onBackgroundSelected: (String) -> Unit,
    onSetBackgroundDefault: () -> Unit,
    onGenerate: () -> Unit,
    onBackToTemplates: () -> Unit,
    onPremiumSelected: () -> Unit,
) {
    val selectedBackground = backgroundPresets.firstOrNull { it.id == selectedBackgroundId }
        ?: backgroundPresets.first()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
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
                Text(text = stringResource(R.string.render_templates_button))
            }
        }

        Text(
            text = stringResource(R.string.render_instruction),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
        ) {
            PaperPreviewSurface(
                preset = selectedBackground,
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(16.dp),
            )
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                label = { Text(stringResource(R.string.render_text_label)) },
                placeholder = { Text(stringResource(R.string.render_text_placeholder)) },
                modifier = Modifier.fillMaxSize(),
                maxLines = 20,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    errorContainerColor = Color.Transparent,
                ),
            )
        }

        BackgroundPicker(
            presets = backgroundPresets,
            selectedBackgroundId = selectedBackgroundId,
            defaultBackgroundId = defaultBackgroundId,
            onBackgroundSelected = onBackgroundSelected,
            onSetBackgroundDefault = onSetBackgroundDefault,
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
                text = stringResource(R.string.render_generate_button),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        OutlinedButton(
            onClick = onPremiumSelected,
            modifier = Modifier
                .fillMaxWidth()
                .pressScale(),
            shape = RoundedCornerShape(18.dp),
            contentPadding = PaddingValues(vertical = 14.dp),
        ) {
            Text(text = stringResource(R.string.render_premium_button))
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
                initialScale = 0.92f,
                animationSpec = tween(220, easing = FastOutSlowInEasing),
            ) + fadeIn(tween(220, easing = FastOutSlowInEasing)),
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
                    PenWritingAnimation(
                        modifier = Modifier.size(140.dp),
                        inkColor = Color(0xFF1A1410),
                        traceColor = Color(0xFF23443A),
                    )
                    Text(
                        text = stringResource(R.string.render_generating_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.render_generating_subtitle),
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
    onSaveTransparent: () -> Unit,
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

    // Ink-reveal animation for the result bitmap
    val inkRevealProgress = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(bitmap) {
        if (bitmap != null) {
            inkRevealProgress.snapTo(0f)
            inkRevealProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 750,
                    easing = androidx.compose.animation.core.LinearEasing,
                ),
            )
        }
    }

    fun doSave(transparent: Boolean) {
        val bmp = bitmap ?: return
        if (transparent) {
            onSaveTransparent()
            return
        }
        OutputExporter.saveImageToGallery(context, bmp, templateName)
            .onSuccess {
                toast(context.getString(R.string.result_saved))
            }
            .onFailure { toast(context.getString(R.string.result_save_failed, it.message ?: "unknown")) }
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) doSave(pendingTransparent) else toast(context.getString(R.string.result_permission_denied))
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
        }.onFailure { toast(context.getString(R.string.result_share_failed, it.message ?: "unknown")) }
    }

    fun exportPdf() {
        val bmp = bitmap ?: return
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        OutputExporter.exportPdf(context, bmp, templateName)
            .onSuccess { toast(context.getString(R.string.result_pdf_saved)) }
            .onFailure { toast(context.getString(R.string.result_pdf_failed, it.message ?: "unknown")) }
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
                text = stringResource(R.string.render_result_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            TextButton(onClick = onBackToTemplates) {
                Text(text = stringResource(R.string.render_templates_button))
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
                    contentDescription = stringResource(R.string.render_result_image_desc),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp)
                        .stripReveal(progress = inkRevealProgress.value),
                    contentScale = ContentScale.FillWidth,
                )
            }
        }

        if (missingCharacters.isNotEmpty()) {
            Text(
                text = stringResource(R.string.render_missing_glyphs, missingCharacters.joinToString(" ")),
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
                Text(text = stringResource(R.string.result_save_button))
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
                Text(text = stringResource(R.string.result_share_button))
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
            Text(text = stringResource(R.string.result_export_pdf_button))
        }

        TextButton(
            onClick = { saveWithPermission(true) },
            enabled = bitmap != null,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(text = stringResource(R.string.result_save_transparent_button))
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
                Text(text = stringResource(R.string.result_regenerate_button))
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
                Text(text = stringResource(R.string.result_new_note_button))
            }
        }
    }
}

@Composable
private fun BackgroundPicker(
    presets: List<NoteBackgroundPreset>,
    selectedBackgroundId: String,
    defaultBackgroundId: String,
    onBackgroundSelected: (String) -> Unit,
    onSetBackgroundDefault: () -> Unit,
) {
    val isCurrentDefault = selectedBackgroundId == defaultBackgroundId
    val selectedPreset = presets.firstOrNull { it.id == selectedBackgroundId } ?: presets.first()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.render_background_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(selectedPreset.labelRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF1A1410),
                    )
                    if (selectedPreset.premium) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = Color(0xFF7C4D11).copy(alpha = 0.12f),
                        ) {
                            Text(
                                text = stringResource(R.string.template_chooser_premium_badge),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF7C4D11),
                            )
                        }
                    }
                    if (isCurrentDefault) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = Color(0xFF23443A).copy(alpha = 0.12f),
                        ) {
                            Text(
                                text = stringResource(R.string.render_background_default_badge),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF23443A),
                            )
                        }
                    }
                }
            }
            if (!isCurrentDefault) {
                TextButton(
                    onClick = onSetBackgroundDefault,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = stringResource(R.string.render_background_set_default),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            presets.forEach { preset ->
                BackgroundPresetCard(
                    preset = preset,
                    selected = preset.id == selectedBackgroundId,
                    isDefault = preset.id == defaultBackgroundId,
                    onClick = { onBackgroundSelected(preset.id) },
                )
            }
        }
    }
}

@Composable
private fun BackgroundPresetCard(
    preset: NoteBackgroundPreset,
    selected: Boolean,
    isDefault: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = when {
        selected -> Color(0xFF23443A)
        isDefault -> Color(0xFF23443A).copy(alpha = 0.24f)
        else -> Color(0xFF1A1410).copy(alpha = 0.12f)
    }
    val bounceScale = selectionBounceScale(selected)

    Card(
        onClick = onClick,
        modifier = Modifier
            .width(58.dp)
            .graphicsLayer {
                scaleX = bounceScale
                scaleY = bounceScale
            },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(if (selected) 2.5.dp else 1.dp, borderColor),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (selected) 4.dp else 0.dp,
        ),
    ) {
        PaperPreviewSurface(
            preset = preset,
            modifier = Modifier
                .padding(4.dp)
                .size(width = 50.dp, height = 40.dp),
            shape = RoundedCornerShape(10.dp),
            showPremiumBadge = true,
        )
    }
}

@Composable
private fun PaperPreviewSurface(
    preset: NoteBackgroundPreset,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    showPremiumBadge: Boolean = false,
) {
    val previewColors = preset.previewColors.map { Color(it) }

    Box(
        modifier = modifier
            .clip(shape)
            .then(
                if (previewColors.size == 1) {
                    Modifier.background(previewColors.first())
                } else {
                    Modifier.background(Brush.linearGradient(previewColors))
                },
            ),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            when (preset.previewPattern) {
                NoteBackgroundPreviewPattern.Texture -> {
                    val step = (size.minDimension / 5.5f).coerceAtLeast(10f)
                    var row = 0
                    var y = step * 0.6f
                    while (y < size.height) {
                        var column = 0
                        var x = if (row % 2 == 0) step * 0.75f else step * 1.15f
                        while (x < size.width) {
                            drawCircle(
                                color = Color(0xFFFFFFFF).copy(alpha = if ((row + column) % 3 == 0) 0.14f else 0.08f),
                                radius = if ((row + column) % 2 == 0) step * 0.12f else step * 0.08f,
                                center = Offset(x, y),
                            )
                            if ((row + column) % 4 == 0) {
                                drawLine(
                                    color = Color(0xFF8A6A3F).copy(alpha = 0.08f),
                                    start = Offset(x - step * 0.22f, y - step * 0.1f),
                                    end = Offset(x + step * 0.28f, y + step * 0.06f),
                                    strokeWidth = 1.1f,
                                )
                            }
                            x += step * 1.3f
                            column += 1
                        }
                        y += step * 0.92f
                        row += 1
                    }
                }

                NoteBackgroundPreviewPattern.Ruled -> {
                    val step = (size.height / 6f).coerceAtLeast(12f)
                    var y = step * 1.1f
                    while (y < size.height) {
                        drawLine(
                            color = Color(0xFF9BB7DB).copy(alpha = 0.7f),
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1.2f,
                        )
                        y += step
                    }
                    drawLine(
                        color = Color(0xFFD58F8F).copy(alpha = 0.85f),
                        start = Offset(size.width * 0.16f, 0f),
                        end = Offset(size.width * 0.16f, size.height),
                        strokeWidth = 1.4f,
                    )
                }

                NoteBackgroundPreviewPattern.Graph -> {
                    val step = (size.minDimension / 7f).coerceAtLeast(10f)
                    var y = 0f
                    while (y <= size.height) {
                        drawLine(
                            color = Color(0xFF8DB2D2).copy(alpha = 0.55f),
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1f,
                        )
                        y += step
                    }
                    var x = 0f
                    while (x <= size.width) {
                        drawLine(
                            color = Color(0xFF8DB2D2).copy(alpha = 0.55f),
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 1f,
                        )
                        x += step
                    }
                }

                else -> Unit
            }
        }

        if (showPremiumBadge && preset.premium) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF7C4D11)),
            )
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
                PenWritingAnimation(
                    modifier = Modifier.size(100.dp),
                    inkColor = Color(0xFF1A1410),
                    traceColor = Color(0xFF23443A),
                )
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
