# Sharingan

A fast, minimal Android manga (PDF) reader.

- **Shelf**: volumes of the same series are grouped from file names (`20th_Century_Boys_Ch156-160_upscaled2x` → "20th Century Boys", Ch. 156–160) and sorted in reading order.
- **Reader**: swipe pages (RTL/LTR toggle), pinch / double-tap zoom, page slider, tap to toggle UI.
- **Resume**: last page saved on every page turn; reopening jumps straight to it.
- **Status**: Unread / Reading / Completed, updated automatically and overridable by long-press.
- **Find manga online** (⋮ → *Find manga online…*): search a title across sources, open a series to see its chapter list, tick chapters (long-press selects a range, *Select not on shelf* picks what you're missing) and download them as `Title - Ch. N` PDFs that group into one series. Chapters already on your shelf are marked.
  - Built-in source: **MangaDex** (public API; pick the chapter language next to it).
  - **Add website**: any manga site — a series page, or the home page of a site about one series, is read for its chapter list; add a search address with `{query}` to search it too. Or paste any series link under *Open a link*.
  - Only download content you have the right to.

## Why it's fast
- PDFs are copied once into app storage; page count + cover are computed at import.
- Reader opens directly on the saved page (page count is in the DB, no waiting on the PDF).
- Platform `PdfRenderer` (no native libs), renders on a background dispatcher, serialised by a mutex.
- LRU bitmap cache + prefetch of neighbouring pages; stale renders are cancelled when you flick past.

## Sources
`data/source/`: a `MangaSource` can `search`, list `chapters` and resolve a chapter's `pages`.
- `MangaDexSource` uses the MangaDex API (search with covers, chapter feed in the chosen language, one upload per chapter number; page links come from the at-home server right before downloading because they expire).
- `WebsiteSource` (user-added, saved in SharedPreferences) and the hidden `LinkSource` (pasted links) use `ChapterListExtractor`: every same-site link with a chapter number in its text or address is a candidate, the biggest group with the same address shape is the chapter list, sorted by number. Search pages keep links whose text contains every query word. JavaScript-built lists go through the WebView automatically.

## Web scraper
`data/scraper/` turns a chapter web page into a PDF:
- `HtmlImageExtractor` reads `<img>` tags (lazy-load `data-src`/`srcset` first), keeps the biggest group of images sharing one directory (the chapter's pages, not logos/ads), and falls back to image URLs inside `<script>` for JS-built readers. An optional "image URL contains…" filter overrides the guess.
- `WebScraper` downloads 4 images at a time (with the page as `Referer`, retries), keeps plain JPEGs byte-for-byte, re-encodes PNG/WebP/AVIF, and cuts long webtoon strips into screen-sized pages.
- `ImagePdfWriter` embeds the JPEGs directly (`/DCTDecode`), so the PDF is about the size of the images and nothing is re-compressed.

- `WebViewRenderer` handles JavaScript readers: when the plain HTML has fewer than 5 pages (or the site blocks plain requests), the chapter is loaded in a WebView shown on the scraper screen. It waits out "checking your browser" interstitials (you can tap through a verification there), scrolls to the bottom so lazy loaders fill every image, then hands the live DOM to the extractor. Downloads reuse the WebView's user agent and cookies. *Advanced options → Always run JavaScript* skips the plain attempt.

Readers that draw pages onto a `<canvas>` (or serve them as `blob:` URLs) still can't be scraped.

## Build
Requires **Android 16 (API 36) or newer** on the device. Open in Android Studio, or run `./gradlew assembleDebug` (JDK 17, Android SDK 37).

## Releases
Tag `vX.Y.Z` to publish a signed, R8-optimized APK/AAB. See [docs/RELEASING.md](docs/RELEASING.md).
