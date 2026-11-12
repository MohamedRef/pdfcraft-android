# Scanner: fingers, glare spots and stains (#306)

**State: research prototype. No page the app makes changes. A testing option reports what it
finds in kept scans (below); nothing else calls it.**
The issue asks for a prototype, measurements on the owner's photos and a recommendation; only
the prototype and its tests on drawn pages exist.

Code: `apps/pdfcraft-android/scan/src/defects.rs`, tests in `tests_defects.rs`.

## The rule everything follows

**Text is never made up.** A finger or a glare spot is filled with paper only when the ring
around it (2 to 6 % of the page's width away) is blank paper: at most 2 % of it ink. Over print
it is left as it is and flagged (`Region::covers_ink`, `Defects::flagged`), so the app can say
"retake". A stain is not filled at all: its colour and darkness are divided out, as a coloured
shadow would be, so print under it keeps its contrast.

## How it works

`defects::find(page, before)` reads the straightened page with its lighting evened out (paper
near white: the Auto look's output) and, for glare, the same page before that correction, where
glare is clipped light. It works on a copy at most 800 pixels long; its masks are at that size.

- **Paper and ink.** The paper's colour is the median, per channel, of the brighter half of the
  page. Ink is darker than 60 % of the paper's luminance (a plain share, since the page is
  evenly lit; the issue names #300's threshold, which needs the whole black-and-white pass).
- **Fingers.** Pixels warmer than the paper (Cr 8–60 above it, Cb 3–45 below it, in BT.601
  YCbCr) and between 25 % and 97 % of its luminance; connected regions of them that touch the
  page's border, cover 1–12 % of the page, meet the border over 4–14 % of the short side and
  reach into the page at least 0.7 times that width. Then the ring decides: blank → fill;
  print → flag.
- **Glare.** In the uncorrected page: regions where at least two channels are 250 or more,
  covering at least 0.2 % of the page, whose surroundings 3 to 8 % of the page's width away
  (print left out) are at least 10 % darker than the region's core (the halo of a highlight,
  unlike white paper next to print). Then the ring decides, as for fingers.
- **Stains.** 8-pixel blocks of the working copy, print and found fingers and glare left out,
  whose paper is off the page's paper by a chroma of more than 0.05 or darker by more than 8 %,
  and smooth (the paper in the block varies by less than 8 grey levels, as a robust spread);
  connected blocks covering 0.5–25 % of the page.
- **Fixes.** `fill_blank` sets a blank-paper finger or glare region, grown by one working pixel,
  to the paper's colour with a 6-pixel feathered edge and the paper's own grain added back.
  `normalise_stains` divides each channel inside a stain (grown by half a block, feathered by
  12 pixels) by the local paper (the lightest value within 1.5 % of the page, box-blurred) and
  multiplies by the page's paper.

Thresholds added here, not in the issue: the finger's reach (0.7 of its width), the stain's
smoothness (8 grey levels), the ink share (0.6), the glare surroundings' distance (3–8 %).

## What was measured, and on what

Pages drawn in code (1200 × 1600, bars of "print" every 30 pixels), on a desktop. **No photo
and no phone.**

- A skin-coloured rounded finger, 110 pixels wide, 260 long, on the blank bottom margin: found,
  not flagged, filled; where it was is within 6 grey levels of the paper, and print is
  untouched. The same finger from the left edge over the print: found, flagged, not filled (the
  page is unchanged byte for byte).
- A clipped spot 60 pixels across with a halo fading over 60 pixels: on blank paper found and
  filled (centre within 6 levels of the paper); over print found, flagged, not filled.
  Pure white paper with print and no halo: no glare.
- A semi-transparent brown stain (channels × 0.86, 0.76, 0.60), 340 pixels across, over print:
  found as one stain; after normalising, the paper inside is within 6 levels of the paper
  outside, and print contrast inside is above 85 % of outside (the issue's two figures).
- Nothing found on: the clean page, a blue stamp ring and a dark signature, a 600 × 350 noisy
  coloured photograph, yellow paper.
- The same page gives the same regions and pixels every time; a buffer that does not match its
  size is refused.

The issue's false-positive rate ("under 2 % on the synthetic set") needs a set of drawn pages
larger than these few; it was not measured.

## The report in kept scans

A testing option, **Report moiré, fingers, glare and stains in kept scans** (Settings → About →
Diagnostics, off by default), runs the moiré detector and this one on each page a kept scan keeps
(`scan/src/inspect.rs`, called through `scan_inspect_page`). It straightens the page as Save
would (no flattening, no turn) and changes nothing: what it finds goes to the log's `inspect`
line and to the page file's `inspect` key. It runs only while **Keep scans for testing** is on,
and adds the time of one more straightening and the detectors to each kept page.

Here the page `find` reads is the straightened page in the Auto look at strength 60, and
`before` is the same page before the look, for glare. Log fields, per kind (`fingers`, `glare`,
`stains`): the count, `_over_print` and `_area` (a share of the page), -1 when the search could
not run; `flagged` (fingers and glare over print); `inspect_look_ms`, `defects_ms`.

## Not done

- Detection precision and recall on the owner's photos (40 with fingers, 100 without), and the
  share of fills that look clean: needs his photos.
- Glare by merging two frames (#297) and the capture-time hints (#295): need the in-app camera.
- The finger's "rounded end" shape cue, and using the detector's page mask (#290) or a notch in
  the refined side (#291): not written.
- Stains are not excluded where #299's photograph mask (`photo_mask`, #382) marks a photograph:
  tried, and the mask marks the drawn brown stain over print as a photograph, so no stain was
  found. Only smoothness keeps photographs out for now.
- A Diagnostics toggle that shows the masks over the page, and a recommendation per defect.
