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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import android.graphics.Typeface as AndroidTypeface
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.baer.handtype.R
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.abs
import androidx.compose.ui.util.lerp
import kotlin.math.cos
import kotlin.math.sin
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
import com.baer.handtype.template.UserTemplateRepository
import com.baer.handtype.testing.HandTypeTestTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TemplateChooserScreen(
    templates: List<TemplateDescriptor>,
    premiumTemplate: TemplateDescriptor,
    initialTemplateId: String? = null,
    onTemplateOpened: ((TemplateDescriptor) -> Unit)? = null,
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

            val initialPage = remember(templates, initialTemplateId) {
                templates.indexOfFirst { it.id == initialTemplateId }
                    .takeIf { it >= 0 }
                    ?: 0
            }
            val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { templates.size })
            val pagerScope = rememberCoroutineScope()
            val haptic = LocalHapticFeedback.current

            LaunchedEffect(templates, initialTemplateId) {
                val targetIndex = templates.indexOfFirst { it.id == initialTemplateId }
                if (targetIndex >= 0 && targetIndex != pagerState.currentPage) {
                    pagerState.scrollToPage(targetIndex)
                }
            }

            HorizontalPager(
                state = pagerState,
                contentPadding = PaddingValues(horizontal = 32.dp),
                pageSpacing = 12.dp,
                modifier = Modifier
                    .testTag(HandTypeTestTags.TEMPLATE_PAGER)
                    .fillMaxWidth()
                    .height(280.dp),
                flingBehavior = PagerDefaults.flingBehavior(
                    state = pagerState,
                    snapAnimationSpec = spring(
                        dampingRatio = 0.78f,
                        stiffness = 240f,
                    ),
                ),
            ) { page ->
                val pageOffset = pagerState.getOffsetFractionForPage(page)
                val scale = lerp(0.95f, 1f, 1f - abs(pageOffset).coerceIn(0f, 1f))
                Box(
                    modifier = Modifier
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .pointerInput(page) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                val up = waitForUpOrCancellation()
                                if (up != null) {
                                    up.consume()
                                    val descriptor = templates[page]
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    pagerScope.launch {
                                        if (pagerState.currentPage != page) {
                                            pagerState.animateScrollToPage(page)
                                        }
                                        if (descriptor.isNew) {
                                            onTemplateOpened?.invoke(descriptor)
                                        }
                                        onTemplateSelected(descriptor)
                                    }
                                }
                            }
                        },
                ) {
                    val descriptor = templates[page]
                    TemplateOptionCard(
                        descriptor = descriptor,
                        accentColor = Color(0xFF23443A),
                        buttonText = stringResource(R.string.template_chooser_use_template),
                        badgeText = if (descriptor.isNew) stringResource(R.string.template_chooser_new_badge) else null,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Delete button: only for user-captured templates
                    if (onDeleteTemplate != null && descriptor.id.startsWith("user_")) {
                        androidx.compose.material3.IconButton(
                            onClick = { pendingDeleteTemplate = descriptor },
                            modifier = Modifier
                                .testTag(HandTypeTestTags.templateDeleteButton(descriptor.id))
                                .align(Alignment.BottomEnd)
                                .padding(end = 14.dp, bottom = 14.dp),
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
            title = { Text(stringResource(R.string.template_delete_title)) },
            text = { Text(stringResource(R.string.template_delete_body, descriptor.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteTemplate?.invoke(descriptor)
                    pendingDeleteTemplate = null
                }) { Text(stringResource(R.string.action_delete), color = Color(0xFFAA3333)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteTemplate = null }) { Text(stringResource(R.string.action_cancel)) }
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
                with(density) { 76.dp.roundToPx() }
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
                            text = ""
                            phase = RenderPhase.Compose.name
                        },
                        onRegenerate = ::generate,
                        onBackToCompose = {
                            resultBitmap = null
                            missingChars = emptySet()
                            phase = RenderPhase.Compose.name
                        },
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
                modifier = Modifier
                    .testTag(HandTypeTestTags.RENDER_MESSAGE_INPUT)
                    .fillMaxSize(),
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
                .testTag(HandTypeTestTags.RENDER_GENERATE_BUTTON)
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
    onBackToCompose: () -> Unit,
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
            IconButton(onClick = onBackToCompose) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.render_back_button),
                )
            }
            Text(
                text = stringResource(R.string.render_result_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            TextButton(onClick = onBackToTemplates) {
                Text(text = stringResource(R.string.render_templates_button))
            }
        }

        if (bitmap != null) {
            var imageScale by remember(bitmap) { mutableFloatStateOf(1f) }
            var imageOffsetX by remember(bitmap) { mutableFloatStateOf(0f) }
            var imageOffsetY by remember(bitmap) { mutableFloatStateOf(0f) }
            Card(
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.clipToBounds(),
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.render_result_image_desc),
                    modifier = Modifier
                        .testTag(HandTypeTestTags.RENDER_RESULT_IMAGE)
                        .fillMaxWidth()
                        .padding(4.dp)
                        .graphicsLayer(
                            scaleX = imageScale,
                            scaleY = imageScale,
                            translationX = imageOffsetX,
                            translationY = imageOffsetY,
                        )
                        .pointerInput(bitmap) {
                            detectTapGestures(onDoubleTap = {
                                imageScale = 1f
                                imageOffsetX = 0f
                                imageOffsetY = 0f
                            })
                        }
                        .pointerInput(bitmap) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val newScale = (imageScale * zoom).coerceIn(1f, 6f)
                                imageScale = newScale
                                if (newScale > 1f) {
                                    imageOffsetX += pan.x
                                    imageOffsetY += pan.y
                                } else {
                                    imageOffsetX = 0f
                                    imageOffsetY = 0f
                                }
                            }
                        }
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

        // Tip card to fill remaining space and surface the sharing feature
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFEEE8DB)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.result_tip_share),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF1A1410).copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
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
                .horizontalScroll(rememberScrollState())
                .padding(end = 8.dp),
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
    val isUserTemplate = descriptor.id.startsWith("user_")
    val context = LocalContext.current
    var userSampleBitmap by remember(descriptor.id) { mutableStateOf<Bitmap?>(null) }
    if (isUserTemplate) {
        LaunchedEffect(descriptor.id) {
            userSampleBitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val repo = UserTemplateRepository(context)
                    val template = repo.loadTemplate(descriptor.id)
                    val config = HandwritingRenderConfig(
                        maxWidthPx = 1200,
                        targetLineHeightPx = 62,
                        marginPx = 32,
                    )
                    val full = HandwritingBitmapRenderer.render(
                        text = descriptor.sampleText,
                        template = template,
                        config = config,
                        seed = descriptor.id.hashCode().toLong(),
                    ).bitmap
                    // Crop to ink — scan for rows/cols that differ from the paper bg color.
                    // To optically centre the text, we include full descenders but add
                    // equal extra top padding to balance the descender depth below baseline.
                    val paperColor = 0xFFF8F1E4.toInt()
                    val w = full.width; val h = full.height
                    var top = 0; var left = 0; var right = w - 1
                    outer@ for (y in 0 until h) for (x in 0 until w) {
                        if (full.getPixel(x, y) != paperColor) { top = y; break@outer }
                    }
                    // Collect all rows with any ink.
                    val inkRows = (top until h).filter { y ->
                        (0 until w).any { x -> full.getPixel(x, y) != paperColor }
                    }
                    val bottom = inkRows.lastOrNull() ?: (h - 1)
                    outer@ for (x in 0 until w) for (y in top..bottom) {
                        if (full.getPixel(x, y) != paperColor) { left = x; break@outer }
                    }
                    outer@ for (x in w - 1 downTo left) for (y in top..bottom) {
                        if (full.getPixel(x, y) != paperColor) { right = x; break@outer }
                    }
                    val hPad = 14
                    val topPad = hPad
                    val botPad = 22
                    val cx = (left - hPad).coerceAtLeast(0)
                    val cy = (top - topPad).coerceAtLeast(0)
                    val cw = (right - left + 1 + hPad * 2).coerceAtMost(w - cx)
                    val ch = (bottom - cy + 1 + botPad).coerceAtMost(h - cy)
                    if (cw > 0 && ch > 0) Bitmap.createBitmap(full, cx, cy, cw, ch) else full
                }.getOrNull()
            }
        }
    }
    val templateFontFamily: FontFamily? = remember(descriptor.id) {
        when (descriptor.id) {
            "classic_script" -> runCatching {
                FontFamily(AndroidTypeface.createFromAsset(context.assets, "fonts/Inkfree.ttf"))
            }.getOrNull()
            "flowing_cursive" -> FontFamily(AndroidTypeface.create("cursive", AndroidTypeface.NORMAL))
            else -> null
        }
    }
    val cardShape = RoundedCornerShape(28.dp)
    val cardContainerColor = if (isUserTemplate) Color(0xFFFFF8EC) else Color.White
    val cardBorder = if (isUserTemplate) {
        BorderStroke(1.dp, Color(0xFFE5C57A).copy(alpha = 0.9f))
    } else {
        null
    }
    val titleColor = if (isUserTemplate) Color(0xFF4F2F16) else MaterialTheme.colorScheme.onSurface
    val sampleContainerColor = if (isUserTemplate) Color(0xFFFFF9EF).copy(alpha = 0.92f) else accentColor.copy(alpha = 0.08f)
    val sampleBorder = if (isUserTemplate) BorderStroke(1.dp, Color(0xFFE7CE92).copy(alpha = 0.8f)) else null
    val buttonColor = if (isUserTemplate) Color(0xFF8E5A21) else accentColor

    Card(
        shape = cardShape,
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = if (isUserTemplate) 8.dp else 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = cardBorder,
    ) {
        Box(
            modifier = Modifier
                .background(cardContainerColor, cardShape)
                .clip(cardShape)
                .graphicsLayer { shape = cardShape; clip = true }
                .fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = descriptor.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        color = titleColor,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (badgeText != null) {
                        NewBadgePill(text = badgeText, sparkleKey = descriptor.id)
                    }
                }

                Surface(
                    color = sampleContainerColor,
                    shape = RoundedCornerShape(20.dp),
                    border = sampleBorder,
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        if (isUserTemplate) {
                            val bmp = userSampleBitmap
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp)),
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(40.dp)
                                        .background(
                                            Color(0xFFE7CE92).copy(alpha = 0.35f),
                                            RoundedCornerShape(8.dp),
                                        ),
                                )
                            }
                        } else {
                            Text(
                                text = descriptor.sampleText,
                                style = MaterialTheme.typography.headlineSmall,
                                fontFamily = templateFontFamily,
                                color = accentColor,
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = descriptor.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(buttonColor),
                ) {
                    Text(
                        text = buttonText,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isUserTemplate) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun NewBadgePill(text: String, sparkleKey: String) {
    // Each particle: [xFraction, yFraction, sizeFraction, phaseOffset, cycleCount(1-3)]
    // CycleCount is an integer so sin(wrapped * 2π * n) completes exactly n full cycles
    // per animation loop — no discontinuity at restart boundary.
    // xFraction and yFraction are spread around the pill perimeter (not centered on text).
    val sparkleParticles = remember(sparkleKey) {
        val rng = Random(sparkleKey.hashCode())
        // Sparkles straddle the pill boundary — center near the 10dp outer margin edge.
        // Outer box = pill + 10dp padding on each side. In normalized canvas coords:
        //   left boundary ≈ x ∈ [0.08, 0.18], right ≈ [0.82, 0.92]
        //   top boundary  ≈ y ∈ [0.10, 0.25], bottom ≈ [0.75, 0.90]
        val placements = List(8) {
            val edge = rng.nextInt(4)
            val x: Float
            val y: Float
            when (edge) {
                0 -> { x = 0.08f + rng.nextFloat() * 0.10f; y = 0.10f + rng.nextFloat() * 0.80f }  // left edge
                1 -> { x = 0.82f + rng.nextFloat() * 0.10f; y = 0.10f + rng.nextFloat() * 0.80f }  // right edge
                2 -> { x = 0.15f + rng.nextFloat() * 0.70f; y = 0.10f + rng.nextFloat() * 0.15f }  // top edge
                else -> { x = 0.15f + rng.nextFloat() * 0.70f; y = 0.75f + rng.nextFloat() * 0.15f } // bottom edge
            }
            floatArrayOf(
                x,
                y,
                0.10f + rng.nextFloat() * 0.08f,         // size — smaller than before
                rng.nextFloat(),                         // phase
                (1 + rng.nextInt(3)).toFloat(),          // cycleCount 1..3
            )
        }
        placements
    }
    val transition = rememberInfiniteTransition(label = "badgeSparkle")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "badgeSparkleProgress",
    )

    // Outer Box adds invisible margin around the pill for sparkles to float in.
    Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            sparkleParticles.forEach { p ->
                // (progress + phase) % 1 wraps seamlessly at restart boundary.
                // sin(wrapped * 2π * cycleCount) completes exactly n full cycles → no jump.
                val wrapped = (progress + p[3]) % 1f
                val wave = (sin(wrapped * 2f * PI.toFloat() * p[4]) * 0.5f + 0.5f).coerceIn(0f, 1f)
                val alpha = (0.15f + 0.85f * wave * wave).coerceIn(0f, 1f)
                val radius = size.height * p[2] * (0.3f + 0.7f * wave)
                val cx = size.width * p[0]
                val cy = size.height * p[1]
                val inner = radius * 0.15f
                val starPath = Path()
                for (i in 0 until 8) {
                    val angle = (i.toFloat() / 8f) * 2f * PI.toFloat() - PI.toFloat() / 2f
                    val r = if (i % 2 == 0) radius else inner
                    val x = cx + cos(angle).toFloat() * r
                    val y = cy + sin(angle).toFloat() * r
                    if (i == 0) starPath.moveTo(x, y) else starPath.lineTo(x, y)
                }
                starPath.close()
                drawPath(starPath, Color(0xFFFFD740).copy(alpha = alpha))
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(10.dp)
                .background(Color(0xFFE4B75A), shape = RoundedCornerShape(10.dp))
                .border(1.dp, Color(0xFFB8841E).copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                text = text,
                color = Color(0xFF3D1F00),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
            )
        }
    }
}

private data class BadgeSparkleParticle(
    val xFraction: Float,
    val yFraction: Float,
    val sizeFraction: Float,
    val flashAlpha: Float,
    val flashWindow: Float,
    val phase: Float,
    val speed: Float,
    val rotation: Float,
    val rotationSpeed: Float,
)

private fun buildBadgeSparkleParticles(seedKey: String): List<BadgeSparkleParticle> {
    val random = Random(seedKey.hashCode())
    val startX = 0.38f
    val startY = 0.04f
    val endX = 0.9f
    val endY = 0.46f
    val tangentX = endX - startX
    val tangentY = endY - startY
    val tangentLength = kotlin.math.sqrt((tangentX * tangentX) + (tangentY * tangentY))
    val normalX = -tangentY / tangentLength
    val normalY = tangentX / tangentLength
    return List(32) {
        val progress = random.nextFloat()
        val laneOffset = when (random.nextInt(5)) {
            0 -> -0.09f - random.nextFloat() * 0.015f
            1 -> -0.04f - random.nextFloat() * 0.02f
            2 -> 0.03f + random.nextFloat() * 0.02f
            3 -> 0.07f + random.nextFloat() * 0.02f
            else -> -0.01f + random.nextFloat() * 0.02f
        }
        val drift = (random.nextFloat() - 0.5f) * 0.025f
        val xFraction = (startX + tangentX * progress + normalX * laneOffset + drift).coerceIn(0.16f, 0.98f)
        val yFraction = (startY + tangentY * progress + normalY * laneOffset + drift * 0.8f).coerceIn(0.0f, 0.6f)
        BadgeSparkleParticle(
            xFraction = xFraction,
            yFraction = yFraction,
            sizeFraction = 0.03f + random.nextFloat() * 0.028f,
            flashAlpha = 0.82f + random.nextFloat() * 0.42f,
            flashWindow = 0.18f + random.nextFloat() * 0.1f,
            phase = random.nextFloat(),
            speed = 0.55f + random.nextFloat() * 0.95f,
            rotation = random.nextFloat() * 45f,
            rotationSpeed = 0.3f + random.nextFloat() * 0.65f,
        )
    }
}

private fun sinBellPulse(progress: Float, center: Float, halfWidth: Float): Float {
    if (halfWidth <= 0f) return 0f
    val directDist = abs(progress - center)
    val dist = minOf(directDist, 1f - directDist)
    if (dist >= halfWidth) return 0f
    val normalized = dist / halfWidth
    return (cos(normalized * PI.toFloat()) * 0.5f + 0.5f).coerceIn(0f, 1f)
}

@Composable
private fun BadgeSparkleOverlay(
    progress: Float,
    particles: List<BadgeSparkleParticle>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        particles.forEach { particle ->
            val wrappedProgress = ((progress * particle.speed) + particle.phase) % 1f
            val wave = (sin(wrappedProgress * 2f * PI.toFloat()) * 0.5f + 0.5f).coerceIn(0f, 1f)
            val pulse = 0.22f + 0.78f * (wave * wave)
            val alpha = (pulse * particle.flashAlpha).coerceIn(0f, 1f)
            if (alpha <= 0.01f) return@forEach

            val center = Offset(
                x = size.width * particle.xFraction,
                y = size.height * particle.yFraction,
            )
            val scaledRadius = size.minDimension * particle.sizeFraction * (0.3f + 0.7f * pulse)
            val currentRotation = particle.rotation + wrappedProgress * 72f * particle.rotationSpeed

            val coreColor = Color.White.copy(alpha = alpha)
            val midColor = Color(0xFFFFF1A3).copy(alpha = alpha * 0.82f)
            val glowColor = Color(0xFFFFCC52).copy(alpha = alpha * 0.42f)

            withTransform({ rotate(degrees = currentRotation, pivot = center) }) {
                drawFourPointStar(center, scaledRadius * 2.9f, glowColor)
                drawFourPointStar(center, scaledRadius * 1.75f, midColor)
                drawFourPointStar(center, scaledRadius, coreColor)
            }
        }
    }
}

private fun DrawScope.drawFourPointStar(center: Offset, outerRadius: Float, color: Color) {
    val innerRadius = outerRadius * 0.13f
    val path = Path()
    for (i in 0 until 8) {
        val angle = (i.toFloat() / 8f) * 2f * PI.toFloat() - PI.toFloat() / 2f
        val r = if (i % 2 == 0) outerRadius else innerRadius
        val x = center.x + cos(angle) * r
        val y = center.y + sin(angle) * r
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color)
}

@Composable
private fun BadgeCornerRibbon(
    badgeText: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val width = size.width
            val height = size.height
            val shadowPath = Path().apply {
                moveTo(width * 0.34f, 0f)
                lineTo(width, height * 0.47f)
                lineTo(width, height * 0.86f)
                lineTo(width * 0.03f, height * 0.11f)
                close()
            }
            val ribbonPath = Path().apply {
                moveTo(width * 0.29f, 0f)
                lineTo(width, height * 0.42f)
                lineTo(width, height * 0.8f)
                lineTo(0f, height * 0.08f)
                close()
            }
            val foldPath = Path().apply {
                moveTo(width * 0.84f, height * 0.34f)
                lineTo(width, height * 0.42f)
                lineTo(width, height * 0.58f)
                lineTo(width * 0.88f, height * 0.49f)
                close()
            }

            drawPath(path = shadowPath, color = Color(0xFF8A531B).copy(alpha = 0.52f))
            drawPath(path = ribbonPath, color = Color(0xFFE4B75A))
            drawPath(path = foldPath, color = Color(0xFF9A6324).copy(alpha = 0.82f))
            drawLine(
                color = Color(0xFFFFF3CB).copy(alpha = 0.95f),
                start = Offset(width * 0.33f, height * 0.07f),
                end = Offset(width * 0.93f, height * 0.45f),
                strokeWidth = height * 0.045f,
            )
            drawLine(
                color = Color(0xFF9A6324).copy(alpha = 0.42f),
                start = Offset(width * 0.17f, height * 0.12f),
                end = Offset(width * 0.93f, height * 0.63f),
                strokeWidth = height * 0.038f,
            )
        }

        Text(
            text = badgeText,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-4).dp, y = 16.dp)
                .graphicsLayer { rotationZ = 33f; clip = true },
            color = Color(0xFF4A2A06),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

@Composable
fun RenameHandwritingDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    val trimmedName = name.trim()

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.rename_handwriting_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = stringResource(R.string.rename_handwriting_body))
                OutlinedTextField(
                    value = name,
                    onValueChange = { updated ->
                        if (updated.length <= 40) {
                            name = updated
                        }
                    },
                    modifier = Modifier
                        .testTag(HandTypeTestTags.RENAME_HANDWRITING_INPUT)
                        .fillMaxWidth(),
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.rename_handwriting_label)) },
                    supportingText = {
                        if (trimmedName.isEmpty()) {
                            Text(text = stringResource(R.string.rename_handwriting_error_blank))
                        }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmedName) },
                enabled = trimmedName.isNotEmpty(),
            ) {
                Text(text = stringResource(R.string.rename_handwriting_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.rename_handwriting_cancel))
            }
        },
    )
}
