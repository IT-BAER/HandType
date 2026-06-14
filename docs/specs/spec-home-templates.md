# Spec: Home / Template Chooser

The home screen of HandType. It is the start and restore destination of the app, and the entry point into the core authoring loop (pick a style, type a message, render, export). It presents a horizontal carousel of bundled handwriting templates plus a separate premium card for capturing your own handwriting.

Source of truth:
- `app/src/main/java/com/baer/handtype/feature/template/TemplateScreens.kt` (the `TemplateChooserScreen` composable, lines 146-417; the carousel card `TemplateOptionCard`, lines 1316+).
- `app/src/main/java/com/baer/handtype/template/BundledTemplateRepository.kt` (template descriptors, the source data).
- `app/src/main/java/com/baer/handtype/MainActivity.kt` (router wiring, lines 167-187).
- Strings in `app/src/main/res/values/strings.xml`.

## Purpose

Let the user choose what handwriting style to write in before composing a note. The chooser is a catalog, not the editor: selecting a template navigates forward to `HandwritingRenderScreen` (the message editor). It is also the single discovery point for the premium "capture your own handwriting" feature.

Two free built-in templates ship today (`classic_script`, `flowing_cursive`). User-captured templates (premium output) appear in the same carousel after the user completes a capture. The premium feature itself is advertised by a distinct card below the carousel.

## Entry points

`TemplateChooserScreen` is rendered for `RootRoute.TemplateChooser`, which is the default and restore route of the app (`MainActivity` initializes `route` to `TemplateChooser.name`). Reached by:

- App launch (cold start) when no prior route was persisted.
- The "Templates" drawer item (`DrawerDestination.Templates`).
- System back from any deep screen: the app has no back stack, so back collapses straight to the chooser (see navigation spec).
- After a successful premium capture: `MainActivity` sets `route = TemplateChooser` and passes the new template's id as `initialTemplateId`, so the carousel opens scrolled to the freshly captured template (and the post-capture rename dialog is shown over the chooser).

Note: because `route` is saved with `rememberSaveable`, relaunch after process death restores the last visited screen, so the chooser is not guaranteed to be the screen the user sees on relaunch.

## Layout

Single scrolling `Column` (`verticalScroll`) on a hardcoded cream `Scaffold` background `Color(0xFFF5EFE4)`, top to bottom:

1. Hamburger button (top-left). A custom `IconButton` containing three stacked 2dp `Box` bars in ink `Color(0xFF1A1410)`, not `Icons.Default.Menu`. Opens the nav drawer (`onOpenDrawer`).
2. Title: `template_chooser_title` ("Choose a handwriting style", DE "Handschriftstil wählen"), `headlineMedium`.
3. Subtitle: `template_chooser_subtitle` ("Built-in templates are the default path. You can also capture your own handwriting."), `bodyLarge`, color `MaterialTheme.colorScheme.onSurfaceVariant` (a themed token, so it leaks default M3 styling rather than a brand color).
4. The carousel: a `HorizontalPager` (test tag `template_pager`), height 280dp, `contentPadding = 32dp` horizontal, `pageSpacing = 12dp`. One `TemplateOptionCard` per template. Off-screen neighbor cards peek at the edges (visible in `11_template2.png`). A custom snap fling (`spring`, dampingRatio 0.78, stiffness 240) and a per-page scale (0.95 to 1.0 based on page offset) give the cards a subtle focus effect.
5. Page-dot indicator: only rendered when `templates.size > 1`. One dot per template, centered. Selected dot is 8dp solid green `Color(0xFF23443A)`; inactive dots are 6dp green at 0.3 alpha. Animated size/color.
6. Premium card: a separate `Card` (cream `Color(0xFFFFF9F2)`, 6dp elevation, brown hairline border `Color(0xFF7C4D11)` at 0.18 alpha, 28dp corners, 20dp horizontal padding). See "Premium entry" below.

Each carousel card (`TemplateOptionCard`, 28dp corners, `SpaceBetween` column) contains:
- Title row: `descriptor.displayName` (`titleLarge`, max 2 lines, ellipsized) and, if `descriptor.isNew`, a "New" badge pill (`NewBadgePill`, animated sparkle).
- Sample surface: a tinted rounded surface (accent at 0.08 alpha for built-ins) holding the preview, then a 4dp gap, then `descriptor.description` (`bodySmall`, `onSurfaceVariant`).
- A green "Use template" button (`template_chooser_use_template`), a `Box` styled as a button (`Color(0xFF23443A)`, 18dp corners) rather than an M3 `Button`.

Built-in cards are white with a green accent. User-captured cards (id starts with `user_`) are warm-cream `Color(0xFFFFF8EC)` with a gold border, brown title `Color(0xFF4F2F16)`, brown "Use" button `Color(0xFF8E5A21)`, an 8dp elevation, and a delete "X" overlay (see Interactions).

![First run, page 1 of 2](../../artifacts/ux/10_firstrun.png)
![Second carousel page](../../artifacts/ux/11_template2.png)

## Carousel content (today)

Free built-in templates (`BundledTemplateRepository.builtInTemplates`, carousel order):

| id | displayName (string) | Sample text | Sample font in card | Premium |
|----|----------------------|-------------|---------------------|---------|
| `classic_script` | "Ink Script" (`template_ink_script_name`) | `"Meet HandType."` (hardcoded literal) | `fonts/Inkfree.ttf` asset | no |
| `flowing_cursive` | "Flowing Cursive" (`template_flowing_cursive_name`) | `"Elegant flow."` (hardcoded literal) | system `"cursive"` | no |

User templates are appended after the built-ins (`chooserTemplates = builtInTemplates + userTemplates` in `MainActivity`). Their sample preview is not text: the card renders an actual sample bitmap from the captured template (rendered off the IO dispatcher, then ink-cropped against paper color `0xFFF8F1E4`). User templates can carry `isNew = true`, which shows the "New" badge.

The two built-in sample strings (`"Meet HandType."`, `"Elegant flow."`) are English literals in the repository, not string resources, so they do not localize.

## Premium entry ("Meine Handschrift" / My Handwriting)

A dedicated `Card` below the carousel built from the `premiumTemplate` descriptor (`custom_capture`):
- Header row: `premiumTemplate.displayName` ("My Handwriting", `premium_default_template_name`, `titleLarge` semibold) and a brown "Premium" pill (`template_chooser_premium_badge`, brown `Color(0xFF7C4D11)`).
- Tinted brown surface with `premiumTemplate.sampleText` ("Capture your own style", `template_premium_sample`, `headlineSmall`, brown) and `premiumTemplate.description` ("Extract your personal handwriting from a photographed practice sheet.", `template_premium_description`, `bodySmall`, themed `onSurfaceVariant`).
- A solid brown call to action `Color(0xFF7C4D11)` labelled "Capture handwriting" (`template_chooser_premium_button`), rendered as a styled `Surface`/`Text`, not an M3 `Button`.

The whole card is clickable (`Card(onClick = ...)`, with a `LongPress` haptic) and calls `onPremiumSelected`, which routes to `RootRoute.PremiumCapture` (`CameraCaptureScreen`).

## States

- Default (built-ins only): two cards, two dots, premium card. This is the first-run state in `10_firstrun.png` / `11_template2.png`.
- With user templates: carousel grows by the count of captured templates. Dot count grows accordingly. User cards show a bitmap sample and a delete button.
- Single template: if `templates.size == 1`, the dot indicator is hidden entirely (there is no "1/1" or empty indicator).
- New template highlight: a user template with `isNew = true` shows the "New" badge pill and, on first open, is marked seen (`markTemplateSeen`).
- Post-capture: `initialTemplateId = pendingRenameTemplateId ?: userTemplates.firstOrNull { it.isNew }?.id`, so the carousel auto-scrolls to the new/pending template via the `LaunchedEffect` re-scroll, and the rename dialog appears over the chooser (owned by `MainActivity`, not by this screen).
- Sample-loading (user cards): while the sample bitmap is rendering, the card shows a placeholder bar (gold tint) in place of the preview.

There is no first-run onboarding, no value-prop screen, no empty-state illustration, and no permission priming (confirmed). The chooser is always the first interactive surface.

## Interactions

- Tap a card: the entire card is the hit target via a manual `awaitEachGesture` on the page `Box` (not `Card(onClick=...)`). On the up-event it fires a `LongPress` haptic, animates to that page, calls `onTemplateOpened` (only if `descriptor.isNew`), then `onTemplateSelected(descriptor)`. The visible green "Use template" button is decorative: it does not have its own `onClick`; tapping anywhere on the card (title, sample, or button) navigates.
- Swipe: horizontal pager swipe moves between templates with the custom spring snap.
- Tap the premium card: `onPremiumSelected` -> `RootRoute.PremiumCapture`.
- Tap the hamburger: `onOpenDrawer` opens the `ModalNavigationDrawer`.
- Delete a user template: user cards (id starts with `user_`) render a red "X" `IconButton` (`Color(0xFFAA3333)`, test tag `template_delete_<id>`) in the card's bottom-right, only when `onDeleteTemplate != null`. Tapping it sets `pendingDeleteTemplate`, which shows an `AlertDialog`: title `template_delete_title`, body `template_delete_body` with the name, confirm "Delete" (`action_delete`, red text), dismiss "Cancel" (`action_cancel`). Confirm calls `onDeleteTemplate`, which deletes via the repository and refreshes the list.

## Navigation targets

| Action | Target |
|--------|--------|
| Tap a built-in or user template | `RootRoute.RenderTemplate` (sets `selectedTemplateId`, then loads `HandwritingTemplate` and shows `HandwritingRenderScreen`) |
| Tap the premium card / button | `RootRoute.PremiumCapture` (`CameraCaptureScreen`) |
| Tap the hamburger | Opens the nav drawer (no route change) |
| Confirm delete | Stays on chooser; carousel refreshes |

Selecting a template does not load it inline. `MainActivity` sets `selectedTemplateId`, switches route to `RenderTemplate`, and `TemplateRenderRoute` loads the template off the IO dispatcher, showing `LoadingTemplateScreen` only if the load exceeds 400ms.

## Current behavior notes (verified from source)

- No `MaterialTheme` overrides app-wide. The subtitle and card descriptions use `onSurfaceVariant`, which renders with the default M3 baseline rather than a brand color, coexisting with the hardcoded green/brown/cream literals. Default purple surfaces on any focused `TextField`/`TextButton` (not present on this static screen, but it is the same root cause).
- The chooser callbacks are wired in `MainActivity` lines 167-187. `onTemplateOpened` only does work when `descriptor.isNew` (marks seen, refreshes).
- The carousel card sample for built-ins is plain text styled with a handwriting-ish font; in the screenshots the Ink Script sample does not read as obviously handwritten.
- The rename dialog is not part of this screen; it is hosted by `MainActivity` and triggered by post-capture pending state.

## Known UX gaps

From the home UX findings (`artifacts/wf/ux/home.md`), severity in parentheses:

- (P0) No value proposition or onboarding. Title and subtitle describe a chooser, not the benefit ("type text, get believable handwriting"). No first-run intro exists, and relaunch can restore a deep screen, so the one chance to set context is wasted. Recommendation: a once-shown intro (DataStore-gated) or a persistent benefit line. [H1, `10_firstrun.png`]
- (P0) Carousel discoverability is dots-only. The second free template is signalled only by two small low-contrast dots plus a sliver of the next card; new users miss half the free catalog. Recommendation: increase next-card peek, add a "1/2"/arrow affordance, or move to M3 `HorizontalMultiBrowseCarousel`. [H2, `10_firstrun.png`, `11_template2.png`]
- (P0) Dual visual identity. Hardcoded brand green/cream/brown literals coexist with leaked default M3 (the `onSurfaceVariant` subtitle/descriptions, and purple on any focused TextField/TextButton). Recommendation: define a real light/dark `ColorScheme` (green primary, brown secondary/tertiary, cream surface) and route literals through tokens. [H3]
- (P1) Sample text reads as a slogan, not a handwriting demo. "Meet HandType." / "Elegant flow." look like headlines, and the Ink Script preview is not visibly handwritten. Recommendation: render each card's sample in its actual handwriting style and use note-like copy ("Liebe Grüße, bis bald!"), optionally labelled "Vorschau". [H4, `10_firstrun.png`, `11_template2.png`]
- (P1) Premium card over-competes with the free primary path. 6dp elevation, warm tint, full-width brown CTA give it near-equal weight to the green "Use template" action, pulling a first-time user toward a feature they cannot evaluate yet. Recommendation: demote to a tonal/outlined, lower-elevation card and frame premium as a post-trial upgrade. [H5, `10_firstrun.png`]
- (P1) Custom controls bypass M3 standards. The hamburger is a hand-stacked 3-bar `Box`, and the in-card CTAs and "New" badge are custom `Box`+`Text`, not M3 `Button`s, so they lack ripple/pressed/focus states and button semantics (TalkBack does not announce them as buttons). Recommendation: use `Icons.Default.Menu` (with `contentDescription`) and real `Button`/`FilledTonalButton`. [H6, `10_firstrun.png`]
- (P1) Whole-card gesture fires a long-press haptic on a normal tap. The custom `awaitEachGesture` selects on any up-event and always triggers `HapticFeedbackType.LongPress`, so a light tap (or aborted swipe) navigates and buzzes like a long-press, and it is unclear which element is the real button. Recommendation: use `Card(onClick=...)` or wire the visible button's `onClick` for correct ripple, semantics, and tap-appropriate haptics. [H7, `10_firstrun.png`]
- (P2) Not visibly edge-to-edge. Content starts below a default status-bar inset rather than drawing the cream surface under a transparent system bar. Recommendation: edge-to-edge with top window insets. [H8, `10_firstrun.png`]
- (P2) Unbranded feel. The drawer header shows a raw "v0.1.0-debug" build string and the title has no brand mark. Recommendation: add a wordmark near the title and hide the raw debug version from user-facing surfaces. [H9, `10_firstrun.png`]
- (P2) Low-contrast indicators. The inactive carousel dot is green at 0.3 alpha on cream and only 6dp, and the premium badge is brown-on-light-brown at low alpha. The sole "there is more" cue is also the lowest-contrast element on screen. Recommendation: raise dot contrast/size or use a labelled indicator; verify badge text meets 4.5:1. [H10, `10_firstrun.png`]
