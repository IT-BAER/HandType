# Feature spec: Editor

The Editor is the compose phase of the authoring loop: the screen where a user types a message, picks paper, and triggers a render. It is the `Compose` phase of `HandwritingRenderScreen`, implemented by the private composable `ComposePhaseContent`.

- Source: `app/src/main/java/com/baer/handtype/feature/template/TemplateScreens.kt` (`HandwritingRenderScreen` ~L421, `ComposePhaseContent` ~L600, `BackgroundPicker` ~L1040, `BackgroundPresetCard` ~L1129).
- Paper catalog: `app/src/main/java/com/baer/handtype/template/NoteBackgrounds.kt` (`NoteBackgroundPreset`, `NoteBackgroundCatalog`, `NoteBackgroundPreferences`).
- Renderer entry: `app/src/main/java/com/baer/handtype/template/HandwritingBitmapRenderer.kt` (`render` / `renderCursive`).
- Reached from the chooser via `onTemplateSelected` -> `RootRoute.RenderTemplate`. The template bitmap glyphs/typeface are loaded off the IO dispatcher before this screen renders.

Screenshots: ![Editor filled](../../artifacts/ux/14_editor_filled.png) ![Editor typed](../../artifacts/ux/13_editor_typed.png)

## Screen layout

`ComposePhaseContent` is a single vertical-scroll `Column` (16dp horizontal, 12dp vertical padding, 12dp item spacing), top to bottom:

1. **Title row** (`SpaceBetween`): template display name (`headlineSmall`) on the left, a "Vorlagen" / "Templates" `TextButton` on the right (`render_templates_button`, fires `onBackToTemplates`).
2. **Instruction line** (`render_instruction`, `bodyMedium`, `onSurfaceVariant`).
3. **Message field** inside a 220dp-tall `Box`: a `PaperPreviewSurface` (the selected paper, 16dp rounded) sits behind a transparent-container `OutlinedTextField`.
4. **Paper picker** (`BackgroundPicker`): section header + selected-paper label/badges + a horizontally scrolling swatch row.
5. **Primary action**: green full-width "Erstellen" / "Generate" `Button`.
6. **Secondary action**: full-width outlined "Meine Handschrift verwenden" / "Use My Handwriting" `OutlinedButton`.

## Inputs

### Message field

| Property | Value |
|----------|-------|
| Composable | `OutlinedTextField` |
| State | `text: String`, hoisted to `HandwritingRenderScreen` as `rememberSaveable` (survives config change) |
| Label | `render_text_label` ("Deine Nachricht" / "Your message"), floating |
| Placeholder | `render_text_placeholder` ("Hello, world!") |
| `maxLines` | 20 |
| Container | transparent (focused/unfocused/disabled/error all `Color.Transparent`) so the paper preview shows through |
| Size | fills the 220dp `Box` |
| Test tag | `HandTypeTestTags.RENDER_MESSAGE_INPUT` = `"render_message_input"` |

No character cap, no input filter, no keyboard-action wiring (default IME behavior). The field overlays `PaperPreviewSurface`, so its background is the chosen paper, not a solid field surface.

### Paper selection

Selection is held by `selectedBackgroundId: String` (rememberSaveable). On screen entry both `selectedBackgroundId` and `defaultBackgroundId` are initialized from `NoteBackgroundPreferences.getDefaultBackgroundId()` (SharedPreferences `"note_background_prefs"`, key `"default_background_id"`, falling back to `"warm_cream"`).

## Validation and empty handling

There is no enabling/disabling guard on either action. The "Erstellen" button is always enabled.

**Blank message on Generate.** `generate()` (~L482) substitutes the blank message with a hardcoded English filler paragraph:

```
"The quick brown fox jumps over the lazy dog. Every morning she would sit by the window,
watching the rain trace patterns on the glass. It was a quiet kind of beauty, the sort that
only patience could reveal."
```

The substitution is silent (`text.ifBlank { ... }`), not localized, and produces a real, auto-archived result as if the user had typed it. On a German-locale device this renders an English note with no hint that it was filler. See Known UX gap E2.

**Missing glyphs.** Characters with no glyph in the template are not blocked at input time. They are reported only after render: `RenderResult.missingCharacters` is surfaced on the Result phase as a notice. The renderer supports A-Z, a-z, 0-9 and `. , ! ? ' -`; everything else falls into the missing set.

## Paper options

Source of truth: `NoteBackgroundCatalog.selectablePresets`. Eleven selectable presets in row order (six free, five premium), plus one non-selectable transparent preset used only for transparent export.

| # | id | Pattern | Tier | Notes |
|---|-----|---------|------|-------|
| 1 | `plain_white` | Solid | free | |
| 2 | `warm_cream` | Solid | free | default (`NoteBackgroundCatalog.DEFAULT_ID`), `0xFFF8F1E4` |
| 3 | `soft_butter` | Solid | free | |
| 4 | `rose_blush` | Solid | free | |
| 5 | `cool_mist` | Solid | free | |
| 6 | `soft_sage` | Solid | free | |
| 7 | `parchment_sheet` | Texture | premium | |
| 8 | `recycled_sheet` | Texture | premium | |
| 9 | `kraft_sheet` | Texture | premium | |
| 10 | `ruled_sheet` | Ruled | premium | |
| 11 | `graph_sheet` | Graph | premium | |
| - | `transparent` | - | - | non-selectable; used only by transparent PNG export |

`NoteBackgroundPreviewPattern` values: `Solid`, `Texture`, `Ruled`, `Graph`. Each preset carries `previewColors: List<Int>` and a `labelRes` for its localized name. Preview line colors are drawn in `PaperPreviewSurface` (ruled `0xFF9BB7DB` with margin `0xFFD58F8F`; graph `0xFF8DB2D2`; texture flecks).

### Picker behavior (`BackgroundPicker`)

- **Header row**: section title `render_background_title` ("Papier" / paper) with, below it, the selected preset's name (`stringResource(selectedPreset.labelRes)`), plus inline pills:
  - a premium pill (`template_chooser_premium_badge`, brown `0xFF7C4D11` @ 12% bg) when `selectedPreset.premium`;
  - a default pill (`render_background_default_badge` "Standard"/"Default", green `0xFF23443A` @ 12% bg) when the selection is the current default.
- **Make-default action**: a "Als Standard" / "Make default" `TextButton` (`render_background_set_default`) shown only when the selection is not already the default. It calls `NoteBackgroundPreferences.setDefaultBackgroundId(selectedBackgroundId)` and updates local `defaultBackgroundId`.
- **Swatch row**: horizontally scrolling `Row` (8dp gap) of `BackgroundPresetCard`, one per selectable preset. Tapping a card calls `onBackgroundSelected(preset.id)`.

### Swatch (`BackgroundPresetCard`)

- 58dp-wide white `Card` containing a 50x40dp `PaperPreviewSurface` (with `showPremiumBadge = true`).
- Selected state: 2.5dp green border `0xFF23443A` + 4dp elevation + a brief bounce scale (`selectionBounceScale`).
- Default-but-not-selected: green border @ 24% alpha.
- Neither: 1dp near-black border @ 12% alpha (the low-contrast resting state; see E4).
- No per-swatch text label; the active paper name appears only in the header above.

Premium presets are selectable here (no paywall gate inside this picker); the premium upsell is the separate "Meine Handschrift verwenden" button and the chooser's premium card.

## Actions

### Erstellen / Generate (primary)

- Full-width filled `Button`, green `0xFF23443A`, 18dp corners, 16dp vertical padding, `titleMedium` label (`render_generate_button`).
- Test tag `HandTypeTestTags.RENDER_GENERATE_BUTTON` = `"render_generate_button"`. Fires a `LongPress` haptic, then `onGenerate` -> `generate()`.
- `generate()`: sets phase to `Generating`, picks `seed = System.nanoTime()`, calls `renderNote(inputText, selectedBackgroundId, seed)` on `Dispatchers.Default`. Render config: `maxWidthPx = (layoutWidth - 32dp)`, `targetLineHeightPx = 76dp`, `noteBackground = presetOrDefault(selectedBackgroundId)`. Stroke-based templates route to `renderCursive`, others to `render`.
- On success: stores `resultBitmap`, `missingChars`, `lastRenderedText`, `lastRenderSeed`; switches to `Result`; best-effort archives the bitmap to history (`OutputExporter.archiveBitmap`, `Dispatchers.IO`).

### Meine Handschrift verwenden / Use My Handwriting (secondary)

- Full-width `OutlinedButton`, 18dp corners, 14dp vertical padding, label `render_premium_button`. Calls `onPremiumSelected` -> `RootRoute.PremiumCapture`.
- This is the premium capture entry point (photograph a practice sheet, extract glyphs on-device). It uses no brand color: the outline and text inherit the leaked default M3 purple, so it does not read as premium. See E1 / E5.

## Navigation

- **Into the editor**: chooser `onTemplateSelected` sets the selected template id and routes to `RootRoute.RenderTemplate`; the template loads off the IO dispatcher (a `LoadingTemplateScreen` may appear after a 400ms delay) before `HandwritingRenderScreen` shows in its `Compose` phase.
- **Back to chooser**: the only on-screen route is the "Vorlagen" `TextButton` (`onBackToTemplates`). There is no `TopAppBar`, no leading back arrow, and no `BackHandler` in this phase. System back is whatever the host activity provides. See E3.
- **Forward to Result**: a successful Generate transitions phase `Compose` -> `Generating` -> `Result` via `AnimatedContent` inside the same `HandwritingRenderScreen` (not a separate route). Returning from Result via `onBackToCompose` clears the result bitmap and returns to this phase; "New note" additionally clears `text` and resets paper to `defaultBackgroundId`.
- **To premium capture**: secondary button -> `RootRoute.PremiumCapture`.

## State and persistence

| State | Holder | Survives config change | Survives process death |
|-------|--------|------------------------|------------------------|
| `text` | `rememberSaveable` | yes | yes |
| `phase` | `rememberSaveable` (String) | yes | yes |
| `selectedBackgroundId` | `rememberSaveable` | yes | yes |
| `defaultBackgroundId` | `rememberSaveable` | yes | yes |
| default paper | SharedPreferences | n/a | yes (persistent) |
| `resultBitmap`, `missingChars` | `remember` | no | no (would force re-generate) |

There is no per-screen ViewModel; all state is composable-local.

## Theming note

This screen sets no `MaterialTheme.colorScheme`. Brand colors are per-component literals (green `0xFF23443A` primary, brown `0xFF7C4D11` premium, cream surfaces). Every component that reads an M3 color role falls back to baseline purple: the "Vorlagen" link, the `OutlinedTextField` focus ring and floating label, and the "Meine Handschrift verwenden" outlined button all render purple, coexisting with the green/cream brand palette.

## Known UX gaps

From `artifacts/wf/ux/editor.md`:

- **E1 (P0, theming).** Default M3 purple leaks across the editor (Vorlagen link, TextField focus outline + floating "Deine Nachricht" label, the outlined secondary button) while the rest is brand green/cream/brown. Two accent languages on one screen. Fix: define a real `MaterialTheme(colorScheme = ...)` in MainActivity mapping `primary` to green `0xFF23443A`, accent to brown `0xFF7C4D11`, surfaces to cream, so these components inherit brand color with no per-call overrides.
- **E2 (P0, empty-input handling).** Empty field + "Erstellen" silently renders a hardcoded English pangram on a German device (`generate()`, `text.ifBlank { "The quick brown fox..." }` ~L485-490), with no `enabled` guard and no notice. Fix: gate the button on `text.isNotBlank()`, or replace the hidden English string with a localized, clearly-labeled opt-in sample plus supporting text.
- **E3 (P1, affordance / nav).** "Vorlagen" is a purple `TextButton` in the title slot where a back affordance is expected; there is no top app bar and no `BackHandler`, so on-screen back is ambiguous and system back is undiscoverable. Fix: adopt an M3 `TopAppBar` with a leading `ArrowBack` (matching the Result phase, which already uses one) and the template name as title.
- **E4 (P1, picker labeling / contrast).** Six near-white swatches differ only by faint tint; only the selected paper is named, and selection is a thin 2.5dp green ring. Low contrast, recall-dependent. Fix: clearer M3 selected-card state (stronger border + check overlay), higher unselected-tile contrast, and surface the active paper name as the row scrolls.
- **E5 (P1, action hierarchy / color semantics).** The green "Erstellen" and the purple-outlined "Meine Handschrift verwenden" compete; the premium path uses the leaked default, not the brown premium accent used elsewhere. Fix (after E1): make the secondary button subordinate, outline it in brand brown `0xFF7C4D11`, and add a small premium/lock affordance so its meaning is explicit.
- **E6 (P2, input affordance).** The floating-label `OutlinedTextField` over a paper-preview `Box` with a transparent container can read as "is this editable?" before focus. Fix: add persistent supporting text ("Tippe deine Nachricht") and a clearer resting outline color (brand, post-E1); consider a filled text field for stronger affordance on a textured surface.
- **E7 (P2, layout / polish).** The paper section, swatch row, and two buttons sit in a flat scroll `Column` with uniform 12dp spacing and no grouping between "compose" and "options". Fix: group paper options in an M3 outlined/elevated section, pin the primary CTA, and go edge-to-edge with proper window insets so the CTA stays reachable with the keyboard open.
