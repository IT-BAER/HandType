package com.baer.handtype.feature.info

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.baer.handtype.BuildConfig
import com.baer.handtype.R

private val PaperBg = Color(0xFFF5EFE4)
private val Ink = Color(0xFF1A1410)
private val Teal = Color(0xFF23443A)
private val Amber = Color(0xFF7C4D11)

const val GITHUB_REPO_URL = "https://github.com/IT-BAER/HandType"
const val GITHUB_PRIVACY_URL = "https://github.com/IT-BAER/HandType/blob/main/PRIVACY.md"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoScaffold(
    title: String,
    onOpenDrawer: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = PaperBg,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, color = Ink) },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Text("\u2630", color = Ink, style = MaterialTheme.typography.titleLarge)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = PaperBg,
                ),
            )
        },
    ) { padding -> content(padding) }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = Ink,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Body(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = Ink,
    )
}

@Composable
fun HelpScreen(onOpenDrawer: () -> Unit) {
    InfoScaffold(title = "Help", onOpenDrawer = onOpenDrawer) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            SectionTitle("1. Pick a template")
            Body("Open Templates from the side menu and swipe through the styles. Tap a card to start composing in that handwriting.")
            SectionTitle("2. Type your text")
            Body("Type or paste any text into the field. Line breaks and punctuation are preserved as you wrote them.")
            SectionTitle("3. Generate")
            Body("Tap Generate. HandType lays out your text glyph-by-glyph, varying spacing, rotation and pressure so each note looks naturally hand-written.")
            SectionTitle("4. Save, share or export")
            Body("From the result screen you can save the note as a PNG to your gallery, save without the paper background as a transparent PNG, share to any app, or export as a multi-page PDF.")
            SectionTitle("5. History")
            Body("Every generated note is automatically archived. Open History from the side menu to revisit, re-export, share or delete past notes.")
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun FaqScreen(onOpenDrawer: () -> Unit) {
    val items = remember {
        listOf(
            "Where are saved files stored?" to
                "PNGs and PDFs go to Pictures/HandType in your gallery. On Android 9 and older the app needs storage permission the first time.",
            "What is the transparent PNG for?" to
                "It's the same handwriting without the paper background, useful when you want to drop the note onto another image, document or slide.",
            "Can a long note span multiple PDF pages?" to
                "Yes. The PDF export paginates automatically and snaps page breaks to whitespace between text rows so no character gets clipped.",
            "Why does a glyph look slightly off?" to
                "Each character is composed from one of several stored variants and is then rotated, jittered and re-weighted to mimic real handwriting variation.",
            "What does the History screen actually keep?" to
                "A private copy of every generated note plus the source text. Nothing is uploaded; deleting an entry removes the on-device copy only.",
            "What is the premium feature?" to
                "Capturing your own handwriting from a photo to build a personal template. The capture itself runs on-device with ML Kit; nothing leaves the phone.",
        )
    }

    InfoScaffold(title = "FAQ", onOpenDrawer = onOpenDrawer) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items.forEachIndexed { i, (q, a) ->
                FaqItem(question = q, answer = a, initiallyOpen = i == 0)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FaqItem(question: String, answer: String, initiallyOpen: Boolean) {
    var open by rememberSaveable(question) { mutableStateOf(initiallyOpen) }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF9F2)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = !open },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = question,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (open) "\u25B4" else "\u25BE",
                    color = Teal,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            AnimatedVisibility(visible = open) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = answer,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink,
                    )
                }
            }
        }
    }
}

@Composable
fun AboutScreen(onOpenDrawer: () -> Unit) {
    val context = LocalContext.current
    InfoScaffold(title = "About", onOpenDrawer = onOpenDrawer) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
            Spacer(Modifier.height(12.dp))
            Text("HandType", style = MaterialTheme.typography.headlineSmall, color = Ink, fontWeight = FontWeight.Bold)
            Text(
                text = "Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
                color = Ink.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = "Turn typed text into handwriting that actually feels written. Built-in templates work fully offline; nothing about your text leaves your device.",
                style = MaterialTheme.typography.bodyLarge,
                color = Ink,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO_URL)))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("View source on GitHub") }
            Spacer(Modifier.height(24.dp))
            Text(
                text = "© 2025 IT-BAER. Made with care.",
                style = MaterialTheme.typography.bodySmall,
                color = Ink.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
