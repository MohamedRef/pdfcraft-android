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

`cargo test -p pdfcraft-scan`: synthetic pages with known truth (flat light, gradient with a hard
shadow, dappled shade, coloured light, pencil, pen and stamp, a finger at the edge, a warm soft
shadow, printed dark areas, yellow paper kept or whitened, blank and black pages, thread counts)
and each filter against its definition (disk dilation, 3×3 median, area shrink, wide blur, masks).
