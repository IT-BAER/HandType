# Privacy Policy

_Last updated: October 18, 2026_

HandType is designed to be offline-first and tracker-free.

## What HandType does NOT do

- It does **not** include any analytics SDK.
- It does **not** include any crash-reporting SDK.
- It does **not** include any advertising or attribution SDK.
- It does **not** run or call any server of its own. Generating, saving, sharing, exporting and the History gallery all run fully on your device. Purchases, the review prompt and the document scanner use Google Play services, as described below.

## Data stored on your device

- **Generated notes (History)** are stored as PNG files (plus an optional `.txt` sidecar with the source text you typed) inside the app's private storage. They never leave your device automatically.
- **Personal handwriting templates** are stored in the app's private storage.
- **Settings** (theme, language, default paper, whether you finished the intro), the **premium unlock flag**, and a **counter of successful saves, shares and exports** for the review prompt are stored in the app's private storage.
- Private app data is excluded from Android cloud backup and device-to-device transfer. Export notes you want to keep before uninstalling or changing devices.
- **Files you explicitly save or export** are written to `Pictures/HandType` (PNG and transparent PNG) or `Download/HandType` (PDF).
- **Files you share** are placed temporarily in the app's cache directory and exposed through Android's `FileProvider` to the app you select.

## Premium purchase (Google Play)

HandType Premium is a one-time purchase handled by Google Play Billing. HandType never sees your payment details. The app asks Google Play which purchases belong to your Google account, checks Google Play's signature on your device, and stores only the unlock flag. Google's privacy policy applies to the purchase: <https://policies.google.com/privacy>.

## Rating prompt (Google Play)

After your third successful save, share or export, HandType may ask Google Play once to show its rating dialog. The counter stays on your device. Google Play decides whether the dialog appears, and HandType does not see your rating or review. The "Rate" and "Share" buttons in Settings open the Play Store or the Android share sheet.

## Premium handwriting capture

The optional "use my handwriting" feature opens Google's document scanner, where you can scan or select a practice-sheet photo. HandType extracts the characters from that sheet locally and saves a personal template. The app does not upload your photo or handwriting. Google Play services may need to download the scanner component before its first use.

## Permissions

- HandType does **not** request camera or storage permissions. The document scanner runs inside Google Play services and handles the camera itself. Saving uses Android's `MediaStore`, so no storage permission is needed.
- **Internet and network state** are declared by Google's billing, review and scanner libraries so that Google Play services can work. HandType itself sends no data over the network.

## Deleting your data

- Use the **History** screen inside the app to delete archived notes.
- Files you exported to the gallery, Downloads or any share target are owned by your filesystem and must be removed via your Files or Gallery app.
- Uninstalling HandType removes all of the app's private data (including History, templates and the unlock flag). A purchase stays on your Google account and can be restored after reinstalling.

## Source code

HandType is open source under the Apache License 2.0. The full source code is public at <https://github.com/IT-BAER/HandType>, so every statement in this policy can be checked.

## Changes

If this policy changes, this file will be updated and a note will appear in the app's release notes.

## Contact

Open an issue at <https://github.com/IT-BAER/HandType/issues>.
