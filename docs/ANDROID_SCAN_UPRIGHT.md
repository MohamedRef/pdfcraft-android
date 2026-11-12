# Scan: turning pages the right way up (#339)

A phone held flat above a desk does not know which way is up, so a scanned page can come out
sideways or upside down. Each page is now looked at by a small classifier after it is
straightened, and turned when the classifier is sure. Code: `apps/pdfcraft-android/scan/src/
upright.rs`, called from `process_page` when `PageRequest.upright` is set, which
`AppViewModel.saveScan` does unless the testing option **Turn pages upright** is off.

## The model

PP-LCNet x1.0 as PaddleClas trained it to tell which way up an image with text in it is
(Apache-2.0, the PaddlePaddle Authors): four answers, 0°, 90°, 180° and 270°. 3.4 MB as shipped.
`tools/scan-models/README.md` says where the file is from (the `rapid-orientation` package on
PyPI; the Hugging Face file the issue names could not be reached) and how it was made;
`assets/models/ATTRIBUTION.txt` carries its notice. Pinned by size and SHA-256 and run on `rten`
like the scanner's other models.

## How a page is judged

1. **Tiles, not the whole page.** The network looks at 224 × 224 pixels. The issue has it look
   at the whole page shrunk to that size. There are no letters left at that size, only a layout,
   and a layout reads much the same upside down. So the page is shrunk to 1120 pixels along its
   longer side (a line of 10-point print on A4 is then about 13 pixels high) and cut into
   224-pixel squares. The nine with the most detail are looked at; a square with next to none
   (empty paper) has no say.
2. **A vote.** Each tile answers with four likelihoods. They are put together as the mean of
   their logarithms, so a tile that rules a turn out counts for more than one that merely
   prefers another. The **confidence** is the share of that vote the winning turn has, and the
   **agreement** is the share of tiles that gave it themselves.
3. **Stopping early.** When the four busiest tiles all give one answer with 0.75 of the vote,
   the other five are not looked at.
4. **Acting.** The page is turned only at a confidence of 0.5 or more and an agreement of 0.5
   or more. Otherwise it stays as its corners gave it: a wrong turn is worse than none.
5. **The turn** moves the straightened page's pixels to their new places. Nothing is resampled
   a second time, and the sides swap for a quarter turn.

A whole photo saved in Original is the camera's own file and is never turned. An empty page has
no tiles with a say and is left alone.

## Measured

The ten private reference photos (not in the repository), each straightened to the reference's
corners by this pipeline with no look applied, then turned four ways: 40 pages.

| What the network is shown | Right, of 40 |
|---|---|
| The whole page at 224 × 224, as the issue describes | 32; 2 of the 8 wrong with a likelihood of 0.8 or more |
| Up to nine tiles, as above | 40, all acted on |
| The same, stopping after four tiles that agree | 40, all acted on |

(The first row was run with ONNX Runtime in Python, the others with the app's own code.)

Two of the ten reference results are sideways themselves, as the issue says; the classifier says
so for both, in all four turns. The ten are mostly Arabic, several with handwriting, stamps or
tables.

The least sure of the 40 right answers had a confidence of 0.53 and an agreement of 0.56, both
on those two sideways documents. The thresholds of 0.5 sit just under that: they are chosen
from these ten documents and nothing else, with no wrong answer among them to set them
against. The diagnostics log records the confidence, agreement and tiles of every page for that
reason.

Time on a desktop processor, per page: 25–170 ms (2 to 9 tiles, about 15 ms a tile). **No phone
has been timed.** The issue's target of 60 ms on the phone was set for one look at the whole
page and will not be met by nine tiles; four tiles is the common case for plain print.

Drawn pages (`scan/src/tests_upright.rs`, pages from `tools/scan-eval/text_pages.py`): English
print, Arabic print and a form with a table and a signature, each turned four ways, are all
turned back to exactly the page that was drawn, each settled by four tiles at a confidence of
0.84–0.87.

## Where this departs from the issue

- Tiles and a vote instead of one look at the whole page, and no second look at the page turned
  by 180°: the tiles are the second opinions.
- The thresholds are 0.5 and 0.5 on the vote, not 0.85 on one answer. This network's likelihood
  for a plain printed page is about 0.85 at best.
- The model file is from PyPI, not Hugging Face (see the tools README).
- The classifier sees the straightened page at full size and the page is then turned in place,
  instead of a preview pass and corners shifted for a second straightening. The pixels are the
  same.

## Not done

- No "Turned upright · Undo" chip and no rotate button on the scan screen; a page turned wrongly
  is put right in the saved PDF (Organize pages), or by scanning again with the option off.
- The option is among the scanner's testing options, not in the ordinary settings.
- No rule that lets a page the classifier is unsure of follow its neighbours in a multi-page
  scan.
- The page shown before saving is not turned.
- No test set beyond the ten photos; no separate figures for Arabic, Latin and handwriting.
- No phone timings; nothing has run on a device.

## Size

APK +3.4 MB.
