package com.baer.handtype

import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.baer.handtype.feature.capture.CameraCaptureScreen
import com.baer.handtype.feature.capture.SheetSampleProcessor
import com.baer.handtype.feature.info.AboutScreen
import com.baer.handtype.feature.info.FaqScreen
import com.baer.handtype.feature.info.GITHUB_PRIVACY_URL
import com.baer.handtype.feature.info.HelpScreen
import com.baer.handtype.feature.shell.AppDrawerContent
import com.baer.handtype.feature.shell.DrawerDestination
import com.baer.handtype.feature.template.HandwritingRenderScreen
import com.baer.handtype.feature.template.HistoryScreen
import com.baer.handtype.feature.template.LoadingTemplateScreen
import com.baer.handtype.feature.template.RenameHandwritingDialog
import com.baer.handtype.feature.template.TemplateChooserScreen
import com.baer.handtype.template.TemplateDescriptor
import com.baer.handtype.template.BundledTemplateRepository
import com.baer.handtype.template.HandwritingTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                HandTypeRoot()
            }
        }
    }
}

@Composable
private fun HandTypeRoot() {
    val context = LocalContext.current
    val repository = remember(context) { BundledTemplateRepository(context) }
    val builtInTemplates = remember { repository.listBuiltInTemplates() }
    val premiumTemplate = remember { repository.premiumTemplate() }
    var route by rememberSaveable { mutableStateOf(RootRoute.TemplateChooser.name) }
    var selectedTemplateId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRenameTemplateId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRenameTemplateName by rememberSaveable { mutableStateOf<String?>(null) }
    var userTemplates by remember { mutableStateOf(repository.listUserTemplates()) }

    fun refreshUserTemplates() {
        userTemplates = repository.listUserTemplates()
    }

    // Refresh user templates whenever the chooser is shown so newly captured ones appear.
    LaunchedEffect(route) {
        if (route == RootRoute.TemplateChooser.name) {
            refreshUserTemplates()
        }
    }
    val chooserTemplates = remember(builtInTemplates, userTemplates) { builtInTemplates + userTemplates }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }
    BackHandler(enabled = !drawerState.isOpen && route != RootRoute.TemplateChooser.name) {
        route = RootRoute.TemplateChooser.name
    }

    fun navigate(dest: DrawerDestination) {
        if (dest == DrawerDestination.Privacy) {
            scope.launch { drawerState.close() }
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_PRIVACY_URL)))
            return
        }
        route = when (dest) {
            DrawerDestination.Templates -> RootRoute.TemplateChooser.name
            DrawerDestination.History -> RootRoute.History.name
            DrawerDestination.Help -> RootRoute.Help.name
            DrawerDestination.Faq -> RootRoute.Faq.name
            DrawerDestination.About -> RootRoute.About.name
            DrawerDestination.Privacy -> RootRoute.TemplateChooser.name
        }
        scope.launch { drawerState.close() }
    }

    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }

    AnimatedContent(
        targetState = route,
        transitionSpec = { routeTransitionSpec() },
        label = "rootNav",
    ) { currentRoute ->
        val isImmersive = currentRoute == RootRoute.RenderTemplate.name ||
            currentRoute == RootRoute.PremiumCapture.name

        if (isImmersive) {
            when (currentRoute) {
                RootRoute.RenderTemplate.name -> {
                    val templateId = selectedTemplateId
                    if (templateId == null) {
                        route = RootRoute.TemplateChooser.name
                    } else {
                        TemplateRenderRoute(
                            templateId = templateId,
                            repository = repository,
                            onBackToTemplates = { route = RootRoute.TemplateChooser.name },
                            onPremiumSelected = { route = RootRoute.PremiumCapture.name },
                        )
                    }
                }
                RootRoute.PremiumCapture.name -> PremiumCaptureRoute(
                    repository = repository,
                    onTemplateSaved = { newId, defaultName ->
                        pendingRenameTemplateId = newId
                        pendingRenameTemplateName = defaultName
                        route = RootRoute.TemplateChooser.name
                    },
                )
            }
        } else {
            val drawerDest = when (currentRoute) {
                RootRoute.History.name -> DrawerDestination.History
                RootRoute.Help.name -> DrawerDestination.Help
                RootRoute.Faq.name -> DrawerDestination.Faq
                RootRoute.About.name -> DrawerDestination.About
                else -> DrawerDestination.Templates
            }
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    AppDrawerContent(current = drawerDest, onSelect = ::navigate)
                },
            ) {
                when (currentRoute) {
                    RootRoute.TemplateChooser.name -> TemplateChooserScreen(
                        templates = chooserTemplates,
                        premiumTemplate = premiumTemplate,
                        initialTemplateId = pendingRenameTemplateId ?: userTemplates.firstOrNull { it.isNew }?.id,
                        onTemplateOpened = { descriptor ->
                            if (descriptor.isNew) {
                                repository.userRepository().markTemplateSeen(descriptor.id)
                                refreshUserTemplates()
                            }
                        },
                        onTemplateSelected = { descriptor ->
                            selectedTemplateId = descriptor.id
                            route = RootRoute.RenderTemplate.name
                        },
                        onPremiumSelected = { route = RootRoute.PremiumCapture.name },
                        onOpenDrawer = openDrawer,
                        onDeleteTemplate = { descriptor ->
                            repository.deleteUserTemplate(descriptor.id)
                            refreshUserTemplates()
                        },
                    )
                    RootRoute.History.name -> HistoryScreen(onOpenDrawer = openDrawer)
                    RootRoute.Help.name -> HelpScreen(onOpenDrawer = openDrawer)
                    RootRoute.Faq.name -> FaqScreen(onOpenDrawer = openDrawer)
                    RootRoute.About.name -> AboutScreen(onOpenDrawer = openDrawer)
                }
            }

            if (currentRoute == RootRoute.TemplateChooser.name) {
                val renameTemplateId = pendingRenameTemplateId
                val renameTemplateName = pendingRenameTemplateName
                    ?: renameTemplateId?.let { templateId ->
                        repository.userRepository().getTemplateDescriptor(templateId)?.displayName
                    }
                if (renameTemplateId != null && renameTemplateName != null) {
                    RenameHandwritingDialog(
                        initialName = renameTemplateName,
                        onConfirm = { updatedName ->
                            repository.userRepository().renameTemplate(renameTemplateId, updatedName)
                            refreshUserTemplates()
                            pendingRenameTemplateId = null
                            pendingRenameTemplateName = null
                        },
                        onDismiss = {
                            pendingRenameTemplateId = null
                            pendingRenameTemplateName = null
                        },
                    )
                }
            }
        }
    }
}

private fun AnimatedContentTransitionScope<String>.routeTransitionSpec(): ContentTransform {
    val anim = tween<androidx.compose.ui.unit.IntOffset>(260, easing = FastOutSlowInEasing)
    return when {
        targetState == RootRoute.PremiumCapture.name ->
            slideIntoContainer(SlideDirection.Up, anim)
                .togetherWith(slideOutOfContainer(SlideDirection.Up, anim))

        initialState == RootRoute.PremiumCapture.name ->
            slideIntoContainer(SlideDirection.Down, anim)
                .togetherWith(slideOutOfContainer(SlideDirection.Down, anim))

        targetState == RootRoute.RenderTemplate.name ->
            slideIntoContainer(SlideDirection.Start, anim)
                .togetherWith(slideOutOfContainer(SlideDirection.Start, anim))

        initialState == RootRoute.RenderTemplate.name ->
            slideIntoContainer(SlideDirection.End, anim)
                .togetherWith(slideOutOfContainer(SlideDirection.End, anim))

        else ->
            slideIntoContainer(SlideDirection.Start, anim)
                .togetherWith(slideOutOfContainer(SlideDirection.Start, anim))
    }
}

@Composable
private fun TemplateRenderRoute(
    templateId: String,
    repository: BundledTemplateRepository,
    onBackToTemplates: () -> Unit,
    onPremiumSelected: () -> Unit,
) {
    val template by produceState<HandwritingTemplate?>(initialValue = null, templateId) {
        value = withContext(Dispatchers.IO) {
            repository.loadTemplate(templateId)
        }
    }

    var showLoading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(400L)
        showLoading = true
    }

    if (template != null) {
        HandwritingRenderScreen(
            template = template!!,
            onBackToTemplates = onBackToTemplates,
            onPremiumSelected = onPremiumSelected,
        )
    } else if (showLoading) {
        LoadingTemplateScreen(
            title = "Loading template",
            body = "Preparing bundled bitmap glyphs for the default handwriting flow.",
        )
    }
}

@Composable
private fun PremiumCaptureRoute(
    repository: BundledTemplateRepository,
    onTemplateSaved: (String, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val initialInstruction = stringResource(R.string.premium_instruction)
    val processingInstruction = stringResource(R.string.premium_processing)
    val noGlyphsInstruction = stringResource(R.string.premium_no_glyphs)
    val markerDetectionFailedInstruction = stringResource(R.string.premium_marker_detection_failed)
    val saveFailedInstruction = stringResource(R.string.premium_save_failed)
    val processFailedInstruction = stringResource(R.string.premium_process_failed)
    val cameraFailedInstruction = stringResource(R.string.premium_camera_failed)
    var instructionText by rememberSaveable {
        mutableStateOf(initialInstruction)
    }
    var isProcessing by remember { mutableStateOf(false) }

    CameraCaptureScreen(
        instructionText = instructionText,
        isProcessing = isProcessing,
        onImageCaptured = { bitmap ->
            if (isProcessing) return@CameraCaptureScreen
            isProcessing = true
            instructionText = processingInstruction
            scope.launch {
                runCatching {
                    withContext(Dispatchers.Default) {
                        SheetSampleProcessor.process(
                            bitmap,
                            debugContext = context.applicationContext,
                            skipPageDetection = true,
                        )
                    }
                }.onSuccess { result ->
                    if (result.glyphs.isEmpty()) {
                        instructionText = if (result.detectionFailed) {
                            markerDetectionFailedInstruction
                        } else {
                            noGlyphsInstruction
                        }
                        isProcessing = false
                        return@onSuccess
                    }
                    val defaultTemplateName = createDefaultTemplateName(context)
                    val savedId = runCatching {
                        repository.userRepository().saveTemplate(
                            displayName = defaultTemplateName,
                            glyphMap = result.glyphs,
                        )
                    }.getOrElse { throwable ->
                        android.util.Log.e("PremiumCapture", "Failed to persist user template", throwable)
                        instructionText = throwable.message
                            ?: saveFailedInstruction
                        isProcessing = false
                        return@onSuccess
                    }
                    android.util.Log.i(
                        "PremiumCapture",
                        "Sheet capture saved as $savedId. Glyphs=${result.glyphs.size} missing=${result.missing.size}",
                    )
                    onTemplateSaved(savedId, defaultTemplateName)
                }.onFailure { throwable ->
                    android.util.Log.e("PremiumCapture", "Sheet processing failed", throwable)
                    instructionText = throwable.message
                        ?: processFailedInstruction
                    isProcessing = false
                }
            }
        },
        onCaptureError = { throwable ->
            android.util.Log.e("PremiumCapture", "Camera capture failed", throwable)
            instructionText = throwable.message ?: cameraFailedInstruction
            isProcessing = false
        },
    )
}

private fun createDefaultTemplateName(context: Context): String {
    val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
    return context.getString(
        R.string.rename_handwriting_default_name,
        context.getString(R.string.premium_default_template_name),
        dateFormat.format(Date()),
    )
}

private enum class RootRoute {
    TemplateChooser,
    RenderTemplate,
    PremiumCapture,
    History,
    Help,
    Faq,
    About,
}
