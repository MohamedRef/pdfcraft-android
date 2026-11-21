# Scan: the 0.0.46-beta baseline (#779)

What the scanner does today, written down before #778's changes, so that each of them can be
compared with it: the words the comparison uses, the two ways of testing, the test pages, the
runs to make on the phone and the tablet, the commands, and the figures that exist so far.

**Where it stands (21 November 2026).** No phone or tablet was at hand when this was written, so
the issue's fallback applies: the protocol is complete and can be followed, the desktop figures
are measured, and the device rows are pending.

| Part | State |
|---|---|
| Definitions, protocols, fixtures, manifest, scenarios, commands | Written here |
| Desktop, drawn photos (synthetic) | Measured at `c3a6e6a`: [section 8](#8-desktop-figures-synthetic) |
| Galaxy S24 Ultra | One row from one kept scan of 12 pages, re-opened, not a protocol run: [section 9](#9-real-device-figures). Every protocol run is pending |
| Galaxy Tab S11 | Nothing. Every run is pending |
| Matched reference (another scanner on the same photos) | Nothing: [section 10](#10-what-is-missing) |
| SmartDoc 2015 (public photos) | Not run: the dataset could not be fetched here ([section 7](#smartdoc-2015)) |

Three kinds of figures are kept apart everywhere, in separate tables and never added together:
**real device** (a phone or tablet ran the app), **synthetic** (photos drawn in code, on a
desktop) and **matched reference** (the same photo through this app and another scanner).

The owner's photos, the pages made from them and his raw logs stay out of the repository, the
issues and the pull requests. What is here from them is counts and times.

## 1. Words

**Capture attempt.** One press of the shutter, or one photo auto-capture takes. In the log: one
`camera` line. A burst merged in low light is one attempt.

**Acceptance event.** The moment an attempt becomes a page of the scan. What the person sees is
the page count on the camera's thumbnail going up by one; that is *visible acceptance*, and it is
the event to time. The log has no line for the count changing: `camera` (the file is written,
`capture_ms` from the shutter) comes before it and `photo` (the search for the page is done)
around it, so visible acceptance is timed from a screen recording. An attempt that failed, is
still pending, was discarded, retaken or cancelled is not an acceptance, and a run reports
attempts and acceptances as two counts.

**Readiness.** Something being ready for the person's next step. It is not acceptance and is
reported apart from it, by name:

| Readiness | The event | In the log |
|---|---|---|
| Next-ready | the shutter takes the next photo | no line; from a screen recording |
| Detection ready | the search for the page has an answer | `photo` (`find_ms` with `decode_ms`, `detect_ms`, `second_look_ms`, `refine_ms`) |
| Preview ready | the page as it will be saved is made at screen size | `page_ready` (`ms`, `waited_ms`); `scan_ready` when no page waits |
| Full page ready | the page is made at the size it is saved at | `full_page_ready` (`ms`) |
| Document usable | the document screen is open and shows made pages | `screen ... to=document`, `doc_shown` (`made`, `waiting`) |
| Saved or shared | the PDF is written | `saved` / `share` (`wait_ms` from the tap, `make_ms`) |

**Found.** The app proposed an outline of its own for the page: `found` is not null in the page
file, `edges_found=true` in the log.

**Whole-photo fallback.** When nothing is found the corners start as the whole photo. That is not
a found crop. A page saved as the whole photo counts as a miss, also when nobody touched it and
also when the photo happens to hold only the page.

**Miss.** The photo holds a page and the app found none. Reported in two parts: left as the whole
photo, and cropped by hand.

**Wrong accepted page.** The app found an outline, nobody corrected it, it was saved, and it is
not the page: another rectangle in view, part of the page, the page with a strip of table, or
print cut off. No log line can say this; a person looks at each saved page and judges
(adjudication), and the count is written down with who judged and when. With checked corners
(`"checked": true`, #425) the rule is: untouched and more than 0.5 % of the photo's diagonal from
the checked corners. *Clipped content* (any print cut by the crop) is counted on its own, since a
crop can be wrong by being too wide without losing anything.

**Untouched.** Found, and the corners were left exactly as found. **No touch**: found, and the
largest corner move is at most 0.5 % of the upright photo's diagonal (#288's `no_touch`; a snap
that moves a corner by a pixel is not a correction). **Manual corner**: any page a person had to
crop or correct: a found page moved by more than 0.5 %, or a miss cropped by hand.

**No-manual-corner share** (#286's goal: 85 %). Pages that were found, no touch, *and judged
right*, over **every accepted page of a saved scan whose photo holds a page**. Misses and wrong
accepted pages are in the denominator and not in the numerator. Left out of both, and counted
beside: pages of discarded scans, low-light merges and book halves (their photo is not the test,
#425), and frames with no page in them (there the right answer is "not found"; they get their own
row). It is always written as a count, "7 of 12", with the share after it. It comes from real
device runs only. The synthetic `no_touch` share of `scan-eval` and the CI gate is a different
quantity (found corners against drawn truth) and is never reported under this name.

**Corner move.** The largest distance between a found corner and where it was left, in % of the
upright photo's diagonal.

**Cold and warm.** Cold: the first scan after the app's process starts (`process_start` in the
log), when the models are loaded for the first time. Warm: any later scan in the same process.

**Rates and percentiles.** A rate is written with its count ("10 of 12"). A percentile is written
with its sample size; medians and percentiles interpolate linearly between sorted values (as
`eval.rs`). The 90th percentile is given from 10 values and the 95th from 20; under that only the
median and the largest value mean anything.

## 2. The build under test, and what a run records

| Field | For this baseline |
|---|---|
| Version as shown | PdfCraft 0.0.46-beta (46012) |
| `versionCode` in the APK's manifest | 46012 (base 4601, last digit 2 = `arm64-v8a`; `app/build.gradle.kts`). **To confirm on the installed APK** (Settings → About, and `aapt dump badging` on the file) |
| ABI | `arm64-v8a` |
| Source | `c3a6e6aac8566b203b9e4e8f62b0a6018299a28c` (release commit of 0.0.46-beta) |
| APK SHA-256 | **Pending**: `sha256sum` of the APK as downloaded, written into the run record |
| Engine | 0.21.1 (the log's header) |

Every run, on a device or a desktop, has a record with: the version and build as above; the
device model, Android and One UI version (the log's header has model and Android; One UI is in
Settings → About phone → Software information); the scanner testing options (the page files'
`options`, or the log's `test_options` line); the scenario and fixture IDs; lighting; source
route; the look; the PDF size choice; cold or warm; the device's orientation; date; who ran it.
The manifest in [section 5](#5-the-manifest) holds them.

Two option profiles are run:

- **D, defaults.** Settings → Diagnostics → Scanner testing → **Reset scanner testing options**.
  This is what a user gets, auto-capture and low-light merge included.
- **S, stable.** D, then **Auto-capture: Off** and **Low-light merge: Off**. Only the shutter takes
  a photo and every page is one photo, so two runs differ by the scanner and not by when a frame
  happened to be steady. Detection, borders, flattening and looks are as in D.

## 3. Two protocols

### A. Controlled image input

The same untouched photo files go into each thing compared: this app on the phone, this app on
the tablet, the desktop runner, and another scanner where it can import photos.

1. The files are fixtures with IDs ([section 4](#4-fixtures)), copied to the device unchanged
   (check one file's size after copying; messaging apps recompress).
2. In the app: New scan, then Import in the camera's bar (before the first photo), all files of
   the group in the manifest's order. The page files then say `capture.source: import`.
3. Leave the borders as found for the first save (profile D, look Clean scan, size Balanced);
   that scan is the measure of what the app does alone. Then, in a second scan of the same
   files, correct every border that is wrong and save: its corners, once looked at and marked
   `"checked": true`, are ground truth for `scan-eval`.
4. On the desktop the same files go through `scan-eval <folder> --second-look`.
5. For another scanner: the same files, its automatic crop, its closest look to Clean scan, its
   PDF or image export at the highest quality it has. Write down its name, version, platform and
   preset, and every non-equivalence: whether it could import at all, whether it recompressed
   the input, what its output resolution was. If it cannot import photos, protocol A is not
   possible with it and only protocol B's interaction notes apply.

Results from A are *image replay*: they say what the pipeline does with given pixels and nothing
about the camera, focus, exposure or the live outline.

### B. Live capture

The camera photographs printed fixture sheets.

1. Same sheets, same order, same surface, same light for every run that is compared. Write down
   the light (daylight by a window, ceiling light, lamp from one side; a lux reading if a meter
   app is at hand), the surface, and roughly how far the device was (the page filling about two
   thirds of the frame, unless the fixture says otherwise).
2. Hand-held, as the owner scans. A run on a stand is a different run and is labelled so.
3. Start the screen recorder before the camera opens when visible events are timed. Visible
   events are read off the recording frame by frame; log events come from the log. The two are
   reported in separate columns and never mixed into one distribution.
4. With another scanner on the same device: the same sheets and light, straight after. Timings
   across platforms (the iOS recording in #778) are not compared: that recording is an
   interaction reference only. It has no source photos and no output files, so no quality
   figure can come from it either.

Live results are never pooled with image replay.

## 4. Fixtures

All generated or drawn; none is anyone's document. IDs are stable: a changed fixture gets a new
ID, never a reused one.

### Printable sheets and their drawn photos (exist today)

`python3 tools/scan-eval/size_pages.py <out>` writes, for each sheet, `<name>.png` (the sheet at
300 dpi, for printing at 100 %), `<name>.jpg` (a 12-megapixel photo of it drawn in code: tilted on
a desk, uneven light, a little blur and grain) and `<name>.json` (the true corners and the true
text). The files depend on the Pillow and font versions, so a run names each file by its SHA-256
in the manifest.

| ID | Sheet (`.png`, to print) | Drawn photo (`.jpg`, protocol A) | Covers |
|---|---|---|---|
| `P-A4-TEXT11` | `letter-en-11pt` | yes | clean A4 sheet, English print |
| `P-A4-SMALL8` | `small-print-en-8pt` | yes | small English print |
| `P-A4-FORM` | `form-en` | yes | table, blue stamp, signature (colour to keep) |
| `P-RECEIPT` | `receipt-en` | yes | long narrow paper, faint grey print |
| `P-A4-PHOTO` | `mixed-photo-en` | yes | colour photograph on the page |
| `P-A4-AR` | `letter-ar` | yes | Arabic print |

### Drawn scenes (exist today)

`scan-eval --synthetic N --seed S --size WxH` draws scene `i` with seed `S + i` and the kinds in
turn; the ID is the runner's own name, `synth-<seed>-<kind>`, with the size.

| ID | Kind | Covers |
|---|---|---|
| `synth-*-desk` | desk | perspective tilt up to 45° on a dark desk |
| `synth-*-light` | light | low-contrast tabletop (plain, wood, cloth, noisy) |
| `synth-*-receipt` | receipt | long narrow paper |
| `synth-*-book` | book | a bowed page (not a real spread: see the measuring page's gaps) |
| `synth-*-shadow` | shadow | a heavy soft-edged shadow |
| `synth-*-lowlight` | lowlight | dim light, blur (σ 0.8–2.5 px) and grain |

The two fixed sets: **`SYN200`** = seeds 0–199 at 800 × 1066, and **`GATE40`** = seeds 288–327 at
600 × 800 (the CI gate's photos).

### Groups still to be made (live capture, protocol B)

Printed sheets and set-ups for what neither set above covers. Each is a group ID, the sheets it
uses and how it is set up; none has been photographed.

| ID | Set-up | Sheets |
|---|---|---|
| `L-CLEAN` | one sheet flat on a dark table, even light | `P-A4-TEXT11`; the same printed on Letter paper as `P-LETTER-TEXT11` |
| `L-NOTES` | coloured sticky notes on the sheet, lines marked with pastel highlighters | `P-A4-TEXT11` |
| `L-SMALL` | small print, English and Arabic | `P-A4-SMALL8`, `P-A4-AR` |
| `L-FAINT` | a line written in pencil on the sheet; the faint receipt | `P-A4-FORM`, `P-RECEIPT` |
| `L-SHADOW` | a lamp from one side, the hand's and the device's shadow across the page | `P-A4-TEXT11` |
| `L-TILT` | from about 45° at each of the four sides | `P-A4-FORM` |
| `L-PILE` | the sheet on top of a pile of other sheets, skewed against them | `P-A4-PHOTO` on top |
| `L-LOWCON` | the sheet on a white or cream table | `P-A4-TEXT11` |
| `L-BOOK` | a bound book open at a spread with a curved gutter (a public-domain book, or these sheets bound) | — |
| `L-EMPTY` | the table alone, no page; and a frame with only a non-paper rectangle (a tablet, a box) | — |
| `L-DEFOCUS` | focus locked on the far edge of the table, then the page photographed | `P-A4-SMALL8` |
| `L-MOTION` | the device swept sideways, and separately towards the page, while the shutter is pressed | `P-A4-SMALL8` |

`L-DEFOCUS` and `L-MOTION` are reported as their own groups and never pooled with the rest: they
are about the photo, not the page finder. `L-EMPTY` has no page, so it is out of the
no-manual-corner denominator; its figure is how often something was found (a false page).

**Held out.** When a threshold is tuned on this data, the pages with an even page number and the
drawn scenes with an odd seed are not looked at while tuning, and are reported after it. Nothing
has been tuned on any of it yet, so nothing is spent.

## 5. The manifest

One JSON file per run, kept beside the run's export (outside the repository when the run holds
anyone's photos). `scan-eval` and `kept_scan_summary.py` do not read it; it is the record that
makes two runs comparable, and the aggregates are quoted with its `run_id`.

```json
{
  "schema": 1,
  "run_id": "2026-11-28-s24u-R10-D-1",
  "kind": "real_device",
  "protocol": "live_capture",
  "scenario": "R10",
  "app": {"version": "0.0.46-beta", "version_code": 46012, "abi": "arm64-v8a",
          "commit": "c3a6e6aac8566b203b9e4e8f62b0a6018299a28c", "apk_sha256": "…"},
  "device": {"model": "SM-S928B", "name": "Galaxy S24 Ultra", "android": "17 (API 37)", "one_ui": "…",
             "orientation": "portrait"},
  "options": {"profile": "D", "changed": {}},
  "conditions": {"lighting": "ceiling light, evening", "lux": null, "surface": "dark wood table",
                 "hand_held": true, "cold": true},
  "source_route": "in_app",
  "look": "clean", "pdf_size": "balanced",
  "reference": null,
  "fixtures": [
    {"position": 1, "id": "P-A4-TEXT11", "group": "L-CLEAN", "sha256": null, "held_out": false, "notes": ""}
  ],
  "attempts": 10, "accepted": 10,
  "adjudication": {"by": "…", "date": "…", "wrong_accepted": 0, "clipped_content": 0, "notes": ""},
  "exports": {"kept_scans": "local path", "diagnostics": "local path", "screen_recording": "local path", "pdf": "local path"},
  "notes": ""
}
```

| Field | Values |
|---|---|
| `kind` | `real_device`, `synthetic`, `matched_reference` |
| `protocol` | `controlled_image_input`, `live_capture` |
| `scenario` | `S4`, `S4H`, `R10`, `R20`, `A-PRINT6`, `SYN200`, `GATE40`, or a group ID |
| `options.profile` | `D` or `S`; `changed` lists every testing option that differs from the profile, by its key in the page file's `options` |
| `source_route` | `in_app`, `phone_app`, `import`, `kept_reopened` (a kept scan opened again: its times are for making pages again, not for capture) |
| `reference` | `null`, or `{"name", "version", "platform", "preset", "non_equivalences": []}` |
| `fixtures[].sha256` | of the input file for protocol A; `null` for a printed sheet |
| `fixtures[].position` | the page's place in the scan, which is also the number written on the sheet |
| `adjudication` | who looked at every saved page, when, and the two counts of [section 1](#1-words) |

## 6. The runs to make on the phone and the tablet

For the owner, on the Galaxy S24 Ultra (portrait) and the Galaxy Tab S11 (portrait, then
landscape). Each run is one scan. None has been made.

**Sheets.** Print the six sheets of [section 4](#4-fixtures) at 100 %. For the 10- and 20-page
runs print them again until there are 20 sheets, in the table's order and round again. Write the
page number, 1 to 20, large in the top right corner of each with a marker: the number is how the
PDF's order is checked.

**Before every run**

1. Settings → About: note the version and build. It must read 0.0.46-beta (46012) for this
   baseline. If a later build is installed, the run is still worth making, but it is recorded
   as that build and compared with this baseline, not added to it.
2. Settings → Diagnostics: **Record diagnostics log** on, then **Clear diagnostics log**.
3. Scanner testing: **Reset scanner testing options** (profile D). For profile S also set
   Auto-capture to Off and Low-light merge to Off.
4. **Keep scans for testing** on, then **Delete kept scans**, so the export holds this run only.
5. For a cold run, close the app from Recents and open it again.
6. Start Android's screen recorder (the recording shows only the test sheets).

**After every run**

1. Stop the recorder.
2. Scanner testing → **Share kept scans**: one zip with the photos, the page files and the log.
3. Save the PDF somewhere you can open it, open it, and check: the page count, the numbers in
   order, each page the whole sheet and nothing but the sheet, the smallest print readable at
   100 %.
4. Fill in the manifest: attempts, accepted pages, and for each saved page whether its crop is
   right (the adjudication counts).
5. Keep the zip, the recording and the PDF together under the `run_id`. Send the zip the way the
   earlier ones were sent; nothing from it goes into an issue.

### S4: four pages, as in the reference recording

Sheets 1–4: `P-A4-SMALL8`, `P-A4-TEXT11`, `P-A4-PHOTO`, `P-A4-FORM` (small print, headings, a
colour photograph, a form). Profile D, then once more with profile S.

| Step | Do | Write down |
|---|---|---|
| 1 | From Home, get to the camera | every tap from Home to the live viewfinder, and what each opened |
| 2 | Choose **Many pages** | taps; whether it was already chosen |
| 3 | Photograph sheets 1–4 one after another, changing the sheet with the viewfinder open | per sheet: shutter or auto-capture; whether the count went up; whether anything covered the viewfinder between photos |
| 4 | Finish the batch | which control finished it, and the taps |
| 5 | On the screen that opens | which page is shown first, whether it is the corrected page or the photo, and where the page count is |
| 6 | Share | taps to the share sheet; how many pages are selected by default; which size was offered |
| 7 | Save PDF | taps; the wait |

What the code at `c3a6e6a` is expected to do, from reading it (#778's notes; **not observed on a
device**): Home opens a tools sheet before New scan; the camera's page thumbnail is the Done
control; Share opens the size choice for the whole scan. The run confirms or corrects each.

### S4H: the same with a hand and a competing rectangle

As S4, with a closed laptop, tablet or box lying in view beside the sheet, and each sheet changed
by hand while the camera watches. Also write down, per sheet: whether the live outline went to
the other rectangle or the hand, whether auto-capture fired on anything that was not the settled
sheet (a wrong capture is an attempt and, if it was kept, a wrong accepted page).

### R10: ten pages, the typical run

Sheets 1–10, profile D, cold. Many pages; photograph all ten; open the review of the batch at
page 2 and go back to the camera if the build offers that (write down what 0.0.46 does instead;
#780 is about this); finish; correct only the borders that are wrong; Save PDF at Balanced. Then the
same once more warm, and once with profile S.

### R20: twenty pages, the stress run

Sheets 1–20, profile D, warm, as fast as the shutter allows. Finish, do not correct anything,
Share as PDF, then Save. Write down anything that stalled, any page missing or out of order, and
whether the app was closed by Android (a `process_start` line in the middle of the log says so).

### A-PRINT6: the six drawn photos imported (protocol A)

Copy the six `.jpg` files of `size_pages.py` to the device, New scan → Import all six in the
table's order, profile D, save without touching a border. Same on the tablet. The desktop figure
for the same files is in [section 8](#8-desktop-figures-synthetic).

### What each run reports

Counts first: attempts, accepted, found, misses (whole photo / cropped by hand), wrong accepted,
clipped content, untouched, no touch, the no-manual-corner share as a count. Then times, each
with its sample size:

| Time | Source |
|---|---|
| shutter → visible acceptance | screen recording. `camera.capture_ms` (shutter to file) is its lower bound from the log |
| acceptance → next-ready | screen recording |
| capture → corrected preview | `photo.find_ms`, then `page_ready.ms` and `waited_ms`; the gap between a page's `camera` line and its `page_ready` line, from their time stamps |
| Done → document usable, first corrected page | `screen … to=document`, `doc_shown` (`made`, `waiting`), `scan_ready.ms`; the first corrected page on screen from the recording |
| save or share wait | `saved.wait_ms`, `share.wait_ms`, `make_ms` |
| per stage | `page.decode_ms`, `rectify_ms`, `look_ms`, `encode_ms`; `preview.*_ms`; `full_page_ready.ms` |
| bytes a page | `saved.bytes_per_page` with `size_choice` |
| memory | the log's header (`available_ram_mb`, `max_heap_mb`) and its `[memory]` and `[app]` lines; whether the process restarted |

## 7. Commands

All from the repository's root, at the commit under test.

```sh
# the drawn sets, on the path the gate has always measured, and on the app's default path
cargo run --release -p pdfcraft-scan --features synth --bin scan-eval -- --synthetic 200 --json syn200.json --md syn200.md
cargo run --release -p pdfcraft-scan --features synth --bin scan-eval -- --synthetic 200 --second-look --json syn200-second.json
cargo run --release -p pdfcraft-scan --features synth --bin scan-eval -- --synthetic 40 --seed 288 --size 600x800 --second-look

# the CI gate (GATE40 with limits) and the rest of the scanner's tests
cargo test --locked -p pdfcraft-scan -p pdfcraft-android

# the six printed sheets and their drawn photos, then the runner on the photos
python3 tools/scan-eval/size_pages.py ~/size-pages
cargo run --release -p pdfcraft-scan --features synth --bin scan-eval -- ~/size-pages --second-look --json print6.json

# a kept-scan export (unzipped outside the repository): aggregates only
python3 -I tools/scan-eval/kept_scan_summary.py ~/kept/run-1 --since last-install --json run-1.json
#   --since "MM-DD HH:MM" --until "MM-DD HH:MM"   one sitting of the log
#   --wrong-accepted N                            untouched pages a person judged wrong
python3 -I tools/scan-eval/kept_scan_summary.py ~/kept/run-1 ~/kept/run-2        # several exports as one

# the same export against this build's finder, page by page (prints page names: keep it local)
cargo run --release -p pdfcraft-scan --example scan_replay -- ~/kept/run-1
# and, once its corners are checked ("checked": true, #425), as a test set
cargo run --release -p pdfcraft-scan --features synth --bin scan-eval -- ~/kept/run-1 --second-look

# comparing a later build with this baseline: exits with 1 when a measure's mean got worse
cargo run --release -p pdfcraft-scan --features synth --bin scan-eval -- --synthetic 200 --second-look --baseline syn200-second.json

# the tools' own tests
python3 -m unittest discover -s tools/scan-eval -p 'test_*.py'
```

`kept_scan_summary.py` ([tools/scan-eval/README.md](../tools/scan-eval/README.md)) reads the page
files and the log's `[scan]` lines, and of a photo only the file header, for its size. It prints
counts, shares with their counts, and for every time field of every `[scan]` event the sample
size, median, 90th and 95th percentile and the largest value. It prints no file name and no
corner. Every key is optional, so an export from an older build gives fewer rows. A log usually
runs over several builds: `--since last-install` counts from the last `[update] install` line,
and the report says when an install lies inside the window.

### Which border decision each runner makes

The app's default path (`find_in` in `apps/pdfcraft-android/src/scan.rs`, called with ScanLab's
options) is: the network, then **the second look** (#290: the line detector is asked too when the
network finds nothing or an unsure page with at most two sides on edges), then the edges refined
at full size. The second look is on by default in the app (`ScanLab.secondLook = true`) and off
in the Rust default (`ScanFindOptions::default()`).

| Runner | Finder | Refine | Second look | The app's default path? |
|---|---|---|---|---|
| The app, default options | auto | on | **on** | yes |
| `scan-eval`, no flags (as before #779) | auto | on | off | **no** |
| `scan-eval --second-look` (added with this baseline) | auto | on | on | yes, by the same two rules (`second_look_wanted`, `second_look_takes`) written out again in `eval.rs`; it does not call `find_in` |
| CI gate (`tests_gate.rs`, `GATE40`) | auto | on | off | **no** |
| CI's runner step (`--synthetic 6`) | auto | on | off | **no** |
| `scan_replay` ("this build" column) | the page file's `finder` | the page file's `refine` | off, whatever the page file's `second_look` says | **no** |
| `scan_reference detect` | auto | on | off | **no** |

So until this baseline every offline figure for borders was taken without the second look. The
gap is closed for `scan-eval` by a flag, and a test holds the flag to the app's behaviour on the
cases the app's own test uses (`tests_eval.rs`). It stays open for the CI gate, `scan_replay` and
`scan_reference detect`: their figures are for the network and refinement alone. The harness
copies the rule instead of calling the app's function because the function lives in the UniFFI
crate, which the scan crate cannot depend on; moving it into the scan crate would be the proper
fix and is not done here, since it would touch the app's path.

None of the offline runners runs: the live outline, auto-capture, the low-light merge, the
document type's choices, turning upright (`scan-eval` switches it off), automatic flattening,
finger removal, or the size choice at save. `scan-eval` makes every page with Clean scan at
strength 50 (`eval::EVAL_STRENGTH`); in the app a document type or the slider can set another (the
pages of [section 9](#9-real-device-figures) were made at 60).

### SmartDoc 2015

SmartDoc 2015 Challenge 1 is published under CC BY 4.0, so using it for measurement is allowed
with attribution; it is not redistributed here and no file made from it goes into the
repository. It was **not fetched and not run**: from the machine this was written on both
sources were refused by the network policy (the GitHub release of
`jchazalon/smartdoc15-ch1-dataset` and Zenodo record 1230218), and this work was not to download
large datasets in any case. `scan_dataset_smartdoc.py` therefore remains tested on hand-written files only, as
[ANDROID_SCAN_MEASURING.md](ANDROID_SCAN_MEASURING.md) says. To run it where the dataset can be
downloaded:

```sh
python3 tools/scan-eval/scan_dataset_smartdoc.py ~/smartdoc ~/smartdoc-set --every 30
cargo run --release -p pdfcraft-scan --features synth --bin scan-eval -- ~/smartdoc-set --second-look --md smartdoc.md
```

Exclusions to state with any SmartDoc figure: frames the dataset marks rejected or with a corner
missing; every frame but each 30th; all its pages are A4 on five backgrounds, and they are frames
of video, not still photos from a phone's camera.

## 8. Desktop figures (synthetic)

**Synthetic: photos drawn in code, on a desktop. Nothing here ran on a phone, and none of it is
the no-manual-corner share of #286.** Drawn photos have straight, hard page edges; they show
whether the pipeline got worse, not how often it is right on real paper.

Source `c3a6e6a` (0.0.46-beta) with this baseline's `--second-look` flag; release build, x86_64
Linux, 4 cores, 21 November 2026. The detector's model is the one in the repository.

### SYN200: 200 drawn photos, 800 × 1066, seeds 0–199, the six kinds in turn

Both paths, because every earlier figure was taken on the first and the app runs the second:

| | Network and refinement (no second look) | The app's default path (`--second-look`) |
|---|---|---|
| Page found | 194 of 200 (97.0 %) | 199 of 200 (99.5 %) |
| Line detector asked / its page taken | – | 8 / 7 |
| `no_touch` (found corners within 0.5 % of the drawn ones) | 165 of 200 (82.5 %) | 171 of 200 (85.5 %) |
| `iou` median / mean | 0.9999 / 0.9792 | 0.9999 / 0.9937 |
| `corner_err_max` median / mean / p95 | 0.0001 / 0.0114 / 0.0278 | 0.0001 / 0.0039 / 0.0153 |
| `corner_err_mean` mean / p95 | 0.0066 / 0.0088 | 0.0020 / 0.0056 |
| `aspect_err` median / mean / p95 | 0.0001 / 0.0233 / 0.0568 | 0.0001 / 0.0057 / 0.0009 |
| `line_straightness` median, px (n) | 3.390 (197) | 3.371 (199) |
| `bowed_px` median (n) | 2.318 (197) | 2.182 (199) |

The eight photos where the line detector was asked: on five the network had found nothing and
the line detector's page was taken (four of them then within `no_touch`, one a page still off,
`iou` 0.74); on two the network's unsure page was replaced by a better one (`iou` 0.95 and 0.90
to 1.00); on one both found nothing. No photo where it was not asked changed. The geometry of the
first column is the same, digit for digit, as the 12 November run in
[ANDROID_SCAN_MEASURING.md](ANDROID_SCAN_MEASURING.md): the finder has not changed on these
photos since.

By kind, on the app's default path (in brackets: without the second look, where it differs):

| Kind | Photos | Found | `no_touch` | `iou` median | `corner_err_max` p95 |
|---|---|---|---|---|---|
| desk | 34 | 34 (33) | 34 of 34 (31) | 1.0000 | 0.0000 (0.0435) |
| light | 34 | 34 | 34 of 34 | 0.9999 | 0.0001 |
| receipt | 33 | 33 (30) | 31 of 33 (29) | 0.9999 | 0.0134 (0.3031) |
| book | 33 | 33 | 8 of 33 | 0.9883 | 0.0242 |
| shadow | 33 | 32 | 31 of 33 | 0.9999 | 0.0314 |
| lowlight | 33 | 33 (32) | 33 of 33 (32) | 0.9988 | 0.0012 (0.0015) |

The clean-up measures are taken on the page straightened with the true corners, so they are the
same on both paths (Clean scan at strength 50):

| Measure | Median | Mean | p95 | n |
|---|---|---|---|---|
| `paper_white` | 0.9123 | 0.8896 | 0.9903 | 200 |
| `illum_spread` | 0.0281 | 0.1053 | 0.5322 | 200 |
| `ink_contrast` | 255.0 | 253.5 | 255.0 | 200 |
| `hue_shift` (degrees) | 6.516 | 6.818 | 10.804 | 200 |
| `psnr` (dB) | 18.553 | 16.887 | 20.874 | 200 |
| `ssim` | 0.7654 | 0.7305 | 0.8393 | 200 |
| `sharpness`, found page, default path | 20.253 | 22.558 | 43.753 | 200 |

These differ from the 12 November table (`paper_white` mean 0.854 then, `ssim` mean 0.703):
Clean scan has changed since; this is its state at 0.0.46-beta.

**Repeatability.** The first column was run twice on the same machine: every measure of every
one of the 200 photos came out the same to the last digit (the two JSON reports' `metrics` and
`summary` are equal). Only the times differ between runs. Across machines the same should hold
for the same Rust version and CPU family; that was not checked.

### GATE40: the CI gate's 40 photos, 600 × 800, seeds 288–327

Through the runner, on both paths: **identical**. The line detector is asked on none of the 40,
so the gate does not exercise the second look at all, with the flag or without.

| Measure | Median | Mean | p95 | n |
|---|---|---|---|---|
| Page found | 40 of 40 | | | |
| `no_touch` | | 36 of 40 (90.0 %) | | 40 |
| `iou` | 0.9998 | 0.9965 | 1.0000 | 40 |
| `corner_err_max` | 0.0001 | 0.0018 | 0.0104 | 40 |
| `aspect_err` | 0.0001 | 0.0012 | 0.0004 | 40 |
| `line_straightness` (px) | 2.665 | 4.187 | 14.308 | 40 |
| `paper_white` | 0.8733 | 0.8559 | 0.9719 | 40 |
| `illum_spread` | 0.0482 | 0.1263 | 0.5533 | 40 |
| `ink_contrast` | 255.0 | 251.1 | 255.0 | 40 |
| `hue_shift` (degrees) | 5.956 | 6.167 | 12.637 | 40 |
| `psnr` (dB) | 17.446 | 15.981 | 20.004 | 40 |
| `ssim` | 0.7360 | 0.7015 | 0.8143 | 40 |

The gate itself (`tests_gate.rs`, the same photos with its limits) passes at this commit:
`cargo test --locked -p pdfcraft-scan -p pdfcraft-android` ran 444 and 139 tests, none failing.

### A-PRINT6: the six drawn photos of the printed sheets, 12 megapixels

`size_pages.py`'s six `.jpg` files through `scan-eval <folder>`, on both paths: **identical**, the
line detector asked on none.

| Measure | Value | n |
|---|---|---|
| Page found | 6 of 6 | 6 |
| `no_touch` | 6 of 6 | 6 |
| `iou` | 1.0000 at the median and the mean | 6 |
| `corner_err_max` | under 0.0001 of the diagonal on every photo | 6 |
| `line_straightness` (px) | median 0.68, largest 2.40 | 4 |
| `sharpness` | median 35.6 | 6 |

No proportions (the files name no paper) and no clean-up measures (no drawn truth per pixel).
These are drawn photos too, and as easy for the edges as the scenes above; their use is that
the same six files can be imported on the phone and the tablet (run A-PRINT6), which gives a
device row on identical input. As generated here with Pillow 12.3.0, the first 16 hex digits of
each file's SHA-256: `letter-en-11pt` 9d8641fc30e93457, `small-print-en-8pt` 4af4514dee9a55ae,
`form-en` a4e320b66acb4b61, `receipt-en` 409117ed21b50596, `mixed-photo-en` 507d239a9e4e4997,
`letter-ar` 4b14833f620e2221. A machine with other font or Pillow versions writes other bytes:
compare runs only on files with the same hashes.

### Desktop times

Not recorded as a baseline. The machine was compiling other work while these ran (load average
7 to 13 on 4 cores), and the stage times came out two to three times those of the 12 November
table in the measuring page, which remains the last undisturbed desktop run. Desktop times say
nothing about a phone in any case.

## 9. Real device figures

### 9.1 Galaxy S24 Ultra, 0.0.46-beta (46012): one kept scan, re-opened (n = 12)

This is the only real-device row, and it is not a protocol run. Read its limits before its
numbers:

- **One scan of 12 pages, one sitting, one person, one light.** Samsung SM-S928B, Android 17
  (API 37), engine 0.21.1, exported on 21 November 2026. The pages are the owner's own documents,
  not fixtures, so there are no fixture IDs and nobody else can repeat it.
- **Source route `kept_reopened`.** The scan was opened again from its PDF in the viewer
  (`from_recent` and `edited_from_viewer` are true and `capture` is null on all 12 pages), two
  borders were corrected, and it was saved over the same PDF. **Its times are for making pages
  again from kept photos, not for capture.** The sitting began with a new process (cold).
- **The page search was not made by 0.0.46.** The 12 photos were taken with the in-app camera on
  19 November: the log's 12 `photo` lines of that evening carry the same byte counts in the same
  order, and two `[update] install` lines follow them. The build of that evening is not named in
  the log, and its option list has no second look. So "found" and "not found" below are that
  build's answers. 0.0.46's finder ran on these pages only when Auto-detect was pressed on the
  crop screen (3 presses, a page found each time).
- **Options**: all 12 page files say 0.0.46-beta (46012), finder auto, refine on, second look on,
  automatic flattening on, upright on; Clean scan, document type "document", size Balanced.
- **Adjudication**: one of the pages saved as found and untouched is a wrong crop. That is
  page 8, judged on 21 November 2026 in the analysis of this export by the assistant that wrote
  this baseline, from the page file's corners (opposite sides 1.79 to 1 against 1.28 at most
  on the pages found right), not by the owner. Nobody has checked the other pages' corners
  against the photos (`checked` is false on all 12), so `scan-eval`'s geometry measures cannot be
  taken on this export.
- Made with `kept_scan_summary.py <export> --since "11-21 14:33" --wrong-accepted 1`.

Borders, 12 saved pages:

| What | Count |
|---|---|
| Found (the earlier build's search) | 10 of 12 (83 %) |
| Not found | 2 of 12 (17 %): both cropped by hand, none left as the whole photo |
| Found, then corrected by hand | 2 of 12; both moves over 30 % of the diagonal (largest 33.8 %), so the outline found was not the page |
| Untouched | 8 of 12 (67 %); 8 of the 10 found |
| No touch (move at most 0.5 %) | 8 of 12: no page had a small correction |
| Untouched but wrong (adjudicated) | 1 |
| Outlines found that were not the page, corrected or not | 3 of 10 found |
| Manual corner (correction or hand crop) | 4 of 12 (33 %) |
| **No manual corner and right (#286's goal: 85 %)** | **7 of 12 (58 %)** |

Other decisions on the 12 pages as saved:

| What | Counts |
|---|---|
| Quarter turns the page was saved with | none: 4, one: 2, two: 3, three: 3 |
| Who turned it | the classifier: 10, the person: 2 |
| Flattened | 5 of 12 |
| The flattening check's reason | kept: straighter 4, much better 1; not kept: few lines 3, not better 2, unmeasured 2 |
| Flattening decision taken from the kept one, not made again | 12 of 12; none skipped as already flat; the text-line fallback was tried on 7 |
| Paper | A4: 11, custom: 1 |
| Output | JPEG; 3.87 megapixels at the median (largest 6.04); 200 dpi at the median (largest 250) |
| Bytes a page | median 244 016, largest 497 348; the `saved` line: 278 509 a page, 12 pages, Balanced |

Times in this sitting (0.0.46-beta, 21 November 14:33–14:35), in milliseconds. With 12 to 18
values the median and the largest value are the figures to read; the 90th percentile is given
where there are 10 or more.

| Log field | What it times | n | Median | p90 | Largest |
|---|---|---|---|---|---|
| `page.decode_ms` | a saved page: decoding the photo | 12 | 196 | 207 | 247 |
| `page.rectify_ms` | straightening | 12 | 116 | 155 | 159 |
| `page.look_ms` | Clean scan | 12 | 704 | 814 | 875 |
| `page.encode_ms` | encoding | 12 | 69 | 164 | 239 |
| `full_page_ready.ms` | a page made at full size ahead of Save | 13 | 1494 | 1699 | 1802 |
| `page_ready.ms` | a page made at screen size | 18 | 2606 | 4043 | 4079 |
| `page_ready.waited_ms` | its wait before it was made | 18 | 3894 | 22 922 | 23 201 |
| `scan_ready.ms` | until no page waits | 2 | 14 705 | – | 25 361 |
| `crop_detect.ms` | Auto-detect on the crop screen | 3 | 1061 | – | 1309 |
| `crop.ms` | time spent on Adjust borders | 2 | 16 058 | – | 17 273 |
| `viewer_edit_scan.ms` | opening the kept scan from the viewer | 1 | 833 | – | 833 |
| `saved.wait_ms` | Save tapped to PDF written, all 12 pages ready beforehand | 1 | 289 | – | 289 |

Not in this row, because the scan was not captured in this sitting: every capture time
(shutter to acceptance, next-ready, the search's `find_ms`), the live outline, auto-capture,
0.0.46's own found and not-found counts, the second look's decisions (`finding` is null on all
12 pages), memory, and the 10- and 20-page run shapes.

### 9.2 For scale only: the same 12 photos being taken, earlier build

Not the baseline build, and not a protocol run. The evening of 19 November 2026 in the same log,
same phone, a build the log does not name (older than the two installs that follow). It is here
because it is the only live capture in hand, and so that nobody takes it for 0.0.46.

| What | Figure |
|---|---|
| Photos | 12, in-app camera, 11 in Many pages mode and 1 in One page |
| Taken by auto-capture | 0 of 12 |
| `camera.capture_ms`, shutter to file | n 12: median 1756 ms, p90 1885, largest 2128 |
| `photo.find_ms`, the search for the page | n 12: median 2294 ms, p90 3409, largest 4166 |
| Network passes in the search | 7 passes on 7 photos, 5 on 2, 4 on 1, 1 on 2 |
| Found | 10 of 12 |
| First Save of the 12 pages | 19.9 s from the tap, 5 of 12 full-size pages ready beforehand |

## 10. What is missing

Everything below is **pending**: no figure exists.

Device runs, each with its manifest, export, recording and adjudication:

| Device | Orientation | Scenario | Profile | State |
|---|---|---|---|---|
| Galaxy S24 Ultra | portrait | S4 | D | pending |
| Galaxy S24 Ultra | portrait | S4 | S | pending |
| Galaxy S24 Ultra | portrait | S4H | D | pending |
| Galaxy S24 Ultra | portrait | R10 cold | D | pending |
| Galaxy S24 Ultra | portrait | R10 warm | D | pending |
| Galaxy S24 Ultra | portrait | R10 | S | pending |
| Galaxy S24 Ultra | portrait | R20 | D | pending |
| Galaxy S24 Ultra | portrait | A-PRINT6 (import) | D | pending |
| Galaxy S24 Ultra | portrait | the `L-` groups of section 4 | D | pending; the groups are not set up yet |
| Galaxy Tab S11 | portrait | S4 | D | pending |
| Galaxy Tab S11 | portrait | S4H | D | pending |
| Galaxy Tab S11 | portrait | R10 cold | D | pending |
| Galaxy Tab S11 | portrait | R20 | D | pending |
| Galaxy Tab S11 | portrait | A-PRINT6 (import) | D | pending |
| Galaxy Tab S11 | landscape | S4 | D | pending |
| Galaxy Tab S11 | landscape | R10 cold | D | pending |
| Galaxy Tab S11 | landscape | R20 | D | pending |

Other gaps:

| What | State |
|---|---|
| The installed APK's manifest `versionCode`, SHA-256, and One UI version | pending: not readable from an export |
| Tablet model number and Android version | pending: no log from the tablet exists |
| 0.0.46's own found / not-found counts on real photos | pending: section 9.1's search was an earlier build's |
| Second look decisions on a device (`second_look`, `network_sides`, `lines_sides`) | pending: no 0.0.46 `photo` line exists |
| Shutter → visible acceptance, acceptance → next-ready | pending, and needs a screen recording: the log has no line for either |
| Capture → corrected preview, Done → document usable | pending. `kept_scan_summary.py` reports each event's times; it does not yet subtract one page's `camera` time stamp from its `page_ready` time stamp |
| 10-page typical and 20-page stress timings | pending on both devices |
| Memory and responsiveness on the 20-page path | pending. The summary does not read the `[memory]` and `[app]` areas |
| Checked corners on real photos (IoU, corner error, aspect error on a device set) | pending: no export has `"checked": true` pages |
| Clipped-content count | pending: needs a person per page; 9.1's adjudication gave one wrong crop and did not say whether print was cut |
| Text legibility on real pages; character error rate | pending. The scan crate's runner has no text recognition. A repeatable path exists for the six drawn sheets (`scan_size_tests.rs` with `size_pages.py`'s true text, see `ANDROID_SCAN_SIZE.md`); it was not run for this baseline |
| Colour retention on real pages | pending: `hue_shift` exists for drawn scenes only |
| PDF order, page identity and readability at exported size | pending: part of every run's "after" steps |
| Matched reference (another scanner, same photos or same sheets) | pending: no reference app, version or preset has been chosen, and `scan_reference` needs pairs that exist only on the machine that made them |
| SmartDoc 2015 | not run: [section 7](#smartdoc-2015) |
| The `L-` live groups (notes, pencil, shadow, pile, book, empty, defocus, motion) | not photographed |
| Arabic on a device; Letter paper; a real book spread | pending |
| The offline path for the CI gate, `scan_replay` and `scan_reference detect` | they do not make the second look: [section 7](#which-border-decision-each-runner-makes) |
| Held-out split | defined in section 4; nothing tuned, nothing spent |
