# Scan: true page proportions (#293)

A scanned page gets the paper's real shape whatever angle it was photographed from, and its PDF
page is exactly A4 or Letter when the paper is. Code: `apps/pdfcraft-android/scan/src/aspect.rs`,
`exif.rs`, and the size and resolution handling in `page.rs` and `encode.rs`.

## The shape

The four corners of a rectangle in a photo give the rectangle's aspect ratio once the camera's
focal length is known, and give the focal length too when both pairs of sides converge (Zhang and
He, "Whiteboard scanning and image enhancement", Microsoft Research,
https://www.microsoft.com/en-us/research/uploads/prod/2016/12/Whiteboard-It.pdf). With the corners
as homogeneous points `m1` (top-left), `m2` (top-right), `m3` (bottom-left), `m4` (bottom-right),
the photo's centre at the origin and the longer side as the unit:

```
k2 = ((m1 × m4) · m3) / ((m2 × m4) · m3)        n2 = k2·m2 − m1
k3 = ((m1 × m4) · m2) / ((m3 × m4) · m2)        n3 = k3·m3 − m1

f² = −(n2x·n3x + n2y·n3y) / (n2z·n3z)
(width / height)² = (n2x² + n2y² + f²·n2z²) / (n3x² + n3y² + f²·n3z²)
```

The focal length comes from, in this order:

1. **The camera** (`known_focal`): the 35 mm equivalent in the photo's EXIF block
   (`FocalLengthIn35mmFilm`, read in `exif.rs`), or the request's `focal_35mm`. In pixels it is
   `focal × diagonal / 43.27`. It is only used when the photo has a camera's shape (4:3, 16:9, 3:2
   or 1:1 within 2 %): a cropped photo's centre and diagonal say nothing about the lens. A digital
   zoom above 1 (`DigitalZoomRatio`) is applied unless the corners clearly show the focal length
   already includes it.
2. **The corners** (`estimated_focal`), when they fix it. How well they do is measured, not
   assumed: every corner coordinate is moved by the expected corner error (3 pixels in 4000) and
   the spread of the implied focal length is what the estimate is weighted by.
3. **A typical phone camera** (`typical_focal`, 0.75 of the longer side: 26 mm equivalent on a
   4:3 photo), when the corners say little. This is the usual case of a phone tilted towards the
   person and hardly sideways: one pair of sides stays parallel in the photo and `f²` is 0/0. The
   estimate from the corners and the typical value are combined, each weighted by its certainty,
   so there is no threshold at which the answer jumps.
4. **The side lengths** (`side_lengths`), only for corners no rectangle gives, or when the
   formula's answer is more than three times off what the sides show.

The earlier rule (the longer of each pair of opposite sides) is exact only for a frontal photo.
On an A4 page at 35° it gives a page 37 % too wide (test `a_tilted_a4_page_comes_back_as_a4`).

## Measured on a synthetic camera

2,000 to 3,000 random views per row, ratios 1.414, 1.294, 1.0 and 2.5 in both orientations, any
rotation in the photo. Errors are of the long/short ratio. Reproduce with
`cargo test -p pdfcraft-scan tests_proportions -- --nocapture`.

| Views | Focal length | Corner noise | Median | 95th percentile |
| --- | --- | --- | --- | --- |
| Tilted 10–50° both ways | unknown, 0.6–1.2 | 0.5 px | 0.11 % | 0.51 % |
| Tilted 0–50° | known | 0.5 px | 0.08 % | 0.27 % |
| Tilted 10–50° one way, 0–3° the other | known | 0.5 px | 0.06 % | 0.23 % |
| Tilted 10–50° one way, 0–3° the other | unknown, 0.65–0.85 | 0.5 px | 0.52 % | 3.7 % |
| Tilted 10–50° one way, 0–3° the other | unknown, 0.65–0.85 | 3 px | 1.3 % | 7.2 % |
| Everyday: 5–35° one way, 0–10° the other | unknown, 0.65–0.85 | 0.5 px | 0.10 % | 0.97 % |
| Everyday: 5–35° one way, 0–10° the other | unknown, 0.65–0.85 | 3 px | 0.49 % | 2.7 % |
| Frontal, 0–3° | unknown | 0.5 px | 0.04 % | 0.11 % |

What the rows say: with the camera's focal length the shape is right at any tilt. Without it, a
page tilted one way only depends on how close the camera is to the typical one: at 45° a camera
of 0.65 or 0.85 instead of 0.75 leaves up to 8 % (at 30°, up to 4 %; at 15°, about 1 %). The side lengths alone
would be off by more in every one of those cases.

Not measured: real photos. The numbers above are geometry with noise added; lens distortion, a
page that is not flat and corners that are several pixels off are not in them. The phone's
diagnostics log records `aspect` and `focal_mismatch` per page for that (see
`docs/ANDROID_DIAGNOSTICS_LOG.md`).

A misplaced centre (a cropped photo treated as whole): with the centre off by 5 % of the width at
about 30° of tilt, the ratio is off by up to 4.3 %, against 8.9 % for the side lengths on the same
views (test `a_misplaced_centre_costs_a_few_percent`).

## Paper sizes and the PDF page

A shape within 3 % of A4 (1.4142), Letter (1.2941), Legal (1.6471), an ID-1 card (1.5858) or a
square is taken to be that paper, the closest first, in either orientation. `snap_paper = false`
keeps the measured shape. A5 has A4's shape and is never detected.

The output keeps the nearer (larger) side's resolution: with `W` and `H` the longer of each pair
of opposite sides and `r` the ratio, the page is `W × W/r` when `W/H ≥ r` and `H·r × H` otherwise,
at most 40 MP.

For A4, Letter, Legal and the card the page is given a whole number of dots per inch and exactly
`round(dpi × mm / 25.4)` pixels each way, so the PDF page is the paper's size to within half a
point. Other shapes take their long side as 297 mm. The resolution is written into each page file
(JFIF density, PNG `pHYs`), and `doc_create` is called without a `dpi` so each page keeps its own
size: an A4 page and a Letter page in one scan are each exact (test
`each_page_of_the_pdf_has_its_papers_size`). Only when a camera file has to stand in for a page
that failed does the whole document get one resolution, the median of the others.

A whole photo in the Original look is not decoded and encoded again: the camera's JPEG is copied
with a JFIF header added that carries the resolution (`stamp_jpeg_density`).

## Not done here

The setting and the per-page chip to change the detected paper (the crop screen, #292). A focal
length from the camera's calibration (the in-app camera, #294).
