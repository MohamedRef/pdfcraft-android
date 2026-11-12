# Scan: edges refined on the full-size photo (#291)

The search for a document's edges looks at a small copy of the photo, so the corners it returns
are several pixels off at full size: a sliver of desk along one side, or a clipped margin. After
that search, each side is looked at again in the full-size photo, in a narrow band around where it
was found. Code: `apps/pdfcraft-android/scan/src/refine.rs`, called through `scan_refine_quad`
from `AppViewModel.onScanPhoto`.

## How

The photo's luminance is halved until its longer side is at most 1000 pixels (a 12 MP photo gives
three levels: 1000, 2000 and 4000 pixels). The sides are refined on each level in turn, coarsest
first, and the corners moved before the next. The band is the same number of pixels on every
level (1.5 % of the photo's diagonal on the coarsest), so in full-size pixels it halves each time.

For one side on one level:

1. **Scan lines** across the side, one every 4 pixels (32 to 400 of them), leaving out the first
   and last 8 % (rounded or dog-eared corners, and the neighbouring side's edge). A scan line that
   leaves the photo is dropped, so a side running out of the photo is fitted from its visible part.
2. **Profile**: luminance at half-pixel steps, smoothed with a Gaussian of 1 pixel, and its
   derivative.
3. **Light or dark page**: read from the two ends of the band (the outer quarter against the
   inner quarter, the median over the scan lines), not from the strongest step. A ruled line near
   the edge is a pair of steps as strong as the paper's own, one of each sign; between the ends
   of the band they cancel.
4. **Candidates**: on each scan line the local maxima of the derivative with that sign, at least a
   quarter as strong as the side's typical one, up to four, each placed between samples by a
   parabola.
5. **The line**: 200 lines through pairs of candidates (a fixed-seed generator, so the same photo
   always gives the same corners). A line's support is the summed strength of the scan lines with
   a candidate within 1 pixel of it, each counted once. Of the lines with at least 70 % of the
   best support, the **outermost** wins: the paper's edge lies outside its text and ruled lines.
   Then a weighted total-least-squares fit through that line's candidates, twice.
6. **Accepted** when at least 40 % of the scan lines agree and the side turns by less than 6°.
   Otherwise the side stays where it was on this level.
7. **Bent edges**: a parabola through each scan line's strongest step on the coarsest level, where
   the band is widest. A bow above 0.4 % of the side's length that the parabola explains at least
   30 % better than a straight line marks the side as curved (a page that is not flat; #302 uses
   this).

The result is refused, and the input returned, when a corner moved by more than twice the search
distance, the area changed by more than a quarter, or the corners are no longer convex.

## Measured on synthetic photos

Pages rendered with the exact share of page in every boundary pixel (8 × 8 samples), so the true
corners are known to a fraction of a pixel. 24 refinements per row, each from a start up to the
given distance off per corner. `cargo test -p pdfcraft-scan tests_refine -- --nocapture`.

| Photo | Start off by | Mean corner error | 95th percentile |
| --- | --- | --- | --- |
| Light page on a dark desk, 12 MP | 25 px | 0.001 px | 0.001 px |
| The same, blurred by 1.5 px with noise of 6 grey levels | 25 px | 0.008 px | 0.009 px |
| White page on a light table, 8 grey levels apart, 3 MP | 12 px | 0.008 px | 0.010 px |
| Dark page on a light table, 3 MP | 12 px | 0.002 px | 0.002 px |
| Rows of words and a ruled frame 12 px inside the edge, 3 MP | 12 px | 0.002 px | 0.003 px |
| Checked tablecloth under the page, 3 MP | 12 px | worst 0.12 px | |

A finger over 60 % of one side leaves the other three sides' corners within 0.8 px. A page curled
by 1.5 % of its width has both bowed sides reported as curved with the bow measured within 1 %
(17.2 px against 17.4), and the straight ones not.

These numbers say the method is exact when the edge is a clean step. They are not what a phone
photo will give: real edges have soft shadows, paper thickness, lens blur that varies over the
frame and backgrounds with their own lines. That is measured on the owner's photos and the
evaluation set (#288), not here.

Time at 12 MP on a desktop x86-64 core: 6.5 ms for the pyramid, 15 ms per refinement, 15 MB held
while it runs. Not measured on a phone; decoding the photo (not counted here) takes longer than
both. The diagnostics log records `refine_ms`, `refined_sides` and `curved_sides` per photo.

## A corner outside the photo (#291)

A side is fitted from its visible part, and the corner goes where the two fitted sides meet, even
off the photo, up to 15% of the photo's side past the edge (`quad::OUTSIDE`); a meeting point
further out is held at that limit. The page function paints the part with no photo white, and the
crop screen shows such a corner (`docs/ANDROID_SCAN_CROP.md`). The detector still gives corners on
the photo; it is the refinement that moves them out. Checked on drawn pages only
(`a_corner_outside_the_frame_is_where_its_sides_meet`): a page whose top-right corner is 30 px
above and 100 px right of a 2000 × 1500 photo came out within 3 px of each true corner.

## Not done here

- The page mask of a neural detector as a hint (#290 has to exist first).
- Snapping a dragged corner in the crop screen (#292): `scan_refine_quad` takes the sides to look
  at again for that, and the pyramid is built so that it can be kept between calls.
