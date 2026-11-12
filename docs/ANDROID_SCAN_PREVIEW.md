# Scan: a preview of the page as it will be saved (#301)

The scan screen shows each page as Save will make it: straightened, with its proportions, turned
upright, flattened when asked, and with the chosen filter. Under it, **Result** and **Photo**
switch between that page and the camera's photo with the outline of the part kept, so the edges
the app found can still be checked. The native call below makes the page; the screen part is
described after it.

## The call

`scan_preview_page(request: ScanPageRequest, max_side: u32) -> ScanPreview`
(`apps/pdfcraft-android/src/scan.rs`). The request is the one `scan_process_page` takes, so a
preview and a saved page are made from the same description; `output_path` is not used and
nothing is written. `max_side` is the preview's longer side in pixels (64–4096; the screen
should ask for 1280).

`ScanPreview` holds `width`, `height`, `rgba` (four bytes a pixel, ready for an Android
`Bitmap`), `quarter_turns`, `flattened`, `reused` and `timings` (`scan_preview_base` with its
parts when the page had to be straightened, then `scan_preview_look`).

## How it stays quick

The pipeline is split in two (`apps/pdfcraft-android/scan/src/page.rs`):

- `page_base`: decode, proportions, straighten or flatten, turn upright. With
  `PageRequest.max_side` set the page is shrunk to fit and never enlarged, whatever its look, so
  one straightened page serves every look.
- `page_look`: the look on that page.

`scan_preview_page` keeps the last four straightened pages (under 5 MB each at 1280 pixels),
keyed by the photo's path, size and modification time, the corners, and everything that changes
the page before its look (focal length, paper matching, proportions, flatten, upright, size).
The look, its strength, sharpening and smoothing are not in the key: changing only those costs
one look on a small page and no decoding. `scan_release_previews` empties the store;
`MobileSession::release_memory` calls it.

`process_page` is `page_base` then `page_look` then the encoder, as before.

## How close the preview is to the saved page

`a_preview_shows_what_will_be_saved` (`src/scan.rs`): a drawn page of print in a 1400 × 1900
photo, the preview at 1280 against the saved page shrunk to the preview's size, mean absolute
difference in grey levels.

| Look | Difference | Saved at |
|---|---|---|
| Original | 1.4 | 1260 × 1680 |
| Auto (Clean scan) | 2.4 | 1643 × 2190 |
| Colour | 1.8 | 1260 × 1680 |
| Greyscale | 1.7 | 1260 × 1680 |
| Black and white | 5.1 | 2520 × 3360 |

The issue asks for 3 or less. Black and white misses it: it has two levels and is saved at twice
the size, and a small two-level page is rougher than a large one shrunk. A preview half that
size (640) is further off: Auto 4.8, black and white 10.9. A 12-megapixel photo's page is
shrunk more than this test's, so expect the upper figures there. It was not measured on a real
photo.

## The screen

- `ScanScreen` (`ui/ToolScreens.kt`) asks for a preview of the page in view whenever the page, its
  corners, the filter, Flatten or a testing option changes. Until the preview of exactly that is
  ready it shows the photo with its outline and a "Preparing the page" note; if the preview
  fails it shows the photo without the note.
- `AppViewModel.requestScanPreview` builds the request with `scanPageRequest` (`ui/ScanSave.kt`),
  the same function `saveScan` uses, so a preview and the saved page are made from one
  description. Previews run one at a time on a dispatcher of their own (`Dispatchers.Default`
  limited to one thread), so they never run two at once and never compete with each other for
  memory. A newer request replaces one still waiting; one already running finishes (the native
  call cannot be stopped) and its result is dropped if it is no longer the one wanted.
- The preview is held as one `ImageBitmap` of at most 1280 pixels on its long side (under 7 MB).
  It and the native store are released when the scan is saved or discarded.
- Testing option **Show pages as they will be saved** (on by default): off puts back the old
  screen, the photo only, and asks for no preview.
- Diagnostics: a `preview` line per preview made (see `docs/ANDROID_SCAN_TESTING.md`).
- Kept scans: each page file's `preview` says whether one was shown before saving, its time and size.

What was checked: the request builder, the out-of-date rule and which view is shown, by JVM
tests (`ScanSaveTest`), and the whole app by CI's build. **Not run on a phone**: how long a
preview takes there, whether 1280 pixels is sharp enough on his screen, and how the switch between
pages feels.

## Looks per page, strength, the strip, compare, apply to all, cancel

Added after the preview, in the same issue. All of it is Kotlin; the native side is unchanged.

- **A look per page.** `ScanPage.look` (`ScanPageLook` in `ui/scan/ScanLooks.kt`) holds the look
  and its strength (null for the look's own). Choosing a look changes the page shown; a new page
  takes the look of the last page (the one chosen last), and a retaken page keeps its own.
  `saveScan` makes each page in its own look, through the same `scanPageRequest`, which now takes
  the page's strength. The testing option **A look for each page** (on) puts back one look for
  the whole scan when off: choosing a look then changes every page.
- **Strength.** A slider under the looks for every look but Original (`ScanStrengthRow`,
  `ui/scan/ScanLookUi.kt`). It changes the page's strength as it moves; a preview of the same
  page in another strength waits 30 ms before it starts (`SCAN_PREVIEW_SETTLE_MS`), so a quick
  slide makes the value it stops on, and a newer request replaces one still waiting. The testing
  option **Filter strength** still wins: while it sets a strength the slider shows that value,
  can't be moved and says why.
- **While a new look is made** the page in its last look stays on screen ("Preparing the page"
  shown over it) instead of going back to the photo: `ScanPreviewKey.samePage` and `scanShown`.
  The photo is shown only until a page's first preview arrives.
- **The looks strip.** One tile per look, 72 × 96 dp, with its name underneath; the page's look is
  outlined, and its tile is the page's own preview. The other tiles are made after the page's
  preview, Original first, each at the main preview's size (so the straightened page kept
  natively serves them all and each costs one look) and then shrunk
  (`AppViewModel.requestScanTiles`). They share the preview's thread and give way to a page
  preview between looks. The testing option **Show the looks as small previews of the page** (on)
  puts back the looks as words when off, and then only Original is made (for compare).
- **Compare.** Press and hold on the preview to see the page in the Original look (made once per
  page, kept while the page is shown). A note at the bottom says "Hold to see the original", and
  "Original" while held. TalkBack: a long press action, "Show the original", switches it on and off.
- **Apply to all** gives every page this page's look and strength (not its corners, turn or kind
  of document), after asking; it is enabled only when it would change a page. **Reset look** puts
  the page back to Clean scan at its own strength. A page whose look differs from the first page's
  has a dot on its thumbnail.
- **The session.** Each page's look goes in a `look` line after its `page` line, and its Flatten
  page choice in the page line's eighth field (#302), so both survive Android closing the app. The
  scan-wide `flatten` line of a session written before Flatten became per page is still read: its
  pages come back flattened. The version
  stays `pdfcraft-scan 2` on purpose: a build that reads 2 ignores lines it doesn't know, so a
  newer session still opens in an older build (with default looks).
- **Cancel while saving.** The busy dialog has Cancel and a count while pages are made; Cancel
  stops before the next page, deletes the pages made, and leaves the scan on screen as it was.
  Once the pages go into the PDF it can no longer be stopped.
- **Pages saved without clean-up.** A page whose processing fails still goes in as photographed;
  the final message now says how many did.
- **Diagnostics.** `preview` lines gain `look`, `strength` (as made), `strength_source` (`look`,
  `page` or `lab`) and `settled`; `page` lines gain `look`, `look_per_page`, `strength_source` and
  `look_differs`; the `saved` line gains `looks_mixed` and `unenhanced`. New lines: `looks_strip`
  (`index`, `look_strip`, `asked`, `made`, `reused`, `ms`, `slowest_ms`), `look_chosen` (`index`,
  `why` = `look`, `strength`, `reset` or `suggestion` (the Grayscale chip, #299), `look`, `strength`, `strength_source`, `look_per_page`,
  `pages`; the slider writes one when let go), `look_applied_all` (`index`, `look`, `strength`,
  `pages`, `changed`) and `save_cancelled` (`pages`, `made`, `ms`).
- **Kept scans.** Each page file gains `page_look` (`id`, `strength`, `level`, `per_page`); `look`
  and `strength` are now the page's own. `options` gains `look_per_page` and `look_strip`.
  `scan_replay` reads `look` and `strength` as before, so it repeats each page's own.

What was checked: the look rules (new page, retake, choose with and without the option, apply to
all and its question, the dot, the strip's order and which image a tile shows, compare), the
session's new lines and an older build reading them, the request's strength, the page file and
the options, by JVM tests (`ScanLooksTest`, `ScanSessionTest`, `ScanSaveTest`, `ScanLabTest`).
The screen itself was built, not used: **nothing here has run on a phone**, so the strip's speed,
how the slider feels, the hold gesture, TalkBack and the layout at large font sizes are not known.

## Not done (left in #301)

- `ScanCancel`: a native preview cannot be stopped once started.
- Paper White / Keep color: `ScanPageRequest` has no paper field yet.
- Whiteboard as a pipeline of its own (it is Auto color at 85).
- Zoom on the preview (double-tap or pinch to 100 %).
- A test of the latest-wins order with a fake dispatcher; the app has no coroutine test library.
- Timings on a phone (the log now records them, including the strip's).
