# Sharingan

A fast, minimal Android manga (PDF) reader.

- **Library**: import one or many PDFs, rename, delete, filter by status.
- **Reader**: swipe pages (RTL/LTR toggle), pinch / double-tap zoom, page slider, tap to toggle UI.
- **Resume**: last page saved on every page turn; reopening jumps straight to it.
- **Status**: Unread / Reading / Completed, updated automatically and overridable by long-press.

## Why it's fast
- PDFs are copied once into app storage; page count + cover are computed at import.
- Reader opens directly on the saved page (page count is in the DB, no waiting on the PDF).
- Platform `PdfRenderer` (no native libs), renders on a background dispatcher, serialised by a mutex.
- LRU bitmap cache + prefetch of neighbouring pages; stale renders are cancelled when you flick past.

## Build
Open in Android Studio, or `./gradlew assembleDebug` (needs Android SDK 35).
