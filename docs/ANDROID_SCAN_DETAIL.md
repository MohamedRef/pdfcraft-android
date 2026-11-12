# Scan: grain and sharpening in the Clean scan filter (#299, part 1)

The Clean scan filter (the Auto look, #298) now measures the page's grain, smooths it when there
is a lot, and sharpens print a little, all before its tone curve. Code:
`apps/pdfcraft-android/scan/src/enhance/detail.rs`, called from `enhance::auto`.

## What is done to a page

1. **Grain.** The standard deviation of the page's noise in grey levels (of 255), measured on
   blank paper: the median absolute response of the 3 × 3 Laplacian at about 400 000 places
   where the pixel and its four neighbours are all paper (at least 0.85 of the light the look
   already works out for that place), times 1.4826 and over √20.
2. **Smoothing**, only at a grain of 2 or more. The two colour differences are averaged over
   5 × 5 pixels, which removes coloured speckle and leaves edges where they are. The luminance
   is smoothed only where the pixel is within three times the grain of its 5 × 5 average (flat
   paper, not the edge of a stroke), by a bilateral filter two pixels to either side along the
   row and then down the column.
3. **Sharpening.** An unsharp mask on the luminance, the colour kept: each pixel is moved away
   from a Gaussian average of its surroundings (one pixel wide at 300 dpi, 0.6 to 2 pixels with
   the page's resolution) by 0.9 times the setting. Three safeguards: nothing is done where the
   difference is within the grain (it fades in between 2σ + 1 and 4σ + 4 grey levels); a pixel
   never goes more than 4 grey levels past its lightest and darkest neighbour, so no bright line
   follows a letter; photographs and other things that are not print get 0.3 of the amount.
4. The tone curve of #298, as before.

The setting is 0–100, default 30. Both steps can be changed among the scanner's testing options
(**Sharpen text**: Off, 30, 60, 100; **Smooth grain in dim photos**).

## Before the curve or after it

The issue lists sharpening after the tone curve and asks for both orders to be compared. On the
ten private reference photos, against what the reference scanner made of each (mean over the
ten; `reference_pairs.py compare`):

| | Difference | Mid-tones | Faint ink lost | Stroke width | Edge width |
|---|---|---|---|---|---|
| No sharpening (#298 as it was) | 2.90 % | 12.78 % | 1.98 % | 1.050 | 0.911 |
| **Before the curve, 30** (the default) | 2.90 % | 13.02 % | 2.09 % | 1.039 | 0.872 |
| Before the curve, 60 | 2.91 % | 13.32 % | 2.21 % | 1.029 | 0.840 |
| Before the curve, 100 | 2.93 % | 13.76 % | 2.36 % | 1.020 | 0.806 |
| After the curve, 30 | 2.91 % | 13.29 % | 2.11 % | 1.043 | 0.853 |
| After the curve, 60 | 2.94 % | 13.79 % | 2.23 % | 1.039 | 0.808 |
| After the curve, 100 | 2.97 % | 14.42 % | 2.37 % | 1.035 | 0.760 |

Stroke and edge width are ours over the reference's: 1 is the same, an edge width under 1 is
crisper than the reference. The "after" rows are from a Python prototype of the same formula;
the others from the app's code.

What this says:

- Sharpening does not bring the page closer to the reference. At every setting the difference
  stays where it was or grows a little, as the issue expected.
- It makes edges crisper than the reference's (0.91 to 0.87 at the default) and brings the
  stroke width nearer the reference's (1.050 to 1.039).
- It costs a little faint ink: 1.98 % to 2.09 % of the reference's ink comes out lighter than
  it should.
- Before the curve is the better order at every setting, by a small margin, so that is what
  ships.

Whether crisper than the reference is better to look at, or reads better, is not something
these numbers say. That is what the testing option and the owner's photos are for.

## Grain on real photos

All ten reference photos measure 0.0 to 0.4 grey levels: they are small, compressed photos, and
the smoothing does not run on any of them. **The smoothing has only been tried on drawn pages.**

## Tests (`enhance/tests_detail.rs`, drawn pages)

| What | Result |
|---|---|
| Grain of 1, 3, 6 and 10 drawn in; empty page, some print, dense print | measured 0.99, 2.98, 5.97, 9.95, the same for all three |
| A soft edge (blur 1.2 pixels), setting 30 and 100 | rises in 2.94 and 2.60 pixels instead of 3.17; no value more than 1 level past the edge's ends |
| Empty paper with a grain of 4, setting 100 | 4.02 before, 4.03 after |
| Inside a photograph | the largest change is a quarter of what print gets |
| Grain of 6 on empty paper, smoothed | 6.04 to 1.79 |
| A line 1.5 pixels wide on that page | contrast 174.7 before, 174.7 after |
| Coloured speckle | 11.3 to 2.4 |
| 1, 2, 3, 8 and 50 threads; 5, 96, 97 and 300 rows | the same bytes |

The issue's sharpening test asks for the edge to rise in a quarter less distance. Its own formula
gives 18 % less at the strongest setting, and that is what the test asserts.

## Time

Desktop processor, two cores, a 12-megapixel page: measuring the grain is part of working out
the light (unchanged); sharpening 0.27 s on one thread; smoothing 1.1 s on one thread, when it
runs. On the ten reference pages (3.7 megapixels) the whole look went from about 360 ms a page
to 370–400 ms (two runs).
**No phone has been timed.**

## Not done (the rest of #299)

- The photo-region mask from blocks (mid-tone share, colour, gradient directions). Sharpening
  uses the object mask #298 already makes.
- Document-type presets and `scan_classify`.
- The character-error-rate check with text recognition.
- Anything on the owner's test set: it does not exist yet.
