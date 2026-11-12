# Scan: finding the page with a network (#289, #290)

After a photo is taken, the page in it is found by a small neural network instead of the engine's
line detector, and its corners are then moved onto the paper's edges at full size (#291). Code:
`apps/pdfcraft-android/scan/src/detect.rs` and `models.rs`, called through `scan_find_document`
from `AppViewModel.onScanPhoto`.

## The model

DocQuadNet-256 from [MakeACopy](https://github.com/egdels/makeacopy), Apache-2.0, Copyright 2026
Christian Kierdorf: a MobileNetV3 backbone with two heads, 6.7 MB as shipped. It takes the photo
letterboxed into 256 × 256 pixels and returns a 64 × 64 map per corner and one of the page.
`tools/scan-models/README.md` says how the shipped file was made from the published one, and
`assets/models/ATTRIBUTION.txt` carries its notice. The file is pinned by size and SHA-256
(`models.rs`); a file that is not the pinned one is never parsed. It runs on `rten`, the runtime
the text-recognition models already use, so no new native library is added.

The model ships in the APK (6.7 MB more) and is unpacked with the text models the first time a
photo is scanned. It is dropped from memory when Android asks for memory and loads again for the
next photo.

## How a photo is searched

1. The photo is shrunk to fit 256 pixels (each pixel the average of what it covers) and placed
   centred on black.
2. Each corner is the centre of the probability within two cells of that corner map's peak. The
   least sure of the four peaks, times how well the quad and the page map agree (intersection over
   union at 64 × 64), is the **confidence**. Corners that make no convex quad, or a quad under 5 %
   of the photo, give 0.
3. At 0.5 or more the answer is taken. Below that the photo is shown again turned by 90°, −90°,
   −30°, 30°, −60° and 60° (the network was trained on pages roughly upright; this is what its
   authors do, with their thresholds). A turned answer replaces the unturned one only if it is
   surer by 0.1; at 0.7 the search stops.
4. Under 0.45 nothing was found, and the page is the whole photo as before.
5. The corners found are refined on the full-size photo (#291).

Without the model file the engine's line detector is asked, as before. When the network ran and
was not convinced, the line detector is **not** asked: on the photos below it was right on none.

## Measured

**Drawn photos** (tests): a sheet with rows of print on a dark grainy table is found at confidence
0.93 in one look; the same sheet turned 35° is found after four looks (turned −30°); a photo with
no sheet gives nothing after seven looks. Through `scan_find_document`, a sheet's corners end up
within 1.5 pixels of the truth at 1200 × 1600.

**Ten private reference photos.** The owner's ten photos with another scanner's result for each
(they stay outside the repository). The figure is the largest distance of a corner from that
scanner's crop, in % of the photo's diagonal.

| Photo | Line detector (until now) | Network | Network, edges refined |
| --- | --- | --- | --- |
| A | 5.3 | 5.4 | 5.5 |
| B | 16.5 | 2.8 | 1.4 |
| C | 10.1 | 0.4 | 0.5 |
| D | nothing found | 4.3 (turned −60°) | 5.0 |
| E | nothing found | 8.6 (turned −90°) | 8.6 |
| F | 38.7 | 7.0 | 7.0 |
| G | nothing found | nothing found | |
| H | nothing found | nothing found | |
| I | 37.1 | 13.4 (turned 30°) | 13.4 |
| J | 20.7 | 15.1 | 14.5 |

Within 6 %: the line detector on one photo, the network on four. What the table cannot say: the
reference is another scanner's crop, not the paper's true edge. On five of these photos corners of
the page lie outside the photo, and on some the reference crops to the content. Looking at the
outlines over the photos, the network's quad is on the page on A to F; on I and J it is not (I is
a white page filling the frame, J comes out as the table inside the page); on G and H, where the
page fills or leaves the frame, it finds nothing, which leaves the whole photo.

Ten photos, most of them framed tighter than a page on a desk, are not a test set. The
evaluation set of #288 is what will say how often the outline is right.

**Time** on a desktop x86-64 (release build): 44 to 79 ms for one look, up to 340 ms for seven.
Not measured on a phone.

**16-bit weights.** The shipped file stores the weights as 16-bit floats. On the ten photos, with
ONNX Runtime running the 16-bit file in half precision against the 32-bit one: the corners move by
at most 0.042 pixels of the 256-pixel input (0.26 pixels of the photo) and the peak probabilities
change by at most 0.004. In `rten` only the stored weights are rounded, so the difference there is
no larger.

## Not done here

- The page map alone as a rescue when the corners fail (the issue's step 9).
- A corner outside the photo: the network cannot place one, and the crop screen cannot show one
  yet (#292).
- Detection on preview frames (#295), optional model downloads and a Models screen in Settings
  (#289): the one model there is ships in the APK.
- Comparing other published detectors (LDRNet, DocAligner).
