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
| Show pages as they will be saved (#301) | On | Off: the scan screen shows the camera's photo with its outline, as before; nothing is previewed |
| Crop with magnifier and snapping to edges (#292) | On | Off: the crop screen before #292, four corner handles on the thumbnail |
| Flatten pages automatically when it makes their lines straighter (#302) | Off | On: every page is flattened by the network and kept only when its lines of text come out at least 10 % straighter (the Flatten switch still flattens without the check) |
| Turn pages upright (#339) | On | Off: a page stays as its corners gave it (Rotate still works) |
| Unsure pages follow their neighbors (#339) | On | Off: in a scan of several pages each page is turned by its own answer only |
| Sharpen text, in Clean scan (#299) | 30 | Off, 60 or 100 |
| Smooth grain in dim photos, in Clean scan (#299) | On | Off |
| Document types in Clean scan (#299) | On | Off: the filter's own strength and the sharpening above, as before |
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
             "sharpen": 30, "denoise": true, "preview": true, "crop_tools": true, "flatten_auto": false, "settle_turns": true, "presets": true},
 "document_type": {"preset": "receipt", "chosen": false, "strength": 80, "sharpen": 35, "keep_paper": false, "suggest_flatten": false,
                   "suggest_greyscale": true, "ratio": 3.0, "ink_share": 0.043, "colour_ink_share": 0.0, "photo_share": 0.0,
                   "stroke_mm": 0.339, "glare_share": 0.0, "gutter_band": false, "whole_photo": false},
 "turns": {"manual": null, "auto": true, "total": 3, "followed": false, "source": "classifier"},
 "preview": {"shown": true, "reused": true, "ms": 412, "width": 905, "height": 1280, "look": "auto"},
 "crop": {"visits": 1, "corner_drags": 2, "side_drags": 1, "snaps_asked": 3, "snaps_taken": 2, "snaps_held": 0, "snaps_failed": 0,
          "snap_ms_max": 85, "nudges": 0, "detects": 0, "detects_found": 0, "whole_photo": 0, "resets": 0,
          "loupe_decodes": 6, "snap": true, "ms": 9100, "moved_pct": 1.235},
 "result": {"file": "page-01.out.jpg", "width": 1654, "height": 2339, "format": "jpeg", "dpi": 200.0,
            "paper": "a4", "aspect": "known_focal", "focal_mismatch": false, "flattened": false, "bend_pct": 0.0,
            "quarter_turns": 3,
            "orientation": {"quarter_turns": 3, "confidence": 0.81, "agreement": 1.0, "tiles": 4, "sure": true},
            "noise": 0.33, "flatten_check": null}
}
```

The first five keys are the scan test set's format (#288, section 1), so a kept scan is a test
set entry as it stands. `corners` are where the person left the corners: 8 shares of the upright
photo, top-left, top-right, bottom-right, bottom-left. `found` is what the app found by itself,
or `null`. `outcome` is `saved` or `discarded`; a discarded page has no `look`, `strength` or
`result`. `preview` is the last preview the scan screen made of the page before it was saved
(#301): `shown` is false when none was (the option off, or Save tapped before it was ready),
`reused` whether its straightened page was kept from an earlier preview, `ms` the time from the
request to the result, and `look` the filter it showed, which can differ from the one saved when
the filter was changed and saved before the new preview came. `crop` is what happened on the crop
screen (#292), added up over every time the page was cropped with Done, or `null` when it never
was: drags of corners and of sides, snaps asked for, taken, skipped by holding still and failed,
the longest snap, keyboard and TalkBack moves, Auto-detect, No crop and Reset, magnifier regions
decoded, whether Snap was on, the time spent, and `moved_pct`, the largest corner move from where
the screen opened in % of the photo's diagonal. It is not kept if the app is closed by Android
before the scan is saved. `turns` says which way up the page was saved (#339): `manual` is the
person's own turn from Rotate or Undo (0–3 clockwise, `null` when they gave none), `auto` the
ordinary setting "Turn pages upright automatically", `total` the turn the page was saved with,
`followed` whether it took its neighbours' turn, and `source` one of `manual`, `neighbours`,
`classifier` or `off`. `result.quarter_turns` and `result.orientation` stay the classifier's own
answer, also for a page that then followed its neighbours. `document_type` is the kind Clean scan
took the page for (#299), whether the person chose it, the kind's settings and what the page
measured as (`stroke_mm` -1 when there is no ink); `null` for the other filters or with the option
off. `scan_replay` keeps a chosen kind and works an automatic one out again.

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
`snap_paper`, `upright`, `sharpen` and `denoise`, and how its turn was decided (#339:
`auto_upright`, `turns_manual` (-1 for none), `turns`, `turn_source`, `settle_turns`, `followed`);
every press of Rotate or of Undo on the "Turned upright" chip is a `turn` line (`index`, `action`
`rotate` or `undo`, `auto_turns`, `turns`); every `preview` line (#301) says which page and
filter, whether the preview was `made`, whether the straightened page was `reused`, the total `ms`
and the native stages (`preview_base_ms`, `decode_ms`, `rectify_ms`, `preview_look_ms`, and the others
the page needed), its `width` and `height`, `quarter_turns` and `flattened`; every `crop` line
(#292) the page's `index`, whether it was left with Done, and the counts of its `crop` object;
every `crop_snap` line whether a `corner` or `side` was let go, how many `sides` were asked for and
how many were `refined` or `curved`, whether the snap was `taken`, `stale` (another drag had
started) or `failed`, and its `ms`; every `crop_detect` line whether Auto-detect `found` a page,
its `source` and `ms`; every `page` and `preview` line `flatten_auto` and, from
#302's check, `flatten_checked`, `flatten_kept`, `flatten_why`, `straight_px`, `straight_flat_px`
(tenths of a pixel, -1 when not measured), `straight_lines` and `straight_flat_lines`; `test_options` is written when an option is changed, `tests_shared` (pages, bytes)
when the zip is handed out, and `tests_cleared` when the kept scans are deleted. The log itself
still holds no file name, path or document content.

## Not verified

None of this has run on a phone: the Kotlin is checked by CI's build and unit tests, the Rust
options and the replay tool by tests and by hand on a desktop.
