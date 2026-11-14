# Large images on a page (#236)

## What the phone showed
In the owner's diagnostics logs (S24 Ultra, 352 MB / 869-page file, 0.0.25-beta to 0.0.28-beta),
every `slow_render` line was page 2 (421–738 ms per whole-page render) or page 1 (255–304 ms).
The median whole-page render was 43 ms. Page 2 was slow on every render, including the reading
stage and long after the open finished, so the cause is the page itself, not contention with
background preparation.

## Cause
Page 2 draws one `/DeviceCMYK` photo of 2550 × 3300 pixels (6 MB Flate, 33.6 MB decoded). The
viewer's `PageRenderer` made a fresh hayro render cache for every request, so every render:

| Step (host callgrind, one render) | Share |
| --- | --- |
| CMYK → RGB conversion of 8.4 M pixels (moxcms LUT, one thread) | 51% |
| Flate decode of 6 MB to 33.6 MB | 25% |
| Area resize to the 1043 × 1333 target | 17% |

## Changes
- **Conversion in parts** (vendored hayro-interpret patch 104). An ICC transform over an image
  of at least 1 Mi pixels runs in up to four runs of whole pixels on scoped threads. The
  transform maps each pixel on its own, so the bytes are identical
  (`large_cmyk_images_converted_in_parts_match_one_run`). It falls back to one run if a thread
  can't start, and wasm32 always uses one run.
- **Decoded images kept across renders.** `PageRenderer` keeps hayro's byte-budgeted decoded-image
  cache (64 MB per insert budget; the map is dropped when it would pass it) from one render to
  the next. On Android, all renderers of one document version share it, whatever the view
  settings. Cache keys include where each image's stream lies in the data, so the images are
  shared only between renderers of the very same bytes. A new document version (an edit, the
  display copy) starts empty, and `release_memory` (memory trims) drops it with the renderers.
  Pixels are identical (`renderers_keep_and_share_decoded_images_with_identical_pixels`).

## Host measurements
4-core x86, the extracted pages 1–4 at the phone's scale (1.702), example
`apps/pdfcraft-android/examples/page_render_timings.rs`:

| Page 2 render | Before | After |
| --- | --- | --- |
| First on a renderer | 205–337 ms | 165–199 ms |
| Later renders of the same document version | 205–337 ms | 35–42 ms |

Page 1 (12 luminosity soft-masked groups) stays at 180–230 ms and is a separate case. No phone
speed-up is claimed until a log from a build with these changes shows it. The kept images cost
memory: up to the cache budget per open document, about 25 MB for this page.
