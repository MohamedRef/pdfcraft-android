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

## Verification
- Pure JVM tests cover NaN/infinite preferences, clamped min/max settings, exact native eraser radius and rejection of invalid untrusted commands.
- Run exact-head Android JVM and Rust/provenance plus repository merge gate. Verify physical S Pen, eraser button, cursor-versus-erased area at multiple zoom levels, page switch, long gestures, highlighter partial erase, Undo/Redo and export/save/reopen on Samsung.
- No measurements of visual latency or physical device behavior are claimed from CI. This change must **not** be merged without passing gates and must not alter the in-progress signed `0.0.15-beta (15012)` release source. The feature belongs to a later release batch.
