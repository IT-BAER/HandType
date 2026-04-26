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
import androidx.compose.runtime.DisposableEffect
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
import com.baer.handtype.ml.HandwritingExtractor
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
                templates = builtInTemplates,
                premiumTemplate = premiumTemplate,
                onTemplateSelected = { descriptor ->
                    selectedTemplateId = descriptor.id
                    route = RootRoute.RenderTemplate.name
                },
                onPremiumSelected = { route = RootRoute.PremiumCapture.name },
                onOpenDrawer = openDrawer,
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

            RootRoute.PremiumCapture.name -> PremiumCaptureRoute()
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
private fun PremiumCaptureRoute() {
    val scope = rememberCoroutineScope()
    val extractor = remember { HandwritingExtractor() }
    var instructionText by rememberSaveable {
        mutableStateOf(
            "Premium feature: write A-Z, a-z, and 0-9 on blank paper, then center the page inside the frame to create your personal template.",
        )
    }

    DisposableEffect(extractor) {
        onDispose {
            extractor.close()
        }
    }

    CameraCaptureScreen(
        instructionText = instructionText,
        onImageCaptured = { bitmap ->
            instructionText = "Processing handwriting sample with ML Kit..."
            scope.launch {
                runCatching {
                    withContext(Dispatchers.Default) {
                        extractor.extract(bitmap)
                    }
                }.onSuccess { result ->
                    instructionText = if (result.glyphs.isEmpty()) {
                        "No alphanumeric glyphs were detected. Try brighter lighting and fill more of the frame with the page. Use system back to return to templates."
                    } else {
                        "Premium capture complete. ${result.glyphs.size} glyph crops were extracted across ${result.glyphMap.size} unique characters. Use system back to return to templates."
                    }
                }.onFailure { throwable ->
                    instructionText = throwable.message
                        ?: "Text recognition failed. Try another capture with sharper focus."
                }
            }
        },
        onCaptureError = { throwable ->
            instructionText = throwable.message ?: "Camera capture failed. Try again."
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
