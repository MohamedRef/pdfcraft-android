# Scan: the crop screen (#292)

When the page's edges were found wrong, or not at all, the crop screen is where they are put
right. On the ten reference photos the detector was wrong or found nothing on four, so for a
large share of pages this screen is what the scanner is like to use. The corners left here are also
what kept scans record as the truth (`docs/ANDROID_SCAN_TESTING.md`).

## What it does

- **Corners and sides.** A corner is dragged as before. The round handle in the middle of a side
  moves the whole side parallel to itself, and its two corners slide along the neighbouring
  sides, so those keep their direction. Corners are hit before sides (48 dp around a corner, 40 dp
  around a side's middle). The shape always stays convex and on the photo: a drag that would fold
  it or leave the photo stops at the last good place.
- **Magnifier.** While a handle is held, a 120 dp circle shows the photo around it at three times
  its on-screen scale, with a crosshair and the sides that meet there. It is placed above the
  finger, or below or beside it near the screen's edges, never over the handle. It shows the
  thumbnail at once and the full photo's own pixels as soon as they are decoded: a region of at
  least 512 photo pixels around the handle (`BitmapRegionDecoder`, turned as the photo's EXIF
  orientation says), decoded again only after the handle has moved a quarter of it. One region is
  decoded at a time, off the main thread, and the latest request wins.
- **Snap.** When a handle is let go, the sides it moved (two for a corner, one for a side) are
  handed to `scan_refine_quad` (#291), which looks for the paper's edge within 1.2 % of the
  photo's diagonal at full size. If it finds a side, the handle slides there (120 ms) with a light
  tick; if not, it stays where it was put. Holding a handle still for 600 ms before letting go
  skips the snap for that drag. The **Snap** switch turns it off, and is remembered. A snap that
  comes back after another drag has started is dropped. Sides placed by hand and not snapped are
  drawn dashed.
- **Buttons.** Auto-detect looks for the page again with the detector the testing options name.
  No crop takes the whole photo, and Reset goes back to the corners the screen opened with.
- **TalkBack and keyboard.** Each of the eight handles is a node named for it ("Top-left corner",
  "Left side"), with Move up, Move down, Move left and Move right actions. Each move is 0.25 % of
  the photo's longer side. With a keyboard, Tab and Shift+Tab go through the handles, the arrows
  move the chosen one, and Shift+arrows move it by 1 %.

The code is in `ui/scan/`: `ScanQuadGeometry.kt` holds the geometry, with no Android in it, and is
tested on the JVM. `ScanCropStats.kt` holds what is logged, and `ScanCropScreen.kt` the screen. The
old screen (`ScanCrop` in `ui/ToolScreens.kt`) is still there for the testing option.

## Testing option, log and kept scans

- **Crop with magnifier and snapping to edges** (on by default): off brings back the old screen.
- Log lines: `crop` when the screen is left, `crop_snap` per snap and `crop_detect` per
  Auto-detect (`docs/ANDROID_SCAN_TESTING.md`). They hold counts, times and booleans only.
- Kept scans: a `crop` object in each page file, added up over every visit.

## Not as the issue described it

- **Corners stay on the photo (0 to 1).** The issue allowed −0.15 to 1.15. But `Quad::from_shares`
  refuses corners beyond −0.01 to 1.01, so a corner off the photo would make the page fail to save.
- **Rotate is not here.** It needs a turn in `ScanPageRequest` and a `rotation` in `ScanPage` and
  `ScanSession` (version 2), and #339's remaining part is the same change. Both will be done
  together.
- **No paper-size chip.** The paper is known only once the page is made. The preview (#301, #380)
  shows the page's shape instead.
- **Crop facts are not saved with the scan session.** If Android closes the app before Save, a
  page keeps its corners but loses its `crop` facts.

## What was checked, and what was not

Checked by JVM tests (`ScanQuadGeometryTest`, `ScanCropStatsTest`, `ScanLabTest`):

- moving a side, including on a trapezoid, where the corners stay on the neighbouring sides'
  lines within 1e-5 and refused moves give null;
- a fast drag stopping at the edge, and corners being hit before sides;
- which sides a release snaps, the 600 ms hold, and when a snap's answer is taken;
- the magnifier's placement for handles at every screen corner, and the mapping from upright to
  stored photo for EXIF orientations 1, 3, 6 and 8;
- the decoded region's place once turned, and the nudge step;
- what is logged and kept.

The screen itself is checked only by CI's build.

**Not run on a phone**, so none of these is known yet:

- whether a corner can be placed within 2 photo pixels with the magnifier;
- whether a release near an edge snaps onto it on real photos, and how long a snap takes (the log
  will say);
- how the side handles feel on a strongly tilted page;
- whether TalkBack reads and moves every handle.
