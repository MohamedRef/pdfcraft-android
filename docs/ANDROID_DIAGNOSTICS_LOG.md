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
  [ink] 12 · #74 #75 #76 #81 #84 #85 #128 #130 #144 #148 #149 #150 #156
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
| `open` | `open_start` with a per-open number, then every open stage and milestone of `OpenTiming` (see `docs/PDF_OPEN_PROFILING.md`), including `native_input_mapped`/`_owned` and `native_display_spliced`/`_copied` (whether the display copy duplicated the file, #234) | `AppViewModel.open`, `DocumentFiles`, native timings | #211 #212 #234 #235 #317 |
| `render` | Page, tile and prefetch render times, slow-render pixels, stale tiles, render failures. `tile_render_cancelled` when a tile the view stopped wanting was stopped while it drew (#91). `render_retry` for each failed page or tile render (page, base or tile, `oom`, `wait_ms` before the retry, `gave_up` after the last try) and `render_recovered` when a retry then worked (`failed_tries`) | `PageImages` | #99 #236 #86 #91 |
| `ink` | One `stroke` line per pen stroke: duration, events, samples, sample rate, how the stroke started (`mode=ink`, `erase` or `highlight`, from the eraser tip or the S Pen button), and app-side latency (count, median, p95, max) of the normal and low-latency paths. `latency_summary` for each path when the log is shared | `StylusLog`, `InkLatency` | #128 #144 |
| `ink` | `gesture`: one line per touch on the page with a drawing tool: the pointer it started with, its role at the start and at the end (`draw`, `erase`, `navigate`, `palm`, `overlay`), pen mode, whether a pen took over from a finger, whether it moved past touch slop (`moved=false` is a tap), most fingers down, finger events and the batched samples they carried, duration, cancelled | `toolGestures`, `InkDiagnostics.kt` | #74 #81 #84 |
| `ink` | `erase`: one line per eraser gesture: points, chunks erased before lift (a long gesture), radius in points | `InkDiagnostics.kt` | #76 |
| `ink` | `stroke_clip`: a finished stroke that crossed its page edge: points before, pieces kept (`0` when it was all off the page), points kept | `InkDiagnostics.kt` | #75 |
| `ink` | `lasso_select` (time to find what a loop encloses, loop points, drafts and saved comments on the document), `lasso_selected` (strokes and comments picked), `lasso_drag` (moves while dragging a selection, total and slowest placement time in µs, whether it moved) | `InkDiagnostics.kt`, `ToolLayer` | #85 |
| `ink` | `palm_guard`: finger touches the palm guard ignored on the page and on the toolbars since the last pen stroke, with the guard size and hand; written after the next stroke and when the log is shared | `PenDiag`, `PalmGuardUi`, `Tools` | #156 |
| `ink` | `pen_button_click`: a side-button click while hovering and its action (`none`, `undo`, `toggle`) | `PenDiag`, `Tools` | #144 |
| `ink` | `pen_gesture`: kind (`scratch_out`, `line_highlight`, `line_underline`, `loop_lasso`), outcome, and how many drafts and saved comments it erased or selected. `gesture_undo`: Undo of a stroke that became a markup, and whether the markup went too | `PenDiag`, `Tools` | #148 |
| `ink` | `guide_stroke`: a stroke that followed the ruler or the protractor's arc or ray, with its angle in whole degrees (the ruler's angle, the arc's sweep, the ray's angle) | `PenDiag`, `Tools` | #150 |
| `ink` | `zoom_box_advance` (new line, new page) and `zoom_box_render` (time, scale, pixels, ok) of the zoom box's sharp background | `PenDiag`, `Tools` | #130 |
| `ink` | `eyedropper` (found a colour or not) and `palette` (import or export, outcome such as `imported`, `full`, `not_a_palette`, `newer_version`, `too_large`, and the number of colours) | `PenDiag`, `InkColorPicker` | #149 |
| `save` | Pause with a document open (dirty or not), and each save: `doc_save`, `refresh` and `write_back` times, bytes written, trigger (`pause`, `close`, `save_now`, `other`) and outcome (`saved`, `held` while Save a copy's picker is open, `read_only`, `failed`). Save a copy: `copy_start` (dirty, whether a save of this visit already wrote the original), `copy_cancel`, `copy_continue` (whether the original's working copy was discarded) | `AppViewModel.onPause`, `AppViewModel.save`, `AppViewModel.saveCopyAs` | #97 #101-#104 #190 |
| `share` | Each share: the choice (`pdf`, `flattened`, `clean`, `page`, `images`), the page in view and page count, and the bytes of the file handed out | `AppViewModel.share` | #205 #155 |
| `update` | Each update check (`auto` for the daily one, found or not, time), downloads (`verified`, `paused` with progress, failures) and installs (installer launched or permission missing) | `UpdaterState`, `UpdatePanel` | #193 |
| `scan` | Each photo (edges found, search time `find_ms` with its parts `decode_ms`, `detect_ms` and `refine_ms`; from #290 what found the page: `source` (`model`, `lines`, `none`, or `failed`), its `confidence`, the `angle` the photo was shown to the network at and how many `passes` it took; from #291 how many sides were `refined_sides` and `curved_sides`), each saved page (edges found, corners moved, largest move in % of the photo diagonal, look, clean-up time, and from #287 the native `decode_ms`, `rectify_ms`, `look_ms`, `encode_ms`, output `out_width`/`out_height`, `out_format` and `dpi`, and from #293 the matched `paper`, the `aspect` source (`known_focal`, `estimated_focal`, `typical_focal`, `side_lengths`) and `focal_mismatch`, and from #302 whether flattening was asked for (`flatten`) and done (`flattened`), the network's `bend_pct` and its time `flatten_ms`, and from #288 the testing options in force: `finder` and `refine` on each photo, `strength`, `side_proportions` and `snap_paper` on each page, and from #339 which way up the page was found to be: `upright` (asked), `quarter_turns` (the turn given, clockwise), `upright_sure`, `upright_confidence`, `upright_agreement`, `upright_tiles` and the time `upright_ms`, and how the turn it was saved with was decided: `auto_upright` (the ordinary setting), `turns_manual` (the person's own turn, -1 for none), `turns` (the turn saved), `turn_source` (`manual`, `neighbours`, `classifier`, `off`), `settle_turns` and `followed` (the multi-page rule), plus a `turn` line for every press of Rotate or Undo (`index`, `action`, `auto_turns`, `turns`), and from #299 the `sharpen` and `denoise` asked of the Clean scan filter and the grain it measured, `noise_sigma` in grey levels (-1 for the other filters)), each page preview the scan screen made (#301: `preview` with `index`, `filter`, `flatten`, `made`, `reused`, `ms`, the native stages as `*_ms`, `width`, `height`, `quarter_turns`, `flattened`), the crop screen (#292: `crop`, `crop_snap`, `crop_detect`), the flattening check on `page` and `preview` lines (#302: `flatten_auto`, `flatten_checked`, `flatten_kept`, `flatten_why`, `straight_px`, `straight_flat_px`, `straight_lines`, `straight_flat_lines`), `test_options` when a scanner testing option is changed, `tests_shared` and `tests_cleared` for the scans kept for testing (see `docs/ANDROID_SCAN_TESTING.md`), the saved pages' size and ratio, OCR time, and a summary with found and untouched pages | `AppViewModel.onScanPhoto`, `AppViewModel.requestScanPreview`, `AppViewModel.saveScan`, `ui/scan/ScanCropScreen.kt`, `ScanDiagnostics.kt`, `ScanLabController.kt` | #286-#307 (gates) |
| `memory` | Java heap and native heap at process start, after the native open, after overlays, on memory trims and when sharing. `page_images_trim` when the app goes to the background: page images kept, MB freed and MB kept | `Diagnostics.memory`, `PageImages.trimToWanted` | #212 #92 #95 |
| `tool` | Engine tools that took 100 ms or more, or failed. A comment edit's time (`comment_add`, `comment_update`) is the cost of #131 | `Engine.tool` | #96 #113 #131 |
| `text` | One `search` line per search (pages, pages searched, hits, total and first-hit time, whether it finished or was replaced); one `word_count` line per Read Aloud start (pages counted, time, finished or stopped); `slow_selection` for a selection query (long press, drag, handles, copy) that took 100 ms or more, waiting included | `SearchState`, `ReadAloud`, `TextSelection` | #87 #88 |
| `home` | `recents` (files on Home, time to work out their sources and locations) and `slow_thumbnail` for a file card preview that took 100 ms or more | `AppViewModel.refreshRecents`, `Thumbnails.load` | #93 #94 |
| `pages` | Page and document tools. Organize: one `page_edit` per page edit (tool, pages touched, page count, ms to the redrawn view). `layer_set` time. `outline_drag` when a bookmark is dropped in Edit contents (moved or not, levels changed, rows) (#266). `mark_applied` / `marks_removed` (kind, update or add, picture, pages). `compress_run` (level, advanced, ms) and `compress_result` (bytes before and after, target). `export_pages_render` / `_result` / `_save` and `export_all_images` / `_result` (format, dpi, files, bytes, skipped). `split` / `split_result`, `extract_pages`. `protect_copy`, `unprotect_copy`, `hidden_removed`, `flattened`. `pdfa_convert` (level, compliant, issues left), `fix_hairlines` (strokes fixed). `print_layout` / `_result` (layout, per sheet, subset, sheets, bytes), then `print_write` (bytes, ms) and `print_finished` from the print framework. `save_to_downloads` (bytes, ms, ok), `save_to_folder` / `_result` (files, saved). Failures of saving and printing go to `error` with `area=pages` | `AppViewModel` page tools, `MainActivity.print` | #165 #168 #172 #173 #174-#177 #181 #183 #185 |
| `error` | Failures with their stage and exception class, engine panics caught, and crashes with the exception classes and code locations | `Diag.failure`, `Engine`, crash handler | #114 |
| `viewer` | Document tabs and split view (`tab_*`, `split_*`), reading and presenting: jumps back and forward, Go to page, the layout a file opens in and why, page mode and spread changes, the spread a window shows, reading colours, keyboard shortcuts, hiding annotations, comment-list filters and dimming, copies without annotations, presentation start and end, clicker keys, the second screen and the pages drawn for it (see below) | `ViewerController`, `AppViewModel`, `TabsController`, `TabStrip`, `ViewerScreen`, `CommentList`, `ExternalPresentation` | #138 #139 #152 #153 #155 #157 #184 #198 #203 #204 #327 |
| `markup` | Comments, markup, links, measuring and study: comment export, import, summary, copy and paste, status and lock; markup follow-up actions, area highlights, replace and insert suggestions, shape annotations; links added, edited, deleted, made from addresses and removed; measure points, saved measurements, scales and exports; masking tape and quizzes (see below). Never a comment's text, a link's address or a measurement's label | `MarkupDiag`, `AppViewModel`, `Tools`, `Study` | #133 #134 #135 #143 #158 #159 #169 #170 #179 #180 #196 #197 |
| `edit` | Content editing: image edits, redaction marks and apply, the hidden-information and fake-redaction checks, OCR runs, reviews and batches, Compare, the accessibility check and its fixes, Prepare form, imported fonts, spacing, overflow warnings, grouped text made editable, moving, copying and pasting objects, and Fill & Sign with digital signatures (see below) | `EditDiagnostics.kt` with `ImageTools`, `Redact`, `OcrTools`, `Compare`, `Accessibility`, `PrepareForm`, `TextTools`, `ObjectSelect`, `SigningController` | #161 #162 #163 #164 #166 #167 #171 #178 #182 |
| `study` | Library: `library_index` (one per indexing pass: time, why it stopped, documents indexed, unchanged, too large, unreadable), `library_index_doc` (time, bytes, pages, characters, bookmarks and comments read), `library_search` (time, query length, files, hits). Flashcards: `card_from_selection`, `card_review` (rating, new, cloze, next interval in days, FSRS stability and difficulty, cards left), `cards_export`, `cards_import`. Study time: `study_time` (active time, pages seen, notes), `focus_timer` (start, pause, lengths), `focus_phase` (how late a phase change was noticed). Digest: `digest_export`, `digest_entries` (entries, highlights, drawings, time to read the highlighted text), `digest_images`. Notebooks: `notebook_create`, `notebook_add_page` (paper, or `user` for a saved page). Backups: `backup_snapshot` (copy time, bytes, comments exported), `backup_write` (ok, time, bytes, versions pruned, how long the copy waited), `backup_versions`, `backup_restore`. Note margins: `margin_add` (side, size %, pages), `margin_remove`. Handwriting: `handwriting` (outcome `read`, `nothing_read`, `no_new_ink` or `ocr_missing`; inks, words, low-confidence words, inks given text, OCR time). Lectures: `lecture_mic`, `lecture_start`, `lecture_pause`, `lecture_resume`, `lecture_stop` (length, bytes, kbit/s, marks, wall time), `lecture_mark` (when, comments added), `lecture_play`, `lecture_speed`, `lecture_seek_comment` | `StudyController`, `BackupController`, `AppViewModel` (margins, handwriting, lectures) | #140 #141 #142 #145 #146 #147 #151 #154 #160 |

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
| `tab_stash` | `doc`, `tabs`, `dirty`, `not_written_back` | The document in front moved to a tab because another opened or came to the front, after its save (#138) |
| `tab_switch` | `doc`, `parked`, `tabs`, `ms` | A tab came to the front; `parked=true` means it opened again from its file, else `ms` is the switch time (#138) |
| `tab_park` | `doc`, `hot` | A tab was saved and closed in the engine to keep at most four documents open (#138) |
| `tab_close` | `doc`, `tabs`, `background`, `parked` | A tab closed: the one in front, one behind it, or a parked one (#138) |
| `tab_home`, `tab_key` | `doc`, `tabs`; `action` (`cycle`, `close`) | Home was opened with tabs kept; Ctrl+Tab or Ctrl+W was pressed (#138) |
| `split_open`, `split_close`, `split_swap` | `doc`, `other`, `tabs` | Split view started, ended or swapped its panes (#139) |
| `split_shown`, `split_resize` | `doc`, `other`, `side_by_side`, `width_dp`, `height_dp`; `percent` | How split view was laid out for the window, and the main pane's share after dragging the divider (#139) |
| `split_snip`, `split_drop` | `doc`, `page`; `other`, `kind` (`ink`, `text`, `image`, `cancelled`, `failed`), `linked`, `ms` | An area was marked in the other pane, and what dropping it on the document in front made, whether it got a back-link, and how long it took (#139) |
| `split_link` | `doc`, `other`, `pane`, `area` | A back-link to a document already open went to the other pane (`pane=true`) or its tab, without reopening it (#139) |

Failures in these steps also write an `[error] failed area=viewer stage=…` line (`comments_hide`,
`reading_colors`, `clean_copy`, `unlocked_copy`, `external_show`).

### `markup` lines

Every line carries `doc` (the document's number in the log).

| Line | Fields | What it answers |
| --- | --- | --- |
| `comments_export` | `share`, `bytes`, `ms` | Comments were exported to a file or shared as XFDF, its size and time (#169) |
| `comments_import` | `kind` (file extension), `bytes`, `added`, `ms` | An XFDF or FDF import, how many comments it added and how long it took (#169) |
| `comments_summary` | `sort`, `ms` | A comment summary was made, in which order and how fast (#158) |
| `comments_copy`, `comments_paste` | `comments`, `pages`; `pages`, `skipped_pages` | Comments copied to another document, and pages a paste had to skip because the document is shorter (#169) |
| `comment_status`, `comment_lock` | `status` (`accepted`, `rejected`, `cancelled`, `completed`, `none`); `locked` | A review status or lock was set from the list (#196) |
| `markup_follow_up` | `action` (`recolor`, `note`, `delete`) | The bar that follows a new markup was used (#197) |
| `area_highlight` | `w_pt`, `h_pt` | A Highlight drag that found no text highlighted the area instead (#134) |
| `text_suggestion` | `kind` (`replace`, `insert`), `lines` | A replace or insert suggestion on selected text (#133) |
| `shape` | `type` (the shape mode, such as `arrow`, `cloud`, `polygon`), `points` | A drawn shape became its own annotation rather than ink (#170) |
| `link` | `action` (`add`, `edit`, `delete`), `target` (`page`, `file`, `web`, `mail`), `from` (`box`, `lasso`) | A link was made, changed or removed, and how its area was chosen (#179 #334) |
| `link_follow_file` | `found`, `page` | A link to another PDF was tapped, and whether that PDF was in the Library or recent files (#334) |
| `links_from_urls`, `links_remove` | `created`, `ms` | Web addresses in the text were turned into links, or all links were removed (#159) |
| `measure_point` | `kind` (`distance`, `perimeter`, `area`, `calibrate`), `snapped`, `points`, `snap_ms` | Each measuring tap, whether it snapped to the page's lines and how long snapping took (#135 #180) |
| `measure_saved`, `measure_scale`, `measure_export` | `kind`, `points`; `method` (`ratio`, `calibrate`), `unit`; `count` | A measurement was saved, a scale set, or measurements exported (#135 #180) |
| `tape_add`, `tape_toggle` | `color` (index in the tape colours), `pattern` (`stripes`, `dots`, `grid`, `plain`), `freehand`; `lifted`, `via` (`tap`, `talkback`) | Tape was laid, or lifted or laid again (#143 #350) |
| `tape_share` | `tapes`, `ms` | Share ▸ With tape showing: how much tape the copy made opaque on its Tape layer, and how long it took (#350) |
| `tape_all`, `tape_remove_all` | `lifted`, `tapes`; `tapes`, `pages` | Reveal all, Cover all, or Remove all tape (#143) |
| `quiz_start`, `quiz_end` | `scope` (`page`, `document`), `items`; `items`, `answered`, `known` | A quiz started over highlights and how it ended (#143) |

Failed engine tools in these steps write the usual `[tool]` and `[error]` lines.

How the Scan gates read from the log: the no-touch rate is `untouched_pages / found_pages` in the
`saved` line; the corner error is the median of `max_move_pct` over the `page` lines with
`corners_moved=true`; the A4 ratio check uses `ratio` in the `page_size` lines (A4 is 1.414).

### `edit` lines

Every line has `doc`. None holds a word, name, expression, field name or alternate text the user typed, or any text from the document. Engine time and failures of these tools are also in `tool` and `error`.

| Line | Fields | What it answers |
| --- | --- | --- |
| `image_edit` | `action` (`crop`, `remove_crop`, `flip_horizontal`, `flip_vertical`, `rotate_by`, `replace`, `ungroup`, ...) | An image edit was done (#161) |
| `redact_mark` | `how` (`area`, `page`, `phrase`, `words`, `pattern`, `regex`), `marked` | How redaction marks were added, and how many (#163) |
| `redact_apply` | `applied` | Marks applied for good (#163) |
| `hidden_info`, `hidden_removed` | `categories` | Hidden-information categories found after applying, and how many were removed (#163) |
| `redact_audit`, `redact_audit_found` | `ms`, `ok`; `findings` | The fake-redaction check, its time, and how many problems it found (#163) |
| `ocr_run` | `editable`, `pages`, `ms`, `ok` | Recognize text in the open file, as searchable text or an editable copy (#166) |
| `ocr_review_apply` | `corrections` | Words corrected in the OCR review (#166) |
| `ocr_batch` | `files`, `failed`, `finished` | OCR for many files, at its end or when cancelled (#166) |
| `compare` | `visual`, `changes`, `identical`, `ms` | A comparison with an older version and its time (#171) |
| `compare_mark`, `compare_report` | `comments`; - | Changes added as comments; the report was written (#171) |
| `a11y_check` | `failed`, `manual`, `passed`, `figures`, `ms` | The accessibility check (#182) |
| `a11y_fix` | `rule` (the engine's rule id), `status` | A rule was fixed, and whether it passes now (#182) |
| `a11y_alt` | `decorative` | Alternate text was set, or an image marked decorative (#182) |
| `a11y_autotag_preview`, `a11y_autotag_apply`, `a11y_tag_fields`, `a11y_report` | `page`, `candidates`, `unclassified_images` (preview) | Auto-tagging, tagging form fields, the saved report (#182) |
| `form_add_field`, `form_delete_field` | `kind`, `drawn` (a box drawn, or a tap) | Prepare form placed or deleted a field (#164) |
| `form_detect`, `form_detect_found`, `form_detect_add` | `ms`, `ok`; `found`; `proposed`, `accepted`, `added` | Detect fields: its time, the proposals, and how many were kept (#164) |
| `form_tab_order` | `order` (`row`, `column`, `structure`) or `move` (`earlier`, `later`), `fields` | The tab order was set (#164) |
| `font_import` | `ok`, `otf` | A font file was imported for a paragraph (#167) |
| `text_spacing` | `line`, `letter` | Line and letter spacing were set (#167) |
| `text_overflow` | `page` | A draft paragraph no longer fits its box (#167) |
| `text_prepare` | `prepared`, `refused` | Text inside grouped artwork was made editable, and groups the engine left alone (#167) |
| `objects_move`, `objects_copy`, `objects_paste` | `count`; `cut`, `count`; `pasted`, `same_doc` | Several objects were moved, copied or cut, and pasted (#162) |

Signing (#178) adds these lines. They never hold a name, email, organization, password, reason, location or certificate:

| Name | Fields | Meaning |
|---|---|---|
| `sign_fill_place` | `element` (`text`, `cross`, `check`, `dot`, `box`, `line`, `signature`, `initials`, ...) | A Fill & Sign element was placed |
| `sign_drawn` | `initials`, `saved`, `placed`, `imported` | A signature or initials was finished on the pad, saved for later or not, placed on a page or not, from an image or drawn |
| `sign_cert_target` | `placement` (`box`, `field`, `invisible`, `read_only`) | A digital signature was started: a drawn box, a signature field, no box, or refused on a read-only file |
| `sign_document` | `placement`, `look` (`name`, `typed`, `drawn`), `reason`, `location`, `ms`, `result` (`signed`, `wrong_password`, `refused`, `error`), `saved` | A document was signed with a digital ID, or the attempt was refused. `reason` and `location` say only whether they were filled in |
| `sign_id_import` | `result` (`added`, `cancelled`, `wrong_password`, `cannot_sign`, `unusable`), `ids` | A digital ID file was added, or why not |
| `sign_id_create` | `ok`, `ms` | A self-signed digital ID was made |
| `sign_id_remove` | `ids` | A digital ID was removed, and how many are left |
| `sign_list` | `fields`, `signed`, `valid`, `unknown`, `invalid` | The document's signature fields and how their signatures checked out |
| `sign_trust` | `trusted`, `count` | A signer was trusted or no longer trusted, and how many are trusted now |
| `sign_signed_version` | `revisions`, `found` | View signed version: the revisions, and whether the signed one was found |

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
