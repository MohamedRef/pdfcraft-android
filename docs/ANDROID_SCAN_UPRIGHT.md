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

## Turning by hand, Undo, and the multi-page rule

Added after the classifier, in the pull request that closes the rest of #339:

- **Rotate** on the scan screen turns the page shown a quarter turn clockwise. From then on the
  page's turn is the person's: the request carries it as `turns` (`ScanPageRequest.turns`,
  `PageRequest::turns`) with `upright` off, so the classifier is not asked about that page again
  and cannot fight the person. The turn is added natively after the straightening, by moving the
  same pixels (`upright_pages::total_turn`, then `turned`), so the page is still resampled once.
- **"Turned upright · Undo"** is shown over the page preview when the classifier turned the page
  and the person has not. Undo gives the page a turn of 0 by hand: the page as its corners give it.
- The person's turn is kept in the scan session (`ScanSession` version 2, a fifth field on each
  page line; a version 1 session from before an update still comes back), so it survives Android
  closing the app while the camera is in front.
- **Settings ▸ Scanner ▸ Turn pages upright automatically**, on by default. Off, no
  page is given to the classifier; Rotate still works. The testing option "Turn pages upright"
  stays, and either one off stops the classifier.
- **The multi-page rule** (`upright_pages::settle` and `followed`, through `scan_settle_turns`):
  in a scan of three pages or more, a page the classifier was not sure of takes the turn of its
  two neighbours when both are sure at a confidence of 0.95 or more and agree. Save makes every
  page once, reads the classifier's answers together, and makes again only a page that followed
  its neighbours into another turn. A page the person turned is nobody's neighbour. The testing
  option "Unsure pages follow their neighbors" switches the rule off.
- What each page's turn came from is in the `page` line of the diagnostics log
  (`auto_upright`, `turns_manual`, `turns`, `turn_source`: `manual`, `neighbours`, `classifier` or
  `off`, `settle_turns`, `followed`), every press of Rotate or Undo is a `turn` line, and a kept
  scan's page file has the same in `turns` (see `ANDROID_SCAN_TESTING.md`). `scan_replay` repeats
  a page with the person's turn or the neighbours' turn in place of the classifier.

What was checked, and on what: the Rust tests in `apps/pdfcraft-android/src/scan.rs` (a drawn
photo turned by hand one, two and three times is exactly the unturned page turned that many
times, in the preview and in the saved page; the person's turn adds to the classifier's on the
drawn Arabic page; a whole photo in Original turned by hand is no longer left as the camera file;
the multi-page rule's cases through the app's call), and the JVM tests in
`ui/scan/ScanTurnsTest.kt`, `ScanSessionTest`, `ScanSaveTest` and `ScanLabTest`. **None of it has
run on a phone**, and the screen itself (where the button and the chip sit, whether the chip is
noticed) has not been seen by anyone.

Departures:

- The chip is shown on the scan screen's page preview, not on a separate review screen (there
  is none); with previews switched off (a testing option) there is no chip, and Rotate works from
  the photo without knowing the classifier's turn.
- The multi-page rule is applied when the scan is saved. The preview shows each page by its own
  answer, so a page that will follow its neighbours is shown unturned until it is saved.
- The rule's 0.95 is the issue's figure. With this network's confidences (0.53–0.87 on the
  reference pages, section above) a neighbour at 0.95 will be rare, so the rule may seldom act.
  That is a reason to look at the logged confidences on the owner's scans, not to lower it blind.

## Not done

- No test set beyond the ten photos; no separate figures for Arabic, Latin and handwriting.
- No phone timings; nothing has run on a device.

## Size

APK +3.4 MB.
