# Feature Spec: History (Verlauf)

The History screen is HandType's on-device archive of generated handwriting notes. Every note the app produces is auto-archived to private internal storage; History is the read-only browser over that store. Users open a note in a detail modal to save it to the gallery, share it, or delete it, and can long-press to bulk-delete. There is no cloud, no sync, no account: the archive is the contents of one folder on the device.

Source of truth: `app/src/main/java/com/baer/handtype/feature/template/HistoryScreen.kt` (the entire subsystem: data model, store, grid, thumbnail, detail dialog). The producer is `app/src/main/java/com/baer/handtype/template/OutputExporter.kt` (`archiveBitmap`).

Screens:
- Grid: [../../artifacts/ux/17_history.png](../../artifacts/ux/17_history.png)
- Detail modal: [../../artifacts/ux/18_history_detail.png](../../artifacts/ux/18_history_detail.png)
- Empty state: [../../artifacts/ux/25_history_empty.png](../../artifacts/ux/25_history_empty.png)

---

## 1. Data model and storage

### 1.1 On-disk format

Notes live in app-private internal storage at `filesDir/history/` (not the gallery, not user-visible through a file manager without root). Each note is one PNG plus optional sidecars:

| File | Required | Content |
|------|----------|---------|
| `<stamp>__<tag>.png` | yes | The rendered note bitmap, PNG quality 100. |
| `<stamp>__<tag>.txt` | optional | The source text the user typed (written only when non-blank). |
| `<stamp>__<tag>.name` | always written by producer | The human-readable template display name. |

Naming rules (must match between producer and reader):
- Separator is a double underscore `__`. The reader splits on the first occurrence (`name.indexOf("__")`), so a tag may itself contain underscores but not `__` at the boundary.
- `<stamp>` is `yyyyMMdd_HHmmss` formatted with `Locale.US`.
- `<tag>` is sanitized by `OutputExporter.safeTag`: lowercased (Locale.US), every run of non `[a-z0-9]` collapsed to `_`, leading/trailing `_` trimmed, empty result becomes `"note"`.
- `id` is the file name without extension (e.g. `20260614_153012__classic_script`). It is the stable grid key and the basis for the sidecar paths on delete.

### 1.2 The producer (`OutputExporter.archiveBitmap`)

```
archiveBitmap(context, bitmap, templateName, sourceText: String? = null): Result<File>
```

It creates `filesDir/history/` (`mkdirs()`), computes `stamp` and `tag = safeTag(templateName)`, writes the PNG, writes the `.txt` only if `sourceText` is non-blank, and **always** writes the `.name` sidecar with the raw `templateName`. History never calls this; it is invoked from the output/result flow after a note is generated.

### 1.3 The reader (`HistoryStore`, an `object`)

- `listEntries(filesDir): List<HistoryEntry>` reads `filesDir/history/`, keeps only `*.png`, maps each through `parse`, sorts `sortedByDescending { it.createdAtMillis }` (newest first). A missing directory returns `emptyList()`.
- `parse(png): HistoryEntry?`
  - Returns `null` (entry silently skipped via `mapNotNull`) when `sep <= 0`, i.e. no `__` or the name starts with `__`.
  - `createdAtMillis` = `PARSER.parse(stampStr)?.time` (format `yyyyMMdd_HHmmss`, Locale.US); on parse failure falls back to `png.lastModified()`.
  - `sourceText` = contents of `<name>.txt` if that file exists, else `null`.
  - `templateDisplayName` = trimmed contents of `<name>.name` if present, else derived from the tag: `_` to space and first character uppercased (e.g. `classic_script` to `Classic script`).
- `delete(entry)` deletes the PNG plus `<id>.txt` and `<id>.name` if they exist.
- `delete(entries: Collection<HistoryEntry>)` iterates the single-entry delete.

### 1.4 `HistoryEntry` (data class)

| Field | Type | Notes |
|-------|------|-------|
| `id` | `String` | File name without extension; grid key. |
| `pngFile` | `File` | The PNG on disk. |
| `templateTag` | `String` | Sanitized tag, passed to save/share for filenames. |
| `templateDisplayName` | `String` | Title shown in card and modal. |
| `createdAtMillis` | `Long` | Sort key and date source. |
| `sourceText` | `String?` | Typed text, shown in modal when non-blank. |

`displayDate()` formats `createdAtMillis` as `"MMM d, HH:mm"` using `Locale.getDefault()` (device locale), so the displayed date is localized while the stored stamp parser is fixed `Locale.US`. This split is intentional.

---

## 2. Screen structure

`HistoryScreen(onOpenDrawer: () -> Unit, modifier: Modifier = Modifier)` is the public entry point. It owns all screen state and both dialogs.

State held in the composable:
- `entries: SnapshotStateList<HistoryEntry>` filled by `reload()` (clear, then `addAll(HistoryStore.listEntries(context.filesDir))`).
- `selected: HistoryEntry?` drives the detail modal.
- `selectedIds: SnapshotStateList<String>` drives multi-select; `selectionMode = selectedIds.isNotEmpty()`.
- `pendingDelete: List<HistoryEntry>?` drives the delete-confirm dialog (one list, used for both single and bulk deletes).

Loading: `LaunchedEffect(Unit) { reload() }` runs once on first composition, and `reload()` also runs after a confirmed delete. There is no pull-to-refresh and no observation of the `history/` directory (see Known UX gaps).

Layout: a `Scaffold` with `containerColor = Color(0xFFF5EFE4)` (brand cream). Inside, a `Column` holds a custom header `Row`, then an `AnimatedContent` keyed on `entries.isEmpty()` that crossfades between the empty state and the grid (fade in 300ms / fade out 200ms).

### 2.1 Header (custom Row, not a TopAppBar)

- A hamburger `IconButton` whose content is a raw Unicode glyph `"☰"` (`☰`) drawn as `Text`, color `Color(0xFF1A1410)`, style `titleLarge`, calling `onOpenDrawer`.
- Title `Text`, style `headlineMedium`. Normal mode shows `R.string.history_title` ("History"); in selection mode it becomes `"History (<count>)"`.
- A `Spacer(weight 1f)` then an `AnimatedVisibility` (slide+fade in 180ms / out 140ms) holding the selection toolbar: a `Cancel` `TextButton` (`clearSelection()`) and a `Delete` `TextButton` (sets `pendingDelete = selectedEntries().takeIf { it.isNotEmpty() }`).

This is a hand-rolled bar with manual padding, not an M3 `TopAppBar`, so it does not get standard insets, ripple, or 48dp targets from the glyph.

---

## 3. Empty state

Screenshot: [../../artifacts/ux/25_history_empty.png](../../artifacts/ux/25_history_empty.png)

When `entries.isEmpty()`, the content branch renders a single centered `Text` (`R.string.history_empty` = "No notes yet. Generate one and it will appear here."), style `bodyLarge`, color `MaterialTheme.colorScheme.onSurfaceVariant`, inside a `Box` with 32.dp padding and `Alignment.Center`. No icon, no illustration, no CTA. Because there is no app-level `colorScheme`, `onSurfaceVariant` resolves to the M3 baseline grey/purple-tinted palette.

---

## 4. Grid

Screenshot: [../../artifacts/ux/17_history.png](../../artifacts/ux/17_history.png)

`LazyVerticalGrid(columns = GridCells.Fixed(2))`, `contentPadding = PaddingValues(16.dp)`, both arrangements `spacedBy(12.dp)`. Items via `itemsIndexed(entries, key = { _, entry -> entry.id })`, each wrapped in `staggeredItemAnimation(index)` (delay `index * 35L` ms, then `tween(250, FastOutSlowInEasing)`) and rendered as a `HistoryThumbnail`.

### 4.1 `HistoryThumbnail` (private)

- Decodes the PNG once per `entry.id` via `BitmapFactory.decodeFile(...)` inside `remember(entry.id)`. This is synchronous, full-size (no `inSampleSize`, no Coil/async loader), and runs on composition.
- Card: `RoundedCornerShape(18.dp)`, `cardElevation(defaultElevation = 3.dp)`, `containerColor = Color.White`. When selected, a `BorderStroke(2.dp, Color(0xFF23443A))` (brand green).
- Image: `fillMaxWidth().aspectRatio(1f)` over a `Color(0xFFFFF9F2)` background, `contentScale = ContentScale.Crop`. The crop is centered, so a wide handwriting strip shows its middle, not its opening words.
- Decode failure shows a plain `Box` with background `Color(0xFFEEE)`. Note this literal is 3 hex digits after the alpha byte, so it resolves to `0x000FFEEE` (near-transparent), not the intended light grey. Treat as a known defect.
- Below the image (`padding(10.dp)`): `templateDisplayName` (`labelLarge`, single line, ellipsis) and `displayDate()` (`bodySmall`, `onSurfaceVariant`).
- Content description: `R.string.history_note_desc` ("Saved note") + `" <id>"`.

### 4.2 Click and selection behavior (`combinedClickable`)

| Gesture | Normal mode | Selection mode |
|---------|-------------|----------------|
| Tap | `selected = entry` (opens detail modal) | `toggleSelection(entry)` |
| Long-press | `toggleSelection(entry)` (enters selection mode) | `toggleSelection(entry)` |

In selection mode each thumbnail also shows a top-end `Checkbox` on a `Color.White.copy(alpha = 0.92f)` `CircleShape`, 8.dp padded, toggling selection. Selection mode is entered only by long-press; there is no other affordance.

---

## 5. Detail modal

Screenshot: [../../artifacts/ux/18_history_detail.png](../../artifacts/ux/18_history_detail.png)

`HistoryDetailDialog(entry, onDismiss, onDeleteRequested)` is an M3 `AlertDialog`, opened when `selected != null`.

- Decodes the full bitmap again via `remember(entry.id) { BitmapFactory.decodeFile(...) }` (a second full-size decode, independent of the thumbnail's).
- Title: `entry.templateDisplayName`.
- Body (`Column`, `spacedBy(10.dp)`):
  1. Preview `Image` (only if bitmap decoded), `fillMaxWidth()`, background `Color(0xFFFFF9F2)` rounded 12.dp, 8.dp padding, `contentScale = ContentScale.FillWidth` (so the modal does show the whole note, unlike the cropped thumbnail). Content description `R.string.history_preview_desc` ("Note preview").
  2. `displayDate()` (`bodySmall`, `onSurfaceVariant`).
  3. `sourceText`, only when non-blank: `bodySmall`, `maxLines = 4`, ellipsis.
  4. Action row (`spacedBy(8.dp)`): two filled `Button`s, each `weight(1f)`:
     - Save: `containerColor = Color(0xFF23443A)` (brand green), label `R.string.action_save`.
     - Share: `containerColor = Color(0xFF7C4D11)` (brand brown), label `R.string.action_share`.
  5. Full-width `OutlinedButton` Delete (`R.string.action_delete`) calling `onDeleteRequested`. It uses default M3 button colors (no custom `colors`), so its outline and text leak the M3 baseline purple.
- `confirmButton`: a `TextButton` Close (`R.string.action_close`) calling `onDismiss`, also default purple text.

So the modal stacks four actions in three palettes (green, brown, purple) with flat hierarchy and no single primary.

### 5.1 Save

`saveImage()`:
- No-op if `bitmap == null`.
- On `SDK_INT < Q` (Android 9 and below): checks `WRITE_EXTERNAL_STORAGE`; if not granted, sets `pendingSave = true` and launches `RequestPermission()`. On grant the callback calls `saveNow`; on denial it toasts `R.string.result_permission_denied`.
- On Q+ no runtime permission is requested.
- `saveNow` calls `OutputExporter.saveImageToGallery(context, bitmap, templateTag)`, which writes a PNG (quality 100) to `Pictures/HandType` (album constant `HandType`, MIME `image/png`). Success toasts `"Saved to Pictures/HandType"`; failure toasts `"Save failed: <msg>"`.

### 5.2 Share

`share()`: no-op if `bitmap == null`; otherwise `OutputExporter.stageBitmapForShare(...)` writes to `cacheDir/shared` and returns a FileProvider URI (`<packageName>.fileprovider`), then `context.startActivity(OutputExporter.buildShareIntent(uri))` (an `ACTION_SEND`, chooser title literal "Share handwritten note"). Failure toasts `"Share failed: <msg>"`.

### 5.3 Delete (from modal)

The Delete `OutlinedButton` calls `onDeleteRequested`, which sets `pendingDelete = listOf(entry)` and clears `selected` (closing the modal first), routing into the shared delete-confirm dialog.

---

## 6. Delete confirmation (single and bulk)

Both single-note (from the modal) and bulk (from the selection toolbar) deletes route through `pendingDelete: List<HistoryEntry>?` and the same `AlertDialog`. `deleteCount = pendingEntries.size` switches the copy:

| | Single (`count == 1`) | Bulk (`count > 1`) |
|--|----------------------|--------------------|
| Title | `history_delete_title` = "Delete this note?" | `history_delete_selected_title` = "Delete %1$d notes?" |
| Body | `history_delete_body` ("This removes the saved copy from your device. Files you've already exported to Pictures or Downloads stay where they are.") | `history_delete_selected_body` (same, plural with count) |
| Toast on confirm | `history_deleted_toast` = "Deleted" | `history_deleted_selected_toast` = "Deleted %1$d notes" |

Confirm (`TextButton`, default purple): `HistoryStore.delete(pendingEntries)`, then `pendingDelete = null`, `clearSelection()`, `reload()`, then the toast. Dismiss (`TextButton`): `pendingDelete = null`. The body copy correctly clarifies that copies already exported to the gallery are untouched: delete removes only the private archive copy and its sidecars.

---

## 7. Colors, theming, localization

- Hardcoded brand literals: cream surface `Color(0xFFF5EFE4)`, card white `Color.White`, image backgrounds `Color(0xFFFFF9F2)`, selected border / Save button green `Color(0xFF23443A)`, Share button brown `Color(0xFF7C4D11)`, hamburger ink `Color(0xFF1A1410)`, decode-fail placeholder `Color(0xFFEEE)` (defective, see 4.1).
- Everything routed through `MaterialTheme` (Checkbox, `OutlinedButton`, all `TextButton`s, `onSurfaceVariant` text) inherits the M3 baseline because `MainActivity` uses a bare `MaterialTheme { ... }` with no `colorScheme`. The result is baseline purple on the modal's Delete/Close and the delete-confirm buttons, clashing with the brand green/cream/brown.
- Localized strings (10 locales): `history_title`, `history_empty`, `history_delete_*`, `history_deleted_*`, `history_note_desc`, `history_preview_desc`, plus shared `action_cancel`, `action_delete`, `action_save`, `action_share`, `action_close`, `result_permission_denied`.
- Not localized (hardcoded English literals): `"Saved to Pictures/HandType"`, `"Save failed: <msg>"`, `"Share failed: <msg>"`. All `Toast.LENGTH_SHORT`.

---

## 8. Performance note

Each note is decoded full-size and synchronously via `BitmapFactory.decodeFile` twice over its lifetime on this screen (once in the thumbnail, once in the detail modal), with no downsampling and no async image library. A large archive or large PNGs can cause memory pressure and scroll jank. Not measured here; flagged as a real risk.

---

## Known UX gaps

From the history UX findings (`artifacts/wf/ux/history.md`), referencing the three screenshots above:

- **H1 (P0, empty state).** Empty History is one line of grey `bodyLarge` text centered on blank cream, no icon, no illustration, no CTA ([25_history_empty.png](../../artifacts/ux/25_history_empty.png), `HistoryScreen.kt` lines 232-244). It reads as a dead end. Fix: a real M3 empty state with an icon/illustration, a `titleMedium` headline, `bodyMedium` supporting text, and a filled brand-green "Create note" button routing to the editor.
- **H2 (P0, color consistency).** The detail modal shows four actions in three palettes (green Save, brown Share, purple-outlined Delete, purple Close text button), with the purple leaking the unset M3 baseline ([18_history_detail.png](../../artifacts/ux/18_history_detail.png), Delete at line 500, Close at line 507). Looks like two visual identities. Fix: set a branded `MaterialTheme(colorScheme = ...)` at the app root; local stopgap is passing `colors` to the `OutlinedButton` (destructive red `Color(0xFFAA3333)`) and the Close `TextButton`.
- **H3 (P0, thumbnail crop).** `ContentScale.Crop` + `aspectRatio(1f)` center-crops the note, hiding its opening words ([17_history.png](../../artifacts/ux/17_history.png), lines 365-369). The first words are a note's identity. Fix: anchor the crop top-left (top-aligned crop, or `FillWidth` in a fixed-height clipped box).
- **H4 (P1, layout/density).** `GridCells.Fixed(2)` (line 247) leaves a single note as a tiny half-width card top-left with a vast empty screen below, reading as broken. Fix: `GridCells.Adaptive(minSize = 160.dp)`.
- **H5 (P1, action overload).** The modal stacks Save, Share, Delete, Close (lines 485-508) with flat hierarchy and no single primary, and the destructive Delete sits beside positive actions (mis-tap risk). Fix: one primary + a dismiss, demote Delete (overflow/icon or below a divider), and consider a `ModalBottomSheet` for the image-led detail.
- **H6 (P1, missing capability).** No rename, search, or sort, even though the data layer already reads a `.name` sidecar (`HistoryStore.parse`, line 114) so rename is half-built but unsurfaced. Past a handful of notes the list becomes unusable and titles are stuck on the auto-derived name. Fix: a rename action in the modal that writes the `.name` sidecar, plus search/sort in a real app bar.
- **H7 (P1, app bar/branding).** The header is a hand-rolled `Row` with the hamburger as a `☰` text glyph (lines 181-202) and `headlineMedium` title, not an M3 `TopAppBar` with an `Icon`. Costs the 48dp target, ripple, insets, scroll behavior, and theming. Fix: `TopAppBar` (or `LargeTopAppBar` for this content-light archive) with `Icons.Default.Menu`.
- **H8 (P2, selection discoverability).** Long-press to enter multi-select is undiscoverable; nothing hints at batch delete. Fix: surface selection via a card overflow or an app bar "Select" action, keeping long-press as an accelerator.
- **H9 (P2, destructive confirmation styling).** The delete-confirm `AlertDialog` confirm button is a plain `TextButton` (line 313) rendering purple, not red, so the destructive action does not read as destructive and has the same weight as Cancel. Fix: red `contentColor` (`Color(0xFFAA3333)` or `colorScheme.error` once a theme exists).
- **H10 (P2, edge-to-edge/insets).** The hand-rolled `Row` bar uses manual padding and ignores M3 window insets, so top spacing relative to the system bars is ad hoc across devices. Folds into H7: adopting `TopAppBar` fixes insets automatically.

Additional engineering gaps worth tracking alongside the UX list:
- **No live refresh.** `reload()` runs only on first composition (`LaunchedEffect(Unit)`) and after a delete. A note archived while this screen is already composed will not appear until it is recomposed/revisited; whether enum-based navigation re-triggers `LaunchedEffect(Unit)` is outside this file and unverified.
- **Decode-fail placeholder color is wrong.** `Color(0xFFEEE)` (line 376) is near-transparent, not the intended light grey. Use a full 8-digit value, e.g. `Color(0xFFEEEEEE)`.
- **Full-size synchronous decodes** in both thumbnail and modal (see section 8) should move to a downsampling async loader before the archive grows large.
- **Malformed files are silently skipped.** Files without `__` are dropped by `parse` with no surfaced error; acceptable for now, but there is no recovery path for a corrupted name.
