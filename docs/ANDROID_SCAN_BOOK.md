# Scanner: book mode (#303, part A)

One photo of an open book becomes two pages, in the reading order chosen. This is part A of
#303. Part B, flattening from text lines, is not started.

**Nothing here has run on a phone.** The figures below come from pages drawn in code, on a
desktop.

## What the person sees

- On the scan screen, beside the looks and Flatten page, a **Book** switch. While it is on, each
  photo taken (or retaken) becomes the two pages of a spread. It is remembered.
- While Book is on, a second chip shows the page order, **Right to left** or **Left to right**;
  tapping it flips it. It starts from the app's layout direction (Arabic: right to left) and is
  remembered.
- A page that is half of a spread says so on the scan screen ("Right page of a spread").
- A photo that is not a spread stays one page (see "When a photo is cut" below).

The issue puts the switch in the in-app camera (#294) with a guide line for the spine. That
camera does not exist yet, so the switch is on the scan screen for now and works with the phone's
camera app. When #294 lands, the switch and a centre guide belong there.

## How it works

```
onScanPhoto
    scan_find_document_with           the spread's outline, as for any page (#290, #291)
    scan_split_spread                 src/scan_book.rs, only while Book is on
        Photo::decode, Pyramid::new   the photo's grey levels, halved down to 1 024 px
        book::split_photo             scan/src/book.rs
            Pyramid::straighten       the outline straightened to 800 px on its longer side
            book::find_gutter         the module from #382, unchanged
            book::split               the outline cut along the gutter, 0.5 % given up each side
    scanBookPages                     ui/scan/ScanBook.kt: two ScanPages in the chosen order
```

- `find_gutter` and `split` are the tested blocks from #382, wired in as they are. This change
  adds `split_photo` (straighten, find, cut) and `Pyramid::straighten`, which samples from the
  smallest level of the edge-refinement pyramid that still has as many pixels across the outline
  as the 800-pixel output, so the small picture is not aliased from the full-size photo.
- When no outline was found, the whole photo is cut: with the book filling the frame, the
  gutter is still in the middle third.
- Each half is an ordinary `ScanPage` with its own corners and the **same photo file**. Save,
  preview, crop and the kept scans treat it as any other page. The half's `found` corners are its
  own outline, so "corners moved" in the log measures moves from the cut, not from the spread.
- **Deleting a page** removes the photo only when no other page uses it. **Retaking** a half
  replaces both halves of that spread. Before this change, a deleted or retaken page left its
  photo in the cache until the scan ended. Now it is deleted at once unless the other half still
  uses it.
- The scan screen used to find the page being cropped by its photo file, which would open the
  left half when the right one was tapped. It now uses the page's key (file plus half).
- **A scan restored after Android closed the app** keeps both halves. The session text gives a
  half's page line a fifth field (`ScanBookHalf.encode`). A session written before this change
  reads as before.

### When a photo is cut

`find_gutter`'s rules, from the issue: the deepest valley of the column profile in the middle
30 % of the straightened spread, at least 6 % darker than the two page halves, refined in 8
bands with a straight line fitted through them (so a tilted spine is followed). With no such
valley, an outline whose shape is that of two A or two Letter pages side by side (within 8 %) is
cut at the centre line with confidence 0. Anything else stays one page.

## Testing option, log, kept scans

- **Testing option** (`ScanLab.bookGutter`, "Book mode: cut at the gutter found in the photo",
  on by default). Off: every spread in book mode is cut at its centre line, so a wrong cut can be
  pinned on the gutter search.
- **Diagnostics log.** On each `photo` line: `book` (on or off) and, when on, `book_order`,
  `book_gutter_search`, `book_split`, `gutter_found`, `gutter_top`, `gutter_bottom`,
  `gutter_confidence` and `book_ms`. On each `page` line: `book_half` (`left`, `right`, `none`).
  `book_mode` is written when the switch or the order is changed.
- **Kept scan page file.** `"book": {"half", "gutter_found", "gutter_top", "gutter_bottom",
  "confidence", "order"}`, or `null`. The options carry `book_gutter`. `scan_replay` repeats a
  half from its own corners like any page.

## Measured, and on what

All of these are on pages drawn in code (`scan/src/tests_book.rs` from #382,
`scan/src/tests_book_photo.rs` and `src/scan_book.rs` here). None of them is a photo of a real
book.

| Case | Result |
|---|---|
| Spread with a gutter at 46 %, 2000 × 1500 photo, outline at a slant | gutter 0.4600 top, 0.4599 bottom; confidence 1.0; the cut meets the outline within 1 % of its width of the drawn gutter |
| Spine tilted from 45 % (top) to 51 % (bottom) | 0.4502 and 0.5100 found; confidence 0.49 |
| One portrait page, no gutter | stays one page |
| Two-page shape, no gutter visible | cut at 0.5, confidence 0 |
| Gutter search off (testing option) | cut at 0.5 whatever the photo shows |
| Through the UniFFI call, square spread with a gutter at 47 % | found within 0.5 %; each half ends 0.5 % of the width short of the gutter |

The time `scan_split_spread` takes was not measured on a phone. It decodes the photo a second
time (after the outline search), which is most of its cost. The log's `book_ms` and the native
`decode_ms` and `gutter_ms` stages will show it.

## Not done, and not verified

- Nothing has run on a phone or seen a photo of a real book. The thresholds (6 % darkness,
  middle 30 %, the 8 % ratio tolerance) are the issue's starting values.
- The issue's acceptance items (10 spreads into 20 pages in both orders on the owner's phone;
  text near the gutter readable after flattening) need the phone.
- The gutter side of each half is not refined again (it is not a paper edge). The outer sides
  keep the refinement of the whole outline. Re-refining the halves' outer sides was not done.
- No `even_edges` pass for a dark band along the gutter. The issue says to add it only if the
  test photos show a band after the lighting step.
- The crop screen does not yet say that a page comes from a spread. That note lands once #292's
  crop screen is merged.
- The switch belongs in the in-app camera (#294) with a guide line, once it exists.
- A portrait spread (two pages above each other) has no centre-line fallback.
- Part B (text-line flattening) is not started.
