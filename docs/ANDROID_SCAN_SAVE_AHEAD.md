# Scan: Save PDF that only writes the file (#504)

**State: done in code, not run on a phone.** Nothing here was measured on the owner's phone.

## What changed

- **Pages made at full size ahead of Save.** Once a page's preview is ready (#465), the page is
  made at full size in the background by the exact native request Save would make
  (`AppViewModel.scanSaveRequest`, shared by Save, Share, Save to gallery and the background pass),
  one page at a time, the page on screen first, and kept in `cache/scan-full` until the scan ends.
  A page is taken only when Save would ask the same request (output path aside), so a change to
  its borders, look, strength, paper, turn, document type, marks, flattening or any testing option
  makes it again. The multi-page turn rule (#339) still runs on Save; a page it turns is made then.
- **When the pass runs.** Only while no preview is being made (the queue of #465 and the screen's
  own preview) and while the camera is closed. It does not wait for the screen to be idle in any
  other way, and a preview asked for while a full-size page is being made runs beside it.
- **Save, Share, Save to gallery** stop the pass (a page it is making is finished and kept), take
  every page already made and make only the others, "Finishing page n of m" counting only those.
  A page they make is kept too, so a Share followed by Save does not make it twice.
- **Text recognition only when asked.** Settings ▸ Scanner ▸ "Make the text selectable", **off**
  by default (owner's choice). On: after the pages, text is recognized page by page and the dialog's
  Skip saves the PDF at once; pages already read keep their text. The viewer's own text recognition
  is still the way to add text to a saved scan.
- **The dialog says what Cancel does.** While pages are made: "Cancel saves nothing; the scan stays
  open." While the gallery is written: pages already saved stay. While text is read: Skip.
- Testing option `fullPagesAhead` ("Make full-size pages before Save", key `full_ahead`, on). Off is
  Save as before. It needs "Make every page in the background" (`backgroundPages`). The text
  recognition switch is an ordinary setting and applies either way.

## What it costs

Measured on the desktop only (a 4-core cloud container, `--release`), with
`examples/scan_sharpness.rs`: a text page drawn by the engine and photographed synthetically into a
3000 × 4000 JPEG at five tilts, made with Auto color at 50.

| | Per page | 50 pages |
|---|---|---|
| Full-size page file (JPEG) | 0.83–1.33 MB, mean about 1.09 MB | about 55 MB of cache |
| Time to make it (desktop) | 0.62–1.39 s | about 50 s of background work |

A real photo of paper with grain, colour and shadows encodes larger than this clean synthetic page,
so the cache on a phone may well be 2–3 times this; `full_page_ready` writes each file's `bytes`.
The cache is emptied when the scan is saved or thrown away. Time per page on the owner's phone is
in the log as `full_page_ready` `ms` (and `page` `enhance_ms` for pages Save made itself); there is
no phone number yet.

## What to look at on the phone

- `full_page_ready` lines (`index`, `ms`, `bytes`, `why`) while the scan is open.
- On `saved` and `share`: `pages_ready` of `pages`, `wait_ms` (tap to file), `ocr`, `ocr_ms`.
- A kept scan's page file: `made_full_before_save`.
- Not verified: heat and battery with the pass running, memory with a full-size page and a preview
  made at the same time, and whether the camera's own work is slowed just after it closes.
- With #505 (file size choices): a page made ahead is encoded at the size level in force; a level
  changed at Save changes the request, so those pages are made again and "Finishing page n of m"
  shows that wait.
