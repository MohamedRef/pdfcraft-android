# Scanner: auto-capture (#296)

With the live outline on (#295, `ANDROID_SCAN_LIVE.md`), the in-app camera (#294) takes the photo
by itself once the page is ready and the phone is still, then waits for the next page. Each photo's
sharpness is measured and recorded. **Nothing here has run on a phone**: it was compiled, and its
logic tested on the JVM.

## How it works

The rules are pure code in `ui/scan/ScanAutoCapture.kt` (`ScanAutoState.reduce`), fed every 100 ms
by the camera screen's advice loop (every 33 ms while the ring fills).

1. **Ready.** The live advice is *Ready* (a steady outline, well framed, enough light), a page is
   shown, and the phone is still. Still means the gyroscope's largest rotation rate over the last
   300 ms is under 0.08 rad/s (`ScanStillness`). A phone without a gyroscope counts as still, so
   the outline's steadiness decides alone.
2. **Arming.** After 500 ms of ready, a green ring fills around the shutter for 600 ms. Losing any
   condition sends it back to searching. The line under the viewfinder then says *Taking the
   photo. Tap here to skip this page*; tapping it skips this page.
3. **The shot** goes through the shutter's own path (focus, capture, the same file and the same
   `camera` log line), with a confirming vibration when the photo is written.
4. **Not twice.** After a photo (by itself or by the shutter) or a skip, it waits for a new page:
   the page gone for 400 ms, or a different page in view. Different means the page's signature
   differs by more than 12 grey levels on average. The signature is 16 × 16 luminance samples
   inside the outline on the analysis frame, with the mean taken off, so a little more or less
   light is not a new page (`scanPageSignature`, `scanSignatureDiffers`). The line says *Next page*
   meanwhile.
5. **The shutter by hand** always works: during the countdown it takes one photo and the countdown
   adds none. A failed photo goes back to searching.
6. **Switching.** *Auto-capture* in the camera's top bar, remembered, and the same switch among
   the scanner's testing options. Without the live outline there is no auto-capture. The first
   time it is on, a tip says how it works for 4 s.

The timings and thresholds are the issue's starting values (`ScanAutoRules`, `ScanStillness`).
They are meant to be set from the owner's diagnostics log.

## The blur check: measured, not acted on

After each in-app photo, `scan_sharpness` (#389, `ANDROID_SCAN_CAPTURE.md`) measures the page
inside the outline at the shutter (the whole photo without one), off the main thread. The score,
`blurred_unverified`, the threshold and the time go to the log and the kept scan's page file.

The issue's retake of a blurred photo is **not** done. The measure does not tell motion blur from
a sharp page, and its threshold (1.0) comes from drawn pages: a sharp drawn page in a JPEG scored
1.07. A prompt from it would ask to retake good photos. Real sharp and shaken photos from the
owner's log should set it first.

## Testing and what is recorded (handover, section 3)

1. **Testing option**: *Auto-capture* (on by default), among the scanner's testing options, and
   in the camera's top bar. Off, only the shutter takes photos.
2. **Diagnostics log** (`scan`): `camera_auto` when the camera opens with it on (`gyroscope`);
   `camera_auto_phase` on each change (`phase` `searching`, `arming`, `capturing`, `waiting`,
   `skipped` true for a skip, and from #781 `why` and `guards`); `camera_auto_option` (`auto_capture`); `camera_sharpness` after each
   in-app photo (`measured`, `score`, `blurred_unverified`, `threshold`, `ms`, `from_outline`,
   `auto_shot`); and on each `camera` line `auto_on` and `auto_shot`.
3. **Kept scans**: `capture` in the page file adds `auto_on`, `auto_shot`, `sharpness` (`null`
   until measured), `sharpness_blurred_unverified` and `sharpness_ms`.

## What was verified, and what was not

Verified: it compiles; `ScanAutoCaptureTest` passes (ready and countdown timing, no second shot of
the same page over 10 s, a swapped page shot, the page gone 300 ms against 400 ms, shaking during
the countdown, never shooting when not ready, not still or without a page, the shutter during the
countdown, a failed photo, a skip, the ring's progress, the signature under more light, two pages
apart, the signature on a turned frame, the turn mapping, bad input, stillness from gyroscope
readings, the recorded fields).

Not verified (no phone): whether 500 + 600 ms feels right or shoots too early; whether the
gyroscope threshold suits a hand-held phone; whether the 12-grey signature threshold tells two
pages of plain text apart (similar pages may look alike at 16 × 16, so the next page then shows
after the old one leaves the frame for 400 ms); the share of automatic photos that come out
blurred; the sharpness time on a 12 MP photo; the issue's acceptance items (20 pages hands-free,
no doubles, no blurred auto photos).

## The capture checks (#781)

A read of the capture code against the owner's log of 2026-11-20 (0.0.45-beta, Many pages; figures
only): the camera's run of ten photos there took every one by the shutter, a capture took 1.6 to
2.0 s from the shutter to its file, the still search for the page then took 0.5 to 4.3 s (median
2.7 s, all 7 looks on six of the ten), an outline was on the screen for 8 of the 10 photos and
steady for 2 (as recorded when the file was written, fault 6 below), the live search looked at
5.5 frames a second (180 ms a frame on average, 829 ms at most; 82 ms in the run in One page just
before it, with no pages being made behind the camera) and found the page on half of them. The
ring started once, for 0.17 s. So that log says nothing of what auto-capture does after it fires,
which is where the faults below are: they were found by reading, and each is shown by a JVM test.

Faults put right, none of them a threshold, all behind one testing option ("Capture checks",
`capture_guards`, on; off is each as before):

1. **The page shot was the one in view when the file was written**, about two seconds after the
   shutter. Whoever lays the next page down on the shutter's sound had that page recorded as shot,
   and auto-capture then waited for it to change: it was never taken by itself. The page is now
   remembered when the photo is asked for (`ScanAutoEvent.Shutter.signature`, or what the ring saw).
2. **A photo taken with no outline shown used an older page's signature.** The signature kept was
   that of the last frame that found a page, whenever that was. The page just shot then looked new
   as soon as it was found, and could be taken a second time (in the log, auto-capture went back to
   searching 0.4 s after both photos taken with no outline). A page not known at the shutter is now
   learned from the first page seen after it (`adopted`).
3. **An automatic photo that failed was fired again** about every second for as long as the fault
   lasted, each time with the shutter's sound. It now waits for a new page; the shutter by hand
   takes it. A photo by hand that failed is looked for again, as before.
4. **A hand over the page made the page new.** One look that differed sent auto-capture back to
   searching, and the page was taken again once the hand had gone. Searching again after a
   difference, the page last shot is remembered: when it is that page that becomes ready, there is
   no ring and no photo (`same_page`). A page gone for 400 ms is new, as before, so a sheet lifted
   and laid down again is still taken again; the shutter by hand always takes a deliberate second
   photo.
5. **A tap on the viewfinder while a photo waited for its focus let the photo go at once.** CameraX
   ends a focus that another replaces as "not focused"; that end was taken for the end of the
   focus the photo waited for, so the photo was taken while the lens moved to the tapped place, and
   the cancelled focus was remembered as the last one. Every focus now has a number
   (`ScanCameraState.focusSeq`), and a replaced focus's end is not acted on.
6. **The outline kept with a photo was the one shown when the file was written**, not at the shot:
   `live_found`, `live_stable`, `live_hint` and `preview_quad` in the log and the page file, and
   the region the sharpness is measured in. They are now those of the moment the capture starts.

Faults put right without an option, because the old behaviour is only wrong (`ScanCameraScreen.kt`,
not testable on the JVM, so **not verified**, by reading only):

7. **An automatic photo in Many pages or Book would have gone into the first photo's file.** The
   advice loop is started once and lives as long as the camera shows; it kept the shutter's path
   of the screen as it was when the camera opened, with the first photo's file in it. The first
   automatic photo after another photo would have been written over page 1's photo, and no page
   added. No automatic photo was ever taken in the owner's logs, so this never happened to him.
   The loop now reaches the screen as it is (`rememberUpdatedState`).
8. **Auto-capture switched off in the camera's top bar went on firing**, and switched on did
   nothing until the camera was opened again, for the same reason.

Not changed, though the log points at them, because only a phone can say by how much: every
timing and limit in `ScanAutoRules`, `ScanStillness`, `ScanLiveTracker`, `ScanLiveHintRules` and
`ScanCameraTiming` (a test pins them). In particular, from the first frame that finds a page to an
automatic photo the page must stay found, sure and steady for six detections (at least 0.4 s; 0.9 s
at the 180 ms a frame of that log), then 0.4 s until Ready is shown, 0.5 s of Ready and 0.6 s of
ring: about 2.4 s without one unsure frame or a pause of half a second, while in that log one
photo followed another after a median of 8 s, a capture and the change of page included.

### What was measured at a desk, and on what

The page signature (16 × 16 single pixels inside the outline, limit 12 grey levels) was tried on
the 12 photos of the owner's kept scan of 2026-11-21, shrunk to the analysis frame's 480 × 640
with the corners in their page files (10 found by the app, 2 set by hand). These are stills, not
live frames, and the corner movement is drawn at random, so this says what to look for, not what
the limit should be:

| | Signature in use (one pixel a sample) | Averaged (each sample the mean over 17 × 17 pixels, measured only) |
|---|---|---|
| The same page, corners moved up to 0.6 % of the frame (240 tries) | half under 4.1, most 11.6 | half under 0.7, most 2.0 |
| The same page, corners moved up to 1.2 % (240 tries) | half under 5.7, most 12.2, 2 over the limit | half under 1.1, most 2.9 |
| Two different pages (66 pairs) | from 8.5, half under 13.0; 19 pairs under the limit | from 4.9, half under 7.6 |
| A page and the next (11) | from 9.0; 3 under the limit | from 4.9 |

With one pixel a sample the two ranges overlap around the limit: a trembling outline can make a
page "new", and about a quarter of the page changes would only be noticed once the frame was
empty. Averaged, they do not overlap on these photos. The averaged signature is therefore
computed beside the one in use and **only logged** (`mean_diff_*`); it decides nothing until live
frames from a phone agree.

### What is recorded for the phone run

- `camera_auto_phase` on every change of phase now, also the ones that happen outside the advice
  loop (`capturing` by the shutter, `waiting` after a photo), with `why`: `armed`, `lost_page`,
  `lost_ready`, `lost_still`, `fired`, `shutter`, `taken`, `failed`, `skipped`, `absent`,
  `differs`, `same_page`; and `guards`.
- `camera_auto_rearm` each time a wait for a new page ends: `why` (`absent`, `differs`, `shutter`,
  `skipped`, `closed`), `wait_ms`, `looks`, and how far the page in view was from the page shot
  over the wait: `diff_last`, `diff_p50`, `diff_max` against `limit`, and `mean_looks`,
  `mean_diff_last`, `mean_diff_p50`, `mean_diff_max` for the averaged signature (-1: none).
- `camera_auto_summary` when the camera closes: `ready`, `armed`, `lost_page`, `lost_ready`,
  `lost_still`, `fired`, `refused`, `same_page`, `taken`, `failed`, `skipped`, `adopted`, `absent`,
  `differs`, `shutter`, and the shutter by hand by where auto-capture was: `shutter_searching`,
  `shutter_arming`, `shutter_waiting`.
- `live_hints` adds what the steady test needs: `gap_p50_ms`, `gap_p90_ms`, `gap_max_ms` between
  detections and `gaps_over` (over the 500 ms after which a run starts again), `runs_stale` and
  `runs_jumped` (runs ended by a pause or by the page jumping), `run_max` (the longest run of sure
  detections) against `steady_needs`, `steady_times`; and the gyroscope: `gyro_readings`,
  `gyro_over_pct`, `gyro_p50`, `gyro_p90`, `gyro_limit`.
- Each `camera` line, and `capture` in a kept scan's page file: `live_wanted` (the advice the
  scene called for, beside `live_hint`, the one on the screen 400 ms behind it), `focus_via`
  (`fresh`, `focused`, `unfocused`, `timeout`), `focus_wait_ms`, `shot_ms` (the capture alone),
  `since_shot_ms`, `auto_phase` (where auto-capture was when the photo was asked for),
  `signature_known`, `capture_guards`.
- `python3 -I tools/scan-eval/kept_scan_summary.py <folder>` counts and spreads all of these.

### The phone run (#781): what to do, and what each part decides

Settings → About → Diagnostics: record the log, keep scans for testing, every scanner testing
option as it comes. Many pages, auto-capture on, the phone held in the hand as usual, ten numbered
sheets of ordinary print. Share the diagnostics after each part (one zip is enough if the parts
are done in order; say the order). Nothing below needs to be judged by eye except where it says so.

| Part | Do this | It decides |
|---|---|---|
| A. Hands off | Lay sheet 1, hold still, and let the camera take all ten without touching the shutter; change sheets at an easy pace. Count the photos taken by themselves | Whether auto-capture fires at all now. `camera_auto_summary`: `ready` against `armed` against `fired`, and which `lost_*` is largest. `live_hints`: `run_max` against `steady_needs`, `gap_p50_ms`, `runs_stale`. If `run_max` rarely reaches 6, the count or the 1.2 % move limit is what to change; if `runs_stale` is large, the 500 ms pause is too short for this phone's frame time; if `lost_still` leads, the gyroscope's limit (`gyro_p50`, `gyro_p90` say where a steady hand sits) |
| B. Ten seconds on one page | After a photo, leave the sheet and the phone as they are for ten seconds. Three sheets | No second photo: `same_page` and `differs` in the summary should be 0 here. `camera_auto_rearm why=closed` or `shutter` after these waits gives `diff_max` (and `mean_diff_max`) for a page that did not change: the limit must be above it |
| C. Change on the sound | Take sheet 1 by the shutter and swap to sheet 2 as soon as the shutter sounds, before the page count rises. Five sheets | Check 1: each next sheet is taken by itself (`fired` rises, `auto_phase=arming` on its `camera` line). `diff_last` at `why=differs` is how far a new page is: the limit must be below it. Compare with `mean_diff_last` |
| D. Hand over the page | After a photo, pass a hand slowly over the sheet and take it away. Five times | Check 4: no second photo; `same_page` counts the times it was held back |
| E. Lift and lay down | After a photo, lift the same sheet out of view for a second and lay it down again. Three times | Today it is taken again (`why=absent`). Say whether that is wanted: this is a choice, not a measurement |
| F. Alike sheets | Three sheets of the same form or letterhead, one after the other, hands off | Whether pages that look alike are told apart: `why=differs` or only `absent`, and the two `diff` figures |
| G. Tap and shoot | Press the shutter and at once tap the viewfinder away from the middle. Five times, then look at the five pages in Enhance at full zoom | Check 5: `focus_via` on those `camera` lines (`focused` or `timeout`, not `unfocused`), and whether the pages are sharp, by eye |
| H. By hand, in a hurry | Ten sheets by the shutter as fast as is comfortable | `focus_wait_ms` and `shot_ms` split the 1.8 s: how much is waiting for focus (the 2 s after which a focus is no longer trusted, the 0.8 s wait) and how much the capture itself. `since_shot_ms` is the pace to beat; `auto_phase` says where auto-capture was each time (`waiting`: it had not noticed the new page; `searching`: not ready; `arming`: nearly) |
| I. The switch | With the camera open in Many pages after two photos, turn Auto-capture off in the top bar, hold a sheet steady for ten seconds, turn it on again, hold steady | Fault 8: nothing is taken while off; it fires while on |
| J. The first automatic photo after another | In Many pages take sheet 1 by the shutter, let sheet 2 be taken by itself, press Done | Fault 7: two pages, page 1 is sheet 1 and page 2 is sheet 2 |
| K. Dim, tilted, white on white | Parts A and B once more on a dim table, once with the phone tilted about 30°, once with a white sheet on a white table | The same figures per condition; which advice holds Ready back (`*_ms` in `live_hints`) |

To compare with before, repeat A to D with "Capture checks" off (Settings → About → Diagnostics →
scanner testing options). Not part of this run: the retake prompt for blurred photos (the
sharpness score still cannot tell motion blur, see above), the tablet, and the flash.

### Verified, and not

Verified: `ScanCaptureGuardsTest` (each of faults 1 to 6 with the checks off, as it was, and on;
a new page taken as promptly as before; a lifted sheet; the shutter's deliberate second photo; a
skip; the focus numbers with one and two taps and with the wait running out; how a capture
started and its times; the outline kept; every log line and field above; the averaged signature;
the limits unchanged). The pure code and that test, with `ScanAutoCaptureTest`, `ScanLiveLogicTest`,
`ScanLowLightTest` and the reducer's tests of `ScanCameraLogicTest`, were compiled and run on a
desktop with a stand-alone Kotlin compiler (85 tests); the testing option's own test and the tests
that need the app's other classes were not. Not verified: everything on a phone; the camera
screen's code (`ScanCameraScreen.kt`, `ScanLiveOverlay.kt`), which CI compiles first; faults 7 and
8, which no JVM test here reaches; that CameraX ends a replaced focus the way its documentation
says on the owner's phone; the cost of the averaged signature on the analysis thread (about 20 000
pixel reads a found frame, beside a detection of 80 to 180 ms).

## Where the code is

`ui/scan/ScanAutoCapture.kt` (rules, signature, stillness), `ui/scan/ScanCaptureLog.kt` (what is
logged of them, #781), wired in `ui/scan/ScanCameraScreen.kt`;
the signature is taken in `ScanLiveAnalyzer` (`ui/scan/ScanLiveOverlay.kt`); the sharpness call in
`ui/scan/ScanCameraRouter.kt`; the switch in `ScanCameraModel` and `ScanCameraSetting.kt`. Strings:
`res/values*/strings_scan_auto.xml`.
