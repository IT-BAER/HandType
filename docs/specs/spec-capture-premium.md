# Spec: Premium Capture flow

Status: as-built description plus near-term direction. This documents the premium "capture your own handwriting" feature exactly as it exists in source today, then lists the known UX gaps to close.

Scope: the user journey from the capture intro screen, through getting and filling a printable practice sheet, photographing or importing it, on-device processing into a user template, and the permissions involved. Everything runs offline. No analytics, no network calls in this flow.

## 1. Overview

The premium flow turns a photographed practice sheet into a reusable handwriting template, fully on-device. The entry point is `CameraCaptureScreen`, hosted by `PremiumCaptureRoute` in MainActivity. Capture itself is delegated to the Google Play Services ML Kit Document Scanner activity (not an in-app CameraX preview). The returned bitmap is fed to `SheetSampleProcessor`, which detects four corner fiducials, perspective-corrects the page, crops each grid cell, cleans each glyph, and the resulting `Map<Char, Bitmap>` is persisted by `UserTemplateRepository.saveTemplate`.

Reference screenshots:
- ![Capture intro screen](../../artifacts/ux/23_capture_intro.png)
- ![ML Kit scanner / permission surface](../../artifacts/ux/24_capture_perm.png)

Key files:
- `app/src/main/java/com/baer/handtype/feature/capture/CameraCaptureScreen.kt` (intro UI + scanner launch)
- `app/src/main/java/com/baer/handtype/MainActivity.kt` (`PremiumCaptureRoute`, around line 280) (state, processing, save)
- `app/src/main/java/com/baer/handtype/feature/capture/PracticeSheetGenerator.kt` (printable sheet)
- `app/src/main/java/com/baer/handtype/feature/capture/SheetSampleProcessor.kt` (extraction pipeline)
- `app/src/main/java/com/baer/handtype/ml/GlyphPostProcessor.kt` (shared per-glyph cleanup)
- `app/src/main/java/com/baer/handtype/template/UserTemplateRepository.kt` (persistence)

## 2. Entry point and intro screen

`CameraCaptureScreen` is a single scrolling `Column` inside a bare `Scaffold` (container color `MaterialTheme.colorScheme.surface`). There is no `TopAppBar` and no labeled back affordance; exit relies on system/predictive back. Layout from source: outer padding 20.dp horizontal / 16.dp vertical, column spacing 16.dp.

The screen contents (top to bottom):

1. Intro `Card` (24.dp corners, 20.dp inner padding) containing:
   - Section title, `R.string.camera_section_title` = "Capture your handwriting sheet" (`headlineSmall`).
   - The `instructionText` parameter (`bodyMedium`, `onSurfaceVariant`). The route passes `R.string.premium_instruction` = "Write A-Z, a-z, and 0-9 on the practice sheet, then center the page inside the frame to create your personal template." This same text slot is reused to surface processing status and error messages (see Section 6). The composable default value is a hardcoded English literal and is never seen by end users because the route always overrides it.
   - Nested guide `Card` (18.dp corners, 14.dp padding, `secondaryContainer` at alpha 0.55) titled `R.string.camera_pre_scan_title` = "Before you scan", followed by four numbered `GuideStepRow`s:
     1. `camera_pre_scan_step_1` = "Print the practice sheet."
     2. `camera_pre_scan_step_2` = "Write the matching character in each box and keep it sitting on the guide line."
     3. `camera_pre_scan_step_3` = "Capture the whole page and keep all four black squares fully visible."
     4. `camera_pre_scan_step_4` = "Use even light and avoid blur, glare, and hard shadows."
   - Tip line, `camera_tip` = "Tip: use even lighting and keep all rows of letters visible." (`labelLarge`, colored `colorScheme.primary`).
   - Full-width `TextButton`, `camera_sheet_button` = "Get the practice sheet (PNG)".
2. Optional error `Card` (`errorContainer` / `onErrorContainer`, 16.dp corners), rendered only when `errorMessage != null`.
3. Fixed 24.dp `Spacer`.
4. Primary full-width `Button`: 72.dp tall, 22.dp corners, label `camera_scan_button` = "Scan or import practice sheet". Test tag `HandTypeTestTags.CAMERA_SCAN_BUTTON = "camera_scan_button"`. Disabled while `isLaunching || isProcessing`; in that state it shows an 18.dp / 2.dp-stroke `CircularProgressIndicator` plus either `camera_opening_scanner` = "Opening scanner..." or `camera_processing_button` = "Processing...".

Color note: every visible color in this screen comes from `MaterialTheme.colorScheme`. MainActivity wraps the app in a bare `MaterialTheme {}` with no `colorScheme` override, so the primary button, the tip line, and the sheet `TextButton` all render in the default M3 baseline purple, not the brand green (`Color(0xFF23443A)`) used as a hardcoded literal on CTAs elsewhere. This is the dominant brand-trust problem on the flow (see Section 9, CAP-01).

## 3. Get the practice sheet

The "Get the practice sheet (PNG)" `TextButton` calls a private `Context.shareTemplateSheet()` extension. Despite the "get/download/print" wording, the action is a share, not a download or print:

- It calls `PracticeSheetGenerator.renderToShareableUri(context)`, which renders the sheet bitmap, writes it as `handtype_practice_sheet.png` under `cacheDir/shared` (PNG quality 100), and returns a `FileProvider` URI (authority `${applicationId}.fileprovider`).
- It then fires `Intent.ACTION_SEND` (type `image/png`) wrapped in `Intent.createChooser`, with flags `FLAG_GRANT_READ_URI_PERMISSION` and `FLAG_ACTIVITY_NEW_TASK`.
- The chooser title ("Send practice sheet") and the subject extra ("HandType practice sheet") are hardcoded English literals, so a non-English device shows English in the chooser (see Section 9, CAP-02).
- If `renderToShareableUri` throws, the extension silently returns: no error card, no `onCaptureError` callback, no user feedback.

### Practice sheet geometry (`PracticeSheetGenerator`)

The sheet is an A4-at-150-dpi bitmap, `1240 x 1754` ARGB_8888 on a white background:
- Outer margin 60px. Four solid black square fiducials, side `FIDUCIAL_SIZE = 80`px, centered on the inner page corners (note: the class KDoc says 60x60, but the constant is 80; trust 80).
- A uniform 8x9 grid (`GRID_COLS = 8`, `GRID_ROWS = 9` = 72 cells). `cellCharacters` = A-Z + a-z + 0-9 (62 real characters), then the app language's letters (`localeCharacters`: German `ÄÖÜäöüß`, other languages none), then space placeholders to fill the grid. Spaces get a drawn cell border and guide lines but no label, and are skipped during extraction. The scan reads the row count from the printed borders (`SheetSampleProcessor.detectGridRows`), so 8x8 sheets printed before the language row still map correctly.
- Each cell shows a faint corner label (`LABEL_COLOR = 0xFFB7B0A3`, textSize 28, drawn at `left + 8`, `top + 32`) so it tells the user which character to write without biasing the stroke.
- Three centered guide ticks per cell at ratios `GUIDE_UPPER_RATIO = 0.32`, middle `0.55`, `GUIDE_BASELINE_RATIO = 0.78`. Segment length 26px, centered on the cell. Grid stroke 2px (`GRID_COLOR = 0xFFCCC4B4`), guide stroke 1.5px (`GUIDE_COLOR = 0xFFD8D1C3`).
- The fiducial-centre rectangle defines the canonical grid bounds; `normalizedCells` expresses each cell in 0..1 of that rectangle, with `gridInset = 0.06` so cells never overlap the printed corner squares.

## 4. Camera capture (scan) and gallery import

The primary button calls a local `launchScanner()`:

1. Guard: returns early if `isLaunching || isProcessing`.
2. Debug-only injection: `HandTypeDebugHooks.loadImportedScanBitmap()` (wrapped in `runCatching`). On DEBUG builds with `importedScanPath` set to an existing file, it decodes that file and immediately calls `onImageCaptured(bitmap)` without launching the scanner. This is the instrumentation-test path only; it returns null on release builds.
3. Resolve the host `Activity` via a tailrec `Context.findActivity()`. If none, call `onCaptureError(IllegalStateException("Cannot launch scanner: no Activity context"))` and return.
4. Set `isLaunching = true`, build `GmsDocumentScannerOptions`, get the client, and request `getStartScanIntent(activity)`. On success, launch the returned `IntentSender` through a `StartIntentSenderForResult` launcher. On failure, reset `isLaunching`, set `errorMessage`, and call `onCaptureError`.

Scanner options (from source):
- `setScannerMode(SCANNER_MODE_FULL)`
- `setPageLimit(1)` (only the first page is read via `pages.firstOrNull()`)
- `setGalleryImportAllowed(true)`
- `setResultFormats(RESULT_FORMAT_JPEG)`

There is no in-app camera preview, no manual/auto capture toggle, and no gallery picker owned by HandType. The "Manuell / Automatische Aufnahme" segmented toggle, the dark viewfinder, the crop/perspective UI, and the gallery-import button in screenshot 24 all belong to the ML Kit Document Scanner activity. Gallery import is reached through that same activity (because `setGalleryImportAllowed(true)`), which is why the single button is labeled "Scan or import practice sheet". (Note: a `camera_gallery_button` string exists in `strings.xml` but is not used by the active screen.)

On scanner return:
- `isLaunching = false`.
- If `resultCode == RESULT_OK`: read `GmsDocumentScanningResult.fromActivityResultIntent`, take `pages.firstOrNull()?.imageUri`, open it via `contentResolver.openInputStream`, and decode with `BitmapFactory.decodeStream`. On success, clear `errorMessage` and call `onImageCaptured(bitmap)`. On failure (null stream raises "Failed to open scanned image URI"; null decode raises "Failed to decode scanned image"), set `errorMessage` and call `onCaptureError`.
- If the result is not OK (user cancel): nothing happens beyond re-enabling the button. No message, no callback.

## 5. Processing the captured image into a template

`PremiumCaptureRoute.onImageCaptured` (MainActivity around line 302) drives processing:

1. Re-entrancy guard: returns if already `isProcessing`.
2. Set `isProcessing = true` and swap `instructionText` to `R.string.premium_processing` = "Processing handwriting sample with ML Kit…". (The copy says ML Kit, but the active sheet pipeline does not use ML Kit; see Section 8.)
3. On `Dispatchers.Default`, call `SheetSampleProcessor.process(bitmap, debugContext = context.applicationContext, skipPageDetection = true)`.

`SheetSampleProcessor.process` (returns `SheetExtractionResult(glyphs, missing, rectifiedDebugBitmap, detectionFailed)`, does not throw on detection failure):
1. Downscale so the longest side is `<= MAX_WORKING_DIM = 900`; compute Rec. 601 luma.
2. Because `skipPageDetection = true` (the scanner already cropped the page), use the full working bounds as the page rect with a null paper mask. If the skip path yields zero glyphs, it retries once with `skipPageDetection = false` (full page-location via Otsu + bright-peak flood fill).
3. Otsu-threshold inside the page rect into a binary ink mask.
4. `findFiducials`: 4-connected component scan, dispatch components to quadrants split at the page centre, then a 3-tier shape filter (density/aspect) picks the most fiducial-like filled square nearest each corner (TL/TR/BR/BL). If four are not found, return `detectionFailed = true` with empty glyphs.
5. `rectify`: `Matrix.setPolyToPoly` maps the four detected fiducial centroids onto `PracticeSheetGenerator.fiducialLayout`, drawn onto a `1240 x 1754` white canvas (`RECTIFIED_WIDTH`/`RECTIFIED_HEIGHT`).
6. For each `normalizedCells` entry (skipping spaces): convert to rectified pixels, inset (`topInset = max(3, cellHeight*0.03)`, `sideInset = max(3, min(cellW,cellH)*0.03)`) to skip the printed border, crop the cell.
7. Per-cell sanitization: `suppressPrintedCellLabel` whites out the faint label corner (smaller mask for i/j); for lowercase i/j only, `suppressCenteredGuideTicks` erases the centered 0.55-ratio tick; `suppressCellEdgeResidue` paints a 2px white frame on all four edges.
8. Build `guideYsInCrop` from the upper (0.32) and baseline (0.78) ratios only (middle deliberately excluded), then `GlyphPostProcessor.cleanGlyph(crop, guideYsInCrop, expectsDiacritic = GlyphPostProcessor.expectsDiacritic(char))`. `expectsDiacritic` is true for lowercase i/j (uppercase I/J excluded) plus the accented `DIACRITIC_CHARS` set.
9. `hasInk(cleaned)`: keep the glyph if total alpha exceeds `50 * 255 = 12750`; otherwise add the char to `missing`.

`cleanGlyph` output is ARGB_8888, paper transparent, ink near-black `0xFF1A1410` with anti-aliased alpha, placed bottom-aligned on a fixed-height `220`px canvas (variable width). When `debugContext` is passed, debug artifacts are dumped to `externalCacheDir/sheet_debug/` (captured, working, page-rect overlay, mask, rectified, and per-cell raw/cleaned crops).

Save path (back in the route):
- If `result.glyphs.isEmpty()`: set `instructionText` to `premium_marker_detection_failed` when `detectionFailed`, else `premium_no_glyphs`; reset `isProcessing`; stay on the screen.
- Otherwise build a default name via `createDefaultTemplateName` (`premium_default_template_name` = "My Handwriting" + medium date) and call `repository.userRepository().saveTemplate(displayName, glyphMap = result.glyphs)`. The repo writes one PNG per character (quality 100) into a `u<createdAt-millis>` folder and returns an id `user_<millis>`.
- On save success, `onTemplateSaved(savedId, defaultTemplateName)` advances the flow. On save or process failure, the thrown message (or a fallback string) is shown in `instructionText` and `isProcessing` is reset.

## 6. State and status surfacing

`PremiumCaptureRoute` owns two pieces of state: `instructionText` (a `rememberSaveable`) and `isProcessing`. The single `instructionText` slot in the intro card is overloaded to show, in turn: the initial instruction, the processing message, success-blocking failures (`premium_no_glyphs`, `premium_marker_detection_failed`, `premium_save_failed`, `premium_process_failed`, `premium_camera_failed`), or a raw exception message. Inside the composable, decode/launch failures are surfaced separately through the red `errorContainer` card (`errorMessage`). So a failed capture can appear in two different places (a red card, or just rewritten body text) with no retry control in either.

## 7. Permissions

- The manifest declares `android.permission.CAMERA` and `uses-feature android.hardware.camera.any` with `required="true"`, plus `WRITE_EXTERNAL_STORAGE` capped at `maxSdkVersion="28"`.
- The capture screen itself requests no runtime camera permission. The ML Kit Document Scanner is a separate Play Services activity that owns the camera, handles its own permission, performs perspective correction and rotation, and exposes the data-access footer seen in screenshot 24 ("...can only access the images you scan"). HandType never sees a `CAMERA` grant/deny result in this flow.
- There is no in-app permission priming or rationale before the handoff. Tapping the button jumps straight from the cream intro into the scanner's black viewfinder, and any OS permission dialog appears there with no prior in-app context. Unused permission-rationale strings (`camera_permission_*`, `camera_grant_button`, `camera_open_settings_button`) exist in `strings.xml` from an earlier in-app camera design but are not wired into the active screen.
- Offline-first holds: the scanner, the pipeline, and persistence are all on-device. Nothing in this flow touches the network.

## 8. Code-vs-reality notes (do not propagate the stale wording)

- The `CameraCaptureScreen` KDoc and several strings describe a CameraX/ML-Kit-OCR flow with "Manuell/Automatische Aufnahme" and an in-app preview. The active capture is fully delegated to the GMS Document Scanner; there is no in-app camera surface or capture-mode toggle owned by HandType.
- `premium_processing` says "with ML Kit", but the live extraction path is `SheetSampleProcessor` (fiducial detection + perspective rectify + per-cell crop). It does not call ML Kit. The ML Kit text-recognition extractor (`HandwritingExtractor`) exists but is not wired into this flow.
- Many `camera_*` and `camera_permission_*` strings (`camera_gallery_button`, `camera_adjust_*`, `camera_capture_button`, `camera_default_instruction`, the permission set) are leftovers from the prior in-app camera design and are not rendered by the current screen.
- `PracticeSheetGenerator` KDoc says "Fiducial size: 60x60px"; the actual constant is `FIDUCIAL_SIZE = 80`.

## 9. Known UX gaps (from capture findings)

| ID | Severity | Gap | Direction |
|----|----------|-----|-----------|
| CAP-01 | P0 | The marquee CTA, the tip line, and the "get the sheet" link render in default M3 purple (leaked from the missing theme) while brand CTAs elsewhere are green. The most important paid-feature button looks un-branded. | Define a real `MaterialTheme(colorScheme = ...)` with brand green as `primary`, cream as `surface`, brown as `tertiary`. The `Button`, `TextButton`, and tip then inherit green automatically. Use centralized tokens, not per-component literals. |
| CAP-02 | P0 | "Get the practice sheet (PNG)" actually fires `ACTION_SEND` (a share chooser), not a download/print. The chooser title and subject are hardcoded English literals, so non-English devices see English. | Relabel to the real action ("Share / print practice sheet"), or offer an actual print/save path. Move the chooser title and subject into `strings.xml` so they localize. |
| CAP-03 | P0 | No in-app permission priming or "what happens next" before the scanner handoff. The cream intro jumps straight into a black system camera, and any OS camera prompt appears with no context, undercutting the offline-first promise. | Add a one-line primer (and optionally a first-run M3 bottom sheet) stating that scanning stays on-device and the next screen is the document scanner. |
| CAP-04 | P1 | No `TopAppBar` and no labeled back affordance on the intro; exit relies on system/predictive back, and nothing anchors this as the premium step. | Add an M3 top app bar (medium/large suits a marquee feature) with a back navigation icon and the title. |
| CAP-05 | P1 | Errors split into two weak channels: decode/launch errors show a small red text card with no retry, while no-glyph / marker-detection failures only rewrite the intro body text. No prominent error state, no "try again", no example image. | Use a distinct M3 error result state with an explicit "Erneut scannen" (re-scan) action and a small annotated example of a correctly captured sheet. |
| CAP-06 | P1 | Reading-heavy intro (four steps + tip) with no preview of a filled sheet and no before/after of typed text becoming handwriting. Effort is fully visible; reward is invisible. | Lead with a compact visual (filled-sheet thumbnail, ideally a typed-to-handwriting before/after), apply M3 expressive emphasis to the primary CTA, and demote the step list into a secondary expandable area. |
| CAP-07 | P2 | The flow visibly hands off to the ML Kit scanner's own black chrome and footer, breaking brand continuity. The scanner UI is not themable. | Accept the scanner UI but smooth the seam: keep the clear "Opening scanner…" loading copy, and on return land on a branded confirmation/preview rather than the bare scanner. Document that the scanner chrome is ML Kit and not restylable. |
| CAP-08 | P2 | The scanner's "Manuell / Automatische Aufnahme" toggle is the first in-flow decision, but the user gets no guidance on which mode captures the full page with all four markers. | In the intro primer, recommend the mode that best captures the whole page with the four corner markers before the user enters the scanner. |
