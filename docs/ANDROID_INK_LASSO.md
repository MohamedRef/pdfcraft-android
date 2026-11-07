# Android Draw lasso selection — first increment

## Scope

Draw → Lasso selection draws a closed region on one PDF page and highlights only the **complete, still-uncommitted** pen strokes entirely inside it. Each selection is in PDF page coordinates, so pan/zoom does not change the selected geometry. A new lasso replaces the previous one. Switching Draw subtools clears the transient selection. Completed saved PDF annotations, text markup, signatures, forms, voice comments and other content are not altered.

This first increment is deliberately **selection only**. It does not claim drag/move, resize, delete, grouping across pages or persisted-annotation lasso support; those are separate next increments requiring undo/redo and native PDF integration. The selected bounding boxes are visual previews, not actual PDF annotations.

## Safety

- Select only whole strokes with two or more points, never partial segment edits.
- Reject degenerate lassos and nonfinite page coordinates. Bound gesture sample buffers to 512 points.
- Lasso selection does not affect the native document, existing pending-ink stroke order or pen appearance, pressure samples, undo/redo or SAF recovery.
- A cancelled gesture clears the lasso path. Selection cannot silently modify a previously saved PDF.

## Verification

Four JVM regressions cover contained/border/partially excluded strokes, invalid polygons, finite translation coordinates and page bounds. Required PR gates: Rust/source provenance, Android JVM and aggregate merge gate at exact final PR head. Physical Samsung S Pen, rotated/cropped pages, touch navigation, accessibility, small windows and saving/reopening still need hands-on validation. No APK per feature; one release after the agreed three-feature batch.

## Next increments

1. Move selected pending strokes with a single undoable transaction, while preserving original pressure and paint order.
2. Resize selected strokes and expand to safe native saved-PDF Ink selection/editing after the engine/API contract is verified.
