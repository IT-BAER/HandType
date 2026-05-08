package com.baer.handtype.feature.template

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.baer.handtype.R
import com.baer.handtype.ui.animation.staggeredItemAnimation
import com.baer.handtype.template.OutputExporter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One archived handwriting note saved under [java.io.File.getFilesDir]/history/.
 * The PNG is `<stamp>__<tag>.png`; an optional sidecar `<stamp>__<tag>.txt`
 * holds the source text the user typed.
 */
data class HistoryEntry(
    val id: String,
    val pngFile: File,
    val templateTag: String,
    val createdAtMillis: Long,
    val sourceText: String?,
) {
    fun displayDate(): String =
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(createdAtMillis))
}

object HistoryStore {
    private val PARSER = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun listEntries(filesDir: File): List<HistoryEntry> {
        val dir = File(filesDir, "history")
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".png") }
            ?.mapNotNull { png -> parse(png) }
            ?.sortedByDescending { it.createdAtMillis }
            ?: emptyList()
    }

    private fun parse(png: File): HistoryEntry? {
        val name = png.nameWithoutExtension
        val sep = name.indexOf("__")
        if (sep <= 0) return null
        val stampStr = name.substring(0, sep)
        val tag = name.substring(sep + 2)
        val createdAt = runCatching { PARSER.parse(stampStr)?.time }.getOrNull() ?: png.lastModified()
        val txt = File(png.parentFile, "$name.txt").takeIf { it.isFile }?.readText()
        return HistoryEntry(
            id = name,
            pngFile = png,
            templateTag = tag,
            createdAtMillis = createdAt,
            sourceText = txt,
        )
    }

    fun delete(entry: HistoryEntry) {
        entry.pngFile.delete()
        File(entry.pngFile.parentFile, "${entry.id}.txt").takeIf { it.exists() }?.delete()
    }

    fun delete(entries: Collection<HistoryEntry>) {
        entries.forEach(::delete)
    }
}

@Composable
fun HistoryScreen(
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val entries: SnapshotStateList<HistoryEntry> = remember { mutableStateListOf() }

    fun reload() {
        entries.clear()
        entries.addAll(HistoryStore.listEntries(context.filesDir))
    }

    LaunchedEffect(Unit) { reload() }

    var selected by remember { mutableStateOf<HistoryEntry?>(null) }
    val selectedIds: SnapshotStateList<String> = remember { mutableStateListOf() }
    var pendingDelete by remember { mutableStateOf<List<HistoryEntry>?>(null) }

    fun toggleSelection(entry: HistoryEntry) {
        if (selectedIds.contains(entry.id)) {
            selectedIds.remove(entry.id)
        } else {
            selectedIds.add(entry.id)
        }
    }

    fun selectedEntries(): List<HistoryEntry> = entries.filter { selectedIds.contains(it.id) }

    fun clearSelection() {
        selectedIds.clear()
    }

    val selectionMode = selectedIds.isNotEmpty()

    Scaffold(
        modifier = modifier,
        containerColor = Color(0xFFF5EFE4),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.IconButton(onClick = onOpenDrawer) {
                    Text(
                        text = "\u2630",
                        color = Color(0xFF1A1410),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))
                Text(
                    text = if (!selectionMode) {
                        stringResource(R.string.history_title)
                    } else {
                        "${stringResource(R.string.history_title)} (${selectedIds.size})"
                    },
                    style = MaterialTheme.typography.headlineMedium,
                )
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                AnimatedVisibility(
                    visible = selectionMode,
                    enter = fadeIn(tween(180)) + slideInHorizontally(tween(180)) { it / 3 },
                    exit = fadeOut(tween(140)) + slideOutHorizontally(tween(140)) { it / 3 },
                ) {
                    Row {
                        TextButton(onClick = ::clearSelection) {
                            Text(stringResource(R.string.action_cancel))
                        }
                        TextButton(
                            onClick = {
                                pendingDelete = selectedEntries().takeIf { it.isNotEmpty() }
                            },
                        ) {
                            Text(stringResource(R.string.action_delete))
                        }
                    }
                }
            }

            AnimatedContent(
                targetState = entries.isEmpty(),
                transitionSpec = {
                    fadeIn(tween(300)).togetherWith(fadeOut(tween(200)))
                },
                label = "historyContent",
                modifier = Modifier.weight(1f),
            ) { isEmpty ->
                if (isEmpty) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.history_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
                            val isSelected = selectedIds.contains(entry.id)
                            staggeredItemAnimation(index = index) { animModifier ->
                                HistoryThumbnail(
                                    entry = entry,
                                    selectionMode = selectionMode,
                                    isSelected = isSelected,
                                    modifier = animModifier,
                                    onClick = {
                                        if (selectionMode) {
                                            toggleSelection(entry)
                                        } else {
                                            selected = entry
                                        }
                                    },
                                    onLongClick = { toggleSelection(entry) },
                                    onSelectionToggle = { toggleSelection(entry) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { entry ->
        HistoryDetailDialog(
            entry = entry,
            onDismiss = { selected = null },
            onDeleteRequested = {
                pendingDelete = listOf(entry)
                selected = null
            },
        )
    }

    pendingDelete?.let { pendingEntries ->
        val deleteCount = pendingEntries.size
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = {
                Text(
                    if (deleteCount == 1) {
                        stringResource(R.string.history_delete_title)
                    } else {
                        stringResource(R.string.history_delete_selected_title, deleteCount)
                    },
                )
            },
            text = {
                Text(
                    if (deleteCount == 1) {
                        stringResource(R.string.history_delete_body)
                    } else {
                        stringResource(R.string.history_delete_selected_body, deleteCount)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    HistoryStore.delete(pendingEntries)
                    pendingDelete = null
                    clearSelection()
                    reload()
                    val toastText = if (deleteCount == 1) {
                        context.getString(R.string.history_deleted_toast)
                    } else {
                        context.getString(R.string.history_deleted_selected_toast, deleteCount)
                    }
                    Toast.makeText(context, toastText, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun HistoryThumbnail(
    entry: HistoryEntry,
    selectionMode: Boolean,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelectionToggle: () -> Unit,
) {
    val bitmap = remember(entry.id) {
        runCatching { BitmapFactory.decodeFile(entry.pngFile.absolutePath) }.getOrNull()
    }
    Box(modifier = modifier.fillMaxWidth()) {
        Card(
            shape = RoundedCornerShape(18.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = if (isSelected) BorderStroke(2.dp, Color(0xFF23443A)) else null,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        ) {
            Column {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.history_note_desc) + " ${entry.id}",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .background(Color(0xFFFFF9F2)),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .background(Color(0xFFEEE)),
                    )
                }
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = entry.templateTag.replace('_', ' '),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = entry.displayDate(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (selectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color.White.copy(alpha = 0.92f), CircleShape),
            ) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onSelectionToggle() },
                )
            }
        }
    }
}

@Composable
private fun HistoryDetailDialog(
    entry: HistoryEntry,
    onDismiss: () -> Unit,
    onDeleteRequested: () -> Unit,
) {
    val context = LocalContext.current
    val bitmap: Bitmap? = remember(entry.id) {
        runCatching { BitmapFactory.decodeFile(entry.pngFile.absolutePath) }.getOrNull()
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    var pendingSave by remember { mutableStateOf(false) }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pendingSave) {
            saveNow(context, bitmap, entry.templateTag, ::toast)
        } else if (!granted) toast("Storage permission denied")
        pendingSave = false
    }

    fun saveImage() {
        if (bitmap == null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pendingSave = true
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
        }
        saveNow(context, bitmap, entry.templateTag, ::toast)
    }

    fun share() {
        val bmp = bitmap ?: return
        runCatching {
            val uri = OutputExporter.stageBitmapForShare(context, bmp, entry.templateTag)
            context.startActivity(OutputExporter.buildShareIntent(uri))
        }.onFailure { toast("Share failed: ${it.message ?: "unknown"}") }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.templateTag.replace('_', ' ')) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.history_preview_desc),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFFFF9F2), RoundedCornerShape(12.dp))
                            .padding(8.dp),
                        contentScale = ContentScale.FillWidth,
                    )
                }
                Text(
                    text = entry.displayDate(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!entry.sourceText.isNullOrBlank()) {
                    Text(
                        text = entry.sourceText,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Button(
                        onClick = ::saveImage,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF23443A)),
                    ) { Text(stringResource(R.string.action_save)) }
                    Button(
                        onClick = ::share,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4D11)),
                    ) { Text(stringResource(R.string.action_share)) }
                }
                OutlinedButton(
                    onClick = onDeleteRequested,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.action_delete)) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

private fun saveNow(
    context: android.content.Context,
    bitmap: Bitmap?,
    templateTag: String,
    toast: (String) -> Unit,
) {
    if (bitmap == null) return
    OutputExporter.saveImageToGallery(context, bitmap, templateTag)
        .onSuccess { toast("Saved to Pictures/HandType") }
        .onFailure { toast("Save failed: ${it.message ?: "unknown"}") }
}
