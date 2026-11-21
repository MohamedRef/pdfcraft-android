# Scanner: fingers, glare spots and stains (#306)

**State: the automatic look fills fingers with this (#466, `remove_fingers`); glare and stains
are a research prototype that a testing option reports in kept scans (below).**
The finger search has been set on the owner's photos (#466, 13 Nov): it finds the three fingers
on his ten reference photos and nothing else on them. On 21 Nov the fill was set again on a kept
scan from his phone, where it had left dark pieces of two thumbs (#784, "The whole finger"
below). Glare and stains are still as first written, on drawn pages only.

Code: `apps/pdfcraft-android/scan/src/defects.rs`, tests in `tests_defects.rs`.

## The rule everything follows

**Text is never made up.** A glare spot is filled with paper only when the ring around it (2 to
6 % of the page's width away) is blank paper: at most 2 % of it ink. Over print it is left as it
is and flagged (`Region::covers_ink`, `Defects::flagged`), so the app can say "retake". A stain
is not filled at all: its colour and darkness are divided out, as a coloured shadow would be, so
print under it keeps its contrast.

A finger is flagged when print runs up to it: more than 1 % of the band 0.8 % of the page's
width around it is ink (`TOUCH_BAND`, `TOUCH_INK`). Such a finger hides part of that print.
There are two fills, and the caller chooses:

- `fill_blank` leaves a flagged finger as it is.
- `fill_fingers` puts paper over every finger. Nothing is made up: what the finger hid stays
  hidden, and paper shows where the finger was. `Defects::fingers_over_print` says how many
  hid print, so the app can tell the person that only a new photo brings it back.

Either fill takes a finger whole or not at all: a finger that goes on past what was found
(`Region::ragged` over `FINGER_RAGGED`) is left as it is, and the count of fingers filled is then
smaller than the count found.

(Until #466 the ring decided for fingers too. On the owner's photos every finger had print
within the ring, since people hold a page by its margin, so nothing would ever have been
filled.)

## How it works

`defects::find(page, before)` reads the straightened page with its lighting evened out (paper
near white: the Auto look's output) and, for glare, the same page before that correction, where
glare is clipped light. It works on a copy at most 800 pixels long; its masks are at that size.

- **Paper and ink.** The paper's colour is the median, per channel, of the brighter half of the
  page. Ink is darker than 60 % of the paper's luminance (a plain share, since the page is
  evenly lit; the issue names #300's threshold, which needs the whole black-and-white pass).
- **Fingers.** Pixels warmer than the paper (Cr 6–75 above it, Cb 2–62 below it, in BT.601
  YCbCr, and Cr at least 0.45 of Cb's size: redder than yellow) and between 12 % and 97 % of
  its luminance. Gaps up to 1.2 % of the short side are closed and pockets under 1 % of the
  page that the skin shuts in are filled (a nail, a ring), then parts thinner than 1.6 % of the
  short side are dropped (the shadow line a hand leaves along the edge). A region is a finger
  when it touches the page's border, covers 0.15–15 % of the page, meets the border over
  2.5–40 % of the short side, reaches into the page at least 0.25 times that width, fills at
  least 40 % of the box around it, and is skin for at least half (the rest is the nail).
- **The whole finger.** Skin is the finger's lit part. Around it, what is darker than 93 % of the
  paper (`FINGER_PAPER`) and wider than 1.6 % of the short side (twice `FINGER_SOLID_PRINT`:
  print is thinner) joins the finger when it is within 0.6 of the finger's width at the border
  from the skin (`FINGER_SHADOW`): its sides in shadow. Then whatever is darker than that,
  however thin, within 0.8 % of the short side (`FINGER_RIM`): the dark line it casts along its
  edge. Both go from pixel to neighbouring pixel and never across paper, so print that does not
  touch the finger is not reached, and print that does (a form's frame running under it) loses
  that 0.8 % beside it at most. The region's area is the whole finger's; whether print runs up
  to it is asked of the skin and of the whole finger, and either says yes.
- **Whether a fill would be clean.** `Region::ragged` is the share of the band 1.2 % of the short
  side wide just around the whole finger (`RAGGED_BAND`) that is neither paper nor print (dark
  and wider than print). Over 10 % (`FINGER_RAGGED`) the finger goes on past what was found (a
  sleeve, the rest of the hand, a wide shadow) and is not filled: paper over part of it would
  leave the rest cut off beside a white patch.
- **Glare.** In the uncorrected page: regions where at least two channels are 250 or more,
  covering at least 0.2 % of the page, whose surroundings 3 to 8 % of the page's width away
  (print left out) are at least 10 % darker than the region's core (the halo of a highlight,
  unlike white paper next to print). Then the ring decides, as for fingers.
- **Stains.** 8-pixel blocks of the working copy, print and found fingers and glare left out,
  whose paper is off the page's paper by a chroma of more than 0.05 or darker by more than 8 %,
  and smooth (the paper in the block varies by less than 8 grey levels, as a robust spread);
  connected blocks covering 0.5–25 % of the page.
- **Fixes.** `fill_blank` sets a blank-paper finger or glare region, grown by one working pixel,
  to the paper's colour with a 6-pixel feathered edge and the paper's own grain added back;
  `fill_fingers` does the same for every finger.
  `normalise_stains` divides each channel inside a stain (grown by half a block, feathered by
  12 pixels) by the local paper (the lightest value within 1.5 % of the page, box-blurred) and
  multiplies by the page's paper.

Thresholds added here, not in the issue: the stain's smoothness (8 grey levels), the ink share
(0.6), the glare surroundings' distance (3–8 %), and every finger rule above (the issue's
starting values were: 1–12 % of the page, 4–14 % of the short side at the border, and a reach
of 0.7 of that width added here at first).

## What was measured, and on what

### A kept scan of twelve pages from the owner's phone (21 Nov, on a desktop, #784)

Two of the twelve photos show a hand holding the sheet, the thumb over the bottom margin and
over the printed frame of a form. Each page was made again from its photo with the corners and
options its page file records, and the finger step run on the look's output.

| | Thumb 1 (lit; a near-black line along one side) | Thumb 2 (a third of its width lit, both sides in shadow) |
|---|---|---|
| Region found, working pixels: skin alone, then the whole finger | 8 575, 9 665 | 4 107, 6 657 |
| Pixels of the thumb's extent darker than 128 after the fill: before this change, after | 1 548, 0 | 21 414, 0 |
| The same, darker than 230 (not white) | 4 545, 0 | 29 987, 0 |
| Dark pixels within 30 pixels around the thumb (the frame's lines): before the fill, after | 505, 315 | 995, 630 |
| `ragged` with the skin alone, with the whole finger | 0.16, 0.00 | 0.68, 0.00 |

The thumb's extent has 86 985 and 106 244 pixels at the pages' sizes (1596 × 2257, 1786 × 2526).
Before the change the first page showed a white patch with a dark streak along one side, the
second a white patch with the thumb's dark flank beside it. After it both show paper, and the
form's frame stops at the patch and goes on at its other side: the 190 and 365 dark pixels lost
around the thumb are the frame's lines within about 12 pixels of it. Nothing beyond 30 pixels
of the thumb changes on either page. The other ten pages report no finger before and after,
one of them with a hand in the photo outside the page. Both thumbs hid print (the frame), and
both pages say so (`fingers_over_print` 1).

Not measured: any other photo with a finger; a finger beside a photograph or a solid block of
ink (drawn only: the fill is then refused or, when the block is smaller than the reach and
touches the finger, the block goes with it); the step's time, on a phone or steadily on the
desktop (the added work, an opening of the working copy and two bands, is done only for a
region already taken for a finger, so a page without one does none of it).

### The owner's ten reference photos (fingers only, 13 Nov, on a desktop)

The photos are private; only these figures leave them. Each page was straightened by the
reference's corners and given the Auto look at strength 60, as the report below does.

Three fingers are on the ten photos, on two of them:

| Finger | Share of the page | Width at the border, of the short side | Reach, of that width | Fills its box | Skin | Print runs up to it |
|---|---|---|---|---|---|---|
| A fingertip at the bottom edge of an open booklet | 0.40 % | 17 % | 0.27 | 0.67 | 0.96 | no |
| A finger with a dark nail at its top edge, in the hand's shadow | 1.19 % | 24 % | 0.50 | 0.56 | 0.72 | yes |
| A thumb on a parcel label | 2.40 % | 32 % | 0.56 | 0.66 | 0.99 | yes |

- **With the issue's starting rules none of the three was found.** The fingertip is under 1 %
  of the page and far from elongated; the thumb is 32 % of the label's short side where the rule
  allowed 14 %; the finger in shadow failed the skin colours (too dark, and too strongly
  coloured after the Auto look) and its nail split it.
- **With the rules above all three are found**, and nothing on the other eight photos. The
  nearest things that are not fingers are slivers of table or cloth along an edge: their reach
  is 0.03 to 0.22 of their width, against 0.27 for the shallowest finger. That gap is narrow,
  and a sliver that did pass would be paper-filled table, not lost print.
- `fill_fingers` removes all three; a faint grey trace of the shadow stays where the finger in
  shadow was. Looked at by eye, on these two pages only.
- The reference scanner's own results for these two photos still show all three fingers.

Three fingers are very few. The rules fit them and the drawn pages below; they have not met a
photo with no finger where something skin-coloured touches the edge (a peach logo, a wooden
ruler), which they would take for a finger when its shape fits.

### Drawn pages

Pages drawn in code (1200 × 1600, bars of "print" every 30 pixels), on a desktop.

- A skin-coloured rounded finger, 110 pixels wide, 260 long, on the blank bottom margin: found,
  not flagged, filled; where it was is within 6 grey levels of the paper, and print is
  untouched. The same finger from the left edge over the print: found, flagged, not filled (the
  page is unchanged byte for byte).
- A fingertip 204 pixels wide and 58 deep at the bottom edge (the booklet's, drawn): found and
  filled. A thumb 390 wide and 220 deep with a dark red nail, lit and in shadow: one region
  with the nail inside it. A 14-pixel and a 40-pixel strip of skin colour along the right edge,
  a band across the whole top and a round stamp in the margin: no finger. A finger over print:
  flagged; `fill_blank` leaves it, `fill_fingers` fills it and changes no print elsewhere.
- A thumb 120 pixels of skin wide and 330 long from the bottom edge, with a dark grey side 56
  pixels wide and a 6-pixel near-black line along the other side, over a form's frame (two lines
  4 pixels thick): found whole, filled; none of its 182 × 330 extent is left off the paper's
  colour (17 160 pixels were with the skin alone), the frame is untouched from 40 pixels beside
  it, the print above is as it was. A finger beside a dark grey block 645 × 220 along the edge
  (a sleeve): found, not filled, the page unchanged byte for byte.
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

- Detection precision and recall on a real set (the issue asks for 40 photos with fingers and
  100 without), and the share of fills that look clean: three fingers on ten photos is all there
  is so far.
- A line the finger hid is not drawn on across the patch (a form's frame stops at it): that
  would be making print up.
- Glare and stains on real photos: not looked at. On the ten photos the stain search called the
  thumb a stain until the finger search found it; nothing else was checked.
- Glare by merging two frames (#297) and the capture-time hints (#295): need the in-app camera.
- The finger's "rounded end" shape cue, and using the detector's page mask (#290) or a notch in
  the refined side (#291): not written.
- Stains are not excluded where #299's photograph mask (`photo_mask`, #382) marks a photograph:
  tried, and the mask marks the drawn brown stain over print as a photograph, so no stain was
  found. Only smoothness keeps photographs out for now.
- A Diagnostics toggle that shows the masks over the page, and a recommendation per defect.
