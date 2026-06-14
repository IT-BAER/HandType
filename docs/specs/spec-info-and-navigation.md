# Spec: Navigation Drawer + Info Surfaces

Scope: the app-wide navigation drawer and the static informational screens reachable from it (Templates, History, Help, FAQ, About, Privacy). Covers drawer structure, each info screen's content and behavior, which actions open external links, and the known UX gaps.

This is a single-Activity app with a hand-rolled, enum-based router (no Jetpack Navigation, no `NavHost`, no back stack). The drawer is the only menu surface, and it is mounted only for non-immersive routes.

## Source of truth

- `app/src/main/java/com/baer/handtype/MainActivity.kt` - navigation host. Owns `route` state, the `AnimatedContent` route switch, `ModalNavigationDrawer`, the `navigate(dest)` click handler (lines 101-116), and back handling (lines 94-99). `RootRoute` enum (private) is declared here.
- `app/src/main/java/com/baer/handtype/feature/shell/AppDrawer.kt` - drawer UI. Declares the public `DrawerDestination` enum and `AppDrawerContent(current, onSelect)`.
- `app/src/main/java/com/baer/handtype/feature/info/InfoScreens.kt` - `HelpScreen`, `FaqScreen`, `AboutScreen`, the shared `InfoScaffold`/`SectionTitle`/`Body`/`FaqItem` helpers, and the two GitHub URL constants.
- `app/src/main/res/values/strings.xml` - drawer labels `drawer_nav_*` (lines 131-136), Help block (157-168), FAQ block (170-183), About block (185-190). The app ships 10 locales with parallel keys; the device under test runs German.

---

## 1. Navigation drawer

Screenshot: ![Drawer](../../artifacts/ux/16_drawer.png)

### Structure

`AppDrawerContent` renders a `ModalDrawerSheet` (background `PaperBg = Color(0xFFF5EFE4)`, full height) containing:

1. **Header** (a `Row`, 20.dp padding): the circular app icon (`R.drawable.ic_launcher_foreground`, 56.dp, clipped to `CircleShape`, white backing), the hardcoded title "HandType", and a version line `"v${BuildConfig.VERSION_NAME}"`. In the debug variant this renders the raw build string "v0.1.0-debug".
2. **Item list**: one `NavigationDrawerItem` per `DrawerDestination` entry, in declaration order, each built inside `DrawerDestination.entries.forEachIndexed`. Items have a `label` only, no leading icon, no trailing icon.
3. **Footer**: `Spacer(Modifier.weight(1f))` + `Spacer(Modifier.height(16.dp))`. Nothing is rendered in this zone; it is empty reserved space.

### Destinations

`DrawerDestination` (public enum in `feature.shell`), six entries, each carrying a `titleResId`:

| Order | Enum | Label key | English label | Maps to |
|-------|------|-----------|---------------|---------|
| 1 | `Templates` | `drawer_nav_templates` | Templates | `RootRoute.TemplateChooser` |
| 2 | `History` | `drawer_nav_history` | History | `RootRoute.History` |
| 3 | `Help` | `drawer_nav_help` | Help | `RootRoute.Help` |
| 4 | `Faq` | `drawer_nav_faq` | FAQ | `RootRoute.Faq` |
| 5 | `About` | `drawer_nav_about` | About | `RootRoute.About` |
| 6 | `Privacy` | `drawer_nav_privacy` | Privacy | external link (no route) |

The list is flat: there is no grouping, no section header, and no divider between the work destinations (Templates, History) and the info/legal destinations (Help, FAQ, About, Privacy).

### Selection and styling

- The highlighted item (`current`) is derived in `MainActivity` from the active route: History/Help/Faq/About map to their own destination; everything else (including the chooser and immersive routes) highlights `Templates`.
- Colors are hardcoded literals, not `MaterialTheme.colorScheme` tokens: `Ink = Color(0xFF1A1410)` text, `Teal = Color(0xFF23443A)` selection. Selected item: `selectedContainerColor = Teal.copy(alpha = 0.18f)` (a soft green pill), `selectedTextColor = Teal`. Unselected: transparent container, `Ink` text. Because the drawer hardcodes its colors, it does not leak the default-M3 purple that other token-driven components show.
- Each item fades in on a stagger: `delay(index * 30L)` then `Animatable.animateTo(1f, tween(200, easing = FastOutSlowInEasing))`, driving `graphicsLayer { alpha }`.

### Drawer behavior

- `drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)`.
- Opened via `onOpenDrawer` (a lambda `scope.launch { drawerState.open() }`) passed to each non-immersive screen and triggered by that screen's hamburger button.
- The drawer is mounted only inside the non-immersive branch of the `AnimatedContent` switch. Immersive routes (`RenderTemplate`, `PremiumCapture`) render without the drawer, so the menu is unreachable from those screens; exit is via in-screen back/up callbacks or the system back gesture only.
- Back handling (two `BackHandler`s in priority order): if the drawer is open, back closes it; else if `route != TemplateChooser`, back resets `route = TemplateChooser`. There is no multi-level back stack: back from any info screen jumps straight to the chooser, not to the previous screen.

### `navigate(dest)` dispatch

`MainActivity.navigate` (lines 101-116) is the `onSelect` handler:

- **Privacy is special-cased first.** It does NOT change route. It closes the drawer, then fires `context.startActivity(Intent(ACTION_VIEW, Uri.parse(GITHUB_PRIVACY_URL)))` and `return`s. This leaves the app to GitHub/browser and needs network. The `DrawerDestination.Privacy -> RootRoute.TemplateChooser.name` line later in the `when` is dead code (never reached).
- All other destinations set `route` to the matching `RootRoute.*.name`, then close the drawer.

---

## 2. Info screens (shared scaffold)

Help, FAQ, and About all render through the private `InfoScaffold`:

- A `Scaffold` with `containerColor = PaperBg`.
- A `CenterAlignedTopAppBar` titled from a string resource (`Ink` text), with `containerColor = PaperBg`.
- Navigation icon: the Unicode glyph `☰` (hamburger, `☰`) drawn as `Text`, not a Material vector `Icon`. Tapping it calls `onOpenDrawer`.
- These screens read `MaterialTheme.typography` but no `MaterialTheme.colorScheme`; all colors are the hardcoded `PaperBg`/`Ink`/`Teal` literals.

Each screen's content is a single `verticalScroll` `Column` ending in a 24.dp trailing `Spacer`. None has a ViewModel; the only persisted state anywhere in this group is FAQ open/closed flags.

### 2.1 Help

Screenshot: ![Help](../../artifacts/ux/19_help.png)

- Title: `help_title` ("Help"). Content padding `horizontal = 24.dp, vertical = 8.dp`.
- Exactly 5 fixed `SectionTitle` + `Body` pairs from `help_s1_*` through `help_s5_*`. English titles: "1. Pick a template", "2. Type your text", "3. Generate", "4. Save, share or export", "5. History".
- No accordion, no icons, no imagery, no links. The only interaction is scroll. It is a wall of numbered text paragraphs.

### 2.2 FAQ

Screenshot: ![FAQ](../../artifacts/ux/20_faq.png)

- Title: `faq_title` ("FAQ"). Content padding `horizontal = 16.dp, vertical = 8.dp`, items spaced `12.dp` apart.
- Exactly 6 question/answer pairs (`faq_q1`/`faq_a1` .. `faq_q6`/`faq_a6`), each rendered as a `FaqItem` `Card` (surface `Color(0xFFFFF9F2)`, elevation 2.dp, inner padding 16.dp).
- Each card toggles `open` on click. Open state is `rememberSaveable(question) { mutableStateOf(initiallyOpen) }`, keyed by the question string, so it survives recomposition and rotation.
- Item 0 starts expanded (`initiallyOpen = i == 0`); the rest start collapsed. Toggles are independent (multiple can be open at once, not single-select).
- Expand uses `fadeIn + expandVertically` over `tween(200)`; collapse uses `fadeOut + shrinkVertically` over `tween(150)`.
- The chevron is a Unicode glyph in `Teal`: `▴` (`▴`, open) / `▾` (`▾`, closed), drawn as `Text`, not a Material icon.
- No external links. FAQ answers `faq_a5` and `faq_a6` restate the offline promise ("Nothing is uploaded", "nothing leaves the phone").

### 2.3 About

Screenshot: ![About](../../artifacts/ux/21_about.png)

- Title: `about_title` ("About"). Content padding `horizontal = 24.dp, vertical = 16.dp`, centered horizontally.
- App icon: `R.drawable.ic_launcher_foreground`, 96.dp, clipped to `CircleShape` on a white background.
- "HandType" title (`headlineSmall`, bold), then a version line: `stringResource(R.string.about_version, BuildConfig.VERSION_NAME)` ("Version %1$s"), which in the debug variant reads "Version 0.1.0-debug".
- Description: `about_description` ("Turn typed text into handwriting... nothing about your text leaves your device.").
- A full-width `OutlinedButton` labeled `about_github_button` ("View source on GitHub"). It sets no `colors`, so under the bare `MaterialTheme {}` shell its text and outline resolve to the default-M3 purple, the only purple on the screen.
- Copyright: `about_copyright` ("© 2025 IT-BAER. Made with care.").

---

## 3. External links (what leaves the app)

Two top-level constants in `InfoScreens.kt`:

- `GITHUB_REPO_URL = "https://github.com/IT-BAER/HandType"`
- `GITHUB_PRIVACY_URL = "https://github.com/IT-BAER/HandType/blob/main/PRIVACY.md"`

Two actions open an external `Intent.ACTION_VIEW` (handled by the GitHub app or a browser, requiring network and leaving the app):

| Trigger | Constant | Site |
|---------|----------|------|
| About "View source on GitHub" `OutlinedButton` | `GITHUB_REPO_URL` | `AboutScreen`, InfoScreens.kt line 243 |
| Drawer "Privacy" item | `GITHUB_PRIVACY_URL` | `MainActivity.navigate`, line 104 |

Everything else (Help content, FAQ content, the rest of About) is fully in-app with no links. There is no `PrivacyScreen` and no Privacy route: Privacy is purely an external-link side effect.

Neither `startActivity` call is wrapped in a try/catch. If no app handles the `ACTION_VIEW` Intent, the call would throw `ActivityNotFoundException`; that failure path is unverified.

---

## Known UX gaps

From the info UX findings (`artifacts/wf/ux/info.md`). IDs preserved.

- **INF-1 (P0) - Debug version string is user-facing.** The drawer header binds `"v${BuildConfig.VERSION_NAME}"` and About binds `about_version` to the same `VERSION_NAME`, both rendering "0.1.0-debug" in this build. "v0.1.0-debug" under the logo and "Version 0.1.0-debug" on About read as an unfinished internal build, eroding trust in the very first surface a user opens. Fix: format a clean semantic version for release (drop `-debug`), and ideally keep the version only on About, reserving the drawer header for brand.

- **INF-2 (P0) - Leaked default purple on the About button.** The "View source on GitHub" `OutlinedButton` sets no `colors`, so under the bare `MaterialTheme {}` it inherits the M3 baseline purple, clashing with the green/cream/brown brand and being the only purple element on screen. Fix: install a real `colorScheme` so all token-driven components inherit brand color at once; quick local fix is `ButtonDefaults.outlinedButtonColors(contentColor = Teal)` plus a Teal border.

- **INF-3 (P0) - Offline-first promise vs. external GitHub jumps.** Both Datenschutz and the About button open github.com via `ACTION_VIEW`, needing network. About states "nothing about your text leaves your device" one line above a button that sends the user off-device, and the privacy policy itself is unreachable offline. Fix: bundle PRIVACY.md as a local asset rendered in-app (offline-readable), and demote the GitHub repo link to a clearly-marked secondary external action.

- **INF-4 (P1) - Flat, icon-less, ungrouped drawer.** All six destinations render in one loop with no leading icon and no dividers, mixing work screens (Templates, History) with info/legal screens (Help, FAQ, About, Privacy) at equal weight. Fix: add M3 leading icons to every item and split into a primary group and a secondary "Info" group with a section label or `HorizontalDivider`.

- **INF-5 (P1) - Help is a text wall for a visual product.** Five `SectionTitle` + `Body` prose pairs with no icons, numbered avatars, or imagery, for an app whose value is visual. Fix: convert steps to M3 list items or step cards with leading numbered avatars and small visuals; consider a first-run carousel or a "try a sample" entry point.

- **INF-6 (P1) - Datenschutz has no external-link signal.** The Privacy item is a standard `NavigationDrawerItem` visually identical to in-app destinations, yet tapping it ejects the user to a browser with no warning. Fix: append a trailing `OpenInNew` icon (and/or move it to a distinct footer "links" zone) so the off-app jump is telegraphed before the tap.

- **INF-7 (P2) - Unicode glyphs instead of Material icons.** The hamburger (`☰`) and FAQ chevrons (`▴`/`▾`) are `Text` glyphs, so they ignore icon sizing/tint conventions and look slightly off next to crisp Material rendering. Fix: use `Icon(Icons.Default.Menu)` and `Icons.Default.ExpandMore/ExpandLess`, tinted with brand color.

- **INF-8 (P2) - Flat top app bar.** Info screens use `CenterAlignedTopAppBar` with no large/flexible variant, no edge-to-edge accent, no noted predictive-back affordance. Fix: consider an M3 `LargeTopAppBar` for Help/About to give the title presence and scroll-collapse; ensure edge-to-edge + predictive back app-wide.

- **INF-9 (P2) - Empty drawer footer.** The drawer reserves a bottom `Spacer(weight=1f)` + 16.dp but renders nothing there, leaving roughly the bottom 60% empty. Fix: use the footer zone for a divider + secondary items (language, later theme, "Privacy"/GitHub as footer links), per M3 drawer footer guidance.
