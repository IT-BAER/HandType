# HandType Architecture

HandType is an offline-first Android app that turns typed text into believable handwriting. It runs fully on-device: no analytics, no backend, no network calls in the core flow. The one exception is the Privacy and "view source" links, which open GitHub externally (see [Navigation and the drawer](#navigation-and-the-drawer)).

- Package: `com.baer.handtype` (debug build suffix `.debug`)
- UI: Jetpack Compose + Material3, single Activity, portrait-only
- minSdk 23, compileSdk/targetSdk 34, AGP 8.4.2, Kotlin 1.9.22, JDK 17
- Single Gradle module: `app`

This document covers the module and package layout, the single-Activity navigation model, the layered structure, the Compose state model, persistence and on-device file locations, sharing/export via FileProvider, and build variants. For the rendering internals see [render-pipeline.md](./render-pipeline.md); for the camera + ML capture flow see [ml-capture.md](./ml-capture.md); feature specs live under [specs/](./specs/).

## Module layout

One application module, `app`. There is no version catalog and no multi-module split. Build scripts:

- `build.gradle.kts` (root): plugins declared `apply false` (AGP 8.4.2, Kotlin Android 1.9.22).
- `app/build.gradle.kts`: the only module build script (`namespace`/`applicationId = com.baer.handtype`, sdk levels, signing, build types, Compose, dependencies).
- `gradle.properties`: `android.useAndroidX=true`, `android.enableJetifier=true`, `android.nonTransitiveRClass=true`.

Source tree (`app/src/main/java/com/baer/handtype/`):

```
com.baer.handtype
├─ MainActivity.kt            single Activity + nav host (RootRoute enum, drawer wiring, route wrappers)
├─ feature/                   UI layer (Jetpack Compose screens), one subpackage per area
│  ├─ shell/                  AppDrawer.kt: ModalDrawerSheet + DrawerDestination enum
│  ├─ template/               TemplateScreens.kt (chooser + render/output), HistoryScreen.kt
│  ├─ capture/                CameraCaptureScreen.kt, SheetSampleProcessor.kt,
│  │                          PaperCornerDetector.kt, PracticeSheetGenerator.kt
│  └─ info/                   InfoScreens.kt: Help / FAQ / About + GITHUB_* URL constants
├─ template/                  data + render layer (pure-ish, no Compose)
│  ├─ BundledTemplateRepository.kt   built-in glyph/cursive templates from assets/
│  ├─ UserTemplateRepository.kt      captured templates under filesDir/user_templates/
│  ├─ HandwritingBitmapRenderer.kt   text → bitmap (render / renderCursive)
│  ├─ NoteBackgrounds.kt             paper background catalog + prefs
│  ├─ OutputExporter.kt              PNG / transparent PNG / PDF / share / history archive
│  ├─ PunctuationSynthesizer.kt      synth punctuation for user templates lacking glyphs
│  ├─ BundledGlyphTextNormalizer.kt  input text normalization
│  └─ UserGlyphWidthNormalizer.kt    per-glyph X/Y scale clamping
├─ ml/                        on-device extraction (no Compose, no UI)
│  └─ GlyphPostProcessor.kt          crop/threshold/normalize glyphs to a 220-row canvas
├─ ui/animation/             shared Compose animation helpers (staggeredItemAnimation)
└─ testing/                  HandTypeTestTags + HandTypeDebugHooks (test/debug instrumentation)
```

Resources of note: `app/src/main/res/xml/locale_config.xml` (10 locales), `app/src/main/res/xml/file_paths.xml` (FileProvider config), `app/src/main/res/values*/strings.xml` (10 locale files, 156 strings each). The only committed runtime asset directory is `app/src/main/assets/templates/classic_script/glyphs/` (600 PNGs = 75 codepoints x 8 variants, including Ä Ö Ü ä ö ü ß).

## Layered structure

The app is layered by package, not by Gradle module. Dependencies flow downward only: `feature/` depends on `template/`, `ml/`, and `ui/`; `template/` and `ml/` do not depend on `feature/`.

| Layer | Packages | Responsibility | Compose? |
| --- | --- | --- | --- |
| Shell / navigation | `MainActivity`, `feature/shell` | single Activity, route switching, drawer | yes |
| Feature UI | `feature/template`, `feature/capture`, `feature/info` | screens, dialogs, screen-local state | yes |
| Data + render | `template/` | template loading/persistence, text→bitmap render, export | no |
| ML extraction | `ml/` | glyph post-processing of a captured sheet | no |
| Shared UI utils | `ui/animation` | reusable entrance animations | yes |
| Test/debug hooks | `testing/` | test tags, debug injection hooks | no |

Key boundary: the renderer (`HandwritingBitmapRenderer`) is a pure synchronous `object` (no IO). The repositories do file IO on `Dispatchers.IO`. ML extraction (`ml/`) feeds the capture feature, which writes a user template through `template/UserTemplateRepository`; rendering then reads it back through `template/BundledTemplateRepository`. See [render-pipeline.md](./render-pipeline.md) and [ml-capture.md](./ml-capture.md).

### No centralized theme

`MainActivity.onCreate` wraps the root in a bare `MaterialTheme { ... }` (MainActivity.kt around line 60) with no `colorScheme`, `typography`, or `shapes` override. The platform Activity theme is `@android:style/Theme.Material.Light.NoActionBar`. Consequences:

- There is no design-token layer. Brand colors are hardcoded per-component as literals: green `Color(0xFF23443A)` (primary CTAs), brown `Color(0xFF7C4D11)` (premium / Share accent), cream `Color(0xFFF5EFE4)` / `Color(0xFFFFF9F2)` (surfaces), red `Color(0xFFAA3333)` (destructive), ink `Color(0xFF1A1410)` (text).
- Any component that reads `MaterialTheme.colorScheme` (TextField focus, `TextButton`/`OutlinedButton` text, links) renders with the **default M3 baseline purple**. So two visual identities coexist: brand green/cream/brown and leaked purple.
- No dark theme and no dynamic color (Material You).

## Navigation and the drawer

Single Activity, hand-rolled enum router. There is **no** Jetpack Navigation-Compose: no `NavHost`, no `NavController`, no back stack.

- `MainActivity` (`ComponentActivity`): `onCreate` calls `enableEdgeToEdge()` then `setContent { MaterialTheme { HandTypeRoot() } }`.
- `HandTypeRoot()` (private composable in MainActivity.kt): owns all navigation state and the drawer. A single saveable `var route: String` drives an `AnimatedContent` that swaps screens; a `when (currentRoute)` selects the screen.
- `RootRoute` (private enum in MainActivity.kt, 7 values, stored as `.name` String): `TemplateChooser`, `RenderTemplate`, `PremiumCapture`, `History`, `Help`, `Faq`, `About`. Default / restored route is `TemplateChooser`.
- `DrawerDestination` (public enum in `feature/shell/AppDrawer.kt`, 6 values, each with a `titleResId`): `Templates`, `History`, `Help`, `Faq`, `About`, `Privacy`. Drawer items have **no leading icons** (label-only). The header shows a circular app icon, the title "HandType", and the raw build string `"v${BuildConfig.VERSION_NAME}"` (renders e.g. `v0.1.0-debug`).

Routing rules:

- **Immersive vs drawer routes.** `RenderTemplate` and `PremiumCapture` render without the `ModalNavigationDrawer` (the hamburger is unreachable there; exit is via in-screen back callbacks or the system back gesture). All other routes render inside the drawer.
- **Back handling.** Two `BackHandler`s in priority order: (a) drawer open → close it; (b) else `route != TemplateChooser` → reset to `TemplateChooser`. There is no multi-level back stack: back from any deep screen jumps straight to the chooser, not the previous screen. On `TemplateChooser` with the drawer closed, the system default (exit) applies.
- **Forward navigation into immersive screens** comes from callbacks, not the drawer: selecting a template sets `selectedTemplateId` and `route = RenderTemplate`; the premium tile sets `route = PremiumCapture`.
- **Privacy is not an in-app route.** `navigate(Privacy)` closes the drawer and fires `Intent(ACTION_VIEW, GITHUB_PRIVACY_URL)` (`https://github.com/IT-BAER/HandType/blob/main/PRIVACY.md`), leaving the app to GitHub. About's "view source on GitHub" does the same. Both leave the app and need network, in tension with the offline-first promise.
- **Transitions** (`routeTransitionSpec`, all `tween(260, FastOutSlowInEasing)`): to `PremiumCapture` slides Up, from it Down; to `RenderTemplate` slides Start, from it End; everything else slides Start.

```
                         MainActivity (single ComponentActivity)
                                       │  setContent { MaterialTheme { HandTypeRoot() } }
                                       ▼
              ┌──────────────────  HandTypeRoot()  ──────────────────┐
              │  state: route, selectedTemplateId, pendingRename*     │
              │  AnimatedContent(route) → when(currentRoute)          │
              └───────────────────────────────────────────────────────┘
                  │ drawer routes (ModalNavigationDrawer)      │ immersive (no drawer)
                  ▼                                            ▼
   ┌──────────────┬───────────┬──────┬─────┬───────┐   ┌──────────────┬───────────────┐
   │TemplateChooser│ History  │ Help │ FAQ │ About │   │ RenderTemplate│ PremiumCapture│
   └──────┬────────┴────┬─────┴──────┴─────┴───────┘   └──────┬────────┴───────┬───────┘
          │ select       │ read history/                       │ render          │ capture sheet
          ▼              ▼                                     ▼                 ▼
   RootRoute=Render   HistoryStore                  HandwritingRender    CameraCaptureScreen
                                                     Screen (output)      → ml/ → user template
```

## Compose state model

State lives in `HandTypeRoot()` and individual screens; there are no ViewModels and no DI framework. State that must survive rotation and process death uses `rememberSaveable`; transient/derived state uses `remember`.

- Saveable (survive rotation + process death): `route`, `selectedTemplateId`, `pendingRenameTemplateId`, `pendingRenameTemplateName`, and the premium screen's `instructionText`. Because `route` is saveable, the app **restores the last visited screen** on relaunch, not always home.
- Plain `remember`: `BundledTemplateRepository`, the built-in template list, the premium descriptor, and `userTemplates`. `userTemplates` is refreshed via `LaunchedEffect(route)` whenever the chooser is shown, so newly captured templates appear.
- Async loading: `TemplateRenderRoute` loads the template off `Dispatchers.IO` via `produceState` + `repository.loadTemplate`, and waits `delay(400L)` before showing `LoadingTemplateScreen` so quick loads never flash a spinner.
- Post-capture rename: a successful capture sets `pendingRename*` and routes back to `TemplateChooser`, where `RenameHandwritingDialog` is shown over the chooser; confirm calls `userRepository().renameTemplate(...)`.

There is no first-run onboarding, value-prop, or permission priming screen.

## Persistence and file locations

All persistence is on-device. Nothing is backed by a database; storage is flat files plus two small `SharedPreferences` entries.

| What | Location | Format |
| --- | --- | --- |
| Built-in glyph templates | `assets/templates/<id>/glyphs/u%04X_v%d.png` | bundled PNGs (up to 8 variants/char) |
| User-captured templates | `filesDir/user_templates/<folder>/glyphs/` + `meta.txt` | one PNG per char (`_v0`) + key/value meta |
| History (archived notes) | `filesDir/history/<yyyyMMdd_HHmmss>__<tag>.png` | PNG + optional `.txt` (source text) + `.name` (display name) sidecars |
| Default background id | `SharedPreferences` file `note_background_prefs`, key `default_background_id` | string |
| Share staging | `cacheDir/shared/` | temp PNG (served via FileProvider) |

Notes:

- User template ids are `user_` + `u<createdAtMillis>`; `meta.txt` keys are `displayName`, `isNew`, `createdAt`, `glyphCount`. Constants live in `UserTemplateRepository`.
- History is read-only in the UI (`HistoryScreen` / `HistoryStore.listEntries(filesDir)`), produced elsewhere by `OutputExporter.archiveBitmap`. Entries are sorted newest-first; filenames split on the first `__` (timestamp + tag, `yyyyMMdd_HHmmss`, `Locale.US`). History thumbnails are square `ContentScale.Crop` (center-crop, so they do not show the start of the text). Empty History is centered text only, no icon/illustration/CTA. Bitmaps are decoded full-size and synchronously in `remember(entry.id)` (no async image library, no downsampling), a memory-pressure risk for large histories.

## Sharing, export, and FileProvider

`template/OutputExporter` (a Kotlin `object`) owns all output. The output screen exposes 6 flat actions in 3 colors: Save PNG, Share, Export PDF, Save transparent PNG, Recreate, New note.

- **Save PNG / transparent PNG** → `Pictures/HandType` (MediaStore). Transparent export keys out the paper color per-pixel.
- **Export PDF** → `Download/HandType` (API 29+) or app cache (older), A4 portrait, with smart page slicing that snaps page breaks to the emptiest row.
- **Share** → stages a temp PNG in `cacheDir/shared/`, then `FileProvider.getUriForFile(context, "${packageName}.fileprovider", file)` produces a content URI for `ACTION_SEND`.
- FileProvider authority is `${applicationId}.fileprovider` (`com.baer.handtype.fileprovider`, debug `com.baer.handtype.debug.fileprovider`), declared in `AndroidManifest.xml`, configured by `res/xml/file_paths.xml` (single `<cache-path name="shared" path="shared/" />`).
- MIME types: `image/png`, `application/pdf`. Export filename pattern `HandType_<tag>_<yyyyMMdd_HHmmss>.<ext>`.

## Build variants

- **debug**: `applicationIdSuffix = ".debug"`, `versionNameSuffix = "-debug"`. Adds `compose.ui:ui-tooling` and `ui-test-manifest`. The `testing/` hooks (debug scan injection) are wired here.
- **release**: `isMinifyEnabled = false` (so `proguard-rules.pro` does not run). Signing resolves env vars (`RELEASE_KEYSTORE_*`) first, then `local.properties` (`RELEASE_STORE_*`); if no keystore exists the signing config is simply not attached, so an unsigned release still builds.
- Base config: `versionCode = 1`, `versionName = "0.1.0"`, `compileSdk`/`targetSdk = 34`, `minSdk = 23`.

### Premium capture is a free flag

The premium feature (capture your own handwriting from a photographed practice sheet via the GMS ML Kit document scanner, fully on-device; there is no in-app CameraX preview) is gated only by `premium = true` on the descriptor and a "Premium" badge. There is **no purchase, entitlement, or paywall**: no billing dependency exists anywhere in `app/src/main/java`. It is free to use. See [ml-capture.md](./ml-capture.md).

### Internationalization

`android:localeConfig="@xml/locale_config"` advertises 10 locales: `en`, `es`, `pt-BR`, `ru`, `ja`, `hi`, `de`, `fr`, `ko`, `zh-Hans`. Resource dirs use the older qualifiers `values-pt-rBR` and `values-zh-rCN`, so a qualifier dir name does not always equal its BCP-47 tag. Every locale file has full string parity (156 entries). Some toasts and the loading screen text are hardcoded English literals, not string resources.

## Cross-references

- [render-pipeline.md](./render-pipeline.md): text normalization, per-glyph jitter/warp, baseline layout, backgrounds, export internals.
- [ml-capture.md](./ml-capture.md): GMS document-scanner capture, fiducial detection, `GlyphPostProcessor` glyph extraction, user-template write-back.
- [specs/](./specs/): feature specifications.
- UX screenshots (relative to this file): [home](../artifacts/ux/01_home.png), [editor](../artifacts/ux/12_editor.png), [output](../artifacts/ux/15_output.png), [drawer](../artifacts/ux/16_drawer.png), [history](../artifacts/ux/17_history.png), [history detail](../artifacts/ux/18_history_detail.png), [empty history](../artifacts/ux/25_history_empty.png), [capture intro](../artifacts/ux/23_capture_intro.png).
