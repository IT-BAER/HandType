package com.baer.handtype.feature.template

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.text.format.DateFormat as AndroidDateFormat
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.os.ConfigurationCompat
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
 * holds the source text the user typed. An optional `<stamp>__<tag>.name` holds
 * the human-readable display name of the template.
 */
data class HistoryEntry(
    val id: String,
    val pngFile: File,
    val templateTag: String,
    val templateDisplayName: String,
    val createdAtMillis: Long,
    val sourceText: String?,
) {
    /** [locale] comes from the composition, so it follows the in-app language override. */
    fun displayDate(locale: Locale): String {
        val pattern = AndroidDateFormat.getBestDateTimePattern(locale, "MMMdHm")
        return SimpleDateFormat(pattern, locale).format(Date(createdAtMillis))
    }
}

/** App locale from the composition, so dates follow the in-app language override. */
@Composable
private fun currentLocale(): Locale =
    ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()

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
        val displayName = File(png.parentFile, "$name.name").takeIf { it.isFile }?.readText()?.trim()
            ?: tag.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
        return HistoryEntry(
            id = name,
            pngFile = png,
            templateTag = tag,
            templateDisplayName = displayName,
            createdAtMillis = createdAt,
            sourceText = txt,
        )
    }

    fun delete(entry: HistoryEntry) {
        entry.pngFile.delete()
        File(entry.pngFile.parentFile, "${entry.id}.txt").takeIf { it.exists() }?.delete()
        File(entry.pngFile.parentFile, "${entry.id}.name").takeIf { it.exists() }?.delete()
    }

    fun delete(entries: Collection<HistoryEntry>) {
        entries.forEach(::delete)
    }

    fun rename(entry: HistoryEntry, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return
        File(entry.pngFile.parentFile, "${entry.id}.name").writeText(trimmed)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onOpenDrawer: () -> Unit,
    onCreateNote: () -> Unit = {},
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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = stringResource(R.string.cd_menu),
                        )
                    }
                },
                title = {
                    Text(
                        text = if (!selectionMode) {
                            stringResource(R.string.history_title)
                        } else {
                            "${stringResource(R.string.history_title)} (${selectedIds.size})"
                        },
                    )
                },
                actions = {
                    if (selectionMode) {
                        IconButton(onClick = ::clearSelection) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.action_cancel),
                            )
                        }
                        IconButton(
                            onClick = {
                                pendingDelete = selectedEntries().takeIf { it.isNotEmpty() }
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            AnimatedContent(
                targetState = entries.isEmpty(),
                transitionSpec = {
                    fadeIn(tween(300)).togetherWith(fadeOut(tween(200)))
                },
                label = "historyContent",
                modifier = Modifier.weight(1f),
            ) { isEmpty ->
                if (isEmpty) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = Icons.Default.HistoryEdu,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(64.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.history_empty_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.history_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = onCreateNote) {
                            Text(stringResource(R.string.action_create_note))
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 160.dp),
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
            onRenamed = { reload() },
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
                }) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
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
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
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
                        contentDescription = stringResource(R.string.history_note_desc),
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .background(MaterialTheme.colorScheme.surface),
                        alignment = Alignment.TopStart,
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                }
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = entry.templateDisplayName,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = entry.displayDate(currentLocale()),
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
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), CircleShape),
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
    onRenamed: () -> Unit = {},
) {
    val context = LocalContext.current
    val bitmap: Bitmap? = remember(entry.id) {
        runCatching { BitmapFactory.decodeFile(entry.pngFile.absolutePath) }.getOrNull()
    }

    var showRename by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    var pendingSave by remember { mutableStateOf(false) }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pendingSave) {
            saveNow(context, bitmap, entry.templateTag, ::toast)
        } else if (!granted) toast(context.getString(R.string.result_permission_denied))
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
        }.onFailure {
            toast(context.getString(R.string.result_share_failed, it.message ?: "unknown"))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.templateDisplayName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.history_preview_desc),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                            .padding(8.dp),
                        contentScale = ContentScale.FillWidth,
                    )
                }
                Text(
                    text = entry.displayDate(currentLocale()),
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
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) { Text(stringResource(R.string.action_save)) }
                    FilledTonalButton(
                        onClick = ::share,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.action_share)) }
                }
                OutlinedButton(
                    onClick = { showRename = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_rename))
                }
                HorizontalDivider()
                TextButton(
                    onClick = onDeleteRequested,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )

    if (showRename) {
        var renameText by remember(entry.id) { mutableStateOf(entry.templateDisplayName) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(stringResource(R.string.history_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.history_rename_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    HistoryStore.rename(entry, renameText)
                    showRename = false
                    onRenamed()
                    onDismiss()
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

private fun saveNow(
    context: android.content.Context,
    bitmap: Bitmap?,
    templateTag: String,
    toast: (String) -> Unit,
) {
    if (bitmap == null) return
    OutputExporter.saveImageToGallery(context, bitmap, templateTag)
        .onSuccess { toast(context.getString(R.string.history_saved_toast)) }
        .onFailure { toast(context.getString(R.string.result_save_failed, it.message ?: "unknown")) }
}
