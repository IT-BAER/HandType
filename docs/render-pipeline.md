# Render pipeline: text to handwriting bitmap

How HandType turns a string into a believable handwriting PNG and exports it. Everything below runs fully on-device, no network. Source lives in `app/src/main/java/com/baer/handtype/template/` with one cross-package dependency on `app/src/main/java/com/baer/handtype/ml/GlyphPostProcessor.kt`.

Reference output (default `classic_script` template, warm-cream background): see [output screen](../artifacts/ux/15_output.png).

## Files at a glance

| File | Role |
| --- | --- |
| `template/BundledTemplateRepository.kt` | Loads built-in glyph-image and cursive-font templates from `assets/`. Defines `TemplateDescriptor`, `HandwritingTemplate`, the supported-character list, and the `u%04X_v%d.png` variant lookup. |
| `template/UserTemplateRepository.kt` | Persists and loads user-captured templates under `filesDir/user_templates/`. One PNG variant per character plus a `meta.txt`. |
| `template/HandwritingBitmapRenderer.kt` | The renderer. `render()` (glyph-image path) and `renderCursive()` (system-font path) lay out text, apply organic randomness, draw the background, return a bitmap. |
| `template/NoteBackgrounds.kt` | Paper catalog: solid, transparent, textured, ruled/graph guide lines, plus a SharedPreferences default-background store. |
| `template/OutputExporter.kt` | Save PNG, transparent PNG, multi-page A4 PDF, share staging, private history archive. |
| `template/PunctuationSynthesizer.kt` | Draws `. , ! ? : ; ' " -` in the user's pen style when a user template lacks that glyph. |
| `template/BundledGlyphTextNormalizer.kt` | Normalizes input text before the glyph path runs. |
| `template/UserGlyphWidthNormalizer.kt` | Per-glyph X/Y scale, clamping over-wide user lowercase glyphs. |
| `ml/GlyphPostProcessor.kt` | Owns the normalized-canvas constants the renderer reads (`220`, baseline, guide band). |

## Core data structures

### `TemplateDescriptor` (data class)
Metadata only: `id`, `displayName`, `sampleText`, `description`, `premium: Boolean`, `fontFamily: String?`, `isNew: Boolean`. A non-null `fontFamily` (only `flowing_cursive`, value `"cursive"`) selects the system-font path.

### `HandwritingTemplate` (data class)
A loaded, renderable template:
- `descriptor: TemplateDescriptor`
- `glyphs: Map<Char, List<Bitmap>>` (one entry per character, each holding 1..8 variant bitmaps)
- `typeface: Typeface?`
- `strokeData: Map<String, GlyphOutline>?`
- `isStrokeBased: Boolean` = `strokeData != null`.

`strokeData` is dead weight. `HandwritingBitmapRenderer.parseStrokeData()` always returns an empty map, `GlyphOutline` carries only an unused `advance: Float`, and the cursive path ignores its `strokeData` parameter (it draws with the Android system "cursive" font). `isStrokeBased` is true only for cursive templates, which pass an empty map.

### `HandwritingRenderConfig` (data class): all tuning knobs
| Field | Default | Notes |
| --- | --- | --- |
| `maxWidthPx` | (required) | Layout width budget; word-wrap target. |
| `marginPx` | `28` | Page margin on all sides. |
| `horizontalSpacingPx` | `4` | Extra gap added after each glyph and word. |
| `verticalSpacingPx` | `10` | Inter-line gap. User templates halve it (min 4). |
| `noteBackground` | `warm_cream` | A `NoteBackgroundPreset`. |
| `inkColor` | `0xFF1A1410` | Used only by the cursive path; glyph-image path draws stored ink. |
| `baseFontSizePx` | `120f` | Cursive only. |
| `targetLineHeightPx` | `0` | 0 = derive line height from glyph ink. >0 = force it. |
| `jitterX` / `jitterY` | `2` / `2` | Per-glyph position jitter range. |
| `spacingJitter` | `3` | Random px added/removed between glyphs and words. |
| `wordRotationDeg` | `1.8f` | Cursive word rotation. **Not** read by the glyph path. |
| `minInkAlpha` | `0.85f` | Declared, unused in `render()`. |
| `baselineWaveAmplitude` | `3f` | Glyph path overrides this for bundled templates (hardcoded `6f`). |
| `baselineWavePeriod` | `20f` | Sine period for the baseline wave. |
| `lineDriftPx` | `4f` | Per-line baseline slope amplitude. |
| `fontSizeJitterPct` | `0.06f` | Declared, unused in `render()`. |
| `wordSkewRange` | `0.06f` | Declared, unused in `render()`. |

Runtime caller (`feature/template/TemplateScreens.kt`) builds the config on `Dispatchers.Default` with `maxWidthPx = maxWidth - 32.dp`, `targetLineHeightPx = 76.dp`, the chosen background, and `seed = System.nanoTime()`. So in the live app the glyph-derived line-height path is not exercised; line height is pinned to 76 dp.

### `HandwritingRenderResult` (data class)
`bitmap: Bitmap`, `missingCharacters: Set<Char>` (characters with no glyph and no synthesis, rendered as a gap).

## Glyph sources

There are exactly two real glyph sources (the third, captured strokes, was abandoned).

### 1. Bundled glyph PNGs
Stored in `app/src/main/assets/templates/<templateId>/glyphs/` as `u%04X_v%d.png`: lowercase `u`, 4+ uppercase-hex digits of the Unicode code point, `_v`, the variant index, `.png`. Example: `u0041_v0.png` is the first variant of `A`, `u0021_v3.png` is the fourth variant of `!`.

Supported set (from `BundledTemplateRepository.supportedCharacters`): `A-Z`, `a-z`, `0-9`, and `. , ! ? ' -`. Up to `MAX_VARIANTS = 8` variants per character. `loadTemplate` opens each `u<hex>_v<0..7>.png`, collects whatever exists into `glyphs[char] = List<Bitmap>`, and throws if the template yields zero glyphs. Confirmed on disk: `classic_script` ships 8 variants for the characters checked.

Built-in descriptors:
- `classic_script` (label `template_ink_script_name`, sample `"Meet HandType."`, free, glyph-image).
- `flowing_cursive` (free, `fontFamily = "cursive"`, system-font path, empty glyphs).
- `custom_capture` premium placeholder descriptor (`premium = true`), surfaced as the locked premium entry.

### 2. User-captured glyphs
Saved by the premium capture flow to `filesDir/user_templates/<folder>/glyphs/`, one variant per character (`_v0`). `folder` is `u<createdAtMillis>`; the template id is `user_` + folder (`USER_ID_PREFIX = "user_"`). `loadTemplate` walks `glyphs/`, parses each char from the filename via `Regex("u([0-9A-Fa-f]+)_v\\d+\\.png")`, and stores exactly one Bitmap per char (`listOf(bitmap)`). With one variant, a user template re-renders the same glyph for a given character every time; bundled templates pick randomly among up to 8 variants so the same text re-renders differently.

`meta.txt` is `key=value` lines: `displayName`, `isNew`, `createdAt`, `glyphCount`. A legacy single-line file (no `=`) is read as the display name. User descriptors are `premium = true`, sampleText `"Your handwriting."`, description `"Captured <MMM d, yyyy> [· <n> characters]"`.

### Normalized user canvas
Capture post-processing (`GlyphPostProcessor`) emits every user glyph onto a uniform square so baseline math is consistent:
- `TARGET_CANVAS = 220` (`normalizedCanvasSize()`)
- baseline row `= (220 * 0.74).toInt() = 162` (`normalizedBaselineRow()`)
- guide-band height `= (220 * 0.46).toInt() = 101` (`normalizedGuideBandHeight()`)

The renderer detects this with `hasNormalizedUserCanvas` (id starts with `user_` **and** every glyph height == 220) and switches into a tighter layout mode (see the pipeline below).

> Comment drift: `PunctuationSynthesizer` comments say baseline 163 and x-height-top 62, but the computed values are 162 and 61. The synthesis math uses the real `normalizedBaselineRow()` (162); only the comments are stale.

## Step-by-step: the glyph-image path (`render()`)

1. **Normalize text.** `BundledGlyphTextNormalizer.normalize(text)` unifies `\r\n` and `\r` to `\n`, collapses every other Unicode whitespace variant to a single space, and maps ~45 fancy punctuation code points to ASCII (curly/prime quotes to `'` or `''`, every dash/minus to `-`, ellipsis to `...`, Arabic comma/question, CJK and fullwidth punctuation). Newlines are preserved. (The cursive path skips this step.)

2. **Per-template metrics.** Measure ink bounds of every glyph via `measureInkBounds` (alpha threshold > 20). The median glyph bottom becomes `templateBaselineRow`; average ink width/height drive sizing. Flags: `isUserTemplate` (id starts with `user_`), `hasNormalizedUserCanvas`.

3. **Line height and spacing.** If `targetLineHeightPx > 0` use it; else `(avgInkHeight * 1.35).coerceAtLeast(72)`, with a normalized-canvas floor of `guideBandHeight * 1.35`. `baselineOffsetPx = lineHeightPx * 0.74`. `spaceWidth = max(lineHeightPx*0.20, avgInkWidth*0.45).coerceAtLeast(14)`. User templates and the normalized canvas tighten the organic ranges:

   | Param | Non-user / bundled | User normalized canvas |
   | --- | --- | --- |
   | vertical spacing | `verticalSpacingPx` | `(/2).coerceAtLeast(4)` |
   | horizontal spacing | `horizontalSpacingPx` | `.coerceAtMost(1)` |
   | spacing jitter | `spacingJitter` | `0` |
   | jitter X / Y | `jitterX` / `jitterY` | each `.coerceAtMost(1)` |
   | baseline wave amp | `6f` (hardcoded) | `baselineWaveAmplitude.coerceAtMost(1.5f)` |
   | line drift | `lineDriftPx` | `.coerceAtMost(1.5f)` |
   | line advance | full `lineHeightPx` | `lineHeightPx * 0.72` |
   | size jitter | none | `±4%` per glyph instance |
   | rotation limit | `3.5°` | `1.8°` (any user template) |
   | mesh warp amp | `0.5 + rand*0.5` | `0.12` |

4. **Glyph preparation (`prepareGlyph`, memoized per bitmap).** Crop to ink bounds, then decide where the baseline sits inside the crop:
   - **Normalized user canvas (height 220):** crop a window around the canvas baseline (162). `ascenderReserve = 220*0.56 ≈ 123 px` above, `descenderReserve = 220*0.21 ≈ 46 px` below, but never above row 0 and never cropping into actual ink (honors `i`/`j` dots). `baselineRow` is the ink bottom for glyphs without a descender (writers float letters above the printed guide by varying amounts) and the canvas baseline for `g j p q y`. The advance width ignores a top-right overhang on tall glyphs (`T`, `F`, `7`, `f`, `t`) so the next letter tucks under the bar, never by more than 40%. This trims the large empty margins of the raw 220-square so ink fills the line.
   - **Non-normalized:** lowercase descenders (`g j p q y`) get a fraction-based baseline (`g`/`j` = 0.50, `p`/`q`/`y` = 0.58 for user templates; bundled use `templateBaselineRow`). Every other glyph uses `bounds.bottom`. Uppercase `G/J/P/Q/Y` have no descender, so they use `bounds.bottom`.

5. **Per-character scale (`targetInkHeightPx` + `UserGlyphWidthNormalizer.scales`).** Target ink height = `lineHeightPx * targetInkHeightRatio(char, isUser)` (or full `lineHeightPx` for normalized canvas). `scaleY = target / glyphHeight`; `scaleX = scaleY` normally. For user-template lowercase glyphs wider than a per-letter `maxAspect`, `scaleX` is reduced so width is clamped (`maxAspect`: `m,w` 1.02; `n,u,h` 0.88; `a,c,e,o,s,v,x,z` 0.78; else 0.84). `targetInkHeightRatio` fractions of line height:

   | Class | Bundled | User |
   | --- | --- | --- |
   | punctuation | 0.24 | 0.28 |
   | digit | 0.72 | 0.92 |
   | uppercase | 0.80 | 1.00 |
   | descender g/j | 0.64 | 0.94 |
   | descender p/q/y | 0.64 | 0.90 |
   | dotted i/j | (in `else`) | 0.62 |
   | ascender b/d/f/h/k/l/t | 0.66 | 0.92 |
   | other lowercase | 0.55 | 0.52 |

6. **Layout loop.** Split normalized text on `\n`, then each line on space. Per word: word-wrap when `cursorX + spaceWidth + wordWidth(word) > safeWidth - margin`. Per character pick a variant at random (`rng.nextInt(variants.size)`), falling back to lowercase then uppercase lookup. `wordWidth` pre-measures via `glyphAdvancePx` (bitmap width × `scaleX` × 0.95 normalized / 0.90 other, plus per-class minimum advances). A second wrap check fires if a single glyph would overflow.

7. **Organic randomness, per glyph.** Position jitter `jx/jy` in `±jitterX/Y`; baseline sine `amp * sin(charIndex / wavePeriod + linePhase)`; per-line drift `lineDriftSlope * (charIndex / lineLength)`; rotation `±rotationLimit` (1.8° user, 3.5° bundled); alpha `0.90 + rand*0.08` (≈ 0.90..0.98). Thin glyphs get a `leadingPull` nudge left on the normalized canvas (`i/j/l/I/1` by `lineHeight*0.03`, `f/t/r` by `lineHeight*0.015`). Bundled descenders get a small `descenderDrop` (`lineHeight * 0.05`; user 0.08 is defined but user glyphs already sit on the baseline so no extra drop is applied).

8. **Compose.** Allocate an `ARGB_8888` bitmap sized to used width/height + margins + a small bottom slack for wave/drift/jitter. Draw the background first (`drawBackground` → preset style, passing `lineSpacingPx` and `firstBaselinePx` so guide lines align to text). Then draw each placement with a `Matrix` (scale, translate, optional rotate about the glyph center) and a per-glyph 6×6 `drawBitmapMesh` warp. Mesh vertices are displaced by overlapping sines with amplitude `wAmp` (0.12 user, `0.5..1.0` bundled). Return `HandwritingRenderResult`.

9. **Punctuation synthesis (user templates only).** If a character has no glyph and `PunctuationSynthesizer.supports(c)`, draw it onto a 220-row canvas aligned to baseline 162 in an `InkStyle` estimated from the user's real glyphs. Result is cached per character. Anything still missing is recorded in `missingCharacters` and advanced as a punctuation-sized or space-sized gap. Bundled templates ship full punctuation, so they never synthesize and rarely report missing characters.

## Punctuation synthesis (`PunctuationSynthesizer`)

Supported: `. , ! ? : ; ' " -`. Each mark is drawn on a `width × 220` canvas (widths: `. , ' : ; !` = 40; `"` = 64; `-` = 90; `?` = 96; else 60) using `Canvas` primitives positioned from the normalized baseline (162), x-height top (61), and a fixed ascender top (30). A period is a filled dot at `baseline - dotR`; comma/semicolon use a curving quad tail; `!`/`?` combine a stroke with a dot; `:` is two dots; quotes are short slanted strokes; hyphen is a centered horizontal line.

`InkStyle` (color + stroke width) comes from `InkStyle.estimate(glyphs)`: it samples up to 6 user glyphs, averages ink alpha (clamped 80..240) and RGB (each clamped 0..80), and estimates stroke width from short opaque pixel runs (clamped 2..6 px). Falls back to `InkStyle.DEFAULT` (stroke 3f, argb `0xB0222222`) when no ink is found.

## The cursive path (`renderCursive`)

Independent code for `flowing_cursive`. It does **not** normalize text and does **not** touch template glyphs or `strokeData`. Words are rasterized with `Typeface.create("cursive", NORMAL)`: each char is extracted as a `Path` via `getTextPath`, then given a per-char size (±5%), rotation (±4°), skew (±0.10), baseline offset (±0.5 px), alpha (190..250), and a `wavyPath` contour displacement (amp 0.8..1.0 px) that shifts both stroke edges equally to preserve thickness. Word bitmaps are then placed with jitter, the baseline wave, line drift, word rotation (`±wordRotationDeg`), and word alpha (0.90..1.0). Width estimation uses the cursive `Paint.measureText`.

## Paper backgrounds (`NoteBackgrounds.kt`)

`NoteBackgroundPreset` holds `id`, `labelRes`, `premium`, `previewColors`, `previewPattern` (`Solid | Texture | Ruled | Graph`), and a private `NoteBackgroundStyle`. `render(...)` XORs the render seed with `id.hashCode()` and delegates to the style:

- **SolidBackgroundStyle**: `canvas.drawColor(color)`.
- **TransparentBackgroundStyle** (data object): draws nothing.
- **TexturedPaperBackgroundStyle**: base color, diagonal `LinearGradient` wash (alpha 120), `RadialGradient` vignette (alphas 8 to 34), random flecks (`w*h/12000`, clamped 90..520), random fibers (`w*h/24000`, clamped 50..260).
- **GuidedPaperBackgroundStyle**: base color, lighter wash (alpha 58), faint flecks, then either ruled horizontal lines (alpha 74, width 1.2) positioned from `firstBaselinePx` stepping by `lineSpacingPx`, or a graph grid (minor alpha 46 width 1, major alpha 64 width 1.2), with an optional accent margin line (alpha 96, width 1.6) at `x = max(marginPx*1.75, 48)`.

`NoteBackgroundCatalog`: default `warm_cream` (solid `0xFFF8F1E4`). `selectablePresets` (11): free solids `plain_white` (#FFFFFF), `warm_cream` (#F8F1E4), `soft_butter` (#FFF3C6), `rose_blush` (#FBE7EA), `cool_mist` (#E9F1F7), `soft_sage` (#E8F0E7); premium textured `parchment_sheet`, `recycled_sheet`, `kraft_sheet`; premium guided `ruled_sheet` (Ruled, no margin line) and `graph_sheet` (Graph). A separate `transparentPreset` (id `transparent`) is **not** in `selectablePresets` and reuses the `plain_white` label. `NoteBackgroundPreferences` stores the default id in SharedPreferences file `note_background_prefs`, key `default_background_id`.

Backgrounds are seeded (`renderSeed xor preset.id.hashCode()`), so fleck/fiber layout is reproducible only when a fixed `seed` is passed; the live app passes `System.nanoTime()`.

## Export (`OutputExporter.kt`)

All functions take the rendered `Bitmap`. Save/export return `Result<Uri>`; archive returns `Result<File>`.

- **`saveImageToGallery`**: PNG to `Pictures/HandType` via MediaStore on API 29+, direct file (needs `WRITE_EXTERNAL_STORAGE`) on 23-28.
- **`saveTransparentImageToGallery`**: runs `keyOutPaper(bitmap, paperColor)` then saves. `keyOutPaper` computes per-pixel RGB distance (0..441 range) from `paperColor`: `dist <= 30` → alpha 0, `dist >= 110` → alpha 255, linear soft edge between (keeps anti-aliased ink). Default `paperColor` is cream `0xFFF8F1E4`. **Caveat:** the key-out uses one passed paper color; if the user picked a different solid/textured background the caller must pass the matching color, otherwise the cutout halos or eats ink-adjacent tones.
- **`exportPdf`**: A4 portrait (595x842 pt @72 dpi), 32 pt margin, scaled to width. `computePageCuts` slices tall bitmaps across pages and snaps each cut to the brightest (most paper-empty) row inside a search window of 12% of the slice height, so a page break never bisects a line of text. Row brightness is sampled every `width/64` columns using perceived-luminance weights (30/59/11). Saved to `Download/HandType` on API 29+, app cache (FileProvider) on older.
- **`stageBitmapForShare`** + **`buildShareIntent`**: writes a PNG to `cacheDir/shared`, returns a `FileProvider` URI (`<packageName>.fileprovider`), and builds an `ACTION_SEND` chooser (literal title `"Share handwritten note"`).
- **`archiveBitmap`**: private history copy at `filesDir/history/<yyyyMMdd_HHmmss>__<safeTag>.png` plus an optional `.txt` (source text) and always a `.name` (display name) sidecar.

File naming: `HandType_<safeTag>_<yyyyMMdd_HHmmss>.<ext>` (US locale; `safeTag` lowercases the template name and replaces non-alphanumerics with `_`, falling back to `note`). MIME: `image/png`, `application/pdf`. Album folder: `HandType`.

## Gotchas

- **Dead stroke data.** `strokeData` / `GlyphOutline` / `parseStrokeData()` are vestigial; cursive uses the system font.
- **Unused config knobs.** `minInkAlpha`, `fontSizeJitterPct`, `wordSkewRange` are never read by `render()`. `wordRotationDeg` only affects the cursive path; the glyph path uses its own hardcoded 1.8°/3.5° limits, and overrides `baselineWaveAmplitude` to 6f for bundled templates.
- **Cursive skips normalization,** so fancy Unicode punctuation/whitespace is cleaned only on the glyph-image path.
- **Nondeterminism.** `seed` defaults to `System.nanoTime()`. Bundled glyph variant choice and background flecks/fibers are seeded, so output varies per render unless a fixed seed is passed. User templates (1 variant) are stable per character but still jittered/warped per render.
- **Comment off-by-one** in `PunctuationSynthesizer` (163/62 vs real 162/61); the math uses the real value.
- **Live config bypasses glyph-derived sizing.** `TemplateScreens.kt` always passes `targetLineHeightPx = 76.dp`, so the `avgInkHeight * 1.35` branch is dead at runtime.
- **`keyOutPaper` color mismatch** can leave halos when the chosen background is not the default cream (see export caveat above).
