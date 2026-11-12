# Scan: black and white (#300)

The black-and-white look decides ink or paper for every pixel from what is around it, and stores
the page with one bit per pixel. In the app it is the **Light text** filter. Code:
`apps/pdfcraft-android/scan/src/enhance/bw.rs`.

## How

1. **Even light.** The paper's light is estimated as for the Auto look (#298) and divided out, so
   paper is near 255 everywhere and the thresholds below mean the same in a shadow as beside it.
2. **Two values near each pixel**, both from the luminance blurred by 0.7 pixels (so neither is a
   single noisy pixel):
   - the **lightest** within about 2.6 mm (31 pixels at 300 dpi, 15 to 63 with the page's
     resolution): the paper, since a neighbourhood a little higher than a line of text always
     has paper in it;
   - the **darkest** within about 0.76 mm (9 pixels at 300 dpi, 5 to 19): the ink of the stroke
     the pixel may belong to. Small on purpose, so a pale line next to bold print is measured
     against its own darkness and not the print's.
3. **Ink** is a pixel below the middle of the two, where they are at least a gate apart. The gate
   is the strength setting: `60 − 0.4 × strength` grey levels, so 38 at the default 55 and 30 at
   75 (Light text). A pale stroke and a bold one each get the threshold halfway up their own
   edge; where the two values are close there is nothing to separate and the pixel is paper.
4. **Solid dark areas.** Inside a filled area both values are dark and close together. A pixel
   below 40 % of white is ink whatever is around it.
5. **Specks.** Ink of at most 3 connected pixels with no other ink within 2 pixels is removed.
6. **Photographs.** Where the Auto look found a printed dark area or a photograph, the page is
   an 8 × 8 ordered-dither pattern of that area's tones (as the Auto look renders them) instead
   of solid black and white.
7. **Resolution.** A page under 200 dpi is straightened at twice the size (in the one resampling
   pass that straightens it), because two levels need more pixels than shades of grey to draw a
   thin stroke.

The page is written as a 1-bit PNG with its resolution in `pHYs`, and stays one bit per pixel in
the saved PDF: `crates/create` already packs two-level images (test
`a_black_and_white_page_is_one_bit_in_the_saved_pdf`).

## Why not Sauvola

The issue asked for Sauvola's threshold, `t = m × (1 + k × (s / 128 − 1))` from the local mean and
standard deviation. A prototype on rendered pages of real fonts (serif and sans, 8 to 12 pt at
300 dpi, a 0.4 mm pencil line a fifth as dark as the paper is light, lighting 10 % uneven, noise
of 4 grey levels) gave:

| Method | F-measure of the print | Pencil line kept |
| --- | --- | --- |
| One threshold at 0.69 of white (the look until now) | 0.935 | 24 % |
| Sauvola, k = 0.34 (the issue's default) | 0.959 | 10 % |
| Sauvola, k = 0.2 | 0.941 | 22 % |
| Sauvola, k = 0.1 | 0.932 | 78 % |
| Lightest and darkest nearby (this look) | 0.989 | all of it away from other print |

With one `k`, Sauvola either drops the pale line or thickens every stroke: its threshold follows
the local mean, which is mostly paper, so it sits near the paper for all strokes or far from it
for all. The middle between the paper's value and the stroke's own value is the edge of the
stroke by construction, whatever the stroke's darkness.

## Measured (synthetic pages, ink known pixel by pixel)

`cargo test -p pdfcraft-scan tests_bw -- --nocapture`. The pages are letter-like marks (stems,
bars, diagonals, dots) rendered with exact coverage, blurred by 0.6 pixels, with lighting 10 %
uneven and noise of 4 grey levels. Ink is a pixel at least half covered.

| Page | This look | One threshold |
| --- | --- | --- |
| Print in five sizes, 300 dpi (F-measure) | 0.976 | 0.904 |
| The same at 200 dpi, stems 1.5 to 2.5 pixels wide | 0.914 | 0.876 |
| Pale pencil line kept (bold heading on the same page: all kept) | 100 % | 5 % |
| Photographed page with a shadow and light falling off by a third, through the whole look | 0.993 | 0.919 (the engine's look) |

Blank paper with noise of 6 grey levels stays under 0.01 % black at strengths 0, 55 and 75. A
filled box larger than any neighbourhood is at least 97 % black. Full stops 0.35 mm across survive
the speck removal at 200 and 300 dpi, and at 150 dpi through the doubled page. An A4 page of print
at 300 dpi is 109 KiB.

At 200 dpi the precision is 0.84: a stroke 1.5 pixels wide, blurred by the lens, is wider at half
its depth than the stroke was. That is what any threshold does to a thin blurred stroke.

## Known weaknesses

- **Grey fills with texture** (a photocopied table's shaded header cells) come out speckled: the
  fill's own grain is as contrasty as a pale stroke. A lower strength cleans them at the cost of
  faint strokes. Seen on one of the ten private reference photos.
- **A photograph wider than about a quarter of the page** is not found by the Auto look's object
  search (it takes large dark areas for shadows), so it is thresholded, not dithered.
- **Time.** 12 MP on a desktop x86-64 core: 0.85 s on one thread, 0.75 s on four, of which the
  light estimate shared with the Auto look is 0.36 to 0.47 s. The ten private reference pages
  (1 to 8 MP): 0.24 to 0.53 s. Not measured on a phone; the issue's target there is 0.6 s for
  12 MP, which this is unlikely to meet as it stands. The diagnostics log has `look_ms` per page.

## Not done here

The setting to switch photograph dithering off (the filter screen, #301). OCR error rates on the
owner's photos, old look against new (needs #288). CCITT or JBIG2 encoding.
