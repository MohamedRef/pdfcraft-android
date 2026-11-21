# Scanner: a page's first picture

Refs #785 (finishing delays) and #549 (a page takes seconds). Written 21 November 2026 against
0.0.46-beta. **Nothing here has run on a phone.** Every figure below is a desktop replay unless it
is marked as read from the owner's log.

## What was slow

The owner's log of 21 November 2026 (SM-S928B, a kept 12-page scan reopened, photos of 12.5 MP):

- a page made by the background queue took 2.4–4.1 s (`page_ready ms`, `reused=false`), two at a
  time, so the last pages waited 23 s and the whole scan 25.4 s (`scan_ready ms=25361`);
- over the whole log, a preview not reused took 2.6 s at the median (23 `preview` lines), a page
  2.8 s (119 `page_ready` lines);
- before that, after a shot in the camera: `capture_ms` 1.8 s at the median (shutter to file) and
  `find_ms` 2.4 s (0.4–4.3 s; 13 of 23 photos needed all seven passes of the detector).

So a page appeared cleaned some 3 s after its outline was found, and its outline some 2.4 s after
the file was written. Until then every screen showed the photo as taken.

## Where a preview's time goes

`cargo run --release -p pdfcraft-scan --example scan_first_timing -- <folder> [threads] [out_dir]`
makes each kept page as the scan screen's preview asks it (1280 px, the options its page file
records) and prints every stage. On the owner's 12 photos, medians in ms:

| Stage | Desktop replay (4 threads) | Owner's log, phone (23 previews) |
|---|---|---|
| decode | 169 | 215 |
| straighten by the corners (once or twice a page) | 443 | 96–314 on the lines read |
| lines of the plain page measured (`flatten_flat`) | 19 | 19 |
| flattening network | 478 | 602 |
| its check | 19 | 26 |
| border trim of a kept flattened page (5 pages) | 604 | 234 |
| text-line fallback when the network's page was not kept (7 pages) | 767 | 350 |
| orientation classifier | 81 | 245 |
| look: document type | 307 | 358 |
| look: light | 203 | 229 |
| look: tone | 30 | 50 |
| look: fingers | 40 | 43 |
| **whole preview** | **2917** (most 4290) | **2571** |

The desktop was a shared 4-core machine with other builds running, so its absolute figures move
(a second run under more load gave 5756 ms for the whole preview); the shares are what to read.
On 7 of the 12 pages the flattening network ran and its page was not kept (`fewlines`,
`notbetter`, `unmeasured`): 1.3 s at the median on the desktop for nothing seen. The JPEG decoder
in use (`zune-jpeg` through `image`) cannot decode at a reduced size, so a preview cannot skip the
full decode from the file.

## What changed

A page that is not made yet gets a **first picture** at once, and the page itself is made exactly
as before and replaces it.

- **Native** (`scan/src/first.rs`, `src/scan_first.rs`: `scan_first_page(request, max_side,
  small)`): the page straightened by its corners, with the person's own turn, in its look
  (strength, sharpening, smoothing, paper colour, marks made by hand), 640 px long. It is made from
  a small upright copy of the photo that the app already holds (the thumbnail read at a quarter of
  the size when the photo came), so the photo's file is read only for its header (size and lens).
  The page's proportions and paper come from the same code as the page's own
  (`page::page_shape`), from the photo's full size, so the first picture has the page's shape.
  A copy that is not the photo's shape (or none) is ignored and the photo decoded instead.
- **Left out**, by design: flattening (network, check, text lines, trim), the orientation
  classifier, the document type and the finger search. Nothing of a first picture is kept for the
  page: no preview is reused from it and no flattening decision is taken from it.
- **App** (`ui/ScanPageQueue.kt`): beside the queue, one first picture at a time, the page on
  screen first, for every page whose outline is known and that is not made from its present key.
  It is kept by the page's preview key, so a first picture of other corners, another look, turn or
  option is never shown, and it is dropped when the page is made. It is shown on the pages strip,
  the document screen's tile, the camera's pages button (which now also shows the made page instead
  of the photo as taken) and the scan screen while it says "Preparing". A page counts as ready,
  and is saved, only from the page itself: `readyOf`, `ScanFullPages` and Save do not see first
  pictures. When the page was made before (its corners or look changed), the first picture takes
  the turn it was last shown with.
- **The saved page is byte for byte what it was**: `page_base` lost only two helper functions cut
  out of it (`page_shape`, `page_scale`), and the scanner's own gate and tests pass unchanged.

Testing option "Show each page at once in a quick version while it is made" (`firstPicture`, key
`first_picture`, on). Off: the photo until the page is made, and the camera's button shows the
photo, as before.

## What was measured

Desktop replay on the owner's 12 photos (described: A4 sheets of print and tables photographed by
hand on a light cloth, some sideways or upside down, two with a thumb on the edge), medians in ms:

| | Before | After |
|---|---|---|
| First cleaned picture of a page | 2917 (the preview; most 4290) | **110** (most 140) from the quarter-size copy: header 0, straighten 16, look 89 |
| The same without the copy (photo decoded) | | 569 (most 752) |
| At 960 px instead of 640 | | 241 (most 306) |
| The page itself (preview, full size, saved bytes) | unchanged | unchanged |

Under heavier load the same run gave 201 ms against 5756 ms: about a twenty-fifth either way.
Twelve first pictures one after another are 1.3 s on the desktop where the twelve previews took
25 s on the phone. On the drawn set (`tests_first.rs`, a 1600 × 2000 desk scene): the first
picture has the preview's proportions within 0.5 %, the same paper for every paper hint and both
proportion rules, and is within 6 grey levels of the preview shrunk to its size.

The classifier was tried on the first picture and taken out: at 640 px it gave 3 of the 10 pages
it was asked about another turn than the page itself got (a quarter turn the other way, or upside
down). So **a page the classifier turns is shown unturned in its first picture** and turns when the
page arrives. On this scan that is 6 of 12 pages.

## Not verified

- Nothing on a phone: the time of a first picture there (expected near the desktop's, since the
  look's parts took about the same on both), the cost of reading the thumbnail's pixels and
  writing the small JPEG, and whether making first pictures beside two queue lanes slows the pages.
- The Kotlin was not compiled here (no Android SDK): CI is its first compiler.
- Whether a first picture that later turns, flattens or loses a finger reads as a glitch to the
  owner. The option puts the old behaviour back.
- Memory: a first picture is 640 px (about 1.2 MB of pixels while it is made), kept as a JPEG in the
  cache folder and a 72 dp thumbnail, like a made page.

## Log and page file

- `page_first`: `index`, `made`, `ms`, `from_copy` (false: the photo was decoded), `width`,
  `height`, `turns`, `turn_known` (the turn was the one the page was last made with),
  `first_header_ms`, `first_decode_ms` (only when decoded), `first_rectify_ms`, `first_look_ms`.
- `page_ready` adds `first_ms`: from when the page began to wait to its first picture (-1: none).
- `scan_ready` adds `first_pictures` (made in the batch) and `first_ms` (from the batch's start to
  the last one; -1: none), beside `ms` for the pages themselves.
- Page file: `options.first_picture`, and `first_picture` (`made`, `ms`, `from_copy`, `width`,
  `height`, `turn_known`; null when none was asked).

## Not done, and why

- **`most_at_once` stays 2.** The log shows room in memory (native heap 44–279 MB), but with two
  lanes a page already took 3.4–4.1 s against 2.4–2.6 s alone: the straightening uses four threads
  and the networks their own pool, so a third lane would mostly make each page slower. It needs a
  phone to measure.
- **The detector's 2.4 s** (`find_ms`) is now the larger part of the wait after a shot, and the
  first picture waits for it. The live outline at the shutter (`ScanCapture.previewQuad`) could
  stand in until the search ends, but it was found on only 13 of the 23 shots in the log.
- **Skipping the flattening network on pages it will not keep** would change which pages are
  flattened, so it is not a speed-only change.
- **A made page flying from the viewfinder to the pages button** after a shot. The camera screen
  shows nothing of the kind today. It would touch `ui/scan/ScanCameraScreen.kt` (an overlay over
  the viewfinder, the animation, the button's position), `ScanCameraRouter.kt` (handing the first
  picture and its page key to the screen) and `ScanPageQueue.kt` (a callback when a first picture
  is up), with a testing option; and to be on time it needs the outline at the shutter, see above.
  More than a modest change without a phone to try it on, so it was left as this note.
