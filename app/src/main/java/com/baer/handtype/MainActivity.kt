package com.baer.handtype

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.baer.handtype.feature.template.TemplateChooserScreen
import com.baer.handtype.template.BundledTemplateRepository
import com.baer.handtype.template.HandwritingTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var userTemplates by remember { mutableStateOf(repository.listUserTemplates()) }

    // Refresh user templates whenever the chooser is shown so newly captured ones appear.
    LaunchedEffect(route) {
        if (route == RootRoute.TemplateChooser.name) {
            userTemplates = repository.listUserTemplates()
        }
    }
    val chooserTemplates = remember(builtInTemplates, userTemplates) { builtInTemplates + userTemplates }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val isImmersive = route == RootRoute.RenderTemplate.name || route == RootRoute.PremiumCapture.name

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }
    BackHandler(enabled = !drawerState.isOpen && route != RootRoute.TemplateChooser.name) {
        route = RootRoute.TemplateChooser.name
    }

    val currentDest = when (route) {
        RootRoute.TemplateChooser.name -> DrawerDestination.Templates
        RootRoute.History.name -> DrawerDestination.History
        RootRoute.Help.name -> DrawerDestination.Help
        RootRoute.Faq.name -> DrawerDestination.Faq
        RootRoute.About.name -> DrawerDestination.About
        else -> DrawerDestination.Templates
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

    val content: @Composable () -> Unit = {
        when (route) {
            RootRoute.TemplateChooser.name -> TemplateChooserScreen(
                templates = chooserTemplates,
                premiumTemplate = premiumTemplate,
                onTemplateSelected = { descriptor ->
                    selectedTemplateId = descriptor.id
                    route = RootRoute.RenderTemplate.name
                },
                onPremiumSelected = { route = RootRoute.PremiumCapture.name },
                onOpenDrawer = openDrawer,
                onDeleteTemplate = { descriptor ->
                    repository.deleteUserTemplate(descriptor.id)
                    userTemplates = repository.listUserTemplates()
                },
            )

            RootRoute.History.name -> HistoryScreen(onOpenDrawer = openDrawer)
            RootRoute.Help.name -> HelpScreen(onOpenDrawer = openDrawer)
            RootRoute.Faq.name -> FaqScreen(onOpenDrawer = openDrawer)
            RootRoute.About.name -> AboutScreen(onOpenDrawer = openDrawer)

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
                onTemplateSaved = { newId ->
                    selectedTemplateId = newId
                    route = RootRoute.RenderTemplate.name
                },
            )
        }
    }

    if (isImmersive) {
        content()
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                AppDrawerContent(current = currentDest, onSelect = ::navigate)
            },
        ) {
            content()
        }
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
    onTemplateSaved: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var instructionText by rememberSaveable {
        mutableStateOf(
            "Print the practice sheet, fill every cell with your handwriting, then snap a clear photo with all four black corner squares visible.",
        )
    }
    var isProcessing by remember { mutableStateOf(false) }

    CameraCaptureScreen(
        instructionText = instructionText,
        isProcessing = isProcessing,
        onImageCaptured = { bitmap ->
            if (isProcessing) return@CameraCaptureScreen
            isProcessing = true
            instructionText = "Detecting corner markers and extracting glyphs..."
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
                        instructionText =
                            "No filled cells were detected. Make sure you wrote in the cells and the four black squares are visible."
                        isProcessing = false
                        return@onSuccess
                    }
                    val savedId = runCatching {
                        repository.userRepository().saveTemplate(
                            displayName = "My Handwriting",
                            glyphMap = result.glyphs,
                        )
                    }.getOrElse { throwable ->
                        android.util.Log.e("PremiumCapture", "Failed to persist user template", throwable)
                        instructionText = throwable.message
                            ?: "Could not save handwriting template. Try again."
                        isProcessing = false
                        return@onSuccess
                    }
                    android.util.Log.i(
                        "PremiumCapture",
                        "Sheet capture saved as $savedId. Glyphs=${result.glyphs.size} missing=${result.missing.size}",
                    )
                    onTemplateSaved(savedId)
                }.onFailure { throwable ->
                    android.util.Log.e("PremiumCapture", "Sheet processing failed", throwable)
                    instructionText = throwable.message
                        ?: "Could not process the sheet. Re-capture with all four corner markers visible."
                    isProcessing = false
                }
            }
        },
        onCaptureError = { throwable ->
            android.util.Log.e("PremiumCapture", "Camera capture failed", throwable)
            instructionText = throwable.message ?: "Camera capture failed. Try again."
            isProcessing = false
        },
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
