# HandType

Turn typed text into believable handwriting on Android. Built-in templates work fully offline; nothing about your text leaves your device.

## Features

- Two built-in handwriting styles: **Ink Script** (glyph bitmap) and **Flowing Cursive** (system cursive with per-character jitter, baseline waves and skew)
- Real handwriting feel: per-character rotation, jitter, alpha variation, mesh warp and baseline drift
- Save to gallery as PNG
- Save without paper background as transparent PNG
- Multi-page PDF export with whitespace-aware page breaks
- Share via the system share sheet
- History gallery: every generation is auto-archived locally and can be revisited, re-shared, re-exported or deleted
- Side menu with Templates, History, Help, FAQ, About and Privacy
- Premium (optional): capture your own handwriting from a photo and build a personal template — runs fully on-device with ML Kit

## Privacy

HandType is offline-first and tracker-free.

- No analytics, no crash reporters, no advertising
- No network calls during normal use
- Your text is rendered locally; History is stored only in the app's private storage
- Premium handwriting capture uses on-device ML Kit; the photo never leaves your device

Full policy: [PRIVACY.md](PRIVACY.md)

## Stack

- Kotlin 1.9.22, Jetpack Compose, Material3
- AGP 8.4.2, Gradle 8.6, JDK 17
- minSdk 23, target/compile 34
- CameraX + ML Kit text recognition (premium capture)

## Build

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## License

Apache License 2.0 — see [LICENSE](LICENSE).
