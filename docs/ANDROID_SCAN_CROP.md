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
- **When Auto-detect finds nothing (#443).** A line under the photo says so ("No page outline
  found. Drag the corners, or tap No crop to keep the whole photo.") and TalkBack reads it (a
  polite live region; the line is emptied first, so the same words are read again on the next
  press). The same search on the same photo gives the same answer, so a search that already found
  nothing is not made again: if the import found nothing (`ScanFinding.asked`, the options it
  searched with), or an earlier press did, the next press tries the line detector instead
  (only with the Auto detector, which is the network when its model file is there). When that
  finds the page, the line says so and asks to check the corners; when it finds nothing too, the
  line says that, and further presses do not search: they say at once that the photo was already
  searched. A failed native call is said too, and does not count as an empty search. The line
  goes when a handle is dragged, or No crop or Reset is pressed. The plan is
  `scanCropDetectPlan` in `ui/scan/ScanCropDetect.kt`, tested on the JVM. Before #443 every press
  ran the same search again and nothing was said when it found nothing: the owner's first phone
  log has seven presses in six seconds, each 370 to 410 ms, all `found=false`.
- **TalkBack and keyboard.** Each of the eight handles is a node named for it ("Top-left corner",
  "Left side"), with Move up, Move down, Move left and Move right actions. Each move is 0.25 % of
  the photo's longer side. With a keyboard, Tab and Shift+Tab go through the handles, the arrows
  move the chosen one, and Shift+arrows move it by 1 %.

The code is in `ui/scan/`: `ScanQuadGeometry.kt` holds the geometry, with no Android in it, and is
tested on the JVM. `ScanCropStats.kt` holds what is logged, and `ScanCropScreen.kt` the screen. The
old screen (`ScanCrop` in `ui/ToolScreens.kt`) is still there for the testing option.

## Testing option, log and kept scans

- **Crop with magnifier and snapping to edges** (on by default): off brings back the old screen.
- **Crop: say when Auto-detect finds nothing, and try the line detector next** (#443, on by
  default): off is the behaviour before #443, every press making the same search and nothing said.
- Log lines: `crop` when the screen is left, `crop_snap` per snap and `crop_detect` per
  Auto-detect (`docs/ANDROID_SCAN_TESTING.md`). They hold counts, times and booleans only, and
  from #443 `crop_detect` also has `finder` (the detector used, `none` when the press did not
  search), `other` (the line detector tried because the first search found nothing), `skipped`,
  `note` (`none`, `none_other`, `already`, `found_other`, `failed`, or `none_shown`) and
  `detect_note` (the testing option); `source` is `skipped` and `ms` 0 for a press that did not
  search. The `crop` line and object count `detects_other` and `detects_skipped`.
- Kept scans: a `crop` object in each page file, added up over every visit.

## Corners off the photo (#291)

A page the frame cut off has a corner outside the picture. A handle can go up to 15% of the
photo's side past each edge (−0.15 to 1.15, `QUAD_MIN` and `QUAD_MAX`), the same limit as
`quad::OUTSIDE` in the page function, which paints the part with no photo white. When a corner is
off the photo, the view widens to show it (`quadSpan`, `fitSpan`). The view does not change while
a finger is down, so the photo does not slide under it; it widens when the finger lifts. A snap
may also place a corner off the photo, where the edge finder found the sides meet.

## Not as the issue described it


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
