# Inspection sub-stages and off-path release (#284)

On the owner's 0.0.24-beta (24012) timings (Galaxy S24 Ultra, 352 MB / 869 pages, ~28,000 links)
editing was ready after 1.9–2.4 s. Inspection was the longest background stage (1.08–1.53 s), of
which the annotation read-ahead was only 0.22–0.32 s. The rest had no sub-stage.

## Sub-stages

Recorded only when open timings are recorded; inspection reads and reports the same
(`recorded_inspection_stages_keep_the_result_identical`).

| Stage | What it covers |
| --- | --- |
| `native_inspect_structure` | Opening the lazy structure (cross-reference and object list) |
| `native_inspect_index` | Page index and named destinations |
| `native_inspect_prefetch` | Parallel annotation read-ahead (#233) |
| `native_inspect_outline` | Bookmarks (outline) window |
| `native_inspect_annotations` | Links, comments and annotation attachments |
| `native_inspect_fields` | AcroForm fields and adopted page widgets |
| `native_inspect_fonts` | Per-page font list |
| `native_inspect_release` | Handing the inspected objects to a release thread |

## Release off the open path

Every object inspection read stays converted until the fill pass ends. Freeing them took 51–84 ms
of a host open on the fixture below, on the open path. They are now freed on a short-lived thread
(inline if no thread starts, and on wasm32); nothing reads them after the fill pass.

## Host measurement

Synthetic 356 MB / 869-page / 27,808-link fixture (see `open-cache-passes.md`), 4-core x86,
release build, main at 11d00a5 with and without this change, 9 reading-stage opens each,
alternated, `apps/pdfcraft-android/examples/open_stage_timings.rs`.

| Stage (median) | Before | After |
| --- | ---: | ---: |
| `native_inspect_lazy` | 263 ms | 222 ms |
| `native_background_prepare` | 278 ms | 234 ms |
| open to editing ready | 483 ms | 424 ms |

Host numbers only. The phone's time after the read-ahead (~0.9–1.2 s) is 5–8× the host's, so its
sub-stages are needed before choosing the next change.
