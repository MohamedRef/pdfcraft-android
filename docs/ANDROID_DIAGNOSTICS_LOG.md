# Diagnostics log

The app can record a detailed diagnostics log and share it as a text file, so a problem that
needs evidence from a real phone can be checked without a computer or adb.

## For the person testing

1. Settings → About → Diagnostics → turn on **Record diagnostics log**.
2. Use the app as usual: open the large file, write with the pen, leave the app, scan pages.
3. Settings → About → Diagnostics → **Share diagnostics log**, and send the file
   (`pdfcraft-diagnostics.txt`).
4. **Clear diagnostics log** starts a fresh log for the next test. Turning the switch off deletes
   the log.

The log is kept in the app's private storage, so it survives the app being closed in the
background or crashing. It keeps about the last 1.5 MB (two files of 768 KB); older lines go first.

It replaces the earlier "Record open timings" switch. A phone that had that switch on keeps
recording after the update.

## What a report looks like

An invented example; the numbers are not measurements.

```
PdfCraft diagnostics log
Only event names, times, counts and sizes. No file names, paths or document contents.
app: 0.0.25-beta (25012)
device: samsung SM-S928B
...
Areas (lines · issues):
  [open] 41 · #211 #212 #234 #235 #317
  [ink] 12 · #128
  ...

11-11 21:03:12.481 [open] open_start doc=1 scheme=content from_other_app=false has_password=false
11-11 21:03:12.950 [open] provider_descriptor_copy duration_ms=455
11-11 21:03:13.122 [memory] heap stage=native_ready java_used_mb=38 java_max_mb=512 native_mb=402
11-11 21:03:20.007 [ink] stroke ms=640 events=38 samples=152 sample_hz=237 eraser=false cancelled=false normal_n=36 normal_p50_ms=9 ...
11-11 21:03:25.310 [app] pause own_screen=false changing_config=false finishing=false
11-11 21:03:25.311 [save] pause_with_document doc=1 dirty=true not_written_back=false preparing=false bytes=369098752
11-11 21:03:26.902 [save] save doc=1 trigger=pause outcome=saved ms=1591
```

## Areas, and the issues they answer

| Tag | What is logged | Where | Issues |
| --- | --- | --- | --- |
| `app` | Process start, pause (and whether it was for the app's own picker or share sheet), resume, recording on, log cleared | `PdfcraftApplication`, `MainActivity` | #97 |
| `open` | `open_start` with a per-open number, then every open stage and milestone of `OpenTiming` (see `docs/PDF_OPEN_PROFILING.md`) | `AppViewModel.open`, `DocumentFiles`, native timings | #211 #212 #234 #235 #317 |
| `render` | Page, tile and prefetch render times, slow-render pixels, stale tiles, render failures | `PageImages` | #236 |
| `ink` | One `stroke` line per pen stroke: duration, events, samples, sample rate, and app-side latency (count, median, p95, max) of the normal and low-latency paths. `latency_summary` for each path when the log is shared | `StylusLog`, `InkLatency` | #128 |
| `save` | Pause with a document open (dirty or not), and each save: `doc_save`, `refresh` and `write_back` times, bytes written, trigger (`pause`, `close`, `other`) and outcome | `AppViewModel.onPause`, `AppViewModel.save` | #97 #101-#104 |
| `scan` | Each photo (edges found, edge search time), each saved page (edges found, corners moved, largest move in % of the photo diagonal, look, clean-up time), the saved pages' size and ratio, OCR time, and a summary with found and untouched pages | `AppViewModel.onScanPhoto`, `AppViewModel.saveScan`, `ScanDiagnostics.kt` | #286-#307 (gates) |
| `memory` | Java heap and native heap at process start, after the native open, after overlays, on memory trims and when sharing | `Diagnostics.memory` | #212 #92 |
| `tool` | Engine tools that took 100 ms or more, or failed | `Engine.tool` | #96 #113 |
| `error` | Failures with their stage and exception class, engine panics caught, and crashes with the exception classes and code locations | `Diag.failure`, `Engine`, crash handler | #114 |
| `viewer` | Reading and presenting: jumps back and forward, Go to page, the layout a file opens in and why, page mode and spread changes, the spread a window shows, reading colours, keyboard shortcuts, hiding annotations, comment-list filters and dimming, copies without annotations, presentation start and end, clicker keys, the second screen and the pages drawn for it (see below) | `ViewerController`, `AppViewModel`, `ViewerScreen`, `CommentList`, `ExternalPresentation` | #152 #153 #155 #157 #184 #198 #203 #204 #327 |

### `viewer` lines

| Line | Fields | What it answers |
| --- | --- | --- |
| `jump` | `from_page`, `mode` | A contents, bookmark, link or Go to page jump left this page (#198) |
| `jump_back`, `jump_forward` | `from_page`, `to_page` | The back and forward chip or key returned to the right place (#198) |
| `go_to_page` | `doc`, `labels` (page labels in use), `ok` (false: the entry was refused), `page_count` | Go to page took or refused what was typed (#203) |
| `layout_open` | `doc`, `source` (`remembered`, `file`, `app`), `mode`, `spread`, `auto` | Which layout a file opened in, and why (#152) |
| `page_mode` | `doc`, `from`, `to`, `page` | Continuous, single page or pages in a row was chosen, and the page kept (#152) |
| `spread`, `spread_auto` | `spread`; `on` | Two pages, cover or automatic two pages was chosen (#152) |
| `spread_shown` | `doc`, `spread` (shown), `chosen`, `auto`, `width_dp`, `height_dp` | What the window showed after a rotation or resize, and why (#152) |
| `layout_initial_view` | `doc`, `layout` | The layout was written into the file as its initial view (#152) |
| `reading_colors` | `doc`, `colors`, `ms`, `ok` | Reading colours were applied, and how long the engine took (#184) |
| `shortcut` | `doc`, `action`, `ctrl`, `shift`, `tool` | A keyboard shortcut ran (#153). Keys that are not shortcuts are not logged |
| `comments_hide` | `doc`, `hidden`, `ms`, `ok` | Hide annotations was switched, and how long it took (#155) |
| `comment_filter` | `doc`, `kind`, `status`, `search` (true when a search is typed; never its text), `hidden_authors`, `shown`, `total`, `dimmed` | What the comment list's filters showed and how many comments the page dims (#155) |
| `comment_filter_cleared` | `doc`, `dimmed` | The "Comments filtered" chip cleared the filters (#155) |
| `prepared_copy` | `doc`, `clean`, `unlock`, `ok`, `ms`, `comments_removed`, `bytes` | Share or Print without annotations, or printing a protected file: the copy, its time and size (#155) |
| `present_start`, `present_end` | `doc`, `page`, `pages`, `mode_before`; `s` (seconds presented), `laser_strokes`, `ink_strokes` | A presentation and what was drawn in it (#157 #327) |
| `present_tap`, `present_key` | `doc`, `turn`, `page`; `key` (a key code number), `shift` | Pages turned by a tap or a clicker's key (#157) |
| `external_display` | `doc`, `attached`, `screens`, `width_px`, `height_px`, `secure` | A second screen came or went while presenting; never its name (#157) |
| `external_show` | `doc`, `ok` | The page window opened on the second screen, or could not (#157) |
| `external_page` | `doc`, `page`, `width_px`, `height_px`, `ms`, `ok` | Each page drawn for the second screen, its size and time (#157) |

Failures in these steps also write an `[error] failed area=viewer stage=…` line (`comments_hide`,
`reading_colors`, `clean_copy`, `unlocked_copy`, `external_show`).

How the Scan gates read from the log: the no-touch rate is `untouched_pages / found_pages` in the
`saved` line; the corner error is the median of `max_move_pct` over the `page` lines with
`corners_moved=true`; the A4 ratio check uses `ratio` in the `page_size` lines (A4 is 1.414).

## Privacy rules

The log must never hold document contents, file names, URIs, paths, passwords, typed text,
exception messages, or anything else a user wrote or opened (#105, #107, #108). The code enforces
part of this:

- A line is an event name plus `key=value` fields.
- Values may be numbers, booleans, enums, or short lowercase words (`[a-z][a-z0-9_.-]{0,39}`).
  Any other string is written as `<redacted>`. Keys and event names that are not plain lowercase
  identifiers are replaced too.
- `Diag.failure` logs only the exception's class. A crash line logs exception classes and
  `Class.method:line` code locations, never the message.
- Documents are told apart by a number per open (`doc=3`), never by name.

The rule still holds where the code cannot check it: do not pass a page's text, a comment, a
bookmark title or any other user content even when it happens to be a short lowercase word.

## Adding a log point

Use `Diag` from `io.github.artcraftandroid.pdfcraft.engine`:

```kotlin
// one event
Diag.event(DiagArea.Save, "write_back", "doc" to doc.diagNumber, "bytes" to file.length())

// time a block (adds ms=<duration> and ok=true/false, also when it throws)
val result = Diag.measure(DiagArea.Scan, "ocr", "pages" to n) { eng.tool("ocr_recognize", args) }

// a failure (stage + exception class only)
catch (e: Exception) { Diag.failure(DiagArea.Render, "page_render", e) }

// heap use at a moment
Diagnostics.memory("after_my_step")
```

Rules:

1. **Pick the area** whose issues your evidence answers. Add a new `DiagArea` (tag and issues)
   only for a new subject, and add it to the table above.
2. **Event names and keys** are fixed lowercase identifiers written in the code. Add units to keys:
   `_ms`, `_mb`, `bytes`, `_pct`, `_pt`.
3. **Values** are numbers, booleans, enums or fixed words. To identify a document, use
   `doc.diagNumber`. Never pass user content (see the privacy rules).
4. **Hot paths** (per frame, per pen sample, per tile): log a summary at the end (as the pen's
   `stroke` line does), not one line per sample. When building the fields costs anything (a file
   size, a list), wrap the call in `if (Diag.enabled())`; when the log is off, `Diag.event`
   returns at once but its arguments are still evaluated.
5. **Open stages** keep using `OpenTiming` (`measure`, `milestone`, `duration`, `count`); its
   lines go to the `open` area, or to `render` for render stages, and to logcat as before.
6. **Test it**: a JVM test in `app/src/test/.../engine/DiagnosticsTest.kt` shows how to read lines
   back from a `DiagSink` in a temporary folder.
7. Mention the new line in the table above, with its issue, so the person reading a report knows
   what it means.

## Limits

- The ink latency is app-side: from the pen event's time to the draw call that shows it. It leaves
  out composition and the display, so it is not touch-to-photon latency (see #128).
- `own_screen=true` on a pause is a guess: the app started one of its own screens in the 2 s
  before. Permission prompts and the print dialog are not counted.
- The log measures; it does not show that anything got faster. A speed-up is claimed only from
  before/after reports from the owner's phone.
