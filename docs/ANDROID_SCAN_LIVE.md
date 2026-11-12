# Scanner: the live page outline (#295)

While the in-app camera (#294, `ANDROID_SCAN_CAMERA.md`) shows, the page's outline follows it in
the viewfinder, turns green when the shot is steady, and one line under the viewfinder says what
to change. **Nothing here has run on a phone**: it was compiled, its logic tested on the JVM,
and the native call tested on drawn frames on a desktop (#398).

## How it works

1. **Frames.** An `ImageAnalysis` use case joins the preview and the still in the camera's
   `UseCaseGroup` (one `ViewPort`, so shares of the analysis frame are shares of what the
   viewfinder shows): about 640 × 480, RGBA, `STRATEGY_KEEP_ONLY_LATEST`, on a single-thread
   executor of its own. A slow detection drops frames; it never holds the preview back.
2. **Copy, close, detect.** `ScanLiveAnalyzer` copies the frame's rows packed
   (`scanCopyRgbaRows`, which drops row padding), closes the frame, and calls
   `scanDetectFrame(rgba, width, height, rotationDegrees)` (#398): the detector network's single
   look and a light edge refinement on the frame itself, answered in shares of the upright frame.
3. **Smoothing** (`ScanLiveTracker`, the issue's section 3): each corner moves 0.55 of the way to
   the new answer; a jump of more than 15 % of the diagonal, or an outline older than 500 ms, is
   taken at once. Without an answer the outline stays 350 ms, then fades for 250 ms. Corners keep
   their order when the detector names them from another corner.
4. **Steady**: the last 6 answers, over at least 400 ms, all with confidence ≥ 0.6 and no corner
   moving more than 1.2 % of the diagonal between two of them.
5. **Advice** (`scanLiveHintFor`, most important first): no page for 1.5 s → *Point the camera at
   the document*; mean brightness under 55 → *More light needed*; a corner within 1.5 % of the
   frame's edge → *Fit the whole page in the frame*; the page under 22 % of the frame → *Move
   closer*; opposite sides more than 1.7 × apart → *Hold the phone flatter over the page*; not
   steady → *Hold still*; steady → *Ready*. A new line replaces the old one only after 400 ms
   (`ScanLiveHintHolder`). The line is a polite live region for TalkBack.
6. **Overlay** (`ScanLiveOverlay`): the outline filled at 18 %, a 3 dp line and corner dots, blue
   while moving and green when steady, faint while fading.

The thresholds are the issue's starting values. They are meant to be set from the owner's
diagnostics log.

## Testing and what is recorded (handover, section 3)

1. **Testing option**: *Page outline in the camera* (on by default), among the scanner's testing
   options. Off, no analysis runs and the camera is exactly #294's.
2. **Diagnostics log** (`scan`): `live_frame` for one frame in ten (`frame`, `ms` for the whole
   call, `native_ms`, `width`, `height`, `rotation`, `found`, `confidence`, `luma`, `cut_off`);
   `live_summary` when the camera closes (`frames`, `found`, `avg_ms`, `max_ms`, `shown_ms`), from
   which the detection rate is `frames / shown_ms`; `camera_live_option` when the option changes;
   and on each photo's `camera` line `live`, `live_found`, `live_stable`, `live_hint`.
3. **Kept scans**: `capture` in the page file adds `live`, `live_found`, `live_stable`,
   `live_hint` and `preview_quad`, the outline at the shutter (8 shares of the upright frame).

## What was verified, and what was not

Verified: it compiles; `ScanLiveLogicTest` passes (row copy with and without padding and refusals,
letterbox mapping in portrait and landscape, smoothing and steadiness, the jump, the dropout and
fade, corner renaming, every hint and their order, the 400 ms hold, the recorded fields). The
native call's own tests and desktop timing are in #398 (`ANDROID_SCAN_DETECTOR.md`, "Live
frames"): a median of about 50 ms per 640 × 480 frame on a 4-core desktop.

Not verified (no phone): the detection rate and per-frame time on the owner's phone (the issue's
budget is 50 ms); lag and flicker; whether each hint can be triggered by hand; heat and battery
over three minutes; that the analysis frame's crop matches the preview's exactly (the
`ViewPort`'s crop rectangle is not applied: both streams are 4:3, so it should be the whole
frame). On drawn frames the single look often loses a page whose corner touches the frame's edge
(#398), so *Fit the whole page in the frame* may show less often than it should; *Point the
camera at the document* then shows instead.

Left out, for now: handing the outline at the shutter to the still's detection when that fails
(the issue's section 6; `preview_quad` is recorded so the gain can be judged first), and
interpolating the outline at display rate between answers.

## Where the code is

`ui/scan/ScanLiveLogic.kt` (row copy, mapping, tracker, hints), `ui/scan/ScanLiveOverlay.kt`
(analysis use case, analyzer, overlay), wired in `ui/scan/ScanCameraScreen.kt`. Strings:
`res/values*/strings_scan_live.xml`.
