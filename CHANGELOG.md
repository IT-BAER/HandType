# Changelog

All notable changes to HandType will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.2.0] - 2026-10-26

### Added
- Premium: your own cursive handwriting joins the letters of a word. The join uses your own exit stroke and lead-in, with a short curved bridge between them. Print hands are unchanged.
- Premium: missing punctuation (. , ! ? : ; ' " -) is drawn from your own i dot and l stem, in the pen width and lean of your hand.

### Changed
- Smaller app: release builds are now optimized and shrunk with R8 (APK 78 MB to 36 MB).
- The handwriting sheet and the on-screen sheet show four guide lines per cell: cap line, x-height, baseline and descender line. The instruction is rewritten in all 10 languages. Sheets printed before this update still scan.
- Premium: a captured hand is scaled by its x-height, so small letters no longer come out as tall as capitals. This also applies to saved handwriting.
- Premium: the letters of your own handwriting are spaced by their ink, so a lead-in slides under the previous letter.
- Version 1.2.0 (versionCode 8).

### Fixed
- Reading a filled-in handwriting sheet: descenders that cross into the cell below are kept, tall capitals keep their tops, the printed cell label is erased by its letter shape so pen ink next to it stays, dotted guide lines no longer pass as i dots, and very small handwriting keeps letters like i.
- Wide letters such as a cursive x or w are no longer scaled larger than the other small letters.
- Stems at letter joins stay whole (j, F, r), and joins show no stray coloured pixels.
- Punctuation drawn for your hand is soft and leans with it; a comma is no darker than the letters, and the dot of a ! sits under its stem.

## [1.1.0] - 2026-10-23

### Added
- Premium: print the handwriting sheet directly from the capture screen (one A4 page through the Android print dialog), next to Share.
- Premium: small ink specks follow the pen while you write the sheet on the screen. They are only drawn on screen and do not change the saved letters; they are off when system animations are off.
- Stylus handwriting in the message box on Android 14 and newer, when the keyboard supports it.
- Text size slider on the result screen: the note is drawn again in the same handwriting, smaller or larger (0.7x to 1.5x). Regenerate and transparent export keep the chosen size.
- Premium: write the practice sheet directly on the screen, one character at a time (stylus or finger). Each letter is checked for size, position and, with Google's on-device handwriting recognition, for the expected character before the next one appears.
- German letters: Ink Script now writes Ä Ö Ü ä ö ü ß, and with German as the app language the practice sheet (printed or on screen) adds a row for them, and the capture instructions name them. Sheets printed before this update still scan.
- Accented letters: Ink Script and your own handwriting now write é è ê ë à â á ã ñ ç ô ó õ î í ï û ú ù å and their capitals. The base letter is drawn with the accent added in the same pen.

### Changed
- Writing the handwriting sheet on the screen now shows nine letters per page (3 x 3), so the letters come out smaller and more natural. Next checks the whole page and marks the cells that need another try.
- A stroke may end up to 10% of the cell past its edge before the letter counts as outside; ink past the edge is cut from the saved letter.
- The practice sheet is now called the handwriting sheet (German: Schriftprobe) in all 10 languages, on the printed sheet and in the privacy policy.
- Compose 1.7.8 and Material 3 1.3.2.
- Generate animation: the screen fades into the "Generating" card, the pen draws the whole sign including its cross and underline, and the finished note appears on its paper at once and is then written in line by line.
- Text size slider: the note grows or shrinks while you drag, and the lines re-wrap a moment later in the new size.
- The practice sheet has 9 rows instead of 8, so each cell is slightly shorter.
- Removed the unused ML Kit text recognition library and its install-time OCR model download.
- Removed the `WRITE_EXTERNAL_STORAGE` permission, which applied only up to Android 9.
- Capture diagnostics are logged only in debug builds.
- Version 1.1.0 (versionCode 7).

### Fixed
- The Android share sheet shows a preview of the note instead of an empty thumbnail.
- Printing the handwriting sheet on a phone without the Android print service shows a message instead of closing the app.
- Scanning a filled-in handwriting sheet takes the cells from the printed grid, so pen strokes no longer pass as guide lines, cell borders or corner squares.
- Ink Script splits a word wider than one line with a hyphen instead of breaking it mid-word without one, at a syllable where it can ("Öster-reich", not "Österre-ich").
- "!" and "?" no longer merge into a preceding letter that ends in a vertical stroke ("Tom!").
- The result screen shows a long note at full width and scrolls it, instead of shrinking it into a narrow strip.
- Paywall: Buy is disabled while Google Play is unavailable or the price has not loaded; the price is queried again when the Play state changes.
- Paywall: Restore while a purchase is pending shows the pending message instead of "No previous purchase found".
- Texts in all 10 languages: the FAQ no longer mentions the Android 9 storage permission or a PDF fallback for older versions; the capture instruction and the "no glyphs" hint refer to the scan instead of a camera frame.

## [1.0.1] - 2026-10-22

### Added
- Receive text from other apps through the Android share sheet (plain text): HandType opens on the style chooser, and the chosen style's editor starts with the shared text.

### Changed
- The "Generating your note" card stays for one full 1-second pen stroke before the result appears, even when the render finishes sooner.
- Version 1.0.1 (versionCode 4).

## [1.0.0] - 2026-10-18

### Fixed
- Premium unlocks when the app returns to the foreground after a purchase completed outside the paywall (slow payment methods, promo codes, a purchase on another device); such purchases are also acknowledged then, so Play does not refund them after 3 days.

### Changed
- Privacy policy: no camera or storage permission is requested; Google Play purchases, the rating prompt, on-device settings and the Internet permission declared by Google's libraries are described.
- Release workflow uploads the signed bundle to the Google Play internal testing track and keeps the APK on the GitHub release.
- Version 1.0.0 (versionCode 3).
- About and the privacy policy say that HandType is open source under the Apache License 2.0.

## [0.2.0] - 2026-10-10

### Added
- Settings: "Support HandType" card with Share and Rate rows (Google Play listing).
- In-app review prompt after the third successful save, share or export (Play In-App Review API, shown once; never directly after a share).
- Three-page onboarding walkthrough; Help has a "Show the intro again" button.
- Strings for the new UI in all 10 locales.

### Changed
- Version bumped to 0.2.0 (versionCode 2).
- Minimum Android version raised to Android 10 (minSdk 29); Play's automatic protection needs at least minSdk 24.
- Removed the unused `CAMERA` permission; capture goes through the ML Kit document scanner.
- Result screen: the note preview fits the space above the actions, so Save, Share, Export, Regenerate and New Note are always visible without scrolling.
- Editor: "Your message" is a heading above the paper field instead of a floating label.
- Flowing Cursive card previews ink on paper like the other templates.
- Locked premium papers show a lock badge; every paper card announces its name (and "Premium" when locked) to TalkBack.
- Settings: language names start with a capital letter; Premium rows use the same icon rows as Support.
- History detail: Share uses the same tonal style as on the result screen.
- Capture intro: removed the tip that repeated the steps above it.

### Fixed
- Personal handwriting capture: a scan that cuts the black corner squares at the image edge no longer skews the sheet; letters such as `w` were blown up and `H`/`P` carried printed cell borders.
- Personal handwriting: synthesized hyphens, apostrophes and commas sit at the right height (the hyphen used to drop to the baseline).
- Ink Script: `!` and `?` are full height instead of dot-sized, the hyphen is a short dash at mid x-height instead of a blurred bar, apostrophes sit high, commas hang below the line, and periods match the letters.
- PDF export no longer adds an empty page when only blank paper is left after the last cut.
- Onboarding: the dots and buttons stay in place on every page.
- Template chooser: the menu button stays visible while the page scrolls.
- Editor: the paper behind the message field lines up with its outline; the paper row scrolls to the screen edge.
- History: thumbnails of short, wide notes show every word instead of cutting off the right side.
- History: empty state no longer repeats its title; thumbnails no longer read the raw file name to TalkBack.
- About: copyright year 2026.
- Premium purchase signature check works on Android 6.0-7.1 (API 23-25); `java.util.Base64` (API 26+) replaced by `kotlin.io.encoding.Base64`.
- Personal handwriting capture now correctly detects filled cells ("no filled cells were detected" error resolved).
  - `GlyphPostProcessor.cleanGlyph()`: blank/low-contrast crops now return a transparent canvas instead of the opaque original, preventing blank cells from being counted as ink.
  - `SheetSampleProcessor.hasInk()`: replaced strict per-pixel α≥200 count with alpha-energy sum (`Σalpha > 50×255`), correctly detecting thin characters such as `i`, `l`, and `1`.
- Removed dead `SheetLiveDetector.kt` (leftover CameraX code that blocked compilation after the ML Kit Document Scanner migration).
- Personal handwriting glyph canvas width now trimmed to actual ink width (+16 px padding) so the renderer advances by character width rather than the full 350 px square — eliminates the excessive inter-character gap.
- Reduced glyph canvas height from 350 px to 220 px to match the actual ink region (baseline 175 px, ink top ≤ 21 px headroom), fixing oversized line spacing in generated output.
- User-template rendering now mildly narrows over-wide lowercase glyphs such as `c`, `n`, and `u`, so broad captured forms do not read oversized in final output.

## [0.1.0] - 2026-04-26

### Added
- Convert typed text into realistic handwritten output, fully on-device.
- Side menu with Templates, History, Help, FAQ and About screens.
- Save as PNG (opaque or transparent), share, and export multi-page PDF.
- Automatic history archive of generated handwriting.
- Privacy policy linked directly to the GitHub-hosted source of truth.
- Per-app language picker support for Android 13+.
