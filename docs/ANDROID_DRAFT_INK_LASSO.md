# Android draft ink lasso (first stage)

## Scope and safety

Draw now exposes an explicit **Lasso** subtool beside Pen and Eraser. A freehand loop can select still-uncommitted ink strokes on one source page. Drag from within a selected group's bounds to preview and then commit a rigid, page-clamped move. Only temporary strokes owned by the open Draw layer are touched. Existing native PDF annotations and selected comments stay entirely unchanged.

The selection is a transient UI state, not a native PDF entity. Changing mode does not turn preexisting PDF ink into editable strokes. Leaving Draw commits the remaining draft ink through the existing native `comment_add` path, maintaining style, opacity, width-run pressure representation and source page. The existing stylus-only and palm navigation routing is reused; hardware eraser still takes its established path. A lasso gesture does not run a native engine command.

Undo and Redo now share one ordered local edit history across newly drawn strokes and completed group moves, with engine Undo/Redo fallback after the local stack is exhausted. Applying a move clears the redo branch; an interrupted move discards its preview. Committing the local strokes clears its history.

## Geometry constraints

- The live polygon retains no more than 128 finite positions, decimating intermediate points but retaining its endpoints.
- A selected stroke must have at least two points and either enter or intersect the lasso polygon, including boundary crossing without endpoints inside.
- Only strokes with the same source page are included.
- A group translation is clamped as a whole to that page's document-point bounds. It never splits strokes or moves ink to a second page.
- No document edits occur while the user is still dragging. The visual overlay marks the selection and a temporary transform preview.
- A malformed or interrupted gesture is not allowed to create draft edits.

## Coverage and limitations

`InkLassoTest` checks containment and crossing, invalid/incomplete inputs, rigid group clamps, disjoint bounds and a 4,000-point input decimated to a bounded lasso. Existing pressure, eraser and ink appearance regressions must still pass on the **exact PR head**.

This is **not** full parity with desktop ink editing: selecting already-saved PDF Ink annotations, resize, rotation, nested groups, per-stroke handles and copy/paste remain separate future work. Native saved-ink editability requires engine-level edit and Undo contract review. Physical Samsung S Pen hover/button/palm, Cancel/back, scrolling, device rotation, accessibility and save/reopen checks are not covered by JVM tests. No APK is published for this PR.
