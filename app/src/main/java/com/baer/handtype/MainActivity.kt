package com.baer.handtype

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import com.baer.handtype.feature.capture.CameraCaptureScreen
import com.baer.handtype.feature.capture.SheetSampleProcessor
import com.baer.handtype.feature.info.AboutScreen
import com.baer.handtype.feature.info.FaqScreen
import com.baer.handtype.feature.info.HelpScreen
import com.baer.handtype.feature.info.PrivacyScreen
import com.baer.handtype.feature.settings.AppSettings
import com.baer.handtype.feature.settings.AppSettingsStore
import com.baer.handtype.feature.settings.SettingsScreen
import com.baer.handtype.feature.settings.ThemePreference
import com.baer.handtype.feature.settings.localizedFor
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
import com.baer.handtype.ui.theme.HandTypeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    // Localizing the Activity itself keeps dialogs, sheets and toasts (own windows) in the app language.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.localizedFor(AppSettingsStore(newBase).read().languagePreference))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            HandTypeApp()
        }
    }
}

@Composable
private fun HandTypeApp() {
    val activity = LocalContext.current as Activity
    val settingsStore = remember(activity) { AppSettingsStore(activity.applicationContext) }
    var settings by remember { mutableStateOf(settingsStore.read()) }
    val systemDarkTheme = isSystemInDarkTheme()
    val darkTheme = when (settings.themePreference) {
        ThemePreference.System -> systemDarkTheme
        ThemePreference.Light -> false
        ThemePreference.Dark -> true
    }

    HandTypeTheme(darkTheme = darkTheme) {
        HandTypeRoot(
            settings = settings,
            onSettingsChanged = { updated ->
                val languageChanged = updated.languagePreference != settings.languagePreference
                settings = updated
                settingsStore.write(updated)
                if (languageChanged) activity.recreate()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HandTypeRoot(
    settings: AppSettings,
    onSettingsChanged: (AppSettings) -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(context) { BundledTemplateRepository(context) }
    val builtInTemplates = remember(repository) { repository.listBuiltInTemplates() }
    val premiumTemplate = remember(repository) { repository.premiumTemplate() }
    // Saveable so a recreate (language switch, system theme or font-scale change) keeps the screen;
    // a cold start has no saved state and still opens on home.
    var route by rememberSaveable { mutableStateOf(RootRoute.TemplateChooser.name) }
    var selectedTemplateId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRenameTemplateId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRenameTemplateName by rememberSaveable { mutableStateOf<String?>(null) }
    var userTemplates by remember(repository) { mutableStateOf(repository.listUserTemplates()) }

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
        route = when (dest) {
            DrawerDestination.Templates -> RootRoute.TemplateChooser.name
            DrawerDestination.History -> RootRoute.History.name
            DrawerDestination.Help -> RootRoute.Help.name
            DrawerDestination.Faq -> RootRoute.Faq.name
            DrawerDestination.About -> RootRoute.About.name
            DrawerDestination.Privacy -> RootRoute.Privacy.name
            DrawerDestination.Settings -> RootRoute.Settings.name
        }
        scope.launch { drawerState.close() }
    }

    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }

    AnimatedContent(
        targetState = route,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
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
                    onBack = { route = RootRoute.TemplateChooser.name },
                )
            }
        } else {
            val drawerDest = when (currentRoute) {
                RootRoute.History.name -> DrawerDestination.History
                RootRoute.Help.name -> DrawerDestination.Help
                RootRoute.Faq.name -> DrawerDestination.Faq
                RootRoute.About.name -> DrawerDestination.About
                RootRoute.Privacy.name -> DrawerDestination.Privacy
                RootRoute.Settings.name -> DrawerDestination.Settings
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
                    RootRoute.History.name -> HistoryScreen(
                        onOpenDrawer = openDrawer,
                        onCreateNote = { route = RootRoute.TemplateChooser.name },
                    )
                    RootRoute.Help.name -> HelpScreen(onOpenDrawer = openDrawer)
                    RootRoute.Faq.name -> FaqScreen(onOpenDrawer = openDrawer)
                    RootRoute.About.name -> AboutScreen(onOpenDrawer = openDrawer)
                    RootRoute.Privacy.name -> PrivacyScreen(onOpenDrawer = openDrawer)
                    RootRoute.Settings.name -> SettingsScreen(
                        settings = settings,
                        onSettingsChanged = onSettingsChanged,
                        onOpenDrawer = openDrawer,
                    )
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

            if (!settings.onboardingComplete && currentRoute == RootRoute.TemplateChooser.name) {
                val onboardingSheetState = rememberModalBottomSheetState()
                ModalBottomSheet(
                    onDismissRequest = { onSettingsChanged(settings.copy(onboardingComplete = true)) },
                    sheetState = onboardingSheetState,
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.onboarding_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = stringResource(R.string.onboarding_body),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            onClick = { onSettingsChanged(settings.copy(onboardingComplete = true)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = stringResource(R.string.onboarding_cta))
                        }
                    }
                }
            }
        }
    }
}

private fun AnimatedContentTransitionScope<String>.routeTransitionSpec(): ContentTransform {
    return when {
        targetState == RootRoute.PremiumCapture.name ->
            directionalRouteTransition(SlideDirection.Up)

        initialState == RootRoute.PremiumCapture.name ->
            directionalRouteTransition(SlideDirection.Down)

        targetState == RootRoute.RenderTemplate.name ||
            initialState == RootRoute.RenderTemplate.name ->
            directionalRouteTransition(
                direction = if (targetState == RootRoute.RenderTemplate.name) {
                    SlideDirection.Start
                } else {
                    SlideDirection.End
                },
            )

        else ->
            replaceRouteTransition()
    }
}

private val RouteEnterEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val RouteExitEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

private fun AnimatedContentTransitionScope<String>.directionalRouteTransition(
    direction: SlideDirection,
): ContentTransform {
    val slideSpec = tween<androidx.compose.ui.unit.IntOffset>(
        durationMillis = 300,
        easing = RouteEnterEasing,
    )
    val enter = slideIntoContainer(
        towards = direction,
        animationSpec = slideSpec,
        initialOffset = { fullDistance -> fullDistance / 8 },
    ) +
        fadeIn(
            animationSpec = tween(
                durationMillis = 180,
                easing = RouteEnterEasing,
            ),
        ) +
        scaleIn(
            animationSpec = tween(
                durationMillis = 300,
                easing = RouteEnterEasing,
            ),
            initialScale = 0.985f,
        )
    val exit = slideOutOfContainer(
        towards = direction,
        animationSpec = tween(
            durationMillis = 220,
            easing = RouteExitEasing,
        ),
        targetOffset = { fullDistance -> fullDistance / 18 },
    ) +
        scaleOut(
            animationSpec = tween(
                durationMillis = 220,
                easing = RouteExitEasing,
            ),
            targetScale = 0.995f,
        )
    return enter.togetherWith(exit)
}

private fun replaceRouteTransition(): ContentTransform {
    val enter = fadeIn(
        animationSpec = tween(
            durationMillis = 160,
            easing = RouteEnterEasing,
        ),
    ) +
        scaleIn(
            animationSpec = tween(
                durationMillis = 220,
                easing = RouteEnterEasing,
            ),
            initialScale = 0.99f,
        )
    val exit = fadeOut(
        animationSpec = tween(
            durationMillis = 120,
            easing = RouteExitEasing,
        ),
    ) +
        scaleOut(
            animationSpec = tween(
                durationMillis = 120,
                easing = RouteExitEasing,
            ),
            targetScale = 0.995f,
        )
    return enter.togetherWith(exit)
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
    var showLoading by remember(templateId) { mutableStateOf(false) }

    LaunchedEffect(templateId) {
        delay(350L)
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
            title = stringResource(R.string.loading_template_title),
            body = stringResource(R.string.loading_template_body),
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        )
    }
}

@Composable
private fun PremiumCaptureRoute(
    repository: BundledTemplateRepository,
    onTemplateSaved: (String, String) -> Unit,
    onBack: () -> Unit,
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
        onBack = onBack,
        onImageCaptured = { bitmap ->
            if (isProcessing) return@CameraCaptureScreen
            isProcessing = true
            instructionText = processingInstruction
            scope.launch {
                runCatching {
                    withContext(Dispatchers.Default) {
                        SheetSampleProcessor.process(
                            bitmap,
                            debugContext = context.applicationContext.takeIf { BuildConfig.DEBUG },
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
                        instructionText = saveFailedInstruction
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
                    instructionText = processFailedInstruction
                    isProcessing = false
                }
            }
        },
        onCaptureError = { throwable ->
            android.util.Log.e("PremiumCapture", "Camera capture failed", throwable)
            instructionText = cameraFailedInstruction
            isProcessing = false
        },
    )
}

private fun createDefaultTemplateName(context: Context): String {
    // Locale from the context, not Locale.getDefault(), so the in-app language override applies.
    val locale = ConfigurationCompat.getLocales(context.resources.configuration)[0]
        ?: Locale.getDefault()
    val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
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
    Privacy,
    Settings,
}
