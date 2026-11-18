# Adjustable ink eraser — Android

## Implementation
- Draw has a context-aware **Eraser size** control in the direct two-row toolbar. Selecting Eraser once enables it; tapping Eraser again or the size control opens a diameter slider.
- Adjustable radius: 2–32 PDF page points (diameter 4–64 pt), with existing 8-point default retained. This value persists in the existing local tool preferences; corrupted/out-of-range stored values are sanitized.
- A native erase gesture snapshots its radius on pointer-down. Mid-gesture setting changes cannot change the command retrospectively.
- A zoom-correct circular cursor previews the same radius, using `pixelsPerPoint(page)`. Pen hover and eraser-tip hover likewise use the current saved eraser size rather than a hard-coded 16-pt diameter.
- Native `comment_erase` receives the validated page-space radius; it continues to erase ordinary Ink and opted-in FreehandHighlight Ink only. Locked, Fill & Sign and text-selection highlights remain excluded by the engine.
- Using the eraser does not change pen colors, three pen favorites, nib types, shape modes, author, updater, app version or signing key.

## Erase whole strokes (#521)
- The Eraser size sheet has an **Erase whole strokes** switch, kept in the tool preferences (`ink.eraser.whole`, off by default). The gesture snapshots it on pointer-down like the radius.
- On: a stroke the eraser touches (within its radius of the painted edge, the same reach as a cut) goes all of it. Unsaved draft strokes go at once; saved ink goes when `comment_erase` with `"whole": true` lands, one undo step per gesture. Strokes in the same drawing that the eraser misses keep their exact points; a drawing with none left is deleted.
- The live rub-out view is not used in this mode, since it shows only the part under the eraser.
- Engine: `Edit::EraseInk { whole }` applies `pdfcraft_annot::erase_ink_strokes`; `erase_ink` keeps its behaviour.
- Tests: annot `the_whole_stroke_eraser_removes_each_stroke_it_touches_and_keeps_the_rest`, Android Rust `the_whole_stroke_eraser_removes_a_touched_line_and_is_one_undo_step`, JVM `InkEraseCommandTest.theWholeStrokeEraserAsksForWholeStrokes`. Not yet checked on a phone.

## Exact cut (#676)
- The eraser cuts a stroke exactly where its circle meets the stroke's painted edge (its radius plus half the stroke's width there), in the engine for saved ink (`erase_ink_strokes`, `erased_span`) and in Draw for unsaved drafts (`eraseInkRun`, `erasedSpan`), with the same algorithm: along each segment it reaches, the distance to the eraser's path less the reach is convex, so the erased part is one span, found by golden-section search and bisection. Before, each such segment was sampled every half radius and cut at the last sample outside the reach, so a piece could stop up to half the radius short of the eraser's edge, and the samples stayed in the stroke as points.
- A cut adds two points only (one each side); widths (`/PCWidths`), a pencil's densities (`/PCDensity`) and a draft's scales are interpolated there. A piece shorter than 0.05 pt is dropped, and an eraser reaching less than 0.001 pt into a segment leaves it, so the next step of a gesture does not cut a piece's end again where the last one did.
- A small eraser over long strokes is no longer refused; the work bound is now the number of exact-cut searches in one call (1,000,000, about a second).
- Tests: annot `the_eraser_cuts_a_long_segment_exactly_at_its_edge` (a 200 pt segment erased with radius 5 is cut at 194 and 206, where sampling cut at 192.5 and 207.5) and `the_eraser_cuts_a_curved_pressure_stroke_on_its_reach` (each cut end within 0.05 pt of the reach, none of a piece under the eraser), JVM `InkDraftEraseTest` with the same 200 pt case. Not checked on a phone.

## Verification
- Pure JVM tests cover NaN/infinite preferences, clamped min/max settings, exact native eraser radius and rejection of invalid untrusted commands.
- Run exact-head Android JVM and Rust/provenance plus repository merge gate. Verify physical S Pen, eraser button, cursor-versus-erased area at multiple zoom levels, page switch, long gestures, highlighter partial erase, Undo/Redo and export/save/reopen on Samsung.
- No measurements of visual latency or physical device behavior are claimed from CI. This change must **not** be merged without passing gates and must not alter the in-progress signed `0.0.15-beta (15012)` release source. The feature belongs to a later release batch.
