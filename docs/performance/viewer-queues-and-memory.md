# Viewer work queues and memory bounds

Issues: #88, #91, #92, #93, #94, #95, #96, #98.

These are code-path changes in the Android app. They were checked with JVM unit tests and on the host, not measured on a phone; no device speed-up is claimed here.

## Queues (#96, #88)

All engine queues are slices of `Dispatchers.IO` (native calls block, so they must not occupy the CPU-sized Default pool):

| Queue | Parallelism | Work |
| --- | ---: | --- |
| `commands` | 1 | tools, in the order the UI issued them |
| `renders` | 2 | the view's pages and tiles |
| `thumbnails` | 1 | Pages grid, comment snippets, crop and file-card previews |
| `documentText` | 1 | whole-document search and the Read Aloud word count |
| `interactive` | 2 | selection and Read Aloud text of the pages in view, colour samples |

Selection queries no longer wait behind queued tools; since the bridge publishes each finished tool's document view (`session-lock-and-text-cache.md`), they read the version the last finished tool left. The Read Aloud word count runs page by page on `documentText`, so stopping Read Aloud cancels it and the first paragraph does not wait behind it.

## Memory

- **Backgrounding (#92).** `TRIM_MEMORY_UI_HIDDEN` drops page images that are not on screen and keeps the parsed documents. `RUNNING_LOW`, `RUNNING_CRITICAL` and `BACKGROUND` and above also drop native renderers and text layers, off the main thread.
- **Pages grid (#95).** Thumbnails are kept for the pages in view and 24 on each side; others render again when scrolled back to (was: every page ever shown, about 465 KB each).
- **Stale tiles (#91).** A tile that finishes after the view stopped wanting it is not cached, so it cannot evict images about to be needed (`stale_tile_dropped` in open timings). A tile the view stops wanting while it renders is now also stopped in the engine (`render_page_compact_cancellable` with a `RenderCancel` switch; `tile_render_cancelled` in open timings). Whole pages still finish, because they stand in while their page scrolls back. On a synthetic page of 150,000 strokes, a whole-page render took 1,880–2,017 ms; the same render cancelled 5 ms in returned after 10–11 ms (host, `session_contention_timings cancel`).
- **Images at another scale (#524).** The page image cache is keyed by page, generation and level, not by scale. When the layout changes under a cached image (fit every page to the width, another page mode, a new window size) and the view wants it at another scale, the cached image is dropped so the page renders sharp again; the old pixels stand in until then. A fallback tile cut at another scale is not drawn, since its grid covers another part of the page. The check is a map lookup per wanted image on each view change. Not measured on a phone.
- **File-list thumbnails (#93).** At most two draw at once (was: one per visible cell on `Dispatchers.IO`). A file that cannot be drawn leaves a marker keyed like the cached image, so it is not read and parsed again each time its cell shows.

## Main thread and per-frame work

- **Home (#94).** Each recent file's thumbnail source (a folder listing) and location (possibly a package-manager call) is worked out with the list on `Dispatchers.IO`, not during composition.
- **Overlays (#98).** Search hits are drawn only for the pages in view, found by binary search in the page-ordered hit list, with no per-hit list allocation. Inserting a page's hits uses the same binary search. Note icons and form-field tints skip pages out of view before any coordinate conversion.
