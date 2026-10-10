# Changelog

All notable changes to HandType will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.2.0] - 2026-10-10

### Added
- Settings: "Support HandType" card with Share and Rate rows (Google Play listing).
- In-app review prompt after the third successful save, share or export (Play In-App Review API, shown once; never directly after a share).
- Three-page onboarding walkthrough; Help has a "Show the intro again" button.
- Strings for the new UI in all 10 locales.

### Changed
- Version bumped to 0.2.0 (versionCode 2).
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
