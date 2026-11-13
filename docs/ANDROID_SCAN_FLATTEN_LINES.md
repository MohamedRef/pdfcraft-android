# Scanner: flattening a curled page from its lines of text (#303, part B)

When a page is flattened and the flattening network of #302 has nothing better to offer, the app
now tries a classical fallback that needs no model: it fits the shape of a page that curls in one
direction to the page's lines of text, and draws the page flat from that shape. It is meant for
book pages that curl into the gutter.

**Nothing here has run on a phone.** Every figure below comes from pages drawn in code and run on
a desktop machine in the cloud.

## When it runs

Only when all of these hold:

- the page is being flattened (the scan screen's Flatten switch, or the `flatten_auto` testing
  option);
- the network's page was not kept: its model file is not installed, the network gave no usable
  grid, or #302's guard found the network's page not straighter;
- the `line_flatten` testing option is on (it is by default).

Its page is then put through the same guard as the network's (#302): it is kept only when its
lines of text are at least 10 % straighter than on the page straightened by its corners. When
the fallback gives up or is not kept, the page stays straightened by its corners, as before.

When the scan screen's switch asks for flattening without the check, and the network is
installed, the network's page is kept as before and the fallback does not run.

## How it works (`scan/src/flatten_lines.rs`)

Following Matt Zucker's page dewarping (2016,
<https://mzucker.github.io/2016/08/15/page-dewarping>):

1. **Lines of text.** The page straightened by its corners, with its light evened out
   (`page::evened_grey`, #466: under a shadow the lines cannot be followed otherwise), is cut
   into 32 vertical strips, and
   text lines are followed across them with the same tracker as the straightness measure (#288,
   `straightness::text_lines`). A point is a strip's centre and the line's height there. A line
   is used when it runs across at least 30 % of the width and was seen in at least 5 strips. A
   page needs at least 6 such lines, otherwise the fallback gives up (`few_lines`).
   The issue suggested the OCR detection model for this. The scanner crate has no OCR
   dependency, and the tracker needs no model, so the tracker is used.
2. **Model.** The page is a rectangle in 3D. Its height across its width is a cubic with zero
   value at both ends and slopes `alpha` (left) and `beta` (right). It has a pose (rotation 3,
   translation 3) before a pinhole camera of focal length 1.2 page-lengths. Each line has an
   unknown height, and each point an unknown position across the page.
3. **Fit.** Levenberg–Marquardt on the squared distance between the model's points and the
   found points. Derivatives are forward differences. The per-point positions are taken out
   first with a Schur complement, so only a system of 8 + lines unknowns is solved. It starts
   from a flat page facing the camera and stops after 30 steps, or when a step lowers the error
   by less than 0.1 %. Slopes steeper than 1.5 are not used (`no_fit`).
4. **Grid.** The fitted surface becomes a 31 × 45 sampling grid in photo pixels, the same kind as
   the network's, spaced by arc length across the width so text on the steep part is not
   squeezed. The surface's four corners are pinned to the page's corners. A grid that folds over
   itself is not used (`folds`). The page is drawn once with `unwarp`, as for the network.

## Testing option, log, kept scans

- **Testing option:** `line_flatten` ("Flatten from the lines of text when the network gives
  nothing better"), on by default. Off leaves such a page straightened by its corners, as before.
- **Log:** every `page` and `preview` line has `line_flatten` (the option), `line_ran`,
  `line_gave_up` (`few_lines`, `no_fit`, `folds`, or `none`), `line_kept`, `line_why` (the
  guard's word, or `none`), `line_lines`, `line_points`, `line_steps`, `line_alpha`, `line_beta`,
  `line_rms_before`, `line_rms_after` (pixels), and `line_px` and `line_flat_px` (the two pages'
  line measures in pixels, -1 when not measured). The stage time is `flatten_lines_ms`.
- **Kept scans:** the page file's `result` has a `line_flatten` object with the same facts
  (`gave_up`, `lines`, `points`, `steps`, `alpha`, `beta`, `rms_before_px`, `rms_after_px`,
  `check`), or `null` when the fallback did not run. `options` has `line_flatten`.

## Measured, and on what

All on pages drawn in code (`scan/src/tests_flatten_lines.rs`): 18 rows of word bars on a
1.0 × 1.4 page, curled as the model's cubic, photographed by a pinhole camera from 20–25° off
straight, 900 × 1100 photos, straightened to 700 × 980.

| Page | Curl (alpha, beta) | Lines before (px) | After (px) | Change |
|---|---|---|---|---|
| Curl on the left, tilted 14° | 0.45, −0.10 | 3.68 | 1.09 | −70 % |
| Curl both ways, tilted 23° | 0.30, −0.30 | 4.29 | 0.91 | −79 % |
| Curl on the right, tilted 22° | −0.10, 0.45 | 4.65 | 0.91 | −80 % |
| Flat, tilted 19° | 0, 0 | 0.09 | 0.11 | kept the plain page |

- The first row is just at the issue's 70 % mark, so that test has almost no room.
- On exact points seen head-on, the fit finds `alpha` and `beta` within 15 % of the truth.
- Once a tilted page is straightened by its corners, the size of the curl trades against the
  camera's distance, which the straightened page no longer tells. The fit finds slopes from 0.6
  to 2 times the truth on pages tilted 25°. The signs and the ratio of the two ends are right
  (within 25 %), and the lines still come out straighter, but the issue's "within 15 %" holds
  only for a page seen head-on.
- **Time**, on the cloud machine, one thread, 700 × 980 page: the fit alone took 25 ms, and the
  whole fallback (lines, fit, grid, drawing the page and measuring both pages) took 162 ms. On
  a phone it will be slower; the issue's budget is 1.5 s per page. Not measured on a phone.

## Not done, and not verified

- Nothing has run on a phone or seen a photo of a real book.
- The issue's acceptance items (on the owner's book pages where #302's guard rejected the
  network, how many are now flattened, and the time per page) need the phone.
- The fallback does not use the photo's focal length (#293); see the limit above.
- Pages curled in two directions, or crumpled, are out of scope (the issue says so).
