# Scan: flattening a curled page (#302)

A four-corner crop assumes the page is flat. A sheet held in the hand, a curled receipt or a
folded letter is not, and its lines of text come out bent. With **Flatten page** on, the page is
drawn flat by a network that says where each point of the flat page is in the photo. Code:
`apps/pdfcraft-android/scan/src/flatten.rs`, used by `process_page` when `PageRequest.flatten` is
set, which `AppViewModel.saveScan` does when the chip beside the looks is on.

**It is off unless the person turns it on, for every page of that scan.** Nothing measured so far
tells a bent page from a flat one well enough to decide for them; see "Measured" below.

## The model

[UVDoc](https://github.com/tanguymagne/UVDoc) (Verhoeven, Magne and Sorkine-Hornung, SIGGRAPH Asia
2023), MIT, Copyright 2023 Tanguy Magne: a fully convolutional network of about 8 million weights,
15.8 MB as shipped. It looks at a picture 488 × 712 pixels and returns a grid of 31 × 45 points.
The network draws no pixels: the grid is used to resample the photo itself, so the text is as
sharp as the camera recorded it. `tools/scan-models/README.md` says how the shipped file was made
from the published checkpoint, and `assets/models/ATTRIBUTION.txt` carries its notice. The file is
pinned by size and SHA-256 like the detector's and runs on `rten`.

The model ships in the APK (15.8 MB more) and is unpacked with the other models. The chip is
shown only when the pinned file is there (`scan_can_flatten`).

## How a page is flattened

1. **What the network is shown.** Not the photo, and not the page's bounding box: the page as the
   plain four-corner straightening draws it, with 8 % of its surroundings kept on every side, at
   488 × 712. A landscape page is turned a quarter first, because the network was trained on
   upright pages. (Showing it the bounding box of the page in the photo was tried first, as the
   issue describes. On a page lying at an angle the result was 11 % of the diagonal away from the
   reference; with the straightened view, 1.3–1.5 %.)
2. **The grid.** The answer, from −1 to 1 across that picture, is carried back through the same
   straightening into photo pixels (and turned back for a landscape page).
3. **Refusals.** A grid with a point that is not a number, or one that folds the page over
   itself (`FlattenGrid::folds`; none of the grids for the ten photos below has one), is not
   used: the page is straightened by its four corners as without the switch, and `flattened` is
   false.
4. **Resampling.** Each output pixel's place in the photo is read from the grid with Catmull-Rom
   interpolation (a bilinear grid has kinks at the cell borders), and the photo is sampled there
   by the same sampler the straightening uses, which averages when it shrinks. The output's edges
   are the grid's edges. Outside the photo is white.
5. The page's size and proportions are the ones worked out from its corners (#293), as without
   flattening.

`PageResult.bend` is how far the grid is from the best perspective fit to it: the 95th percentile
of the distance, as a share of the diagonal. It is recorded, and nothing is decided from it.

## Tests

`scan/src/tests_flatten.rs`, on drawn pages:

| Test | Result |
|---|---|
| A page curled like a cylinder, flattened with the exact grid | 31.6 dB against the flat page; lines wander 0.00 px (14 px when straightened by the corners alone) |
| A grid that is a plain perspective, against `rectify` | mean difference 0.00 grey levels |
| A straight line across cell borders | largest jump 0.0001 px |
| Conventions: −1…1 to pixels, the margin, a landscape page turned and turned back | exact |
| Grids with a hole or a fold | refused |
| The network on curled pages it has not seen (drawn) | lines wander 2.0 px (24.5 px without); a flat page's `bend` is 0.27 % |

## Measured

On the ten private reference photos (the same photo as another scanner drew it; they are not in
the repository). Times are one page on a desktop processor, four threads: the model 0.39–0.55 s,
the resampling 15–132 ms for pages of 0.2 to 2 megapixels. **No phone has been timed.**

Seen side by side, three of the ten pages are visibly bent in the photo (a sheet sagging in the
hand, a curled receipt, an invoice with a fold), and flattening straightens their lines; the
fold is left with a slight wave. The seven others are flat or nearly so.

Two measures were tried and neither is steady enough on the bent pages to carry a number that
means much:

- *Distance from the reference's geometry* (`reference_pairs.py register`: what is left between
  our page and the reference after the best perspective fit, 95th percentile, % of the diagonal).
  The same pipeline, run twice, gave 3.5 and 1.5 for the sagging sheet without flattening, and
  4.2 and 1.9 for the invoice, because the fit depends on which points happen to match. On the
  flat pages it repeats to about 0.1.
- *How level the text lines are, block by block* (no reference needed).

| Page | Distance from the reference, plain → flattened | The same, run again | Line angle off level, plain → flattened |
|---|---|---|---|
| A, a sheet sagging in the hand | 3.48 → 2.27 | 1.47 → 1.18 | 2.0° → 0.5° |
| F, a curled receipt | 3.13 → 1.11 | 2.40 → 1.31 | 1.4° → 0.8° |
| G, an invoice with a fold | 4.20 → 1.90 | 1.86 → 2.36 | 0.7° → 1.7° |
| C, a flat label | 0.09 → 0.35 | 0.09 → 0.31 | 0.0° → 0.3° |
| E, a flat note | 0.25 → 0.88 | 0.29 → 0.83 | 1.1° → 2.1° |
| B | 0.85 → 0.60 | 0.79 → 0.64 | 0.9° → 0.5° |
| D | 0.45 → 1.45 | 0.48 → 1.26 | 0.8° → 0.9° |
| H | 0.55 → 2.60 | 0.47 → 2.29 | 1.3° → 0.9° |
| I | 0.49 → 1.62 | 0.44 → 1.32 | 0.9° → 0.7° |
| J | 0.71 → 0.66 | 0.69 → 0.69 | 0.7° → 0.8° |

(B, D, H, I and J are ordinary sheets on a table or in the hand, one of them lying at an angle.
The line angle is the mean, over the blocks of a 4 × 4 division of the page that hold text, of how
far that block's lines are from level; it is itself rough, and reads 1.1° on a page that looks
level.)

What can be said from this: on a flat page the network moves things the plain straightening
had right, by up to 2 % of the diagonal (C, D, E, H, I, in both runs), so flattening must not be
on for every page. On the sagging sheet and the receipt it helps by both measures. On the invoice
the eye says its rows are more level and the measures do not agree with each other.

## Why there is no automatic mode yet

The issue describes one: flatten when the grid's `bend` is 0.3 % or more, and keep the result only
if its lines are at least 10 % straighter. On the ten photos `bend` is 0.34–1.30 % for every page,
flat ones included (the flat label 0.34, the flat note 0.62, the sagging sheet 0.69, the receipt
0.72), so the first rule would flatten everything. Whether a side was found curved when the
corners were refined (#291) does not separate them either. The second rule needs a
line-straightness measure that can be trusted on real pages, which is part of #288 and is not
built; the block-by-block one above is too unsteady.

So this is a switch. The diagnostics log records, per saved page, `flatten` (asked), `flattened`
(done), `bend_pct` and `flatten_ms`, which is what an automatic rule would be built from once
there are more photos.

## Not done

- No automatic mode and no quality guard (above).
- No trim of a strip of background left along an edge.
- The switch is for the whole scan, not per page, and the page shown before saving is not
  flattened: the result is first seen in the saved PDF.
- No phone timings; nothing has been run on a device.
