# Sharingan

A fast, minimal Android manga (PDF) reader.

- **Shelf**: volumes of the same series are grouped from file names (`20th_Century_Boys_Ch156-160_upscaled2x` → "20th Century Boys", Ch. 156–160) and sorted in reading order.
- **Reader**: swipe pages (RTL/LTR toggle), pinch / double-tap zoom, page slider, tap to toggle UI.
- **Resume**: last page saved on every page turn; reopening jumps straight to it.
- **Status**: Unread / Reading / Completed, updated automatically and overridable by long-press.

## Why it's fast
- PDFs are copied once into app storage; page count + cover are computed at import.
- Reader opens directly on the saved page (page count is in the DB, no waiting on the PDF).
- Platform `PdfRenderer` (no native libs), renders on a background dispatcher, serialised by a mutex.
- LRU bitmap cache + prefetch of neighbouring pages; stale renders are cancelled when you flick past.

## Build
Requires **Android 16 (API 36) or newer** on the device. Open in Android Studio, or run `./gradlew assembleDebug` (JDK 17, Android SDK 37).

## Releases
Tag `vX.Y.Z` to publish a signed, R8-optimized APK/AAB. See [docs/RELEASING.md](docs/RELEASING.md).
