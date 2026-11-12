# Scanner: the in-app camera (#294)

Scan takes its photos with a camera screen of its own, built on CameraX 1.6.2, in place of the
phone's camera app. The phone's camera app stays one setting away (Settings → Scan → Scan with),
and one tap away on the camera screen itself, so a fault in the new screen cannot leave anyone
unable to scan. **Nothing here has run on a phone**: it was compiled and its logic tested on the
JVM, nothing more.

## What it does

- **Viewfinder**: the whole 4:3 frame the still will have, letterboxed on black, so nothing
  outside the view ends up in the photo. Preview and still are bound in one `UseCaseGroup` with a
  shared 3:4 `ViewPort` (FIT), so a page outline found on a preview frame (#295) is in the same
  place on the still.
- **Still**: back main camera, `CAPTURE_MODE_MAXIMIZE_QUALITY`, JPEG quality 97, the highest
  4:3 resolution CameraX offers (`HIGHEST_AVAILABLE_STRATEGY` with
  `RATIO_4_3_FALLBACK_AUTO_STRATEGY`), flash off, zoom ratio 1.0 (no digital zoom and no
  ultra-wide, whose distortion bends page edges).
- **Exposure**: a third of a stop under (`scanExposureIndex`), so white paper is not clipped and
  faint pencil and stamps survive. Rounded to the camera's step and kept in its range; nothing
  when the camera has no compensation.
- **Focus**: continuous by default; a tap focuses and meters there (held 4 s) with a ring. The
  shutter captures at once when the last focus succeeded less than 2 s ago, otherwise it focuses
  at the middle first and captures when that ends or after 800 ms (`ScanCameraState.reduce`).
- **Light**: the torch, not the flash, so the glare spot can be seen and avoided before the shot.
- **Upright**: the screen stays portrait; an orientation listener sets the still's
  `targetRotation`, so a photo taken with the phone turned is stored upright (EXIF), as the
  scanner's later stages expect.
- **Pages**: each photo goes into `cacheDir/camera/scan-<nanoTime>.jpg`, the file the scan asked
  for, and through `AppViewModel.onScanPhoto` exactly like a photo from the phone's camera app:
  detection, the testing options and kept scans are unchanged. The camera then stays open for the
  next page (not for Retake, which takes one); **Done (N)** goes to the review screen.
- **Shutter sound** only when the phone is not silent or on vibrate. The screen stays on.

## Permission and fallbacks

Declaring `CAMERA` changes the phone's camera app too: Android refuses `IMAGE_CAPTURE` to an app
that declares `CAMERA` without having it granted. So `ScanCameraRouter` asks for the permission
before either camera is used. The decision is the pure `scanCaptureRoute`:

| Camera on the phone | Permission | Refused just now | Scan with | Goes to |
|---|---|---|---|---|
| no | any | any | any | camera screen with Import photos only |
| yes | granted | | PdfCraft camera | the camera screen |
| yes | granted | | Phone's camera app | the phone's camera app |
| yes | not granted | no | any | an explanation, then the system's permission dialog |
| yes | not granted | yes | any | Import photos and Open settings (never the camera app, which would fail) |

- Imported photos are copied into `cacheDir/camera/` and go through `onScanPhoto` one by one.
  A Retake imports one photo.
- Back from the system settings with the permission granted, the screen becomes the camera.
- Add image (the editor's camera photo) always uses the phone's camera app, after the same
  permission request; refused, it says so and nothing is added.
- The camera failing to start (no provider, binding refused) shows a message with the phone's
  camera app as the way on.

## Testing and what is recorded (handover, section 3)

1. **Testing option**: Scan with, among the scanner's testing options and in the ordinary
   settings (one setting: `scanCamera` in the app's preferences). Phone's camera app is the
   scanner exactly as before #294, except for the permission request.
2. **Diagnostics log** (`scan` area): `camera_open` (`route`, `retake`), `camera_ready`
   (`mp10`, `exposure_index`, `exposure_step_milli`, `torch_available`), `camera` per photo
   (`source` = `in_app` / `phone_app` / `import`, `mp10`, `width`, `height`, `exposure_index`,
   `exposure_step_milli`, `torch`, `focused`, `rotation`, `capture_ms` from shutter to file),
   `camera_capture_failed` (`code`), `camera_failed`, `camera_permission` (`granted`),
   `camera_choice`, `camera_phone_app`, `camera_import` (`picked`, `copied`).
3. **Kept scans**: each page file has `capture` with the same fields as the `camera` line, or
   `null` when the photo's capture is not known (a scan Android restored after closing the app).

## What was verified, and what was not

Verified: the code compiles; the JVM tests in `ScanCameraLogicTest` pass (route table, exposure
index for 1/3, 1/2, 1/6 and 1 stop steps and for ranges without the target, the reducer's
focus-then-capture, 800 ms wait, ignored second taps, failed capture, Done during a capture,
the device turn, the log and page-file fields).

Not verified (no phone was available): that the camera opens and binds on the owner's phone;
the still resolution actually chosen; the time from shutter to thumbnail (the issue's target is
1.5 s); focus and exposure behaviour; the torch; rotation and EXIF on a turned phone; the
permission flow including Open settings; process death while the camera is open; the shutter
sound rule. The diagnostics log records each of these so they can be read from one shared log.

Left out, as the issue allows: the focal length from the camera's calibration (section 7) for
#293's `focal_35mm`; a rotating icon set on a turned phone; filling storage to test the error
path. The page outline and hints (#295), auto-capture (#296) and the low-light merge (#297) build
on this screen; the line under the viewfinder is kept for #295's hint.

## Where the code is

`ui/scan/ScanCameraLogic.kt` (decisions, reducer), `ScanCameraScreen.kt` (CameraX and the
screen), `ScanCameraRouter.kt` (which camera, permission, import; registered by `MainActivity`),
`ScanCameraModel.kt` (state and the setting), `ScanCameraSetting.kt` (the setting's row),
`ScanCapture.kt` (what is recorded). Strings: `res/values*/strings_scan_camera.xml`.
