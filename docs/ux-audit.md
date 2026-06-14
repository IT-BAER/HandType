# HandType UX / UI Audit

Android app (Kotlin, Jetpack Compose, Material3). Package `com.baer.handtype`. Single module, single Activity (`MainActivity`), enum-based navigation (`RootRoute`). minSdk 23, target/compile 34, AGP 8.4.2, Kotlin 1.9.22.

Audit basis: screenshots in `../artifacts/ux/` (Pixel 7 Pro, Android 16, device locale German), the per-screen UX findings in `../artifacts/wf/ux/`, and the architecture maps in `../artifacts/wf/map/`. Severities: P0 (breaks trust or core task), P1 (clear quality/consistency gap), P2 (polish).

---

## 1. Executive summary

HandType has good bones and a real "wow" moment. The cream-and-green palette reads warm and crafted, the home card communicates the value prop reasonably well, and the rendered handwriting on the output screen is genuinely impressive for a first-time user. The product also keeps a clean offline-first, no-analytics, privacy-first posture that matches its promise on the About screen.

The new-user experience then fractures within seconds, and almost all of it traces back to one root cause. `MainActivity` wraps the whole app in a bare `MaterialTheme { }` with no `colorScheme`, so every component that reads a Material 3 color token (TextField focus ring and floating label, `TextButton`, `OutlinedButton` text, links) renders in the default M3 baseline purple, while the brand green / cream / brown live as hardcoded `Color(0x..)` literals inside individual composables. The result is two visual identities on screen at once. The editor lights up with a saturated purple focus ring and purple "Deine Nachricht" label; the output screen shows six actions in three colors (green filled, brown filled, purple outlined / link) with no single primary; the premium capture intro, the most important conversion surface, paints its entire card and primary CTA purple. To a new user this reads as half-finished or third-party, not premium.

Top themes, in priority order:

1. No Material 3 theme. The purple leak vs hardcoded brand is the single highest-leverage fix in the audit (Section 2).
2. Flat action hierarchy. Output (6 actions, 3 colors) and several modals have no clear single primary; M3 button emphasis levels are unused.
3. No onboarding / value-prop / permission priming, and cold start can restore a deep screen instead of home.
4. Trust and consistency details: a user-facing `v0.1.0-debug` build string, hand-rolled headers instead of real `TopAppBar`s, an icon-less drawer, silent English filler text on empty input, and external GitHub jumps that contradict the offline-first promise.
5. Missing 2026 platform baselines: no dark mode, no dynamic color, weak edge-to-edge / predictive-back integration, and accessibility gaps (low-contrast purple/grey text, custom controls without proper semantics).

---

## 2. The #1 systemic issue: no Material 3 theme

### Root cause

Confirmed in `app/src/main/java/com/baer/handtype/MainActivity.kt` (~line 60):

```kotlin
setContent {
    MaterialTheme {           // no colorScheme, no typography, no shapes
        HandTypeRoot()
    }
}
```

The platform Activity theme is also bare (`@android:style/Theme.Material.Light.NoActionBar` per `AndroidManifest.xml`). Two consequences:

- Every composable that resolves a color role (`primary`, `secondary`, `onSurfaceVariant`, `error`, etc.) falls back to the M3 baseline scheme, whose `primary` is purple. This is why the editor focus ring/label, the "Vorlagen" link, `Als PDF exportieren`, `Ohne Hintergrund speichern`, `Neu erstellen`, `Neue Notiz`, the History modal's `Löschen`/`Schließen`, the About GitHub button, and the entire capture-intro CTA render purple.
- The brand palette is duplicated as literals scattered across feature files: green `Color(0xFF23443A)` (primary CTAs, selected states), brown `Color(0xFF7C4D11)` (premium / Share accent), cream `Color(0xFFF5EFE4)` / `Color(0xFFFFF9F2)` (surfaces), red `Color(0xFFAA3333)` (destructive). There is no single source of truth, no dark scheme, no dynamic color, and no shape/spacing scale.

### Impact

- Two visual identities coexist on nearly every screen (see `15_output.png`, `13_editor_typed.png`, `23_capture_intro.png`). Purple reads as "unfinished" or "a different app," undermining the premium positioning exactly where it matters (the capture upsell).
- Color carries no consistent meaning: a new user cannot tell which color means "primary action," "premium," or "destructive," because purple is simultaneously a link color, a secondary-button color, and the capture primary.
- Several purple-on-cream and grey-on-cream text roles are low-contrast and likely fail WCAG AA 4.5:1 (Section 4).
- Maintenance cost: every brand color is a copy-pasted literal, so any palette change is a find-and-replace across files.

### Fix

Install a real M3 theme once, at the root, and migrate components from literals to tokens:

1. Define `lightColorScheme` and `darkColorScheme` with the brand green as the seed/`primary`, brown as `secondary`/`tertiary`, cream as `surface`/`background`, red as `error`. This single change repaints every leaked purple element app-wide.
2. Wire it into `MaterialTheme(colorScheme = ..., typography = ..., shapes = ...)` in `MainActivity`, branching on `isSystemInDarkTheme()`.
3. Offer optional Material You dynamic color on SDK 31+ (`dynamicLightColorScheme`/`dynamicDarkColorScheme(context)`), falling back to the brand scheme so identity is never lost.
4. Centralize the four literals and the corner-radius / spacing values into design tokens, then replace per-component `Color(0x..)` usages with `MaterialTheme.colorScheme.*` roles.
5. Drive the status / navigation bar colors through the theme.

### Illustrative before/after (NOT yet wired in)

This sketch is illustrative only. It is not present in the repo and should be reviewed before adoption (exact tonal values for `onPrimary`, container roles, and dark surfaces need tuning against the cream brand).

```kotlin
// Brand tokens (illustrative): replaces scattered Color(0x..) literals.
private val BrandGreen  = Color(0xFF23443A) // primary CTAs, selected states
private val BrandBrown  = Color(0xFF7C4D11) // premium / share accent
private val BrandCream  = Color(0xFFF5EFE4) // surfaces
private val BrandCreamHi = Color(0xFFFFF9F2)
private val BrandRed    = Color(0xFFAA3333) // destructive

private val HandTypeLight = lightColorScheme(
    primary        = BrandGreen,
    onPrimary      = Color.White,
    secondary      = BrandBrown,
    onSecondary    = Color.White,
    tertiary       = BrandBrown,
    background     = BrandCream,
    surface        = BrandCreamHi,
    onSurface      = Color(0xFF1A1410),
    onSurfaceVariant = Color(0xFF5A4F42), // darkened for >=4.5:1 on cream
    error          = BrandRed,
)

private val HandTypeDark = darkColorScheme(
    primary   = Color(0xFF8FC9B4),
    secondary = Color(0xFFD9A86A),
    background = Color(0xFF14110D),
    surface    = Color(0xFF1E1A15),
    error      = Color(0xFFE08A8A),
)

@Composable
fun HandTypeTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamic: Boolean = false, // opt-in Material You
    content: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> HandTypeDark
        else -> HandTypeLight
    }
    MaterialTheme(
        colorScheme = scheme,
        typography  = HandTypeTypography, // brand type scale (illustrative)
        shapes      = HandTypeShapes,     // small/medium/large radii (illustrative)
        content     = content,
    )
}

// MainActivity (after):
// setContent { HandTypeTheme { HandTypeRoot() } }
```

After this lands, the per-screen "purple leak" findings below (H3, E1, O2, H2, INF-2, CAP-01, CC-01) collapse into this one change, and the remaining work is demotion/hierarchy and copy.

---

## 3. Per-screen findings

### 3.1 Home / Template chooser

Screenshot: `../artifacts/ux/10_firstrun.png`, `../artifacts/ux/11_template2.png`

| Severity | Category | Issue | Recommendation | M3 reference | Effort |
|----------|----------|-------|----------------|--------------|--------|
| P0 | Value prop / onboarding | No explanation of what the app does. Title "Handschriftstil wählen" + subtitle ("Built-in templates are the default path…") describe a chooser, not the benefit. No first-run intro. | Add a one-line benefit ("Tippe Text, erhalte glaubwürdige Handschrift") and a once-shown lightweight onboarding gated behind a DataStore flag. | M3 onboarding / supporting text | M |
| P0 | Discoverability | Carousel signalled only by two ~6dp dots + a sliver of the next card; new users miss the second template ("Fließende Kursivschrift"), i.e. 50% of the free catalog. | Increase next-card peek (smaller pager `contentPadding`), add a "1/2" count or arrow; consider `HorizontalMultiBrowseCarousel`. | M3 Carousel (multi-browse); pager peek | M |
| P0 | Consistency / theming | Token-driven text (subtitle/descriptions via `onSurfaceVariant`) is default-M3 themed while CTAs are hardcoded; purple surfaces on any focus. | Section 2 theme. | M3 color roles | L (root) |
| P1 | Copy / preview semantics | Sample "Meet HandType." reads as a slogan; Ink Script preview not visibly handwritten. | Render every card sample in its actual handwriting style; use note-like copy ("Liebe Grüße, bis bald!"); label "Vorschau". | M3 cards (meaningful media) | S |
| P1 | Visual hierarchy | Premium "Meine Handschrift" card (6dp elevation, full-width brown CTA, warm tint) competes with the free primary path. | Demote premium to tonal/outlined, lower elevation; one dominant primary per view. | M3 emphasis hierarchy | M |
| P1 | Components / standards | Hamburger is a hand-built 3-bar `Box` stack; in-card CTAs and "New" pill are custom `Box`+`Text`, not M3 `Button`, so no ripple/focus/disabled states and no button semantics. | Use `Icons.Default.Menu` (with `contentDescription`); replace in-card boxes with `Button`/`FilledTonalButton`. | M3 IconButton, Button state layers | M |
| P1 | Tap target / interaction | Whole card tappable via custom `awaitEachGesture` that selects on any up-event and fires a `LongPress` haptic on a normal tap. | Use `Card(onClick=...)` or wire the visible CTA's `onClick`; reserve long-press haptics for actual long-press. | M3 clickable Card semantics | M |
| P2 | Edge-to-edge | Cream surface does not visibly extend under a transparent status bar. | Apply window insets to the top row so cream reaches the edge. | Edge-to-edge + WindowInsets | S |
| P2 | Branding / polish | Generic header; raw `v0.1.0-debug` shown in drawer; no wordmark near title. | Add a small brand lockup; hide debug version from user surfaces. | M3 large/flexible top app bar | S |
| P2 | Accessibility / contrast | Inactive carousel dot is green @0.3 alpha on cream and only 6dp; the "there is more" cue is the lowest-contrast element. | Raise inactive-dot contrast/size or use a labelled indicator. | M3 contrast 4.5:1 | S |

### 3.2 Editor

Screenshot: `../artifacts/ux/13_editor_typed.png`, `../artifacts/ux/14_editor_filled.png`

| Severity | Category | Issue | Recommendation | M3 reference | Effort |
|----------|----------|-------|----------------|--------------|--------|
| P0 | Consistency / theming | Purple leaks in three visible places at once: "Vorlagen" link, TextField focus outline + floating "Deine Nachricht" label, and the outlined "Meine Handschrift verwenden" button. | Section 2 theme. | M3 color roles | M (root) |
| P0 | Empty-input handling | Empty field + "Erstellen" silently renders a hardcoded English pangram ("The quick brown fox…") on a German device (`TemplateScreens.kt` L485-490, `text.ifBlank { ... }`); no `enabled` guard. | Disable the primary button (`enabled = text.isNotBlank()`), or offer an explicit, localized "Beispieltext" chip the user opts into. | M3 Button enabled state; supporting text | S |
| P1 | Affordance / nav | "Vorlagen" is a purple `TextButton` in the title slot where a back affordance is expected; no `TopAppBar`, no `BackHandler` in this screen. | Adopt an M3 `TopAppBar` with leading `ArrowBack` (matches the Result screen at L860-865); template name as title. | M3 top app bar; predictive back | M |
| P1 | Picker labeling / contrast | Six near-white paper swatches, only one named, selection = thin 2.5dp green ring (`BackgroundPresetCard` L1129-1167); hard to tell apart on cream. | M3 selectable cards with stronger border + check overlay; surface the active swatch name on scroll; raise unselected-tile contrast. | M3 selectable states | M |
| P1 | Action hierarchy / color | Primary green "Erstellen" and secondary purple-outlined "Meine Handschrift verwenden" compete; premium upsell does not look premium. | After theme fix, outline the secondary in brand brown and add a "Premium" glyph so its meaning is explicit. | M3 button hierarchy | S |
| P2 | Input affordance | Floating-label `OutlinedTextField` over a paper preview can read as "is this editable?" before focus. | Add persistent supporting text ("Tippe deine Nachricht") and a clear resting outline color; consider an M3 filled text field on the textured surface. | M3 text fields, supporting text | S |
| P2 | Layout / polish | Paper section, swatches, and buttons sit in a flat scroll Column with equal weight; no grouping; verify IME insets so the CTA stays reachable with the keyboard open. | Group paper options in a section; pin the primary CTA; apply window/IME insets. | M3 cards; edge-to-edge | M |

### 3.3 Output / Result

Screenshot: `../artifacts/ux/15_output.png`

| Severity | Category | Issue | Recommendation | M3 reference | Effort |
|----------|----------|-------|----------------|--------------|--------|
| P0 | Hierarchy / IA | 6 actions, flat hierarchy, two filled co-primaries (green Speichern, brown Teilen) plus four more. | Pick ONE primary; demote the rest; fold the 3 export variants into one "Exportieren" entry that opens a `ModalBottomSheet`. Consider bottom app bar + FAB. | M3 bottom app bar/FAB, bottom sheets, emphasis | M |
| P0 | Color / theming | Three button colors on one screen; purple is the leaked default. | Section 2 theme. | M3 color roles | M (root) |
| P0 | Feedback | No visible success confirmation. Save/PDF/transparent fire only a `Toast` (L803-806, L844, L558-562); screen does not change, inviting re-taps and duplicate files. | Replace toasts with an M3 `Snackbar` anchored to the Scaffold, with an "Öffnen" action and the save location ("Gespeichert in Bilder/HandType"). | M3 Snackbar | S |
| P0 | Labeling / clarity | "Neu erstellen" (regenerate, keeps text) vs "Neue Notiz" (clears, back to compose) are near-identical twins. | Differentiate by label + icon ("Anderer Stil" with refresh vs "Neue Notiz" with plus); or move regenerate onto the preview card as an icon button. | M3 icon buttons; content guidelines | S |
| P1 | Overlap / redundancy | Save PNG, Share, Export PDF, Save transparent PNG are 4 overlapping "get it out" actions as 4 controls. | Collapse into one "Teilen / Exportieren" affordance opening a `ModalBottomSheet` with each option + a one-line description. | M3 bottom sheets; ACTION_SEND | M |
| P1 | Discoverability / preview | Pinch-zoom + double-tap reset (L898-917) exist but nothing signals them. | Add a "Zum Zoomen tippen" caption or zoom affordance, or a one-time coach hint. | M3 supporting text/tooltip | S |
| P1 | Affordance mismatch | "Ohne Hintergrund speichern" is a borderless purple `TextButton` (L979-985) that actually writes a file. | Make it a proper low-emphasis button grouped with exports, or fold into the export sheet. | M3 button emphasis | S |
| P1 | App bar / structure | Header is a hand-rolled `Row` (back arrow + centered title + "Vorlagen" text button), no inset handling, no scroll behavior. | Use `CenterAlignedTopAppBar` with nav icon = back and a single action; apply `WindowInsets`. | M3 top app bars; edge-to-edge | M |
| P2 | Content / tone | Yellow tip card explaining the buttons is a band-aid for the confusing action set. | After consolidation, drop it or repurpose for a genuine tip ("Tippe das Bild an, um es zu vergrößern"). | M3 cards; progressive disclosure | S |
| P2 | Shape / expressive | Static 18dp rounded rects, no press shape-morph beyond `pressScale`. | Adopt M3 expressive shapes/motion on the primary; tonal/elevated for depth. | M3 Expressive | S |

### 3.4 History

Screenshot: `../artifacts/ux/17_history.png`, `../artifacts/ux/18_history_detail.png`, `../artifacts/ux/25_history_empty.png`

| Severity | Category | Issue | Recommendation | M3 reference | Effort |
|----------|----------|-------|----------------|--------------|--------|
| P0 | Empty state | Empty History (`25_history_empty.png`) is one line of grey text centered on blank cream: no icon, illustration, or CTA (`HistoryScreen.kt` L232-244). | Build a real M3 empty state: icon/illustration + headline + supporting text + a filled "Notiz erstellen" button (brand green) routing to the editor. | M3 empty states; filled Button | M |
| P0 | Color consistency | Detail modal has 4 actions in 3 palettes: green Speichern, brown Teilen, purple-outlined Löschen (L500), purple Schließen (L507). | Section 2 theme. Local stopgap: pass `colors` so destructive reads red and Close reads brand-neutral. | M3 color roles | S (local) / M (theme) |
| P0 | Thumbnail crop | `ContentScale.Crop` + `aspectRatio(1f)` (L365-369) center-crops, showing the middle ("lt dies ist") not the start of the note. | Top-left anchored crop (`Alignment.TopStart`) or `FillWidth` in a clipped fixed-height box; first words are the note's identity. | ContentScale; M3 card media | S |
| P1 | Layout / density | `GridCells.Fixed(2)` (L247) leaves a single note as a tiny half-width card with a vast empty screen, reading as broken. | `GridCells.Adaptive(minSize = 160.dp)`; consider a list/large-card layout below a threshold count. | M3 responsive grids | S |
| P1 | Action overload | Detail modal stacks Save/Share/Delete/Close flat (L485-508); destructive sits with positive actions. | Reduce to one primary + dismiss; demote Delete to overflow/below a divider; consider `ModalBottomSheet` for image-led detail. | M3 dialogs vs bottom sheets | M |
| P1 | Missing capability | No rename, search, or sort, though the data layer already reads a `.name` sidecar (`HistoryStore.parse` L114). | Add rename in the detail modal (writes the existing sidecar); add search/sort in a real app bar as count grows. | M3 top app bar actions; text fields | M |
| P1 | App bar / branding | Custom `Row` with a `☰` text glyph (L189) instead of a `TopAppBar` + `Icon`. | `TopAppBar`/`LargeTopAppBar` with `Icons.Default.Menu` IconButton; gains 48dp target, ripple, insets, scroll. | M3 top app bars | M |
| P2 | Selection discoverability | Long-press multi-select is undiscoverable. | Surface via a card overflow or an "Auswählen" app bar action; keep long-press as accelerator. | M3 selection patterns | S |
| P2 | Destructive styling | Delete-confirm `AlertDialog` confirm button is a plain `TextButton` (L313) rendering purple, not red. | Color confirm with red / `colorScheme.error`. | M3 dialogs; error role | S |
| P2 | Edge-to-edge / insets | Hand-rolled header uses ad hoc padding, not app-bar window insets. | Folds into the `TopAppBar` migration. | M3 app bar insets | S |

### 3.5 Info / Navigation (drawer, Help, FAQ, About)

Screenshot: `../artifacts/ux/16_drawer.png`, `../artifacts/ux/19_help.png`, `../artifacts/ux/20_faq.png`, `../artifacts/ux/21_about.png`, `../artifacts/ux/22_privacy.png`

| Severity | Category | Issue | Recommendation | M3 reference | Effort |
|----------|----------|-------|----------------|--------------|--------|
| P0 | Trust / polish | Drawer header shows `v${BuildConfig.VERSION_NAME}` = "v0.1.0-debug"; About repeats "Version 0.1.0-debug" (debug variant carries `versionNameSuffix = "-debug"`). | Format a clean release version ("1.0"); hide it in the drawer header (reserve for brand) and keep it only on About. | M3 drawer header = brand | S |
| P0 | Consistency / theming | About's "Quellcode auf GitHub ansehen" `OutlinedButton` (InfoScreens.kt ~L241) has no color override, so it is the only purple element on the screen. | Section 2 theme. Local stopgap: `ButtonDefaults.outlinedButtonColors(contentColor = green)` + brand border. | M3 color roles; outlined buttons | S (local) / M (theme) |
| P0 | Offline-first tension | Datenschutz and About's GitHub button open external `ACTION_VIEW` to github.com (needs network), one line below "nichts über deinen Text verlässt dein Gerät". Privacy policy is unreachable offline. | Bundle `PRIVACY.md` as an in-app asset rendered on an in-app screen; demote GitHub to a clearly secondary external action with an `OpenInNew` icon. | M3 in-app content; external-link affordance | M |
| P1 | Navigation / IA | Drawer items have no leading icons and no grouping; work destinations (Vorlagen, Verlauf) sit with info/legal (Hilfe, FAQ, Über, Datenschutz) at equal weight. | Add leading icons to every `NavigationDrawerItem`; split into a primary group and a secondary "Info" group via a section label / `HorizontalDivider`. | M3 navigation drawer (icon slot, sections) | M |
| P1 | Onboarding / learnability | Help is five numbered text-only paragraphs, no icons/imagery, for a visual product. | Convert steps to M3 list items / step cards with numbered avatars and small visuals; consider a first-run carousel or "Try a sample" CTA. | M3 lists/cards; onboarding | M |
| P1 | Affordance | Datenschutz looks identical to in-app destinations but ejects to a browser with no signal. | Append a trailing `OpenInNew` icon, or move it to a footer "links" zone visually distinct from navigation. | M3 external-link affordance; drawer trailing slot | S |
| P2 | Iconography | Hamburger and FAQ expanders are Unicode glyphs (`☰`, `▴/▾`) drawn as `Text`. | Replace with `Icons.Default.Menu` and `ExpandMore/ExpandLess`, tinted brand. | M3 Icons | S |
| P2 | App bar / 2026 polish | Info screens use `CenterAlignedTopAppBar`; no large/flexible bar, no scroll-collapse for content screens. | Consider `LargeTopAppBar` for Help/About; ensure edge-to-edge + predictive back app-wide. | M3 large/flexible top app bars | M |
| P2 | Empty / footer space | Drawer reserves a bottom spacer but renders nothing; lower ~60% is empty. | Use the footer for a divider + secondary items (language, theme later, GitHub/Datenschutz as footer links). | M3 drawer footer | S |

### 3.6 Capture (premium)

Screenshot: `../artifacts/ux/23_capture_intro.png`, `../artifacts/ux/24_capture_perm.png`

| Severity | Category | Issue | Recommendation | M3 reference | Effort |
|----------|----------|-------|----------------|--------------|--------|
| P0 | Brand consistency | The primary CTA "Übungsblatt scannen oder importieren", the tip line, and the "Übungsblatt holen (PNG)" link all render purple via `colorScheme.primary` (CameraCaptureScreen.kt L196, L199-203, L226). The whole intro card reads lilac. The marquee paid feature's main button is the one un-branded color. | Section 2 theme; primary becomes brand green (or brown premium accent). Align the intro card surface/elevation to other cards. | M3 color roles | M (root) |
| P0 | Information scent / labeling | "Übungsblatt holen (PNG)" actually calls `shareTemplateSheet()` (`ACTION_SEND`, L200, L276-288); chooser title "Send practice sheet" and subject are hardcoded English (L281, L284). | Relabel to the true action ("Übungsblatt teilen/drucken"); move chooser strings into `strings.xml`; offer print/save if that is the intent. | M3 text button; Android sharesheet; i18n | S |
| P0 | Permission priming | No in-app priming; tapping the button jumps straight into ML Kit's black scanner (L131-134), which requests camera permission with no HandType rationale and no transition from the cream intro. | Add a one-line "what happens next" (stays on-device, opens scanner) and an optional first-run bottom-sheet primer before launch. | M3 bottom sheets; Android permission rationale | M |
| P1 | Navigation | Intro is a bare `Scaffold` + scrolling `Column` (L143-154) with no `TopAppBar` and no labeled back; exit relies on system back only. | Add an M3 top app bar (medium/large suits the marquee feature) with a back nav icon and title. | M3 top app bars; predictive back | S |
| P1 | Error handling / recovery | Failures split weakly: decode/launch errors show a plain `errorContainer` text card with no retry (L208-222); "no glyphs"/"marker failed" only overwrite `instructionText` (MainActivity L316-323). | A clear inline error state with an explicit "Erneut scannen" button; surface marker/glyph failures as a distinct result; show an annotated example sheet. | M3 error states; buttons | M |
| P1 | Visual hierarchy / motivation | Reading-heavy intro (4 steps + tip) with no preview of a filled sheet and no depiction of the result. Effort visible, reward invisible. | Lead with a small visual (filled-sheet thumbnail, typed-to-handwriting before/after); M3 expressive emphasis on the CTA; collapse steps into an expandable area. | M3 expressive; cards with media | M |
| P2 | Identity handoff | ML Kit scanner (`24_capture_perm.png`) is a system activity with its own black chrome and "Manuell/Automatische Aufnahme" toggle; HandType cannot restyle it. | Smooth the seam: clear loading copy ("Scanner wird geöffnet…"), and on return land on a branded confirmation/preview, not the bare scanner. Document the constraint. | ML Kit constraints; M3 motion | S |
| P2 | Affordance clarity | The scanner's "Manuell" vs "Automatische Aufnahme" toggle is the first in-flow decision and is unexplained. | In the priming, recommend the mode that best captures the full page with all four corner markers. | M3 segmented buttons labeling | S |

---

## 4. Cross-cutting

### Accessibility

- Contrast: thin purple-on-cream links ("Als PDF exportieren", the long "Ohne Hintergrund speichern (transparentes PNG)" line) and grey empty-state text on near-white likely fail WCAG AA 4.5:1 for normal text. After the Section 2 theme, choose `onSurface`/`onSurfaceVariant`/link roles that clear 4.5:1 on cream and verify with an automated checker. (Refs: `25_history_empty.png`, `15_output.png`.)
- Touch targets and semantics: the hamburger (3-bar `Box`), in-card "buttons" (`Box`+`Text`), and the `☰`/`▴▾` text glyphs lack the 48dp target, ripple, and button/role semantics of real M3 components, so TalkBack does not announce them as buttons. Migrating to `IconButton`/`Button`/`Icon` fixes target size, focus, and semantics in one pass.
- Content descriptions: camera-screen icon buttons (X, flash, gallery import) are icon-only; add `contentDescription` and M3 tooltips. The menu IconButton needs a `contentDescription` too.

### Dark mode

No `darkColorScheme` and no `isSystemInDarkTheme()` branch: the bright cream UI is forced regardless of system setting, and the dark camera viewfinder makes the inconsistency more obvious. Add a dark scheme that preserves brand hues on dark surfaces, and route status/nav bar colors through the theme (Section 2).

### Dynamic color (Material You)

No dynamic color on SDK 31+ despite an Android 16 target device. Offer opt-in `dynamicLightColorScheme`/`dynamicDarkColorScheme(context)` with the brand scheme as the default fallback so identity is protected.

### Motion / predictive back

`HandTypeRoot` drives navigation through a hand-rolled enum router and `AnimatedContent` slide transitions (`routeTransitionSpec`, `tween(260)`), with no Jetpack Navigation and no back stack: `BackHandler` collapses any deep route straight to `TemplateChooser` rather than the actual previous screen. There is no predictive-back preview tying the gesture to its destination. Wire predictive back (`enableOnBackInvokedCallback`, Compose predictive-back APIs); reconsider real back-stack semantics so back returns to the previous screen, not always home.

### Edge-to-edge

`enableEdgeToEdge()` is called in `onCreate`, but content on several screens starts near the status bar / gesture pill and headers are hand-rolled `Row`s without app-bar insets. Apply `WindowInsets` (statusBars/navigationBars/ime) via Scaffold or `.windowInsetsPadding`, and verify IME inset handling in the editor with the keyboard open.

### Content / voice

Mixed quality: home subtitle and the "Meet HandType." sample read like internal/marketing copy rather than a benefit or a demo; "Neu erstellen" vs "Neue Notiz" are too similar in German; the capture "holen (PNG)" label misdescribes a share action; the loading screen and the share-chooser title/subject are hardcoded English literals on a 10-locale app (German device sees English). Move all user-facing strings into `strings.xml`, rewrite labels to match the real action, and align voice toward concrete benefit/outcome.

### Offline-first vs external GitHub links

The product promises offline-first, no analytics, "nothing leaves your device," yet both Datenschutz (`navigate(Privacy)` -> `ACTION_VIEW` to `GITHUB_PRIVACY_URL`) and About's source button leave the app to GitHub and require a network and a browser/GitHub app, and can fail offline. The privacy policy, of all things, is unreachable without a connection. Bundle `PRIVACY.md` in-app as a scrollable offline screen, and present the GitHub source link as a clearly-marked optional external action with an `OpenInNew` affordance.

---

## 5. Prioritized backlog

### P0 (breaks trust or core task)

1. Install a real M3 theme (brand `ColorScheme` + dark + tokens) in `MainActivity`; removes the purple leak app-wide. Effort: L. Highest value, unblocks most P1 color items.
2. Output screen: replace the toast-only save feedback with an M3 `Snackbar` (action + save location). Effort: S.
3. Editor: stop silently rendering the English pangram on empty input; disable the primary button or offer an explicit localized sample. Effort: S.
4. Output screen: establish a single primary action; consolidate the 4 export variants into one entry + `ModalBottomSheet`. Effort: M.
5. Output: rename/disambiguate "Neu erstellen" vs "Neue Notiz" with distinct labels + icons. Effort: S.
6. History: real empty state (icon + headline + "Notiz erstellen" CTA). Effort: M.
7. History: fix thumbnail crop to top-left so the start of the note shows. Effort: S.
8. Drawer/About: hide the `v0.1.0-debug` build string from user-facing surfaces. Effort: S.
9. Bundle `PRIVACY.md` in-app (offline-readable); demote GitHub links to clearly-marked external actions. Effort: M.
10. Capture: prime the camera/scanner handoff (what happens next, stays on-device). Effort: M.
11. Capture: relabel "Übungsblatt holen (PNG)" to its true share action and localize the English chooser strings. Effort: S.
12. Onboarding/value-prop on first run, and default cold start to home (restore route only within a session). Effort: M.

### P1 (clear quality / consistency gaps)

1. Replace hand-rolled headers with real M3 `TopAppBar`s app-wide (editor, output, history, capture), with nav icon + insets. Effort: M.
2. Establish button-emphasis hierarchy everywhere (Filled > Tonal > Outlined > Text); reserve red for destructive. Effort: M.
3. Home: stronger carousel discoverability (peek + count/arrow or multi-browse carousel). Effort: M.
4. Home: render card samples in their real handwriting style; rewrite sample copy. Effort: S.
5. Home: demote the premium card relative to the free primary path. Effort: M.
6. Replace custom `Box`/glyph controls with M3 `Button`/`IconButton`/`Icon` (ripple, target, semantics). Effort: M.
7. Editor: improve paper-swatch labeling, contrast, and selected state. Effort: M.
8. History: adaptive grid, demote Delete, add rename (sidecar already exists). Effort: M.
9. Drawer: leading icons + grouping + external-link affordance for Datenschutz. Effort: M.
10. Help: convert the text wall into step cards / list items with visuals. Effort: M.
11. Add dark mode and (opt-in) dynamic color. Effort: M.
12. Capture: actionable error/recovery state with "Erneut scannen" + example sheet. Effort: M.
13. Accessibility contrast pass after theming; automated check. Effort: S.

### P2 (polish)

1. Predictive-back integration and real back-stack semantics. Effort: M.
2. Centralized shape scale + spacing tokens; standardize card elevation. Effort: M.
3. Output: discoverable zoom affordance; M3 expressive shapes/motion. Effort: S.
4. Capture: smooth the ML Kit handoff (loading copy, branded return), recommend scan mode. Effort: S.
5. Edge-to-edge inset verification on all screens incl. IME. Effort: S.
6. Drawer footer zone (language, links). Effort: S.
7. Destructive-confirm styling (red confirm in delete dialog). Effort: S.
8. Camera icon-button content descriptions + tooltips. Effort: S.

---

## 6. Quick wins (high value, low effort)

- Hide the `v0.1.0-debug` string from the drawer header and About. (P0, S)
- Disable "Erstellen" on empty input (or add a localized sample). Stops the silent English pangram. (P0, S)
- Snackbar with save location instead of an invisible toast on the output screen. (P0, S)
- Top-left thumbnail crop in History so notes are recognizable. (P0, S)
- Disambiguate "Neu erstellen" vs "Neue Notiz" with distinct labels + icons. (P0, S)
- Relabel "Übungsblatt holen (PNG)" to the real share action and move the English chooser strings into `strings.xml`. (P0, S)
- Render home card samples in their actual handwriting style; rewrite the slogan copy as a note. (P1, S)
- Add leading icons to drawer items and an `OpenInNew` icon to Datenschutz. (P1, S)
- Local color stopgap on the worst purple offenders (History modal buttons, About GitHub button, delete-confirm) while the full theme lands. (P0/P1, S)

The single best investment remains Section 2: one real Material 3 theme erases the purple leak across every screen at once and turns most of the remaining color findings into no-ops.
