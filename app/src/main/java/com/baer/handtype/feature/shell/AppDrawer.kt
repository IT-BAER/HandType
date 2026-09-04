package com.baer.handtype.feature.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpCenter
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemColors
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.baer.handtype.R

enum class DrawerDestination(val titleResId: Int, val icon: ImageVector) {
    Templates(R.string.drawer_nav_templates, Icons.Default.Brush),
    History(R.string.drawer_nav_history, Icons.Default.History),
    Help(R.string.drawer_nav_help, Icons.AutoMirrored.Filled.HelpOutline),
    Faq(R.string.drawer_nav_faq, Icons.AutoMirrored.Filled.HelpCenter),
    About(R.string.drawer_nav_about, Icons.Default.Info),
    Privacy(R.string.drawer_nav_privacy, Icons.Default.Shield),
    Settings(R.string.drawer_nav_settings, Icons.Default.Settings),
}

@Composable
fun AppDrawerContent(
    current: DrawerDestination,
    onSelect: (DrawerDestination) -> Unit,
) {
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxHeight(),
    ) {
        Column(modifier = Modifier.fillMaxHeight()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "HandType",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        val drawerItemColors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            unselectedContainerColor = Color.Transparent,
            selectedTextColor = MaterialTheme.colorScheme.onSecondaryContainer,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = stringResource(R.string.drawer_section_create),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        listOf(DrawerDestination.Templates, DrawerDestination.History).forEachIndexed { index, dest ->
            DrawerItem(dest = dest, current = current, onSelect = onSelect, index = index, colors = drawerItemColors)
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

        Text(
            text = stringResource(R.string.drawer_section_info),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        listOf(
            DrawerDestination.Help,
            DrawerDestination.Faq,
            DrawerDestination.About,
            DrawerDestination.Privacy,
            DrawerDestination.Settings,
        ).forEachIndexed { offset, dest ->
            DrawerItem(dest = dest, current = current, onSelect = onSelect, index = offset + 2, colors = drawerItemColors)
        }

        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DrawerItem(
    dest: DrawerDestination,
    current: DrawerDestination,
    onSelect: (DrawerDestination) -> Unit,
    index: Int,
    colors: NavigationDrawerItemColors,
) {
    val anim = remember(dest) { Animatable(0f) }
    LaunchedEffect(dest) {
        delay(index * 30L)
        anim.animateTo(1f, tween(200, easing = FastOutSlowInEasing))
    }
    NavigationDrawerItem(
        label = { Text(stringResource(dest.titleResId)) },
        icon = { Icon(dest.icon, contentDescription = null) },
        selected = dest == current,
        onClick = { onSelect(dest) },
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .graphicsLayer { alpha = anim.value },
        colors = colors,
    )
}
