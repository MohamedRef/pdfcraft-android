# Scan: testing on a real phone (#288)

The scanner has been built and measured on a desktop, on drawn pages and ten reference photos.
What it does with a real camera in real light is found out on a phone. This page says how to
collect that evidence and what comes back.

Everything here is under **Settings → About → Diagnostics**, and appears once **Record
diagnostics log** is on.

## For the person testing

1. Turn on **Record diagnostics log**, then **Keep scans for testing**.
2. Scan as usual, for as long as you like. Move a corner where the app got it wrong; that is the
   most useful thing you can do, because the corners you leave are what the app is judged
   against. A scan you give up on is kept too.
3. **Share kept scans** makes one zip and opens the share sheet. The zip contains the photos of
   the documents you scanned, so the app asks first.
4. **Delete kept scans** when they have been sent. At most 600 MB is kept; older scans make room
   for newer ones.

Nothing is sent anywhere by the app. Kept scans are in the app's private storage and leave it
only through the share sheet.

## The testing options

One per scanner feature. The defaults are the scanner as it is meant to be used; the others
switch a feature off or put back what it replaced, so a page that comes out wrong can be pinned
on one part. While any is changed, the scan screen says so.

| Option | Default | The other choices |
|---|---|---|
| Find the page with | Automatic: the network (#290), or the line detector when its model is missing | Neural network only · Lines only (the detector before #290) · Nothing (the whole photo) |
| Refine edges on the full photo (#291) | On | Off: the corners are the detector's own, from its small copy of the photo |
| Proportions from perspective (#293) | On | Off: from the lengths of the page's sides, as before |
| Match standard paper sizes (#293) | On | Off: the page keeps the shape worked out for it |
| Turn pages upright (#339) | On | Off: a page stays as its corners gave it |
| Sharpen text, in Clean scan (#299) | 30 | Off, 60 or 100 |
| Smooth grain in dim photos, in Clean scan (#299) | On | Off |
| Filter strength | Each filter's own | 20, 40, 60, 80 or 100 for every filter |
| Flatten page (#302) | Off | On, with the switch on the scan screen itself |

**Reset scanner testing options** puts them back. Keeping scans is not one of them: it changes
no page.

## What a kept scan is

One folder per scan, named for when it was saved or discarded (`20261112-081500`). Per page:

- `page-01.jpg`: the camera's file, byte for byte.
- `page-01.json`: the page's facts (below).
- `page-01.out.jpg` or `.png`: the page as the app made it. Saved scans only.

The zip also holds `diagnostics.txt`, the diagnostics log as **Share diagnostics log** writes it.

```json
{
 "corners": [0.11, 0.08, 0.93, 0.10, 0.95, 0.91, 0.07, 0.89],
 "kind": "other", "paper": "unknown", "curved": false, "notes": "",
 "app": "0.0.26-beta (26001)",
 "outcome": "saved",
 "found": [0.11, 0.08, 0.93, 0.10, 0.95, 0.90, 0.07, 0.89],
 "finding": {"source": "model", "confidence": 0.835, "angle": 0, "passes": 1, "refined_sides": 4, "curved_sides": 0},
 "look": "auto", "strength": 60, "flatten": false,
 "options": {"finder": "auto", "refine": true, "side_proportions": false, "snap_paper": true, "strength_override": null, "upright": true,
             "sharpen": 30, "denoise": true},
 "result": {"file": "page-01.out.jpg", "width": 1654, "height": 2339, "format": "jpeg", "dpi": 200.0,
            "paper": "a4", "aspect": "known_focal", "focal_mismatch": false, "flattened": false, "bend_pct": 0.0,
            "quarter_turns": 3,
            "orientation": {"quarter_turns": 3, "confidence": 0.81, "agreement": 1.0, "tiles": 4, "sure": true},
            "noise": 0.33}
}
```

The first five keys are the scan test set's format (#288, section 1), so a kept scan is a test
set entry as it stands. `corners` are where the person left the corners: 8 shares of the upright
photo, top-left, top-right, bottom-right, bottom-left. `found` is what the app found by itself,
or `null`. `outcome` is `saved` or `discarded`; a discarded page has no `look`, `strength` or
`result`.

## On the desktop

Unzip outside the repository (the folders hold someone's documents) and run

```sh
cargo run --release -p pdfcraft-scan --example scan_replay -- <folder> [out_dir]
```

For every page it prints how far the corners the phone found were from where they were left, and
how far this build's detector is, as the largest corner distance in % of the photo's diagonal.
0.5 % or less counts as "no corner needed moving". With `out_dir` each page is made again from
the photo with the corners, look and options its file records, and the size is compared with
what the phone made. This is how a change to the detector or a filter is checked against every
photo collected so far.

## In the diagnostics log

Besides what `docs/ANDROID_DIAGNOSTICS_LOG.md` lists for `scan`: every `photo` line carries the
`finder` and `refine` in force, every `page` line the `strength` used, `side_proportions`,
`snap_paper`, `upright`, `sharpen` and `denoise`; `test_options` is written when an option is changed, `tests_shared` (pages, bytes)
when the zip is handed out, and `tests_cleared` when the kept scans are deleted. The log itself
still holds no file name, path or document content.

## Not verified

None of this has run on a phone: the Kotlin is checked by CI's build and unit tests, the Rust
options and the replay tool by tests and by hand on a desktop.
