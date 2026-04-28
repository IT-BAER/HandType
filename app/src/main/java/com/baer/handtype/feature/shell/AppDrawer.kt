package com.baer.handtype.feature.shell

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.baer.handtype.BuildConfig
import com.baer.handtype.R

private val PaperBg = Color(0xFFF5EFE4)
private val Ink = Color(0xFF1A1410)
private val Teal = Color(0xFF23443A)

enum class DrawerDestination(val titleResId: Int) {
    Templates(R.string.drawer_nav_templates),
    History(R.string.drawer_nav_history),
    Help(R.string.drawer_nav_help),
    Faq(R.string.drawer_nav_faq),
    About(R.string.drawer_nav_about),
    Privacy(R.string.drawer_nav_privacy),
}

@Composable
fun AppDrawerContent(
    current: DrawerDestination,
    onSelect: (DrawerDestination) -> Unit,
) {
    ModalDrawerSheet(
        drawerContainerColor = PaperBg,
    ) {
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
                Text("HandType", color = Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    color = Ink.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        DrawerDestination.entries.forEach { dest ->
            NavigationDrawerItem(
                label = { Text(stringResource(dest.titleResId)) },
                selected = dest == current,
                onClick = { onSelect(dest) },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                colors = NavigationDrawerItemDefaults.colors(
                    selectedContainerColor = Teal.copy(alpha = 0.18f),
                    unselectedContainerColor = Color.Transparent,
                    selectedTextColor = Teal,
                    unselectedTextColor = Ink,
                ),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}
