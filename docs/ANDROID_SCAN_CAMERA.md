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

## Touches stay on the camera screen (#782, 21 Nov)

The camera screen is drawn over the app while a photo is wanted, and in Batch and Book mode the
scan's own screen is underneath it with the page just taken. Until this change a touch that
nothing on the camera screen took went through to that screen: the black around the buttons, the
line under the viewfinder, and the shutter while it is grey (a control that is switched off takes
no touch). Enhance's bar lies at the bottom there, and with five tools on it Rotate is the
middle one, under the shutter. So a press there that the camera screen did not take turned the
page just taken by a quarter, by hand as far as the app knew: the orientation check
is then not asked about that page again, and the turn is kept with the scan.

That is what a kept scan of twelve pages showed. Its log has `tool_open tool=rotate from=bar` and
`turn action=rotate` twice, 9 s and 7 s after a photo was taken in Batch mode, with `live_frame`
lines before and after and no `screen` line between: the camera never left the screen. One of
the two pages, an upright sheet, was saved a quarter turn off, 2339 × 1653 with `turns.manual` 1
and `orientation: null` in its page file; the other was turned back by hand the next day. Made
again on a desktop from its photo without that turn, the first comes out upright, 1951 × 2760.
The proportions and the orientation steps had done nothing wrong: among the twelve pages those
two are the only ones whose turn is not the orientation check's own.

`Modifier.scanCameraKeepsTouches()` on the screen's outer layer (`ScanCameraScreen.kt`) asks for
every pointer event and consumes none, so the buttons and the viewfinder's gestures are as they
were and nothing reaches the screens below. `ScanCameraTouchesTest` shows the screen over a
clickable layer and taps a corner and the bottom's middle. This is a plain fault put right, so it
has no testing option; nothing is added to the log, where `tool_open` with no `screen` line
after `camera_open` was the sign. Not verified: on a phone; with the camera itself on screen (a
JVM test cannot run it, so the test uses the screen as it is on a phone without a camera, which
has the same outer layer). A scan kept before this change keeps such a turn: Rotate on the page,
three times, puts it right.

## Review pages, Undo and Done (#780)

Until this change the last page's thumbnail, bottom right, was also Done: one target for "look at
what I have" and "I am finished", and nothing to take a photo back with. In Many pages and Book
the bottom bar now has five places, the same before and after the first photo:

| Import | Review pages | shutter | Undo | Done |
|---|---|---|---|---|

(mirrored in Arabic). The four beside the shutter are equal shares of the width, each button at
least 56 dp, and a place stays empty rather than close up, so the shutter is always in the middle.
One page shows Import and, once the scan has pages, Review pages; a retake shows Import only.

- **Review pages**: the last page with the page count on a badge, an empty page outline before the
  first. It closes the camera without ending anything and shows the newest page in Enhance
  (Retake, Borders, Rotate and the rest of its bar; a swipe or Back for the other pages). **Add page** (Enhance's
  More menu, or the pages screen) brings the camera back, and Undo still knows the photos taken
  before. With "Scan screens in steps" off it shows the scan screen as it was.
- **Undo**: the last photo this camera took since it opened comes out of the scan, by the path
  Delete on a page uses, so its file goes too; again, and the one before goes. A book photo's two
  pages go together, wherever they were moved. Never an imported photo or a page that was in the
  scan before the camera opened. Undo of the only page leaves an empty scan with the camera open;
  closing the camera then ends the scan (logged as `discard` with 0 pages).
- **Done**: the document screen, as the thumbnail did. A photo being taken is waited for; so is
  one asked for and still focusing (it was dropped before), and one taken but not yet a page. If
  that photo fails, the camera stays open with the error. The screen closes once.
- **Close** (✕, Android's Back) is as before: with pages it leads to the document screen, losing
  nothing; without, it ends the scan. A photo that arrives after the screen was left is deleted:
  before, it could be added and the camera opened again.
- **A button that cannot act** stays where it is, fainter and without its circle. Pressed, the
  line under the viewfinder says why for three seconds: no pages yet; a photo is being added;
  nothing here to undo. TalkBack reads the reason as the button's state. The rules are
  `scanCameraControls` in `ScanCameraControls.kt`.
- **After a photo** the line under the viewfinder says "Photo taken. Adding the page." at once,
  then "3 pages in this scan" when the page is in (1.5 s; also after Undo). The count on the badge
  and in that line is of pages in the scan, never of photos still on their way; a ring turns on
  the Review pages button while one is.

Taps, counted in the code and not on a phone: a page, 1 (the shutter); review the newest page, 1;
back to the camera from there, 2 (More, Add page); retake a page under review, 1 (Retake) plus the
shutter, after which the camera closes as a retake does; undo, 1; finish, 1.

Testing option "Review pages, Undo and Done as separate camera buttons" (`camera_buttons`, on;
it needs the camera's modes and icon bar). Off is the bar as it was, the old Done rules included.
Log: `camera_action`, `camera_page`, `camera_page_lost`, `camera_late`
([ANDROID_DIAGNOSTICS_LOG.md](ANDROID_DIAGNOSTICS_LOG.md)). Page file: `options.camera_buttons`.
An undone photo is deleted with its page, so it is not in a kept scan.

Verified: `ScanCameraControlsTest` (what the bar shows, when each button can act, Undo's choice
with imports, book photos, pages deleted elsewhere and photos still on their way, Done with a
photo focusing, failing, and with the option off); the pure code was compiled and those tests run
on a desktop with a stand-alone Kotlin compiler. Not verified: everything on a phone or tablet;
the Compose code of the bar (not compiled here; CI is its first compile); the look in Arabic and
with large text (the screenshot test draws `camera_buttons_*` when asked, and was not run here);
TalkBack; the time from the shutter to the count (`camera_page wait_ms` will say).

Not done here, and why: a one-tap way back to the camera from Enhance (it needs the Enhance
screen's file); Import during a scan still closes the camera for the imported pages (a cancelled
picker leaves the camera as it was); the page joins the scan only after its thumbnail is read, so
the count follows the photo by that long (`ui/ScanPhotoJobs.kt`); landscape and tablet proportions.

## Where the code is

`ui/scan/ScanCameraLogic.kt` (decisions, reducer), `ScanCameraScreen.kt` (CameraX and the
screen), `ScanCameraRouter.kt` (which camera, permission, import; registered by `MainActivity`),
`ScanCameraModel.kt` (state and the setting), `ScanCameraControls.kt` (Review pages, Undo and Done:
the rules, #780), `ScanCameraBars.kt` (the bars), `ScanCameraSetting.kt` (the setting's row),
`ScanCapture.kt` (what is recorded). Strings: `res/values*/strings_scan_camera.xml`.
