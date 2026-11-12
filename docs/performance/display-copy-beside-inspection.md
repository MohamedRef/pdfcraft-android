# Display copy drawn beside inspection (#317)

The display copy holds the appearances PdfCraft draws for comments and form fields, plus signature
marks, written into an in-memory copy of the file. During complete preparation it was drawn in
`build_document`, after the renderer's inspection had finished. On the owner's 0.0.24-beta (24012)
timings (Galaxy S24 Ultra, 352 MB / 869 pages) `native_display` took 0.15–0.52 s on that path.

The copy needs only the editing source, the comment appearances and field list from the open
passes, and the signature results. `open_prework` now draws it once those are ready, while
inspection still runs, with the same call (`display_parts_from_open`) on the same source. The
editor takes the bytes, the field-appearance cache and, if the copy could not be written, the
repair line. Reading-stage documents, rebuilt page trees and changed bytes keep drawing it in
`build_document`. `open_field_reuse_matches_fresh_display_with_comments_fields_and_orphan_widgets`
now compares the display bytes and signature marks exactly with drawing the copy afterwards.

## Host measurement

4-core x86 host, release build, main with and without this change, 9 reading-stage opens each,
alternated (`apps/pdfcraft-android/examples/open_stage_timings.rs`).

| Fixture | Background preparation, median before | After |
| --- | ---: | ---: |
| 356 MB / 869 pages / 27,808 links / 1,736 squares without appearances | 767 ms | 569 ms |
| Same without the squares (no display copy needed) | 300 / 327 ms | 321 / 310 ms (two runs; no change) |

With the squares, editing-ready median went from 979 ms to 791 ms. On that fixture, the open passes
plus the display copy now take longer than inspection on this host, so they are the next limit
there. Host numbers only; no phone result is claimed.
