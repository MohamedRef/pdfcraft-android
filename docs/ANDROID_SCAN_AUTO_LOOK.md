# Scan: the Auto look ("Clean scan")

Issue #298, tracking #286. Code: `apps/pdfcraft-android/scan/src/enhance/`.

## What it does

One look that makes a photographed page read like a flatbed scan: shadows and uneven light are
removed, paper becomes neutral white, ink goes near black, pen, stamps and logos keep their colour,
and fingers or printed dark areas stay natural. In the app it is the **Clean scan** filter of the
Scan screen and the default for a new scan. The older looks are unchanged.

## How it works

Everything expensive runs on a working copy at most 1000 px long; the full-resolution pass is one
multiply, one curve lookup and a little arithmetic per pixel.

| Step | File | What |
|---|---|---|
| 0 | `page.rs` (`enlarged`) | A straightened page under 3.6 MP is enlarged to it (at most 4× per side) in the same resampling pass that straightens it, so the tone curve works on smooth strokes. |
| 1 | `background.rs` (`stroke_width`) | Stroke width from horizontal dark runs; closing radius = 2.5 × stroke, 4–24 px. |
| 2 | `background.rs` (`closed`) | Per channel: 3×3 median, grey closing with a disk, light blur. Thin dark things are gone, shadows keep their shape. |
| 3 | `objects.rs` | Dark areas that are print or things on the page, and warm areas with a sharp colour edge (fingers), are found so the light is not estimated from them. |
| 4 | `background.rs` (`paper_light`) | The paper's colour under the local light: an average over paper pixels only (three scales), limited by the closing so hard shadow edges stay in place. |
| 5 | `tone.rs` | Each pixel divided by its paper colour; the colour of the page's black made neutral. |
| 6 | `tone.rs` | Tone curve on luminance (at strength 60: black point 0.25, white point 0.97, gamma 1.4) and colour added back at its absolute strength. |
| 6a | `tone.rs` (`paper_spread`, `white_from`, `lifted`) | On a grainy page only: the paper is drawn white from a lower level (see "Grainy pages" below). |

The result is deterministic: the same bytes for the same page on any number of threads.

## How it was measured

The owner supplied ten photos together with what another scanner's one-tap filter made of each.
The pairs are private and are not in this repository. `tools/scan-eval/reference_pairs.py` lines a
pair up pixel by pixel and compares our output with the reference (see `tools/scan-eval/README.md`).

On those ten pairs, with the look applied to the photo resampled onto the reference's own crop:

| | Auto look (this code) |
|---|---|
| Mean absolute luminance difference from the reference | 2.9 % (1.0–6.0 % per pair) |
| Reference ink rendered light by us | 2.0 % |
| Reference paper rendered dark by us | 0.25 % |
| Stroke width, ours / reference | 1.05 (1.00–1.18) |
| Edge transition width, ours / reference | 0.91 (0.69–1.04; below 1 is crisper) |
| Time for a 3.7 MP page, x86-64 desktop, 2 threads | 0.29–0.43 s |

The numpy/OpenCV prototype in `tools/scan-eval/auto_reference.py` gives the same figures.

## Grainy pages: photos taken in dim light (#466)

A photo taken in dim light is grainy, and dividing by a dim light makes the grain larger. The
tone curve is steepest just under white, so the grain showed as grey specks all over the paper.

What the look does now, in `tone.rs`:

- It measures how much the paper varies after the light has been divided out (`paper_spread`:
  the median distance of the paper samples from their median, as a standard deviation, as a
  share of the paper's own brightness).
- Up to a spread of 0.025 nothing changes: **the bytes are the same as before.**
- Over it, the level from which paper is drawn white comes down by 4 times the excess, never
  under 0.80 (`white_from`). The curve keeps its shape up to a fifth of its range below that
  level and then rises smoothly to white (`lifted`), so print and mid greys keep their tone.

Why the spread and not the look's grain measure (`detail::noise_sigma`): that measure reads the
finest detail only. On the pages below, which step 0 had enlarged, it read 0.6 to 3.6 grey
levels where the paper's real variation was 10 to 20.

**Measured (13 Nov, desktop):**

| | Paper's spread | Paper not white, before | After | Print still dark, before | After |
|---|---|---|---|---|---|
| The owner's ten reference photos | 0.005–0.021 | | unchanged: the same bytes | | |
| Five of them dimmed to a quarter of the light, with sensor grain added | 0.040–0.080 | 5.5–24.9 % | 0.2–7.9 % | 94.4–99.4 % | 94.1–99.4 % |
| The same five at a tenth of the light | 0.064–0.114 | 18.6–45.9 % | 0.4–27.0 % | 82.6–98.2 % | 82.1–98.1 % |

The page that stays worst in both rows is the sheet in the dappled shade of a tree: dimmed, its
shade and its grain together are past what the floor of 0.80 can take. The saved files are 14 to
46 % smaller on the dimmed copies (4 to 33 % at a tenth of the light).

**What this is not:**

- **None of the owner's photos was taken in dim light.** Their paper is at 193 to 255 of 255
  and their grain under 1 grey level. The dimmed copies were made by lowering the exposure and
  adding shot and read noise to each pixel. A phone's own processing smooths grain before the
  photo is saved, so a real dim photo will differ; the figures say the direction, not the size.
- The grain is not removed, only kept out of the paper. Specks inside coloured or grey areas
  and along the edges of print stay. The look's smoothing step (`detail::denoise`) still
  starts from the fine-grain measure, which under-reads enlarged pages; that is not changed here.
- Faint marks lighter than the new level (pale pencil, a light stamp) are drawn white on a
  grainy page. On a page under the free spread nothing is lost.
- There is no testing option, log field or kept-scan field for this yet (see the pull request).
- Low-light merge (#297), which lowers the grain itself by averaging four photos, is a
  different thing and still off by default (#426).

## Limits

- A sliver of shade narrower than twice the closing radius and darker than 88 % of the paper
  around it is taken for ink and stays.
- An island of shade darker than half the paper around it is taken for print.
- A cool-coloured object at the page border (a blue sleeve) looks like shade and is whitened unless
  it is darker than half the paper.
- The object thresholds come from ten photos; #298 lists every measured component.
- Curved pages are not flattened here (#302), and the page is not turned upright (#339).
- No phone timing has been taken yet. The target in #298 is 12 MP in under 500 ms.

## Tests

Grainy pages: `tone.rs`'s own `grain_tests` (a clean page keeps its curve exactly; the lift
leaves print alone, has no step and stops at the floor; a drawn dim page with 8 grey levels of
grain goes from 17.6 % of its paper not white to 3.7 %, its print unchanged).

`cargo test -p pdfcraft-scan`: synthetic pages with known truth (flat light, gradient with a hard
shadow, dappled shade, coloured light, pencil, pen and stamp, a finger at the edge, a warm soft
shadow, printed dark areas, yellow paper kept or whitened, blank and black pages, thread counts)
and each filter against its definition (disk dilation, 3×3 median, area shrink, wide blur, masks).

## Document types (#299, section 5)

Clean scan takes each page for a kind of document and uses that kind's strength, sharpening and
paper colour. This wires in two blocks from #382 rather than writing them again: `preset`
(`classify`, `params`, `ink_features`, `gutter_band`) and `photo_mask`. The glue is
`scan/src/page_kind.rs`.

- **Measured on**: a working copy of the straightened page, about 1000 pixels long. Its light is
  evened out by the Auto look's own light map (`enhance::evened`): each channel is divided by the
  paper's colour under the local light, with no tone curve. Both blocks expect "the page after
  lighting correction". The share of near-white pixels (`bright_share`, named `glare_share` before
  #427) and the gutter's dark band are measured before that correction, as their blocks say
  (whitened paper would all count, and the correction removes the gutter).
  `ratio` and `short_mm` come from the page's size and the matched paper. `curved_sides` comes
  from the edge detection (#291), passed in by the app.
- **Kinds and settings**: as in the issue's table (Photo, ID card, Receipt, Whiteboard, Book page,
  Photo-rich document, Document; Coloured form only when a person picks it).
- **What a kind changes** (`page_look_kind`):
  - the look's strength, unless the strength was set by hand (`fixed_strength`): the strength
    testing option, or the page's own from the scan screen's slider (#424). A page whose slider
    was never moved gets its kind's strength, and the slider shows that value;
  - the sharpening: the kind's, scaled by the sharpening testing option over its default
    (`kind_sharpen`, #424). At the default (30) it is the kind's own, 0 is none, 60 twice the
    kind's and 100 three and a third times it, at most 100. Before #424, 30, 60 and 100 all gave
    the kind's own;
  - paper Keep for Photo, ID card and Coloured form.
- **A kind's own tone** (`kind_tone` in the request; testing option "Document types shape Clean
  scan's tone", on): the kind's `extra_gamma` is added to the tone curve's gamma (Receipt +0.3,
  which darkens faint thermal print), Whiteboard's S-curve `0.5 − 0.5·cos(t^0.75·π)` replaces the
  gamma curve, and its colour factor (×1.5) multiplies the colour put back. The other kinds have
  no shape of their own, so their pages are as before (`enhance::Shape`, `Tone::shaped`).
- **Photographs sharpened gently** (`photo_sharpen`; testing option "Sharpen photos on the page
  gently", on): the block photo mask (section 4, `photo_mask.rs`; the rule in force since #427, so
  barcodes, stamps and soft print are sharpened as print; the first rule with "Barcodes, stamps
  and soft print are not photographs" off) is joined to #298's object mask,
  and inside either sharpening is 30 % of what print gets (`enhance::with_photos`). A block counts
  when its weight is at least one half. A page the mask finds to be a picture is all gentle.
- **Suggestions** (testing option "Suggest Flatten page or Grayscale for a document type", on): a
  Book page shows a chip "Flatten this page?" beside the type while the person has not chosen
  either way for the page and the flattening model is there; a Receipt shows "Try grayscale" while
  the look is Clean scan. Tapping a chip makes the change (the page's own Flatten page switch from
  #302, or the Grayscale look); nothing changes by itself, because the type is a guess. A
  `suggestion_taken` line (`index`, `suggestion`) is logged for each tap.
- **Recognition-error check** (`src/scan_cer_tests.rs`, the issue's "OCR safety" test): the drawn
  English page (`scan/tests/data/upright-english.png`, whose text `text_pages.py` holds) is blurred
  and given grain in ten ways, given Clean scan without sharpening, at 30 and at 100, and read by
  the app's own recogniser (ocrs, models from `assets/models/`). Mean character error rate over
  the ten: **5.29 % without sharpening, 5.42 % at 30 (the default), 5.47 % at 100** (the clean
  page: 2.93 %). So the issue's rule, "not higher than without", is **not met** on these pages:
  at 30, three pages read better, five worse and two the same. The test fails only when the default
  is more than half a point worse than none, a bound set after this run to catch a change for the
  worse. Whether the default sharpening stays is open (#299). Drawn pages, English only, no photos.
- **App**: under the page preview, in Clean scan, a chip says "Auto: Receipt" (or the kind the
  person chose). Tapping it lists the kinds and "Automatic". The choice is kept per page in the
  scan session. There is no separate `scan_classify` call: the preview and the saved page both
  report the kind (`ScanPreview.kind`, `ScanPageResult.kind`).
- **Testing**:
  - option "Document types in Clean scan" (`ScanLab.presets`, on); off is the look as before;
  - `page` log fields `presets`, `preset`, `preset_chosen`, `preset_strength`, `preset_sharpen`, `keep_paper`, `suggest_flatten`, `suggest_greyscale`, `page_ratio`, `ink_share`, `colour_ink_share`, `photo_share`, `stroke_mm`, `bright_share` (was `glare_share`, see below), `gutter_band`, `whole_photo`, and from #427 `strict_gutter`, `gutter_loose`, `gutter_cover`, and from #299's second part the kind's tone and its options: `kind_tone`, `kind_gamma`, `kind_s_curve`, `kind_colour`, `photo_sharpen`, `suggestions`;
  - options `kind_tone`, `photo_sharpen`, `suggestions`, and in `document_type` the kind's `extra_gamma`, `s_curve` and `colour_factor`, in a kept scan's page file;
  - `preview` lines carry `preset`; a `preset_chosen` line for each choice;
  - `document_type` in a kept scan's page file. The test set's own `kind` key stays the person's label.

What was checked, and on what (all drawn in code or from `scan/tests/data/`, nothing real):

- a slip three times as long as wide is a Receipt, and its page is byte-identical to the look made with the Receipt's strength (80) and sharpening (35) by hand;
- the drawn English A4 page is a Document, a Book page with two curved sides, and an ID card when matched to that size;
- a white page nine tenths covered by a smooth picture is a Photo;
- a strength set by hand and sharpening off are kept;
- a chosen kind is used and marked as chosen;
- Coloured form keeps a yellow paper;
- only Clean scan has kinds.

### The book page's gutter rule (#427)

A review of `main` at `fa61b265` ran document types on the owner's ten reference photos: two pages
that are not books (a sheet in dappled tree shade and a flat label) were called Book page, both
through the gutter band. The first rule (`preset::gutter_band`) only asks that the 6 % strip along
one long side is, on average, 12 % darker than the page's median and the other side's is not, so a
patch of shade or a band of dark surround left by the crop is enough.

- **Strict rule** (`preset::gutter_reading`): the first rule, and on the dark side the strip is
  also dark along at least 9 of 10 equal pieces of the side's length (a gutter runs the whole
  binding), the next strip in (6–12 %) is not dark (a gutter is narrow), and the shadow fades
  inward: the strip's inner half is still at least 4 % under the median and its outer half at
  least 3 % of the median darker than the inner half (a band of surround is flat or stops).
  A curved side still makes a Book page on its own.
- **Kept with the straightened page**: the measurements depend only on the straightened page, so
  `PageBase` keeps them (`PageBase::measured`) the first time a Clean scan look asks. The
  preview's later looks, strength changes and the strip's Clean scan tile reuse them instead of
  measuring the page again. The page's bytes are the same either way.
- **`glare_share` renamed `bright_share`**: it is the share of pixels with all three channels at
  250 or more before the light is evened, which white paper in good light reaches (0.42 on one
  evenly lit page in the review). No rule reads it. The log field and the kept scan's key are
  renamed with it.
- **Testing**: option "Book page only when a shadow runs down the whole binding side and fades
  inward" (`ScanLab.strictGutter`, `strict_gutter`, on); off is the first rule. `page` log fields
  `strict_gutter` (the option), `gutter_band` (the rule in force), `gutter_loose` (what the first
  rule says) and `gutter_cover` (share of the darker side's length that is dark, 0–1); the same
  `gutter_loose` and `gutter_cover` in the kept scan's `document_type`, and `strict_gutter` in its
  `options`.
- **Checked on drawn pages only** (`tests_preset.rs`, `tests_page_kind.rs`): a shadow that fades
  inward along the whole side passes both rules (left, right, and the bottom of a landscape page);
  a flat band, a thin black surround, a patch of shade over 40 % of the side, and a broad shadow
  over 30 % of the page pass the first rule only; the drawn A4 page with a flat band is a Document
  under the strict rule and a Book page under the first. **Not checked on any real photo**, not on
  the two pages of the review, and not on a real book page: a gutter shadow narrower than about
  3 % of the page's width, or one that does not fade, is no longer a gutter. Whether the strict
  rule misses real books is for the owner's kept scans (their `kind` labels) to show, through the
  confusion table #299 asks for.

### The photograph rule (#427)

With the gutter rule fixed, the flat label of the review came out as a **photo-rich document**:
its photo share read 0.151 against the rule's 0.15, and it has no photograph. Run on the ten
reference photos, the photo mask's first rule (S13 section 4: mid tones, colour, spread of
gradient directions) read 13 % of a stamped sheet, 15 % of that label, 10 % of a shipping label
and 6 % of a letterhead page as photograph. Looking at what it marked: **barcodes, ink stamps and
soft print.** In a small photo their thin strokes never get as dark as ink, so a block of them is
about half grey and half blank paper, which is what the first rule calls mid tone.

- **What gives print away**: blank paper showing between many edges. Of the blocks the first rule
  marked on those pages, the ones a quarter paper or more had a median of 45 to 68 % of their
  pixels on a strong gradient. The light evening also turns a photograph's bright parts white,
  so paper alone is not enough of a sign (a first try with paper alone took the drawn photograph
  page from 64 % to 6 %): those parts are smooth, under 5 % of their pixels on an edge.
- **The rule in force** (`photo_mask::is_photo`): the first rule, unless the block is at least a
  quarter paper (`PRINT_PAPER`, luminance above 0.85) **and** has at least a fifth of its pixels
  on a strong gradient (`PRINT_EDGES`). Both readings are made from one measuring of the blocks
  (`photo_masks`) and kept with the page (`Measured`), so the rule can be swapped and compared.
- **On the ten reference photos** (photo share by the first rule → by the rule in force):
  0.130 → 0.058, 0.151 → 0.000, 0.105 → 0.035, 0.057 → 0.000, 0.021 → 0.000, 0.014 → 0.014, and
  0 on the other four. What is left on the three pages that keep some is not print and not
  paper: a hand at the page's edge, an object lying on a corner, and the surroundings showing
  where the crop cut past the sheet. All ten are now Document, with the same bytes as with
  document types off; with the first rule put back, the label is Photo-rich again. The nearest
  page is at 0.058, against 0.15 for photo-rich.
- **Testing**: option "Barcodes, stamps and soft print are not photographs"
  (`ScanLab.strictPhoto`, `strict_photo`, on); off is the first rule. `page` log fields
  `strict_photo` (the option), `photo_share` (the rule in force) and `photo_loose` (what the first
  rule reads; -1 when there is no kind); `photo_loose` in the kept scan's `document_type` and
  `strict_photo` in its `options`.
- **Checked on drawn pages** (`tests_photo_mask.rs`, `tests_page_kind.rs`): a drawn barcode and
  drawn soft print are photographs by the first rule only (0.093 each → 0); a photograph whose
  top fades to white reads the same by both; a drawn label with a barcode over a quarter of it is
  a Document under the rule in force and Photo-rich under the first; the page that is mostly a
  photograph is a Photo under both.
- **Not known**: how a real printed photograph reads. None of the ten reference photos has one,
  so the rule's safety for photographs rests on drawn ones, which are smooth. A photograph with
  fine texture in its bright parts (foliage against sky, a halftone screen) could lose those
  blocks; its darker parts stay, and holes inside a region are closed. The owner's kept scans
  will show it: `photo_share` against `photo_loose` on pages with photographs.

**One finding:** the same picture covering four fifths of the page measured 0.595 photo share through the light evening, just under the 0.6 rule, so it came out as Photo-rich. The block's own test of an 80 % picture, on a page already white, gives over 0.6. Whether real photographs land on either side is for the owner's scans to show.

Not verified: the issue's acceptance items (the confusion matrix on the owner's labelled photos, the reference-pair numbers, halo crops). None of this has run on a phone, and no timing was taken. The measuring adds one light map and one pass over a copy about 1000 pixels long for each Clean scan page and preview.
