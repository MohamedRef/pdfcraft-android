# Screen pictures for UI review

Reviews of this app are mostly done from the code: there is no emulator in the review environment
and the owner's phone is the only device. To let a reviewer see a screen, a JVM test draws the
main screens with Robolectric (native graphics) and writes PNG files (#462).

Nothing is compared against stored pictures: the files are for a person to look at. A picture is
what Robolectric draws, not what a phone shows; fonts, the status bar and the camera are not a
phone's. **Never a real photo** in these tests: pages and frames are drawn in code.

## What is drawn

`app/src/test/java/io/github/artcraftandroid/pdfcraft/ui/home/SettingsScreenshotsTest.kt`:
every Settings page (`SettingsPage`), light and dark, in English and Arabic (right to left) at
360×640 dp and 384×832 dp, French (long words) at 360×640 dp, and each English page whole
(384 dp wide, 2400 dp tall) for the rows below the fold.

Files: `app/build/screenshots/settings/<page>_<locale>_<size>_<theme>.png`.

`app/src/test/java/io/github/artcraftandroid/pdfcraft/ui/scan/ScanScreenshotsTest.kt`: the
scanner's screens on main. The in-app camera's bars in each mode (One page, Many pages, Book) laid
out as `ScanCameraScreen` lays them around a drawn frame (CameraX does not run in a JVM test), the
camera permission page, and the crop screen on a drawn page (light and dark). Then the screens after
the camera with "Scan screens in steps" on, drawn by the real `ScanScreen` on a scan of three drawn
pages: Adjust borders (#458) on page 2, Enhance (#459) after its Next, and the document screen
(#460), light and dark. The engine does not run in a JVM test, so the testing option "Show pages as
they will be saved" is off for these: the testing-option note shows at the top, Enhance shows the
drawn page instead of the finished one, and the look tiles are empty. English and Arabic at
360×640 dp and 384×832 dp, French and German (long words) at 360×640 dp.

Files: `app/build/screenshots/scan/camera_<mode>_<locale>_<size>.png`,
`camera_permission_<locale>_<size>.png`, `crop_<locale>_<size>_<theme>.png`, and
`borders_`, `enhance_` and `document_<locale>_<size>_<theme>.png`.

## In CI

The Android job runs the pictures on a pull request whose merge commit changes
`app/src/main/java/.../ui/`, `app/src/main/res/` or one of the two tests, and uploads them as the
`screen-pictures` artifact (the run's Summary page, Artifacts). They add about half a minute.

## Locally

The test is off in an ordinary `testDebugUnitTest` run. From `apps/pdfcraft-android/android`:

```sh
./gradlew testDebugUnitTest --tests '*ScreenshotsTest' -Ppdfcraft.screenshots=true -Ppdfcraft.abis=arm64-v8a
```

Robolectric downloads its Android runtime from Maven Central the first time. Where Maven Central's
download host is blocked, point it at a mirror with
`-Ppdfcraft.robolectricRepo=https://maven-central.storage-download.googleapis.com/maven2`
(the Gradle dependencies themselves still come from the repositories in `settings.gradle.kts`).

## Adding a screen

Draw it inside `PdfcraftTheme` with the app's own `AppViewModel` and capture with
`captureToImage()`, as the Settings test does. Code that loads the engine library (`engineVersion()`,
opening a document) does not run in a JVM test: keep such calls behind `runCatching` or out of the
screen being drawn.
