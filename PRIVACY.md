# Privacy Policy

_Last updated: 2025_

HandType is designed to be offline-first and tracker-free.

## What HandType does NOT do

- It does **not** include any analytics SDK.
- It does **not** include any crash-reporting SDK.
- It does **not** include any advertising or attribution SDK.
- It does **not** call any backend during normal use. Generating, saving, sharing, exporting and the History gallery all run fully on your device.

## Data stored on your device

- **Generated notes (History)** are stored as PNG files (plus an optional `.txt` sidecar with the source text you typed) inside the app's private storage. They never leave your device automatically.
- **Files you explicitly save or export** (PNG, transparent PNG, PDF) are written to the locations you choose (typically `Pictures/HandType` or your share target).
- **Files you share** are placed temporarily in the app's cache directory and exposed through Android's `FileProvider` to the app you select.

## Premium handwriting capture

The optional "use my handwriting" feature uses Google ML Kit on-device text recognition to extract glyph crops from a photo of your handwriting. The photo and the recognized characters are processed locally; nothing is uploaded.

## Permissions

- **Camera** — requested only when you start the premium handwriting capture.
- **Storage** (`WRITE_EXTERNAL_STORAGE`) — requested only on Android 9 and older when you save a file to the gallery. On Android 10+ HandType uses scoped storage / `MediaStore` and no permission prompt is needed.

## Deleting your data

- Use the **History** screen inside the app to delete archived notes.
- Files you exported to the gallery, Downloads or any share target are owned by your filesystem and must be removed via your Files or Gallery app.
- Uninstalling HandType removes all of the app's private data (including History).

## Changes

If this policy changes, this file will be updated and a note will appear in the app's release notes.

## Contact

Open an issue at <https://github.com/IT-BAER/HandType/issues>.
