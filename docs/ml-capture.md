# ML capture: own-handwriting capture (premium)

Deep dive into the premium "capture your own handwriting" flow: how the printable practice sheet is generated, how the page is photographed, how the four corner fiducials are detected and the page perspective-corrected, how each grid cell is cropped and sampled, how each glyph is cleaned, and how the result becomes a reusable `UserTemplate`. Everything runs on-device. No network, no analytics.

This is the only premium feature in the app. The user prints a sheet, writes one character per cell, scans the page, and the app builds a handwriting font from the photo.

## End-to-end pipeline at a glance

```
Generate sheet            Capture                 Extract                       Persist
PracticeSheetGenerator -> GMS Document Scanner -> SheetSampleProcessor.process -> UserTemplateRepository.saveTemplate
(printable A4 PNG)        (external activity)      (fiducials, rectify, crop,    (user_<millis>, one PNG/char)
                                                    GlyphPostProcessor.cleanGlyph)
```

Source files:
- `app/src/main/java/com/baer/handtype/feature/capture/PracticeSheetGenerator.kt` (sheet generation + canonical layout)
- `app/src/main/java/com/baer/handtype/feature/capture/CameraCaptureScreen.kt` (capture UI, scanner launch)
- `app/src/main/java/com/baer/handtype/feature/capture/SheetSampleProcessor.kt` (detection, rectification, cell extraction)
- `app/src/main/java/com/baer/handtype/feature/capture/PaperCornerDetector.kt` (alternate corner finder, not in active path)
- `app/src/main/java/com/baer/handtype/ml/GlyphPostProcessor.kt` (per-glyph cleanup, shared)
- `app/src/main/java/com/baer/handtype/ml/HandwritingExtractor.kt` (ML Kit text path, not wired into the live flow)
- `app/src/main/java/com/baer/handtype/MainActivity.kt` (`PremiumCaptureRoute`, around line 280, the production caller)
- `app/src/main/java/com/baer/handtype/template/UserTemplateRepository.kt` (`saveTemplate`)

## On-device and offline

There is no server. Two GMS/ML Kit dependencies back this feature (`app/build.gradle.kts`):

```
com.google.android.gms:play-services-mlkit-document-scanner:16.0.0
com.google.android.gms:play-services-mlkit-text-recognition:19.0.1
```

The manifest declares the model dependency for predownload:

```xml
<meta-data android:name="com.google.mlkit.vision.DEPENDENCIES" android:value="ocr" />
```

These are on-device GMS modules; the scanner and recognizer run locally. Note the meta-data primes `ocr` (text recognition) but the live capture path does not use text recognition (see "Two extraction paths" below). All glyph extraction in `SheetSampleProcessor` is pure pixel math on the bitmap, with no ML Kit call.

## Permissions

- `AndroidManifest.xml` declares `android.permission.CAMERA`, `uses-feature android.hardware.camera.any (required=true)`, and `WRITE_EXTERNAL_STORAGE` capped at `maxSdkVersion=28` (legacy gallery save, unrelated to capture).
- `CameraCaptureScreen` requests **no** camera permission itself. The capture is delegated to the GMS Document Scanner activity, which handles camera permission, the live preview, perspective correction, and rotation internally. There is no runtime permission prompt or permission-priming screen in this flow.

## 1. Practice sheet generation (`PracticeSheetGenerator`)

`PracticeSheetGenerator` is an `object` that renders the printable sheet and also exposes the canonical layout constants that `SheetSampleProcessor` reuses to crop cells. The generator and the extractor share one source of truth for geometry.

### Canvas and layout constants

| Constant | Value | Notes |
| --- | --- | --- |
| `PAGE_WIDTH` x `PAGE_HEIGHT` | 1240 x 1754 | A4 at ~150 dpi, ARGB_8888, white background |
| `OUTER_MARGIN` | 60 px | |
| `FIDUCIAL_SIZE` | 80 px (public const) | solid black square, one per corner |
| `GRID_COLS` x `GRID_ROWS` | 8 x 9 = 72 cells | `LEGACY_GRID_ROWS = 8` for sheets printed before the language row |
| `LABEL_COLOR` | `0xFFB7B0A3` | faint, so it does not bias the user's stroke |
| `GRID_COLOR` | `0xFFCCC4B4` | cell border, 2 px stroke |
| `GUIDE_COLOR` | `0xFFD8D1C3` | guide ticks, 1.5 px stroke |
| `GUIDE_SEGMENT_LENGTH` | 26 px | each tick drawn +/- 13 px about cell centerX |
| `GUIDE_UPPER_RATIO` | 0.32 (internal) | reused by `SheetSampleProcessor` |
| `GUIDE_BASELINE_RATIO` | 0.78 (internal) | reused by `SheetSampleProcessor` |
| `GUIDE_MIDDLE_RATIO` | (0.32 + 0.78)/2 = 0.55 | drawn for the user, never used as an extraction signal |

KDoc drift: the layout comment says "Fiducial size: 60x60px", but `FIDUCIAL_SIZE = 80`. Trust the constants.

### Characters

`cellCharacters(extras, rows)` = `A-Z` (26) + `a-z` (26) + `0-9` (10) = 62 real chars, then `extras`, then `' '` (space) placeholders to fill the grid, laid out left-to-right, top-to-bottom. `extras` come from `localeCharacters(locale)`: `ÄÖÜäöüß` for German, empty otherwise. The app language decides (`cellCharacters(context)` reads the Activity configuration, which carries the in-app language override). The legacy 8-row layout has no room for extras (62 + 2 spaces). Space cells get a border and guide lines but no label, and are skipped during extraction. Punctuation is never in the sheet; for user templates it is synthesized at render time by `PunctuationSynthesizer`.

### Fiducial and grid geometry

`fiducialLayout: FiducialLayout` holds the four fiducial **centre** points (each `Pair<Float, Float>`):
- left/top edge = `OUTER_MARGIN + FIDUCIAL_SIZE/2` = `60 + 40` = 100
- right = `PAGE_WIDTH - OUTER_MARGIN - 40` = 1140; bottom = `PAGE_HEIGHT - OUTER_MARGIN - 40` = 1654
- So the fiducial-centre rectangle is `(100,100) .. (1140,1654)`.

`normalizedCells: List<CellRect>` defines each cell in 0..1 coordinates over the **grid bounds** (the fiducial-centre rectangle, not the full page). A `gridInset = 0.06f` keeps cells inside `0.06..0.94`, so writable cells never overlap the printed black corner squares. Each cell is `(0.94 - 0.06)/8 = 0.11` per axis.

### renderBitmap()

1. White 1240x1754 ARGB_8888 canvas.
2. Title "HandType practice sheet" at `(60, 42)`, textSize 36 bold, color `0xFF1A1410`.
3. Footer "Write each character inside its cell. Keep the four black squares visible." at `(60, 1726)`, textSize 22.
4. Four solid black `FIDUCIAL_SIZE` squares centred on `fiducialLayout`.
5. Per cell: 2 px grid border, three centered guide ticks at the 0.32 / 0.55 / 0.78 ratios (`drawGuideTicks`), and (if not space) the faint label at `rect.left + 8, rect.top + 32`, textSize 28.

### Sharing the sheet

- `renderBitmap()` returns the in-memory bitmap.
- `renderToShareableUri(context)` writes PNG (quality 100) to `cacheDir/shared/handtype_practice_sheet.png` and returns a `FileProvider` URI (authority `${packageName}.fileprovider`).
- The "Get the practice sheet (PNG)" button (`camera_sheet_button`) in `CameraCaptureScreen` calls `context.shareTemplateSheet()`, which builds an `ACTION_SEND` chooser (type `image/png`, subject "HandType practice sheet", chooser title "Send practice sheet", flags `FLAG_GRANT_READ_URI_PERMISSION` + `FLAG_ACTIVITY_NEW_TASK`). If `renderToShareableUri` throws, it fails silently (no error shown, no callback).

## 2. Capture UI and scanning (`CameraCaptureScreen`)

The intro screen shows a card with the section title, an instruction line, a nested numbered "Before you scan" guide (four steps), a tip, the share-sheet button, an optional error card, and the primary scan button. See [capture intro](../artifacts/ux/23_capture_intro.png) and the [scanner UI](../artifacts/ux/24_capture_perm.png).

Public composable signature:

```kotlin
fun CameraCaptureScreen(
    onImageCaptured: (Bitmap) -> Unit,
    onCaptureError: (Throwable) -> Unit,
    modifier: Modifier = Modifier,
    instructionText: String = "Print the practice sheet, fill every cell with your handwriting, then tap Scan to photograph the whole page.",
    isProcessing: Boolean = false,
)
```

The default `instructionText` is a hardcoded English literal, not a `stringResource`. The production caller (`PremiumCaptureRoute`) always overrides it with localized `R.string.premium_*` strings, so end users see localized text; only direct reuse of the composable would surface the English default.

### Scan launch flow

1. The primary full-width button ("Scan or import practice sheet", 72 dp tall, 22 dp corners, test tag `camera_scan_button`) is `enabled = !isLaunching && !isProcessing` and calls `launchScanner()`.
2. `launchScanner()` returns early if already launching/processing.
3. Debug hook: `HandTypeDebugHooks.loadImportedScanBitmap()` (wrapped in `runCatching`). In DEBUG builds with `importedScanPath` set, it returns a decoded bitmap and the screen calls `onImageCaptured(bitmap)` and returns without launching the scanner. This is the instrumentation injection path.
4. Resolve the host `Activity` via `findActivity()` (tailrec unwrap of `ContextWrapper`). If none, fire `onCaptureError(IllegalStateException(...))`.
5. Build options and launch:

```kotlin
GmsDocumentScannerOptions.Builder()
    .setScannerMode(SCANNER_MODE_FULL)
    .setPageLimit(1)
    .setGalleryImportAllowed(true)
    .setResultFormats(RESULT_FORMAT_JPEG)
    .build()
```

`GmsDocumentScanning.getClient(options).getStartScanIntent(activity)` returns an `IntentSender`, launched through a `StartIntentSenderForResult` launcher wrapped in `IntentSenderRequest`.

6. On `RESULT_OK`: read `GmsDocumentScanningResult.fromActivityResultIntent(...)`, take `pages.firstOrNull()?.imageUri`, open via `contentResolver.openInputStream`, decode with `BitmapFactory.decodeStream`. Success -> clear error + `onImageCaptured(bitmap)`. Failure paths raise `error("Failed to open scanned image URI")` or `error("Failed to decode scanned image")`, set the error card, and call `onCaptureError`. A non-OK result (user cancel) only resets `isLaunching`, with no message or callback.

### Manual vs auto capture

The Manuell / Automatische Aufnahme (manual / auto) toggle visible in [the scanner UI](../artifacts/ux/24_capture_perm.png) is provided by the **GMS Document Scanner activity itself**, not by app code. There is no CameraX preview, no in-app capture surface, and no manual/auto toggle in `CameraCaptureScreen`. The KDoc and some wording hint at a CameraX preview, but capture is fully delegated to the external scanner. Gallery import is enabled (`setGalleryImportAllowed(true)`), so the same button also imports an existing photo; that is why the label is "Scan or import practice sheet".

The scanner returns a single page that is already cropped, perspective-corrected, and rotated. That is why the production call passes `skipPageDetection = true` downstream.

### Visual identity note

The intro card and buttons read entirely from `MaterialTheme.colorScheme` (surface, onSurfaceVariant, secondaryContainer, primary, errorContainer). `MainActivity` uses a bare `MaterialTheme {}` with no colorScheme override, so these render in the **default M3 baseline purple**, not the brand green/cream. This is visible as the purple guide-card tint, purple tip text, and purple primary button in [the intro screenshot](../artifacts/ux/23_capture_intro.png).

### Caller wiring (`PremiumCaptureRoute`, MainActivity ~line 280)

On `onImageCaptured`:
1. Guard re-entry, set `isProcessing = true`, swap `instructionText` to the processing string.
2. On `Dispatchers.Default`: `SheetSampleProcessor.process(bitmap, debugContext = context.applicationContext, skipPageDetection = true)`.
3. On success with empty glyphs: show `premium_marker_detection_failed` if `result.detectionFailed`, else `premium_no_glyphs`; stop processing.
4. On success with glyphs: build a default name (`createDefaultTemplateName`, MEDIUM-format date + `premium_default_template_name` "My Handwriting"), then `repository.userRepository().saveTemplate(displayName, glyphMap = result.glyphs)`, and call `onTemplateSaved(savedId, name)`.
5. On save failure -> `premium_save_failed`; on process throw -> `premium_process_failed`; on capture error -> `premium_camera_failed`.

`debugContext` is non-null in production (`applicationContext`), so debug artifacts are always written to `externalCacheDir/sheet_debug` on real runs (see "Debug artifacts").

## 3. Capture pipeline (`SheetSampleProcessor.process`)

Entry point and result:

```kotlin
data class SheetExtractionResult(
    val glyphs: Map<Char, Bitmap>,
    val missing: Set<Char>,
    val rectifiedDebugBitmap: Bitmap?,
    val detectionFailed: Boolean = false,
)

fun process(captured: Bitmap, debugContext: Context? = null, skipPageDetection: Boolean = false): SheetExtractionResult
```

`process` never throws on detection failure: it returns `detectionFailed = true` with empty glyphs. It also retries once: if `skipPageDetection == true` and the first attempt yields zero glyphs, it re-runs `processAttempt` with `skipPageDetection = false` (full page-detection enabled).

Key constants: `MAX_WORKING_DIM = 900`, `RECTIFIED_WIDTH = 1240`, `RECTIFIED_HEIGHT = 1754` (canonical page size). Luma uses Rec. 601 weights: `(r*299 + g*587 + b*114) / 1000`.

### 3a. Downscale and luma

`downscale` scales so the longest side is <= 900 (`Bitmap.createScaledBitmap(..., filter = true)`), returning the working bitmap and `scaleToOriginal = 1/scale` (1f if not downscaled). `computeLuma` produces an `IntArray` of per-pixel luma.

### 3b. Page location (`locatePage`, skipped in production)

When `skipPageDetection = true` (production, scanner output is already cropped), `pageRect` = full working bounds and `paperMask = null`. The page-detection path runs only on the retry or when called with detection enabled:

1. Global Otsu over the whole image.
2. Bright cutoff (`pageBrightCutoff`): the paper peak is the histogram mode above Otsu; `brightCutoff = (otsu + peak) / 2` clamped to `[otsu+10, 245]`. A dim or shadowed sheet (paper at luma ~160-180 on a phone photo) stays one component while wood grain, which sits below Otsu, stays out.
3. Build a "bright" mask (`luma >= brightCutoff`), 4-connected flood-fill, keep the largest bright component as the paper.
4. If the largest bright region is under 10% of total pixels, fall back to the full frame with a null mask.
5. Otherwise return the component's AABB shrunk inward by `min(w,h)*0.005` (min 1) plus the component mask with its enclosed holes filled (`fillEnclosedHoles`), so fiducials and ink count as paper.

### 3c. Ink threshold (`thresholdMaskInRect`)

Runs Otsu **only** over pixels inside `pageRect` (and inside `paperMask` when present), then sets `mask[idx] = luma <= threshold` (true = ink) for every pixel in `pageRect`. The mask gates the histogram only: a fiducial at a shadowed page edge is not enclosed by bright paper, so it would be lost if the mask also gated the output. Background clutter inside the rect is rejected by the fiducial shape tiers.

### 3d. Fiducial detection (`findFiducials`)

This is the active corner detector. It does **not** use `PaperCornerDetector` (see "Dead alternate paths").

1. Quadrant split is at the **paper centre** (midpoint of `pageRect`), not the frame centre, so an off-centre sheet still routes each fiducial to the right quadrant.
2. `collectComponents` does one 4-connected scan of the ink mask, keeping components within a size band:
   - `minSize = max(40, totalArea * 0.0003)`, `maxSize = totalArea * 0.15`.
   - Per component it records size, bbox, centroid, `fillDensity = pixels / bboxArea`, and `aspectRatio = shortSide / longSide` (1.0 = square).
3. `pickFiducial` filters components whose centroid lies in each quadrant, then applies a 3-tier shape filter and, within the chosen tier, picks the component **closest to the physical corner** (squared distance). Corner proximity is the key disambiguator because handwriting blobs in the page centre can mimic a fiducial's density/aspect.

| Tier | fillDensity | aspectRatio | Intent |
| --- | --- | --- | --- |
| 1 | >= 0.55 | >= 0.7 | solid square under good light |
| 2 | >= 0.4 | >= 0.55 | poorly inked print, perspective skew |
| 3 | >= 0.25 | >= 0.4 | any reasonably compact blob |
| last resort | any | any | closest blob to the corner |

If any quadrant yields no fiducial, `findFiducials` returns null and `process` returns `detectionFailed = true`. The "tolerant to perspective distortion" claim has no explicit angle limit; robustness comes only from the size band, the 3-tier filter, and corner proximity (empirical, not a documented number).

### 3e. Perspective correction (`rectify`)

Builds source points from the four fiducial centroids scaled back to original px (`* scaleToOriginal`), destination points = `PracticeSheetGenerator.fiducialLayout` (the four fiducial **centres** on the canonical sheet), `Matrix.setPolyToPoly(..., 4)` (4-point perspective), then draws the original onto a white `1240x1754` canvas with bilinear filtering (`isFilterBitmap = true`).

Because destination = fiducial centres, the rectified grid spans only the fiducial-centre rectangle `(100,100)..(1140,1654)`, not the full 1240x1754 bitmap. The cell math accounts for this by using `fiducialLayout` bounds, not the full page.

### 3f. Per-cell crop and sampling

For each non-space `normalizedCells` entry:

1. Convert the cell's 0..1 coords into rectified px via the fiducial-centre grid bounds (`gridLeftPx=100`, `gridTopPx=100`, width `1040`, height `1554`), clamped into `[0, RECTIFIED_*]`. Then snap each of the four edges onto the printed border: `snapBorderRow` / `snapBorderColumn` pick the darkest full-span row/column within `+/-GUIDE_SEARCH_RADIUS` (20 px) of the nominal edge. A photographed sheet measured 15 px of vertical bow near the bottom rows, so the nominal geometry alone leaves the border inside the crop.
2. Insets to skip only the printed 2 px border (the label is cleared separately):
   - `topInset = max(3, cellHeightPx * 0.03)`
   - `sideInset = max(3, min(cellW, cellH) * 0.03)`
   - The top inset was previously 8% (~13 px), which cut the tops of tall letters (A, T); it was reduced to 3%.
3. `Bitmap.createBitmap` the cell crop.

### 3g. Furniture erase

`eraseSheetFurniture` whites out the printed furniture the crop still contains, from geometry rather than pixel luma:

1. The cell label box from `PracticeSheetGenerator.labelBounds(char)` (the generator's own `getTextBounds` at its label position, shifted into crop coordinates), grown by `FURNITURE_MARGIN` (3 px).
2. A `FURNITURE_MARGIN` frame along all four crop edges for border bleed left after the snap and inset.

The earlier luma-gated label erase kept any pixel darker than luma 150, and a printed label core measured 116..118 on a real scan, so its stem survived as a stray component above `i`/`j`.

Important: **blanket pixel-level guide erasure was removed.** Erasing guide pixels across the whole cell severed letter strokes (for example the H crossbar at the 55% line), turning one connected component into isolated noise that was then also rejected. Guide ticks are now rejected by `GlyphPostProcessor.shouldKeepComponent` (aspect-ratio + area checks) instead.

### 3h. Guide Y hints

`guideYsInCrop` is computed from **only** `GUIDE_UPPER_RATIO` (0.32) and `GUIDE_BASELINE_RATIO` (0.78), translated to crop-local Y (`(cellHeightPx * ratio).toInt() - topInset`) and filtered to the crop height. The middle guide (0.55) is deliberately excluded: it is a handwriting aid, and i/j dots can sit above it and must remain valid ink.

### 3i. Cleanup and ink test

`GlyphPostProcessor.cleanGlyph(crop, guideYsInCrop, expectsDiacritic = GlyphPostProcessor.expectsDiacritic(char))` cleans the glyph (section 5). The stylus path (`InkRasterizer`) uses the same rule.

`expectsDiacritic` is true for lowercase `i`, `j` (uppercase `I`/`J` excluded, no tittle) plus `DIACRITIC_CHARS`: ä ö ü Ä Ö Ü, the acute/grave/circumflex a-u variants, ñ Ñ ç Ç.

`hasInk(cleaned)`: sums alpha over all pixels and keeps the glyph only if `alphaSum > 50 * 255 = 12750`. This catches thin chars (i, 1, l) while rejecting blank cells and faint residue. Kept glyphs go into `glyphs[char]`; the rest into `missing`. `MIN_OPACITY_FOR_INK = 200` is declared but unused (vestigial).

## 4. Dead alternate paths

Two pieces of corner-handling code exist but are **not** in the production path:

- `PaperCornerDetector` (PCA axis fit + oriented bounding box, snap to nearest boundary pixel, order TL/TR/BR/BL by angle around centroid) is consumed only by `rectifyPage` and by `PaperCornerDetectorTest`. `process` -> `processAttempt` always calls the fiducial-based `rectify`, never `rectifyPage`.
- In-file comments are inverted: the header above `rectify` labels it "Legacy ... (kept but unused)" while `rectifyPage` is labelled the "primary path". Actual usage is the opposite: `rectify` (fiducials) is live, `rectifyPage` (paper corners) is dead.

## 5. Glyph cleanup (`GlyphPostProcessor.cleanGlyph`)

Shared by both extraction paths. Output is ARGB_8888 with paper transparent and ink near-black (`INK_COLOR = 0xFF1A1410`), height fixed at the normalized canvas size, width trimmed to the ink. The KDoc says "350x350"; that is stale, the real canvas is 220 tall, variable width.

Constants:

| Constant | Value |
| --- | --- |
| `TARGET_CANVAS` | 220 |
| `TARGET_GLYPH_HEIGHT` | 199 |
| `TARGET_BASELINE_Y` | `(220 * 0.74).toInt()` = 162 |
| `TARGET_GUIDE_BAND_HEIGHT` | `(220 * 0.46).toInt()` = 101 |
| `INK_COLOR` | `0xFF1A1410` |
| `SIDE_PAD` | 8 |
| `MIN_CONTRAST` | 30 |

Pipeline:

1. Rec. 601 luma. If `lumaMax - lumaMin < 30`, return a blank transparent 220x220 canvas (so `hasInk` returns false).
2. Otsu threshold, then a **permissive** ink mask: `luma <= min(otsu * 1.2, 210)`. The cap of 210 keeps a margin below paper (typically luma >= 225) so lightly written strokes count as ink. A 3x3 morphological close (`closeGaps`, `CLOSE_RADIUS = 1`) then bridges gaps up to 2 px in thin strokes; radius 2 was tried and split the fixture `j` into extra components.
3. Diacritic recovery (two passes, scoped to the upper zone, `diacriticZoneEnd` = the guide baseline Y if a band exists, else 40% of crop height):
   - Pass 1 (always): recover connected groups area `3..149` with `luma < 240` (clearly faint dots without paper grain).
   - Pass 2 (only when `expectsDiacritic`): recover the single most-compact upper blob area `1..149` with `luma < 250` (barely visible i/j dots).
4. `extractInkComponents` (8-connected flood fill). `primary` = largest-area component. `shouldKeepComponent` filters the rest by aspect ratio, area, axis gaps, guide-band containment, and an "above primary" diacritic rule. Wide-short components in a guide band, and small tall residue above the primary, are rejected; letter arms and touching components are kept.
5. If `expectsDiacritic`: a safety net re-adds a rejected dot/accent above the primary. Marks smaller than a quarter of the largest mark are dropped as guide residue. The remaining marks move as one group (so both umlaut dots keep their spacing): the nearest sits `max(2, primaryHeight*0.18)` above the body, moving down or up (a mark too close would fuse with the body in the 3x3 dilation), and the group is horizontally centered on the body (`diacriticSourceMap` remembers the original pixel luma so alpha is sourced correctly after the move).
6. Compute the ink bounding box. If `expectsDiacritic`, dilate diacritic seeds 3x3 and recompute. Add `padding = 4`.
7. Build the alpha-masked crop: paper -> `TRANSPARENT`; ink -> `INK_COLOR` with alpha `((permissiveThreshold - luma) / darkSpan) * 255` (darker = more opaque); diacritic pixels alpha-boosted to `230..255` (or 240 when the source pixel is brighter than the threshold).
8. `placeOnCanvas`: scale to `TARGET_GLYPH_HEIGHT = 199` (or to `TARGET_GUIDE_BAND_HEIGHT` when a valid guide band exists, `resolveGuideBand` needs >= 2 entries and `baseline - upper >= 8`), height-fixed 220 canvas, width trimmed to ink + `SIDE_PAD` per side, bottom aligned to `TARGET_BASELINE_Y`. `isFilterBitmap` is disabled when more than one component was kept, to preserve their separation.

This 220-tall, baseline-aligned, guide-band-normalized canvas is what `HandwritingBitmapRenderer` and `PunctuationSynthesizer` rely on (`normalizedCanvasSize()` = 220, `normalizedBaselineRow()` = 162, `normalizedGuideBandHeight()` = 101).

## 6. Two extraction paths

There are two glyph extractors that share `GlyphPostProcessor.cleanGlyph` but serve different roles:

- **`SheetSampleProcessor` (live capture path):** fiducial sheet detection, perspective rectify, known-cell crops. No ML Kit. This is what `MainActivity` wires.
- **`HandwritingExtractor` (ML Kit text path):** wraps a Latin `TextRecognizer` (`TextRecognizerOptions.DEFAULT_OPTIONS`), walks `textBlocks -> lines -> elements -> symbols`, keeps single-char `isLetterOrDigit` symbols (falls back to slicing the element box into equal-width regions when no symbol boxes exist), crops each with `paddingPx = 12`, runs `cleanGlyph` (no guide band, `expectsDiacritic = false`), and returns an `ExtractionResult` (`glyphs`, `glyphMap` first-crop-per-char, `recognizedText`). The header calls it the "alphabet-calibration flow." It is **not** invoked by the live capture flow in `MainActivity`; treat it as an alternate/calibration path. Because it passes no guide band and `expectsDiacritic = false`, it gets none of the i/j tittle recovery the sheet path applies. The equal-width fallback assumes monospaced characters and misaligns proportional handwriting; it is best-effort only.

The manifest's `com.google.mlkit.vision.DEPENDENCIES = ocr` predownload corresponds to this text-recognition path, not the live sheet path.

## 7. Result -> UserTemplate (`UserTemplateRepository.saveTemplate`)

```kotlin
suspend fun saveTemplate(displayName: String, glyphMap: Map<Char, Bitmap>, isNew: Boolean = true): String
```

On `Dispatchers.IO`:
1. Require `glyphMap` non-empty.
2. `createdAt = System.currentTimeMillis()`, `folderName = "u$createdAt"`, create `rootDir/<folderName>` and a `glyphs/` subfolder.
3. Write one PNG per char into `glyphs/` named `u%04X_v%d.png` (uppercase-hex code point + variant), variant 0, quality 100. Exactly one variant per character is persisted (so user-template output is stable per char; bundled templates can carry up to 8 variants).
4. Write `meta.txt` (key=value lines): `displayName` (trimmed, falls back to `folderName`), `isNew`, `createdAt`, `glyphCount`.
5. Return the namespaced id `USER_ID_PREFIX + folderName` = `user_u<millis>` (`USER_ID_PREFIX = "user_"`).

The new id is handed to `onTemplateSaved`, after which the template appears in the chooser (premium, sampleText "Your handwriting."). Missing punctuation is later synthesized by `PunctuationSynthesizer` at render time, because the sheet only covers alphanumerics.

`rootDir` is the user-templates directory under `filesDir` (private app storage). Nothing leaves the device.

## 8. Strings (live capture, `res/values/strings.xml`)

Capture screen (`camera_*`):

| Key | English value |
| --- | --- |
| `camera_section_title` | Capture your handwriting sheet |
| `camera_pre_scan_title` | Before you scan |
| `camera_pre_scan_step_1` | Print the practice sheet. |
| `camera_pre_scan_step_2` | Write the matching character in each box and keep it sitting on the guide line. |
| `camera_pre_scan_step_3` | Capture the whole page and keep all four black squares fully visible. |
| `camera_pre_scan_step_4` | Use even light and avoid blur, glare, and hard shadows. |
| `camera_tip` | Tip: use even lighting and keep all rows of letters visible. |
| `camera_sheet_button` | Get the practice sheet (PNG) |
| `camera_scan_button` | Scan or import practice sheet |
| `camera_opening_scanner` | Opening scanner... |
| `camera_processing_button` | Processing... |

Caller status strings (`premium_*`):

| Key | English value |
| --- | --- |
| `premium_instruction` | Write A-Z, a-z, and 0-9 on the practice sheet, then center the page inside the frame to create your personal template. |
| `premium_processing` | Processing handwriting sample with ML Kit... |
| `premium_no_glyphs` | No alphanumeric glyphs were detected. Try brighter lighting and fill more of the frame with the page. Use system back to return to templates. |
| `premium_marker_detection_failed` | That scan did not look like the HandType practice sheet. Make sure the printed sheet fills most of the scan and all four black squares are visible. |
| `premium_save_failed` | Could not save the handwriting template. Try again. |
| `premium_process_failed` | Could not process the sheet. Re-scan with all four corner markers visible. |
| `premium_camera_failed` | Camera capture failed. Try again. |
| `premium_default_template_name` | My Handwriting |

Note: `premium_processing` says "with ML Kit", but the live path does not use ML Kit. The string is misleading.

## 9. Debug artifacts

When `debugContext != null` (always true in production, `applicationContext` is passed), `process` clears and writes to `externalCacheDir/sheet_debug/` (PNG quality 100): `00_captured.png`, `00_working.png`, `00b_pagerect.png` (red rect overlay), `01_mask.png` (black = ink), `02_rectified.png`, and `cells/%02d_<char>_raw.png` + `_cleaned.png` (non-alphanumeric chars saved as "x"). The directory is cleared at the start of each `process` call. These are useful for `adb pull` inspection when a scan extracts the wrong glyphs.

## 10. Known issues and gotchas

- **No CameraX, no in-app camera.** Despite KDoc wording, capture is fully delegated to the GMS Document Scanner activity. The manual/auto toggle is the scanner's, not the app's.
- **Leaked default purple.** The whole capture UI renders in M3 baseline purple because `MainActivity` uses a bare `MaterialTheme {}` with no brand colorScheme.
- **`premium_processing` cites ML Kit** but the live path is pure pixel math (`SheetSampleProcessor`), no ML Kit call.
- **Share button fails silently** if sheet rendering throws (no error, no callback).
- **User cancel of the scanner is silent** (no message, no callback, button just re-enables).
- **Dead code:** `PaperCornerDetector` + `rectifyPage` are unused in production; `MIN_OPACITY_FOR_INK = 200` is unused; the `rectify`/`rectifyPage` "primary/legacy" comments are inverted relative to real usage.
- **Stale KDoc:** `FIDUCIAL_SIZE` is 80 (KDoc says 60); `cleanGlyph` canvas is 220, not "350x350".
- **Default `instructionText`** in `CameraCaptureScreen` is hardcoded English; only the real caller localizes it.
- **Perspective tolerance is empirical.** There is no explicit max-skew limit; detection works only while all four fiducials are visible and dominant in their quadrants.
