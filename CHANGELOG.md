# Changelog

All notable changes to HandType will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
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
