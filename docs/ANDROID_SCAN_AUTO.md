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
   `camera_auto_phase` on each change (`phase` `searching`, `arming`, `capturing`, `waiting`, and
   `skipped` for a skip); `camera_auto_option` (`auto_capture`); `camera_sharpness` after each
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

## Where the code is

`ui/scan/ScanAutoCapture.kt` (rules, signature, stillness), wired in `ui/scan/ScanCameraScreen.kt`;
the signature is taken in `ScanLiveAnalyzer` (`ui/scan/ScanLiveOverlay.kt`); the sharpness call in
`ui/scan/ScanCameraRouter.kt`; the switch in `ScanCameraModel` and `ScanCameraSetting.kt`. Strings:
`res/values*/strings_scan_auto.xml`.
