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
| A look for each page (#301) | On | Off: choosing a look or a strength changes every page, as before |
| Show the looks as small previews of the page (#301) | On | Off: the looks are words, as before, and no small previews are made |
| Whiteboard: brighter board and stronger marker colors (#301) | On | Off: Whiteboard is Auto color at 85, as before |
| Stop previews that are no longer needed (#301) | On | Off: an old preview runs to the end and is dropped, as before |
| Zoom the page preview (#301) | On | Off: the preview can't be zoomed and no sharper page is made |
| Keep the page's light estimate between Clean scan changes (#446) | On | Off: Clean scan and Whiteboard measure the page's light, grain and sharpening again for every strength or paper change, as before. The page looks the same either way |
| Crop with magnifier and snapping to edges (#292) | On | Off: the crop screen before #292, four corner handles on the thumbnail |
| Crop: say when Auto-detect finds nothing, and try the line detector next (#443) | On | Off: every Auto-detect press makes the same search again and nothing is said when it finds nothing, as before #443 |
| Flatten pages automatically when it makes their lines straighter (#302) | Off | On: every page is flattened by the network and kept only when its lines of text come out at least 10 % straighter (the Flatten switch still flattens without the check) |
| Trim the background left along a flattened page's edges (#302) | On | Off: a flattened page is kept as the network's grid gives it, with any strip of table along its edges |
| Book mode: cut at the gutter found in the photo (#303) | On | Off: in book mode every spread is cut at its centre line, whatever the photo shows |
| Flatten from the lines of text when the network gives nothing better (#303) | On | Off: a flattened page whose network result was missing or not kept stays straightened by its corners, as before |
| Turn pages upright (#339) | On | Off: a page stays as its corners gave it (Rotate still works) |
| Unsure pages follow their neighbors (#339) | On | Off: in a scan of several pages each page is turned by its own answer only |
| Sharpen text, in Clean scan (#299) | 30 | Off, 60 or 100. With document types on, the type's own sharpening is scaled by this level over 30 (#424): 60 is twice the type's, 100 three and a third times it, at most 100 |
| Smooth grain in dim photos, in Clean scan (#299) | On | Off |
| Document types in Clean scan (#299) | On | Off: the filter's own strength and the sharpening above, as before |
| Document types shape Clean scan's tone (#299) | On | Off: a type sets only strength, sharpening and paper color (no receipt gamma, no whiteboard S-curve) |
| Sharpen photos on the page gently (#299) | On | Off: only the objects the look finds itself are sharpened gently, as before |
| Suggest Flatten page or Grayscale for a document type (#299) | On | Off: no suggestion chips |
| Barcodes, stamps and soft print are not photographs (#427) | On | Off: the first photograph rule, which also reads those as photographs, so a label with a large barcode becomes a document with photographs |
| A page whose edges were not found is a Document until it is cropped (#444) | On | Off: the kind of such a page is worked out from the whole photo, table and surroundings included, as before #444 |
| Page strength wins over the document type (#424) | On | Off: the document type's strength replaces the slider's, and every sharpening level but Off gives the type's own, as before #424 |
| Filter strength | Each filter's own | 20, 40, 60, 80 or 100 for every filter |
| Flatten page (#302) | Off | On, with the switch on the scan screen itself |
| Report moiré, fingers, glare and stains in kept scans (#305, #306) | Off | On: each kept page is also run through the two research detectors, and what they see goes to the log (`inspect`) and the page file (`inspect`). No page changes |
| Scan with (#294) | PdfCraft camera | Phone's camera app (also in Settings → Scan; the same setting) |
| Page outline in the camera (#295) | On | Off: no frames are analysed, the camera is as in #294 |
| Auto-capture (#296) | On | Off: only the shutter takes photos. Needs the page outline; also switched in the camera's top bar |
| Low-light merge (#297) | Auto | Always: a burst of 4 merged in any light. Off: one photo, as in #296. Also in Settings ▸ Scan |

Settings → **Scanner models** (#289) lists each model with its state; while the log is
recorded it also has **Copy the models again**, which copies every model out of the app anew
(see `docs/ANDROID_SCAN_MODELS.md`).

**Time a document model from a file (testing only)** (#304) times a model file picked on the phone
and writes the times to the log; it changes no page and keeps no file (see
`docs/ANDROID_SCAN_MAX_QUALITY.md`).

**Reset scanner testing options** puts them back. Keeping scans and the detectors' report are
not among them: they change no page.

## What a kept scan is

One folder per scan, named for when it was saved or discarded (`20261112-081500`). Per page:

- `page-01.jpg`: the camera's file, byte for byte.
- `page-01.json`: the page's facts (below).
- `page-01.out.jpg` or `.png`: the page as the app made it. Saved scans only.

The zip also holds `diagnostics.txt`, the diagnostics log as **Share diagnostics log** writes it.

```json
{
 "corners": [0.11, 0.08, 0.93, 0.10, 0.95, 0.91, 0.07, 0.89],
 "kind": "other", "paper": "unknown", "curved": false, "notes": "", "checked": false,
 "app": "0.0.26-beta (26001)",
 "outcome": "saved",
 "found": [0.11, 0.08, 0.93, 0.10, 0.95, 0.90, 0.07, 0.89],
 "finding": {"source": "model", "confidence": 0.835, "angle": 0, "passes": 1, "refined_sides": 4, "curved_sides": 0, "curved_opposite": false},
 "look": "auto", "strength": 60, "flatten": false, "flatten_chosen": null,
 "options": {"finder": "auto", "refine": true, "side_proportions": false, "snap_paper": true, "strength_override": null, "upright": true,
             "sharpen": 30, "denoise": true, "preview": true, "crop_tools": true, "flatten_auto": false, "flatten_trim": true, "flatten_line_floor": true, "settle_turns": true, "presets": true, "strict_gutter": true, "strict_photo": true, "strict_book": true, "book_gutter": true, "line_flatten": true,
             "look_per_page": true, "look_strip": true, "whiteboard_look": true, "preview_stop": true,
             "preview_zoom": true, "look_reuse": true},
 "document_type": {"preset": "receipt", "chosen": false, "strength": 80, "sharpen": 35, "keep_paper": false, "suggest_flatten": false,
                   "suggest_greyscale": true, "ratio": 3.0, "ink_share": 0.043, "colour_ink_share": 0.0, "photo_share": 0.0,
                   "stroke_mm": 0.339, "bright_share": 0.0, "gutter_band": false, "gutter_loose": false, "gutter_cover": 0.0,
                   "photo_loose": 0.0, "whole_photo": false, "book_loose": false, "curved_opposite": false, "book_mode": false},
 "turns": {"manual": null, "auto": true, "total": 3, "followed": false, "source": "classifier"},
 "capture": {"source": "in_app", "mp10": 499, "width": 8160, "height": 6120, "exposure_index": -1, "exposure_step_milli": 333,
             "torch": false, "focused": true, "rotation": 0, "capture_ms": 640, "live": true, "live_found": true,
             "live_stable": true, "live_hint": "ready", "preview_quad": [0.11, 0.08, 0.93, 0.10, 0.95, 0.90, 0.07, 0.89],
             "auto_on": true, "auto_shot": true, "sharpness": 1.235, "sharpness_blurred_unverified": false, "sharpness_ms": 85,
             "merge": null},
 "preview": {"shown": true, "reused": true, "ms": 31, "width": 905, "height": 1280, "look": "auto",
             "light_reused": true, "look_parts_ms": {"look_tone": 21}},
 "models": {"edges": "ready", "flatten": "ready", "upright": "ready"},
 "crop": {"visits": 1, "corner_drags": 2, "side_drags": 1, "snaps_asked": 3, "snaps_taken": 2, "snaps_held": 0, "snaps_failed": 0,
          "snap_ms_max": 85, "nudges": 0, "detects": 0, "detects_found": 0, "detects_other": 0,
          "detects_skipped": 0, "whole_photo": 0, "resets": 0,
          "loupe_decodes": 6, "snap": true, "ms": 9100, "moved_pct": 1.235,
          "corners_off": 0},
 "page_look": {"id": "clean", "strength": null, "level": 60, "per_page": true, "paper": "auto"},
 "strength_applied": {"strength": 80, "source": "kind", "fixed": false},
 "book": {"half": "right", "gutter_found": true, "gutter_top": 0.4712, "gutter_bottom": 0.4790, "confidence": 0.62, "order": "rtl"},
 "result": {"file": "page-01.out.jpg", "width": 1654, "height": 2339, "format": "jpeg", "dpi": 200.0,
            "paper": "a4", "aspect": "known_focal", "focal_mismatch": false, "flattened": false, "bend_pct": 0.0,
            "quarter_turns": 3,
            "orientation": {"quarter_turns": 3, "confidence": 0.81, "agreement": 1.0, "tiles": 4, "sure": true},
            "noise": 0.33, "flatten_check": null, "flatten_trim": null, "line_flatten": null}
}
```

The first five keys are the scan test set's format (#288, section 1), so a kept scan is a test
set entry as it stands. `corners` are where the person left the corners: 8 shares of the upright
photo, top-left, top-right, bottom-right, bottom-left. `checked` is always written `false`: the
corners become ground truth only once someone has looked at them on the photo and set it to `true`
(#425), and `scan-eval` leaves the page out of its measures until then. `found` is what the app found by itself,
or `null`. `outcome` is `saved` or `discarded`; a discarded page has no `look`, `strength` or
`result`. `preview` is the last preview the scan screen made of the page before it was saved
(#301): `shown` is false when none was (the option off, or Save tapped before it was ready),
`reused` whether its straightened page was kept from an earlier preview, `ms` the time from the
request to the result, `light_reused` whether Clean scan or Whiteboard found the page's light
estimate kept from an earlier look (#446) and `look_parts_ms` the parts of that look that ran
(`look_light`, `look_noise`, `look_denoise`, `look_sharpen`, `look_tone`; only the tone when the
estimate was kept), and `look` the filter it showed, which can differ from the one saved when
the filter was changed and saved before the new preview came. `page_look` is the page's own look
as the scan screen left it (#301): `id` (`original`, `clean`, `colour`, `greyscale`, `whiteboard`
or `bw`; Auto color and Whiteboard share the native `colour` look, so `look` alone can't tell
them apart), `strength` set with the slider (`null` for the look's own), the `level` it was made
at before any testing option, whether each page had its own (`per_page`), and the `paper`
chosen for Clean scan and Whiteboard (`auto`, `white` or `keep`). `strength_applied` is the
strength the saved page was really made at (#424): `source` is `lab` (the testing option),
`page` (the slider), `kind` (the document type's, while the slider was not moved) or `look`;
`fixed` says the document type did not replace it; `null` when no page was made. `crop` is what happened on the crop
screen (#292), added up over every time the page was cropped with Done, or `null` when it never
was: drags of corners and of sides, snaps asked for, taken, skipped by holding still and failed,
the longest snap, keyboard and TalkBack moves, Auto-detect (and from #443 `detects_other`, the
presses that tried the line detector after an empty search, and `detects_skipped`, those that did
not search because every search had found nothing), No crop and Reset, magnifier regions
decoded, whether Snap was on, the time spent, and `moved_pct`, the largest corner move from where
the screen opened in % of the photo's diagonal, and `corners_off`, how many corners were left off
the photo the last time (#291). `models` says which models the engine accepted when the page
was kept (#289): `ready`, `missing` or `corrupt` for each, or `null` when that could not be read. It is not kept if the app is closed by Android
before the scan is saved.
`capture` says how the photo was taken (#294, `docs/ANDROID_SCAN_CAMERA.md`): `source` is
`in_app`, `phone_app` or `import`; the still's size and exposure are known for `in_app` only. It is
`null` when the capture is not known, as for a scan Android restored after closing the app.
From #295 it also holds the live outline at the shutter (`ANDROID_SCAN_LIVE.md`): whether it was
on (`live`), showed a page, was steady, the advice shown (`live_hint`) and the outline itself
(`preview_quad`, 8 shares of the upright frame, or `null`).
From #296 (`ANDROID_SCAN_AUTO.md`): whether auto-capture was on (`auto_on`) and took this photo
(`auto_shot`), and the photo's `sharpness` score inside the outline (`null` until measured or when
it failed), `sharpness_blurred_unverified` and `sharpness_ms`. The blur flag is recorded only: it
does not detect motion blur and its threshold comes from drawn pages, so nothing acts on it.
From #297 (`ANDROID_SCAN_MERGE.md`): `merge` is `null` for one photo, or the low-light burst's
facts: `why`, `frames`, `found`, `merged`, `used`, `rejected_blur`, `rejected_align`,
`psnr_gain_tenths_db`, `burst_ms`, `find_ms`, `merge_ms` and `fallback`. A merged page's
`preview_quad` is `null`: the page is already straightened.

`inspect` is what the research detectors saw on the page when their report is on (#305, #306):
`moire` (`kind`, `period`, `tiles`, `busy_tiles`, `periodic_tiles`), `fingers`, `glare` and
`stains` (`count`, `over_print`, `area`; `null` when the search could not run), `flagged` and
`timings_ms`; `null` when the report is off. Every `inspect` line in the log has the same, flat.

`turns` says which way up the page was saved (#339): `manual` is the
person's own turn from Rotate or Undo (0–3 clockwise, `null` when they gave none), `auto` the
ordinary setting "Turn pages upright automatically", `total` the turn the page was saved with,
`followed` whether it took its neighbours' turn, and `source` one of `manual`, `neighbours`,
`classifier` or `off`. `result.quarter_turns` and `result.orientation` stay the classifier's own
answer, also for a page that then followed its neighbours. `document_type` is the kind Clean scan
took the page for (#299), whether the person chose it, the kind's settings and what the page
measured as (`stroke_mm` -1 when there is no ink); `null` for the other filters or with the option
off. `scan_replay` keeps a chosen kind and works an automatic one out again. `document_type_source` (#444) says where the kind came from: `chosen`, `measured`, `off`, or `no_outline` for a page whose outline was not found and which was not cropped; such a page is made a Document (its `document_type.chosen` is true, since the app chose it) while `document_type`'s measurements still say what the whole photo read as. `book` is set on a page that is half of a photographed book spread (#303): which `half`, whether a dark gutter was found (`gutter_found`; false means the cut is the centre line), where the cut crosses the straightened spread's top and bottom edges as shares of its width, the gutter's `confidence`, and the page `order` in force (`ltr` or `rtl`). It is `null` for a page of its own. The two halves of a spread are kept as two pages, each with its own copy of the one photo, and their `corners` and `found` are the half's own outline.

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
`finder` and `refine` in force and what book mode did (#303: `book`, and when it is on
`book_order`, `book_gutter_search`, `book_split`, `gutter_found`, `gutter_top`, `gutter_bottom`,
`gutter_confidence`, `book_ms`), every `page` line which `book_half` it is (`left`, `right` or
`none`), every `page` line the `strength` used, `side_proportions`,
`snap_paper`, `upright`, `sharpen` and `denoise`, and how its turn was decided (#339:
`auto_upright`, `turns_manual` (-1 for none), `turns`, `turn_source`, `settle_turns`, `followed`);
every press of Rotate or of Undo on the "Turned upright" chip is a `turn` line (`index`, `action`
`rotate` or `undo`, `auto_turns`, `turns`); every `preview` line (#301) says which page and
filter, whether the preview was `made`, whether the straightened page was `reused`, the total `ms`
and the native stages (`preview_base_ms`, `decode_ms`, `rectify_ms`, `preview_look_ms`, and the others
the page needed; for Clean scan and Whiteboard also the look's parts that ran, #446:
`preview_look_light_ms` (the light estimate), `preview_look_noise_ms`, `preview_look_denoise_ms`,
`preview_look_sharpen_ms` and `preview_look_tone_ms`, with `look_reuse` (the testing option) and
`light_reused` (the estimate was kept from an earlier look of the page)), its `width` and `height`, `quarter_turns` and `flattened`, and the page's own
`look`, the `strength` it was made at, where that came from (`strength_source`: `look`, `page` or
`lab`) and whether it waited for the slider to settle (`settled`); every `page` line its `look`,
`look_per_page`, `strength_source` and whether its look differs from the first page's
(`look_differs`); a `looks_strip` line for each run of the looks strip (`asked`, `made`,
`reused`, `ms`, `slowest_ms`), a `look_chosen` line when a look, a strength (once the slider is let
go) or Reset look is chosen, `look_applied_all` for Apply to all, and `save_cancelled` when Save is
stopped (`pages`, `made`, `ms`); `paper` and `whiteboard_look` on every `page` and `preview`
line, `preview_stopped` (`index`, `ms`) when an old preview was stopped for a newer one, and
`preview_detail` (`index`, `made`, `reused`, `ms`, `width`, `height`) for the sharper page made
when zoomed in; the `saved` line `looks_mixed` and `unenhanced`; every `crop` line
(#292) the page's `index`, whether it was left with Done, and the counts of its `crop` object;
every `crop_snap` line whether a `corner` or `side` was let go, how many `sides` were asked for and
how many were `refined` or `curved`, whether the snap was `taken`, `stale` (another drag had
started) or `failed`, and its `ms`; every `crop_detect` line whether Auto-detect `found` a page,
its `source` and `ms`, and from #443 the `finder` used (`none` when the press did not search),
`other`, `skipped`, the line shown as `note` (`none`, `none_other`, `already`, `found_other`,
`failed`, or `none_shown` when no line was shown) and the testing option `detect_note`; every `page` and `preview` line `flatten_auto` and, from
#302's check, `flatten_checked`, `flatten_kept`, `flatten_why`, `straight_px`, `straight_flat_px`
(tenths of a pixel, -1 when not measured), `straight_lines` and `straight_flat_lines`, the
person's own Flatten page choice `flatten_page` (`on`, `off`, `none`) and the border trim
(`flatten_trim`, `trim_looked`, `trimmed`, `trim_left_pct`, `trim_top_pct`, `trim_right_pct`,
`trim_bottom_pct`), and from #303's text-line fallback `line_flatten`, `line_ran`, `line_gave_up`,
`line_kept`, `line_why`, `line_lines`, `line_points`, `line_steps`, `line_alpha`, `line_beta`,
`line_rms_before`, `line_rms_after`, `line_px` and `line_flat_px` (see
`docs/ANDROID_SCAN_FLATTEN_LINES.md`); a `flatten_page` line for each tap of the Flatten page switch; every `model_files` line (#289) what the models check copied and why
(`copied`, `kept`, `failed`, `new`, `changed`, `size`, `unrecorded`, `forced`, `ms`), and while
the log is recorded a `model_states` line with each model's state; a `model_download` or
`model_removed` line for an optional model (none yet); a `model_timing` line (#304) when a picked model file is timed (fields in
`docs/ANDROID_SCAN_MAX_QUALITY.md`); `test_options` is written when an option is changed, `tests_shared` (pages, bytes)
when the zip is handed out, and `tests_cleared` when the kept scans are deleted. The log itself
still holds no file name, path or document content.

## Not verified

None of this has run on a phone: the Kotlin is checked by CI's build and unit tests, the Rust
options and the replay tool by tests and by hand on a desktop.
