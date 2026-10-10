package com.baer.handtype.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.baer.handtype.R
import com.baer.handtype.testing.HandTypeTestTags
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSettingsChanged: (AppSettings) -> Unit,
    onOpenDrawer: () -> Unit,
    premiumUnlocked: Boolean,
    onUnlockPremium: () -> Unit,
    onRestorePremium: () -> Unit,
    onShareApp: () -> Unit,
    onRateApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = stringResource(R.string.cd_menu),
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PreferenceCard(
                title = stringResource(R.string.settings_appearance_title),
                body = stringResource(R.string.settings_appearance_body),
            ) {
                val themePreferences = enumValues<ThemePreference>()
                themePreferences.forEachIndexed { index: Int, preference: ThemePreference ->
                    PreferenceRow(
                        label = themePreferenceLabel(preference),
                        selected = settings.themePreference == preference,
                        onClick = {
                            onSettingsChanged(settings.copy(themePreference = preference))
                        },
                    )
                    if (index != themePreferences.lastIndex) {
                        HorizontalDivider()
                    }
                }
            }

            PreferenceCard(
                title = stringResource(R.string.settings_language_title),
                body = stringResource(R.string.settings_language_body),
            ) {
                val languagePreferences = enumValues<LanguagePreference>()
                languagePreferences.forEachIndexed { index: Int, preference: LanguagePreference ->
                    PreferenceRow(
                        label = languagePreferenceLabel(preference),
                        selected = settings.languagePreference == preference,
                        onClick = {
                            onSettingsChanged(settings.copy(languagePreference = preference))
                        },
                    )
                    if (index != languagePreferences.lastIndex) {
                        HorizontalDivider()
                    }
                }
            }
            PreferenceCard(
                title = stringResource(R.string.settings_premium_title),
                body = if (premiumUnlocked) {
                    stringResource(R.string.settings_premium_unlocked)
                } else {
                    stringResource(R.string.premium_paywall_benefit_capture)
                },
            ) {
                if (!premiumUnlocked) {
                    ActionRow(
                        icon = Icons.Default.WorkspacePremium,
                        label = stringResource(R.string.template_chooser_premium_button),
                        onClick = onUnlockPremium,
                    )
                    HorizontalDivider()
                }
                ActionRow(
                    icon = Icons.Default.Restore,
                    label = stringResource(R.string.premium_paywall_restore),
                    onClick = onRestorePremium,
                )
            }
            PreferenceCard(
                title = stringResource(R.string.settings_support_title),
                body = stringResource(R.string.settings_support_body),
            ) {
                ActionRow(
                    icon = Icons.Default.Share,
                    label = stringResource(R.string.settings_share_app),
                    onClick = onShareApp,
                    modifier = Modifier.testTag(HandTypeTestTags.SETTINGS_SHARE_APP),
                )
                HorizontalDivider()
                ActionRow(
                    icon = Icons.Default.Star,
                    label = stringResource(R.string.settings_rate_app),
                    onClick = onRateApp,
                    modifier = Modifier.testTag(HandTypeTestTags.SETTINGS_RATE_APP),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PreferenceCard(
    title: String,
    body: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun PreferenceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun themePreferenceLabel(preference: ThemePreference): String {
    return when (preference) {
        ThemePreference.System -> stringResource(R.string.settings_theme_system)
        ThemePreference.Light -> stringResource(R.string.settings_theme_light)
        ThemePreference.Dark -> stringResource(R.string.settings_theme_dark)
    }
}

@Composable
private fun languagePreferenceLabel(preference: LanguagePreference): String {
    val tag = preference.languageTag ?: return stringResource(R.string.settings_language_system)
    return nativeLanguageName(tag)
}

internal fun nativeLanguageName(tag: String): String {
    val locale = Locale.forLanguageTag(tag)
    return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
}
