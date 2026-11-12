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

## Not done (the rest of #301)

- `ScanCancel`: a preview cannot be stopped once started.
- A look per page, the strength slider, the looks strip, compare, apply to all, the session's
  new fields.
- Timings on a phone (the log now records them).
