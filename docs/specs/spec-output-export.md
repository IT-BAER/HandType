# Feature spec: Result / Output screen and export paths

Status: as-built (describes current behavior in `main`), with known UX gaps called out at the end.

The Result screen is the final phase of the authoring loop (pick template, type text, pick paper, generate). It shows the rendered handwriting bitmap and offers six actions: Save (PNG), Share, Export as PDF, Save without background (transparent PNG), Regenerate, and New Note. This document specifies each action, what it produces, where the file lands, and how the result is confirmed.

Reference screenshot: ![Result screen](../../artifacts/ux/15_output.png)

## Source of truth

| Concern | File |
|---|---|
| Result UI + action handlers | `app/src/main/java/com/baer/handtype/feature/template/TemplateScreens.kt` (`ResultPhaseContent`, around L762-1035; transparent re-render at L542-578) |
| Save / Share / PDF / transparent / archive | `app/src/main/java/com/baer/handtype/template/OutputExporter.kt` |
| Renderer (produces the bitmap, also re-renders transparent) | `app/src/main/java/com/baer/handtype/template/HandwritingBitmapRenderer.kt` |
| Paper presets incl. `transparentPreset` | `app/src/main/java/com/baer/handtype/template/NoteBackgrounds.kt` |
| Strings | `app/src/main/res/values/strings.xml` (L45-60, L191) |

There is no ViewModel. State (`phase`, `text`, `resultBitmap`, `missingChars`, paper IDs, `lastRenderedText`, `lastRenderSeed`) lives in `remember` / `rememberSaveable` inside `HandwritingRenderScreen`. `resultBitmap` and `missingChars` are plain `remember`, so they are lost on process death and a recreate would force a re-generate.

## How the screen is reached and laid out

`HandwritingRenderScreen` holds a three-value phase state machine (`RenderPhase.Compose`, `Generating`, `Result`) and switches between phase composables with `AnimatedContent`. Generate renders the bitmap on `Dispatchers.Default`, stores `resultBitmap` / `missingCharacters` / `lastRenderedText` / `lastRenderSeed`, switches to `Result`, and best-effort archives a private history copy (see Archive below).

`ResultPhaseContent` is a single vertically scrolling `Column` (16dp horizontal, 12dp vertical padding, 14dp item spacing):

1. Header `Row` (not a real top app bar): back arrow `IconButton` (`onBackToCompose`), centered title `render_result_title` ("Your Note"), trailing `TextButton` `render_templates_button` (`onBackToTemplates`).
2. Result `Card` (white, 20dp corners, 4dp elevation) holding the `Image`. The image plays a 750ms linear ink-reveal (`stripReveal`), is pinch-zoomable (scale clamped `1f..6f`), pans only when zoomed in, and double-tap resets to `1f` / zero offset. Test tag `RENDER_RESULT_IMAGE`. There is no on-screen affordance signaling zoom.
3. Missing-glyph notice (only if `missingCharacters` is non-empty): `render_missing_glyphs` = "Missing glyphs: %1$s" with the missing chars space-joined.
4. Action region (the six actions below).
5. Tip `Card` (`0xFFEEE8DB`) showing `result_tip_share` ("Tip: Use the Share or PDF button to save your note to another app.").

All actions are disabled while `bitmap == null`.

## Filenames, sanitization, and shared constants

Defined in `OutputExporter`:

- Album / subfolder constant: `HandType`.
- Save filename pattern: `HandType_<safeTag>_<yyyyMMdd_HHmmss>.<ext>`, timestamp in `Locale.US`.
- `safeTag`: template display name lowercased, every non-`[a-z0-9]` run collapsed to `_`, trimmed of leading/trailing `_`; empty result becomes `note`.
- MIME types: `image/png` for PNG, `application/pdf` for PDF.
- API split: API 29+ (`Build.VERSION_CODES.Q`) uses MediaStore with `IS_PENDING`; API 23-28 writes a file directly and requires `WRITE_EXTERNAL_STORAGE`.

Save / transparent / PDF functions return `Result<Uri>`; `archiveBitmap` returns `Result<File>`. The screen reads `.onSuccess` / `.onFailure` and shows a `Toast` either way. No success state persists in the UI: after a save the screen is unchanged except for the transient toast.

## The six actions

### 1. Save (PNG)

- Control: filled `Button`, brand green `Color(0xFF23443A)`, left half of the first action row. Label `result_save_button` ("Save").
- Handler: `saveWithPermission(false)` performs a `LongPress` haptic, then on API < 29 checks/requests `WRITE_EXTERNAL_STORAGE` (denial toasts `result_permission_denied`). On grant or API 29+ it calls `doSave(false)`.
- `doSave(false)` calls `OutputExporter.saveImageToGallery(context, bitmap, templateName)`.
- Output: opaque PNG of the rendered bitmap as-is (whatever paper background was chosen), `Bitmap.CompressFormat.PNG`, quality 100.
- Destination: `Pictures/HandType` (MediaStore `EXTERNAL_CONTENT_URI` with `RELATIVE_PATH = Pictures/HandType` on API 29+; `Environment.DIRECTORY_PICTURES/HandType` direct file on 23-28). Visible in the Photos / gallery app.
- Confirmation: toast `result_saved` ("Saved to Pictures/HandType") on success; `result_save_failed` ("Save failed: %1$s") on failure.

### 2. Share

- Control: filled `Button`, brand brown `Color(0xFF7C4D11)`, right half of the first action row. Label `result_share_button` ("Share").
- Handler: `shareImage()` performs a `LongPress` haptic, then `OutputExporter.stageBitmapForShare` followed by `OutputExporter.buildShareIntent`, launched via `startActivity`.
- Output: a PNG copy of the current bitmap written to `cacheDir/shared/HandType_<safeTag>_<timestamp>.png` (quality 100), exposed through `FileProvider` authority `<packageName>.fileprovider` with `FLAG_GRANT_READ_URI_PERMISSION`.
- Intent: `ACTION_SEND`, type `image/png`, wrapped in `Intent.createChooser`. Chooser title is the hardcoded literal `"Share handwritten note"` (not localized).
- Confirmation: none on success (the system share sheet is the feedback). Failure toasts `result_share_failed` ("Share failed: %1$s").
- Note: the staged file lives in cache and is not cleaned up by this code; it relies on cache eviction.

### 3. Export as PDF

- Control: full-width `OutlinedButton` (renders with leaked default M3 purple outline/text; see gaps). Label `result_export_pdf_button` ("Export as PDF").
- Handler: `exportPdf()` performs a `LongPress` haptic, then `OutputExporter.exportPdf(context, bitmap, templateName)`.
- Output: A4 portrait PDF, 595x842 pt @72dpi, 32 pt margin. The bitmap is scaled to the available width (`595 - 64 = 531` pt). Tall notes are sliced across multiple pages: `computePageCuts` snaps each page break to the brightest (most paper-empty) row inside a search window of 12% of the slice height, so cuts avoid running through a line of handwriting. The chosen paper background is included as-is (no key-out).
- Destination: `Download/HandType` (MediaStore `Downloads` with `RELATIVE_PATH = Download/HandType` on API 29+); on older devices it writes to `cacheDir/shared` and returns a `FileProvider` URI.
- Confirmation: toast `result_pdf_saved` ("PDF saved to Download/HandType") on success; `result_pdf_failed` ("PDF failed: %1$s") on failure.

### 4. Save without background (transparent PNG)

- Control: borderless `TextButton`, centered (renders purple; styled like a hyperlink despite performing a gallery write). Label `result_save_transparent_button` ("Save without background (transparent PNG)").
- Handler: `saveWithPermission(true)` -> `doSave(true)` -> the `onSaveTransparent` callback in `HandwritingRenderScreen`. Same `LongPress` haptic and same API < 29 permission gate as Save.
- Implementation detail (important): this path does NOT call `OutputExporter.saveTransparentImageToGallery`. Instead it re-renders the note via `renderNote(...)` using `lastRenderedText` and `lastRenderSeed` with `backgroundId = NoteBackgroundCatalog.transparentPreset.id` (the transparent preset's style draws nothing), producing a genuinely transparent bitmap, then saves that with `saveImageToGallery` and a `_transparent` display-name suffix. The temp bitmap is recycled in a `finally`. Reusing the original seed means the transparent copy matches the visible result exactly. If `lastRenderSeed` is null or `lastRenderedText` is blank, the callback returns and nothing is saved.
- Output: transparent PNG (ink only, no paper), filename `HandType_<safeTag>_transparent_<timestamp>.png`, quality 100.
- Destination: `Pictures/HandType` (same path rules as Save).
- Confirmation: toast `result_saved_transparent` ("Saved transparent PNG to Pictures/HandType") on success; `result_save_failed` on failure.
- Dead-code note: `OutputExporter.saveTransparentImageToGallery` exists and keys out paper via `keyOutPaper` (per-pixel distance from a paper color, default cream `0xFFF8F1E4`, hard 30 / soft 110 in a 0..441 range). It is not used by this screen. The re-render approach avoids key-out halos that `keyOutPaper` would risk if the chosen paper color differed from the default cream.

### 5. Regenerate

- Control: `OutlinedButton`, left half of the secondary row. Label `result_regenerate_button` ("Regenerate").
- Handler: `LongPress` haptic, then `onRegenerate` = `generate()`. This re-runs the renderer with a fresh `System.nanoTime()` seed and the same input text, so the note is re-drawn with new organic variation (jitter, baseline wave, per-character variant choice for bundled templates). Text and paper selection are preserved; a fresh history copy is archived.
- Output / destination: none directly. It replaces `resultBitmap` in place and re-enters the Result phase.
- Confirmation: the new bitmap appears (with the ink-reveal animation). No toast.

### 6. New Note

- Control: `OutlinedButton`, right half of the secondary row. Label `result_new_note_button` ("New Note").
- Handler: `LongPress` haptic, then `onNewNote`, which clears `resultBitmap` and `missingChars`, resets `selectedBackgroundId` to `defaultBackgroundId`, clears `text`, and returns `phase` to `Compose`. This discards the current note.
- Output / destination: none. It is a destructive navigation back to an empty editor.
- Confirmation: none (the editor reappears).

Note: Regenerate and New Note are visually identical `OutlinedButton`s side by side, and their labels are near-twins in several locales (German "Neu erstellen" vs "Neue Notiz"), so the discard action (New Note) is hard to distinguish from the safe re-roll (Regenerate). See gaps O4.

## Archive (automatic, not a user action)

On every successful generate, `HandwritingRenderScreen` best-effort calls `OutputExporter.archiveBitmap` on `Dispatchers.IO`. It writes a private history copy to `filesDir/history/<yyyyMMdd_HHmmss>__<safeTag>.png`, plus a `.txt` sidecar with the source text (only if non-blank) and always a `.name` sidecar with the display name. This is what the History screen reads. Failures are swallowed; the user is not notified.

## Action / output / destination / confirmation summary

| Action | Control + color | Produces | Destination | Confirmation |
|---|---|---|---|---|
| Save (PNG) | Filled, green `0xFF23443A` | Opaque PNG (with paper) | `Pictures/HandType` | Toast "Saved to Pictures/HandType" |
| Share | Filled, brown `0xFF7C4D11` | PNG copy in cache via FileProvider | System share sheet (`ACTION_SEND`) | None (share sheet); toast on failure |
| Export as PDF | OutlinedButton (purple) | A4 PDF, multi-page smart slicing | `Download/HandType` | Toast "PDF saved to Download/HandType" |
| Save transparent PNG | TextButton (purple) | Transparent PNG (re-rendered, ink only) | `Pictures/HandType`, `_transparent` suffix | Toast "Saved transparent PNG to Pictures/HandType" |
| Regenerate | OutlinedButton (purple) | New bitmap, fresh seed, same text | In place (Result phase) | New image animates in |
| New Note | OutlinedButton (purple) | Clears note, resets paper, empties text | Editor (Compose phase) | Editor reappears |
| Archive (auto) | n/a | Private PNG + `.txt` + `.name` | `filesDir/history` | None (silent) |

## Error handling

Every save/share/PDF failure surfaces only a transient `Toast` with the formatted message string (`result_save_failed`, `result_share_failed`, `result_pdf_failed`). On API 23-28 a denied storage permission toasts `result_permission_denied`. There is no retry affordance, no error state on the screen, and no logging surfaced to the user.

## Known UX gaps

From the output UX findings (`artifacts/wf/ux/output.md`). Severities are the reviewer's (P0 highest).

- O1 (P0, hierarchy): Six actions in a flat hierarchy with no single clear primary. Two filled buttons (green Save, brown Share) compete as co-primaries; four more follow. Recommendation: pick one primary, collapse the export variants into one entry that opens an M3 `ModalBottomSheet` or use a bottom app bar + FAB.
- O2 (P0, theming): Three button colors on one screen (green filled, brown filled, purple outlined/link). The purple is leaked default M3 because `MainActivity` (~L60) wraps everything in a bare `MaterialTheme {}` with no `colorScheme`. Fix structurally with a brand `ColorScheme` (green->primary, brown->secondary/tertiary, cream->surface, red->error) so `OutlinedButton` / `TextButton` / links inherit brand color.
- O3 (P0, feedback): No visible success confirmation. Save, PDF, and transparent-save only fire a `Toast`; the screen does not change, so a first-time user may re-tap and create duplicates. Replace with an M3 `Snackbar` anchored to a `Scaffold`, with an "Open" action and the explicit save location; optionally morph the Save button to a confirmed state.
- O4 (P0, labeling): "Regenerate" (keeps text, new seed) vs "New Note" (clears text, back to compose) are near-identical labels styled as identical twin buttons, so users cannot predict which discards the note. Differentiate by label and leading icon, or move regenerate onto the preview card as an icon button.
- O5 (P1, redundancy): Save PNG, Share, Export PDF, and Save transparent PNG are four overlapping "get the output out" actions presented as four separate controls. Collapse into one "Share / Export" affordance that opens a bottom sheet listing PNG, transparent PNG, PDF, and the system share sheet.
- O6 (P1, discoverability): The preview supports pinch-zoom (1x-6x) and double-tap reset, but nothing signals it; it reads as a static image. Add a zoom caption/affordance or a one-time coach hint.
- O7 (P1, affordance): "Save without background" is a borderless `TextButton` styled like a hyperlink yet it writes a file to the gallery. Make it a proper low-emphasis button grouped with the other export options, or fold it into the export bottom sheet (O5).
- O8 (P1, structure): The header is a hand-rolled `Row` (back arrow + centered title + "Templates" text button), not a real `TopAppBar`, so there is no edge-to-edge inset handling or scroll behavior, and the "Templates" link competes with the back arrow for "leave this screen" meaning. Use an M3 `CenterAlignedTopAppBar` with proper `WindowInsets`.
- O9 (P2, content): The yellow tip card exists only to explain the confusing action set; it is a band-aid for O1/O5. Once actions are consolidated, drop it or repurpose it for a genuine tip (for example, "Tap the image to zoom").
- O10 (P2, expressive): All controls use static 18dp rounded rectangles with no pressed/selected shape morph beyond `pressScale`. Adopt M3 expressive shapes and shape-morph on the primary; use a tonal/elevated button for the primary to add depth.
