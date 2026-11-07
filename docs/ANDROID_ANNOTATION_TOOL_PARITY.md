# Android annotation tool parity: explicit shapes (Squid-inspired)

## Scope
Adds direct **Line**, **Rectangle**, and **Oval** choices beside **Freehand** in Draw, without replacing the existing pen, lasso, eraser, markup, three favorites or optional press-and-hold snap recognition. This is original PdfCraft UI/logic, not copied application code.

## Implementation
- Shape mode is stored per open document in `ViewerController`, and reset on leaving Draw.
- A gesture captures its mode, appearance and PDF page. Its preview updates in the Compose ink overlay. The final outline is converted to the existing native PDF Ink `comment_add` payload on commit, preserving editing/export pathways.
- Exact shapes are bounded to their source page. Tiny/invalid gestures are discarded. Freehand keeps existing stylus pressure behavior and snap-to-shape.
- Shape tools stay mutually exclusive with Lasso and Eraser. Hardware eraser still takes priority; touch routing is unchanged. Native ink remains undoable through the existing local/native undo sequence.

## Coverage / QA
Added deterministic unit tests for reversed drags, closed shapes, page clamps, invalid coordinates and tiny gestures. Existing ink appearance, lasso, pressure and shape-snap suites must pass. Run the repository's required **exact PR head** Android/JVM/ARM64 and aggregate merge gates before merging.

**Unverified without a real device:** Samsung S Pen palm rejection, hover/button, physical pressure, moving toolbar, zoom rotation, PDF reload, exported shape visual parity, accessibility and landscape/DeX. No claims about measured latency improvement.

## Next isolated PRs
1. Freehand marker/highlighter (not text selection), configurable translucent ink, appearance preserved across preview/commit/reload.
2. Distinct pen personalities, such as ballpoint/pencil/fountain/brush, requiring native rendering/style persistence review.
3. Adjustable object/stroke eraser, lasso resize/copy and saved-annotation selection, requiring engine support, separate from draft-only selection.
4. Compact tool picker and per-tool presets for S Pen tablets.

Do not merge this PR until all required checks pass; do not create a beta release for this individual tool. Bundle validated PRs into the next approved beta under the existing version policy, with the actual APK versionCode in parentheses.
