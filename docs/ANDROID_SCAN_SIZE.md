# Scan: file size choices and each page's encoding (#505)

How a scanned page is stored in the PDF, and the size the person chooses at Save PDF and Share.
**Nothing here has run on a phone.** The figures below are from pages drawn and photographed in
code on a desktop; the owner's photos and phone have not been measured.

## What the person sees

On the document screen, Save PDF and Share open a sheet first: **Best quality**, **Balanced**
(the default), **Small** and **Smallest**, each with what it is meant for and this scan's
estimated size ("about 1.9 MB"). The estimate makes the first page once at every choice and
multiplies by the number of pages, so it is close for a scan of similar pages and rough for a
mixed one; the sheet says it is from the first page. At Small and Smallest the sheet offers
"See this page as it will be saved": the first page as stored, full screen, pinch to zoom. The
choice is remembered (preference `scanSizeChoice`).

With "Scan screens in steps" off (the old single scan screen), Save PDF has no sheet and uses
the remembered choice.

Save to gallery (#470) is not asked: it stores at Best quality.

A kept scan (Recent scans, #461) holds the photos and the page settings, not the stored pages,
so a scan saved Small can be opened and saved again at Best quality.

## How a page is stored (`scan/src/size.rs`, `scan/src/jpeg.rs`)

Encoded once, from the made pixels (after the look, the finger step and the marks), never a
JPEG made from a JPEG:

1. **No colour on the page → one grey component.** A pixel is coloured when its channels differ
   by more than 28 of 255; a page is coloured when more than 0.05 % of its pixels are. On the
   drawn pages: the letters, the receipt and the Arabic letter are 0 %, the form's blue ink and
   stamp 0.9 %, the page with a photograph 14 %.
2. **Resolution by need**, never enlarged: enough pixels across the page's typical stroke
   (`stroke_mm` × dpi), between the choice's floor and its cap. A page with photographs on it
   (more than 2 % of the page, a Photo page, or a whole photo) keeps the choice's photo cap.
3. **The choice's JPEG quality**, colour at half resolution across and down (4:2:0) below Best.
4. **A page of text alone at Small and Smallest → 1 bit**: no colour, no photographs, a
   Document, Receipt or Book page, not in the Original look, at 200 dpi or more. Made with the
   Black and white look's own rule (#300, no dithering) at up to 300 dpi, and kept only when its
   Group 4 stream is smaller than the page's JPEG. `pdfcraft-create` stores a 1-bit PNG as CCITT
   Group 4 when that is smaller than Flate, as it already did for Black and white.
5. **Huffman tables made for each page.** The image crate's encoder, which wrote every page
   before #505, uses the standard tables of the JPEG specification (Annex K.3) and keeps colour
   at full resolution (4:4:4) whatever the quality. `jpeg.rs` writes the same baseline JPEG with
   tables built from the page's own symbols (Annex K.2, as libjpeg's `optimize_coding`), the
   standard quantisation tables scaled by quality as before, and 4:2:0 when asked.

| Choice | JPEG quality | Resolution cap | Floor | Photo cap | Chroma | 1 bit for text alone |
|---|---|---|---|---|---|---|
| Best quality | 92 | the page's own | – | the page's own | full (4:4:4) | no |
| Balanced | 75 | 250 dpi | 200 dpi | 300 dpi | half (4:2:0) | no |
| Small | 60 | 200 dpi | 150 dpi | 200 dpi | half | yes |
| Smallest | 50 | 150 dpi | 120 dpi | 150 dpi | half | yes |

The Black and white look stays 1 bit at every choice.

**What the encoder change alone saves**, the same pixels and quality: the form (colour, 4:4:4,
quality 92) 601 kB → 469 kB (22 %) from the page's own tables; the page with a photograph
1015 kB → 945 kB (7 %). Half-resolution colour on the form at quality 92 saves another 20 %
(469 kB → 373 kB). A colourless page stored grey instead of RGB with the page's tables: the
letter 752 kB → 545 kB (28 %), the receipt 214 kB → 122 kB (43 %).

## Measured (desktop, drawn pages)

`tools/scan-eval/size_pages.py` draws six A4 pages at 300 dpi (an English letter at 11 pt, small
print at 8 pt, a form with a table, blue ink, a stamp and a signature, a receipt in faint grey,
a page with a colour photograph, an Arabic letter) and photographs each in code: a 12 MP portrait
photo, the page tilted a little on a desk, light falling off towards a corner with a warm cast,
blur 0.8 px, grain 3 levels, saved at JPEG quality 92 (about 2 MB each, as a phone's).
`apps/pdfcraft-android/src/scan_size_tests.rs` makes each with Clean scan (document types on)
as before #505 and at each choice, and reads the stored page with the app's own recogniser
(ocrs, Latin script only).

Two reading measures: the **character error rate** (edit distance over the true text), and
**words missed** (the share of the true text's words not found in the reading, in any order).
The recogniser sometimes reads a block of lines in another order, which costs the character
error rate tens of points with every word read right (the 8 pt page's 44 % before #505 is that);
words missed is the steadier measure. Its noise between nearly identical pages is about one
point (one word in 43 on the receipt is 2.3 points). SSIM compares each stored page, scaled back,
with the page stored as before.

| Page | Stored as | Encoding | dpi | Quality | Bytes | Smaller than before | CER | Words missed | SSIM |
|---|---|---|---|---|---|---|---|---|---|
| letter 11 pt | before | jpeg_rgb | 305 | 92 | 752 051 | 1.0× | 0.32 % | 1.04 % | 1.0000 |
| letter 11 pt | Best | jpeg_grey | 305 | 92 | 544 583 | 1.4× | 0.44 % | 1.38 % | 0.9999 |
| letter 11 pt | Balanced | jpeg_grey | 250 | 75 | 277 408 | 2.7× | 0.13 % | 0.35 % | 0.9948 |
| letter 11 pt | Small | bilevel_g4 | 300 | – | 29 603 | 25.4× | 0.19 % | 0.35 % | 0.9911 |
| letter 11 pt | Smallest | bilevel_g4 | 300 | – | 29 603 | 25.4× | 0.19 % | 0.35 % | 0.9911 |
| small print 8 pt | before | jpeg_rgb | 297 | 92 | 751 003 | 1.0× | 44.25 % | 1.49 % | 1.0000 |
| small print 8 pt | Best | jpeg_grey | 297 | 92 | 551 440 | 1.4× | 44.29 % | 1.27 % | 0.9998 |
| small print 8 pt | Balanced | jpeg_grey | 250 | 75 | 281 823 | 2.7× | 4.63 % | 1.49 % | 0.9953 |
| small print 8 pt | Small | bilevel_g4 | 297 | – | 34 478 | 21.8× | 60.03 % | 3.61 % | 0.9825 |
| small print 8 pt | Smallest | bilevel_g4 | 297 | – | 34 478 | 21.8× | 60.03 % | 3.61 % | 0.9825 |
| form | before | jpeg_rgb | 303 | 92 | 600 976 | 1.0× | 19.25 % | 0.00 % | 1.0000 |
| form | Best | jpeg_rgb | 303 | 92 | 469 017 | 1.3× | 18.86 % | 0.00 % | 0.9999 |
| form | Balanced | jpeg_rgb | 250 | 75 | 206 062 | 2.9× | 15.72 % | 0.00 % | 0.9959 |
| form | Small | jpeg_rgb | 200 | 60 | 128 296 | 4.7× | 26.92 % | 2.20 % | 0.9928 |
| form | Smallest | jpeg_rgb | 150 | 50 | 78 767 | 7.6× | 26.52 % | 0.00 % | 0.9863 |
| receipt | before | jpeg_rgb | 301 | 92 | 214 137 | 1.0× | 2.53 % | 4.65 % | 1.0000 |
| receipt | Best | jpeg_grey | 301 | 92 | 122 472 | 1.7× | 2.11 % | 6.98 % | 0.9999 |
| receipt | Balanced | jpeg_grey | 250 | 75 | 61 189 | 3.5× | 1.69 % | 2.33 % | 0.9980 |
| receipt | Small | bilevel_g4 | 300 | – | 6 111 | 35.0× | 2.53 % | 6.98 % | 0.9953 |
| receipt | Smallest | bilevel_g4 | 300 | – | 6 111 | 35.0× | 2.53 % | 6.98 % | 0.9953 |
| page with a photograph | before | jpeg_rgb | 300 | 92 | 1 015 059 | 1.0× | 0.20 % | 0.53 % | 1.0000 |
| page with a photograph | Best | jpeg_rgb | 300 | 92 | 945 384 | 1.1× | 0.20 % | 0.53 % | 0.9989 |
| page with a photograph | Balanced | jpeg_rgb | 300 | 75 | 419 635 | 2.4× | 0.20 % | 0.53 % | 0.9925 |
| page with a photograph | Small | jpeg_rgb | 200 | 60 | 173 831 | 5.8× | 0.10 % | 1.07 % | 0.9806 |
| page with a photograph | Smallest | jpeg_rgb | 150 | 50 | 108 103 | 9.4× | 0.30 % | 1.07 % | 0.9701 |
| Arabic letter | before | jpeg_rgb | 290 | 92 | 517 052 | 1.0× | – | – | 1.0000 |
| Arabic letter | Best | jpeg_grey | 290 | 92 | 332 700 | 1.6× | – | – | 0.9999 |
| Arabic letter | Balanced | jpeg_grey | 200 | 75 | 137 798 | 3.8× | – | – | 0.9952 |
| Arabic letter | Small | bilevel_g4 | 290 | – | 16 329 | 31.7× | – | – | 0.9899 |
| Arabic letter | Smallest | bilevel_g4 | 290 | – | 16 329 | 31.7× | – | – | 0.9899 |

What it says:

- **Balanced reads as well as before** on every page, within the measure's noise (words missed
  0–1.5 % against 0–4.7 % before), at 2.4–3.8 times smaller. Seven pages like the letter would
  be about 5.3 MB before and 1.9 MB at Balanced.
- **No page is stored larger than before** at any choice, and each choice is no larger than the
  one above it.
- **Small and Smallest are the same for a page of text alone**: 1 bit at the page's resolution.
  It is 22–35 times smaller than before. It costs reading on small print (3.6 % of words missed
  against 1.5 %); on the 11 pt letter and the receipt it is within noise. A 1-bit page under
  200 dpi reads badly, so Smallest does not go lower.
- The stroke measure (`stroke_mm`) is about 0.25 mm on every Latin page here, 8 pt as well as
  11 pt, so the stroke rule only lowers the resolution for thick strokes (it took the Arabic
  letter to 200 dpi at Balanced). Small print is protected by the caps, not by the stroke rule.

How the choices' numbers were set: `scan_size_sweep` in the same file stores each page at
150–300 dpi and quality 50–92 and reads each back. Words missed stays within noise down to
250 dpi at any quality; at 200 dpi the 11 pt letter rises to 1.4–2.8 % and at 150 dpi small
print to 3–4.5 %. Balanced is therefore 250 dpi (quality 75, where the size gain flattens);
Small and Smallest trade reading for size at 200 and 150 dpi, as their names say.

Time on the desktop (4 threads): storing a page takes 60–310 ms for a JPEG (the image crate's
encoder took 65–155 ms) and 220–560 ms for 1 bit, which runs the Black and white rule. On a
phone this is more; not measured.

To run it again:

```sh
python3 tools/scan-eval/size_pages.py /tmp/size-pages
PDFCRAFT_SIZE_PAGES=/tmp/size-pages cargo test --release -p pdfcraft-android scan_size -- --ignored --nocapture
# PDFCRAFT_SIZE_OUT=<folder> also writes every stored page there, to look at
```

## Testing options, log, kept page file

- Testing option **Store each page by what is on it, with size choices at Save** (`autoEncode`,
  key `auto_encode`, on). Off: every page as before #505 (quality 92 at full size through the
  image crate, 1 bit for Black and white), whatever the choice. The sheet is still shown.
- Testing option **Size for every save** (`size`, key `size`): Ask at Save (the default), or one
  choice for every save, with no sheet.
- Log, on each `page` line: `size_choice`, `auto_encode`, `encoding` (`jpeg_rgb`, `jpeg_grey`,
  `bilevel_g4`, or `camera_file` for a whole photo in Original kept as the camera's file), `px_out`
  (pixels), `dpi_out`, `quality`, `half_chroma`, `bytes` (as the PDF stores the page) and
  `encode_why` (words joined by `+`: `colour`/`no_colour`, `stroke`/`cap`/`own_size`, `photo`,
  `text_only`, `smaller_as_jpeg`, `look_bw`, `as_before`). On `saved` and `share`: `size_choice`,
  `bytes` (the PDF), `bytes_per_page` and `pages_bytes`. The sheet logs `size_sheet`,
  `size_options` (the first page's bytes at each choice and the time) and `size_pick`.
- Kept page file: `encoding`, with the same fields as the `page` line.

## Not done, and why

- **JBIG2** for text pages: the engine's `doc_optimize` writes it, with dictionaries shared across
  pages, but the scan's PDF is made by `doc_create`, which stores 1 bit as Group 4. Sharing
  symbols across a scan's pages would make Smallest smaller than Small for text; that needs a
  change in `crates/create` (and the provenance lock), so it is left for its own issue.
- **Mixed raster content** (a 1-bit mask of the ink over a low-resolution colour layer): the
  issue's own "later, its own issue".
- Progressive JPEG and trellis quantisation: not tried.
- The phone: time per page, the estimate's speed, the sheet in the seven languages, TalkBack.
