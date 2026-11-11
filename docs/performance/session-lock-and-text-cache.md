# Renders and text off the session lock; bounded text layers

Issues: #87, #90, #113, #117.

## What changed

- Every change made under the session lock (`MobileSession::with_auto`) now publishes a `DocView` for each prepared document: its display and source bytes, render settings and page sizes. `render_page`, `render_page_compact` and every text query read that view instead of taking the session lock, so they no longer wait for a running tool such as `doc_save`, OCR or optimize. A render that starts while a tool runs draws the version before that tool, which is the version the app asked for. Documents still in their reading stage keep the old locked path.
- Renderers are parsed outside the renderer-cache lock, and text is extracted outside the text-cache lock. `release_memory` therefore never waits for an extraction.
- Text layers are kept for the 32 most recently used pages per document version (was: every page, never evicted). Whole-document passes (`find_in_page` and `page_plain_text`, used by search and the Read Aloud word count) use a kept layer when there is one but do not add new ones.
- `save_attachment` uses `pdfcraft_render::attachment_data_with_limit` with a 256 MiB bound on decoded and unfiltered files. `attachment_data` (desktop) keeps its 1 GiB decode bound and unbounded raw reads, and a differential test checks that both return the same bytes below the bound.

## Measurement

`apps/pdfcraft-android/examples/session_contention_timings.rs`, release profile, on a 4-core cloud host. Fixtures are synthetic and generated locally: 869 US Letter pages, each with 45 lines of text, one raw 400×337 RGB image, 32 URI links and 2 square comments (357,797,013 bytes); and a 1,000-page text-only PDF (45 lines of 12 words a page). Single runs, before = `main` at 4150601, after = this change. These are host numbers, not phone results.

| Measurement | Before | After |
| --- | ---: | ---: |
| `render_page` (page 5, scale 2) started 20 ms into a `doc_save` of the 869-page file | 1,973 ms (save: 1,954 ms) | 25–30 ms (save: 2,179–2,227 ms) |
| Same render with nothing else running | 34 ms | 25–31 ms |
| Resident memory after `find_in_page` on all 1,000 pages | 28 → 405 MiB | 28 → 30 MiB |
| Search time over 1,000 pages | 2,173 ms | 2,156 ms |

The render during the save waited for the whole save before; it now takes about as long as an idle render. Search keeps no text layers, so memory stays flat.

## Not changed, measured for follow-up

- An ink `comment_add` on the 869-page file takes about 1.3–1.6 s on this host, almost all in the engine's refresh after the edit (`Session::refresh_scoped`: display-copy field collection, the full comment list and form enumeration over every page). The first repaint afterwards takes 39–54 ms, so the renderer re-parse named in #131 is not the cost; the edit itself is.
- Lowering one 4 MP page (16,453,524 bytes) through UniFFI takes about 22 ms on this host, against 36 ms for the render itself (#86).
