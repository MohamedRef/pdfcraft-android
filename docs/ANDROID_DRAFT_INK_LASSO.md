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

## Erasing drafts and the lasso's buttons (0.0.33-beta log)

- The eraser no longer saves the drafts before it erases. It cuts draft strokes in the app, at
  once, the way the engine's `comment_erase` cuts saved ink (to the painted edge, split where it
  crosses); the cut is one Draw undo step (`DraftInkEdit.Erased`), and a cancelled gesture puts
  the drafts back. The engine erases only saved ink, and ink still being saved. Before, the first
  erase after writing waited for every stroke to be saved, most of a second each on an 869-page
  document, with nothing erased on screen meanwhile. `InkDraftEraseTest` checks the cut.
- A touch on the lasso's Delete, Link or Recolour belongs to the button. The page under it used
  to take the same touch as the start of a new lasso, which cleared the selection, so Delete had
  nothing left to delete.

## Resize and turn (#689)

- A lasso selection shows two handles while it stands still: one at the bottom-right corner of its box, and one above the middle of its top edge, on a short stalk. A touch within 18 dp of a handle takes it (before a move, since the corner is also on the box).
- **Resize**: dragging the corner scales the selection about the opposite corner, keeping its proportions (how far along the box's diagonal the handle went), from 0.05 to 20 times. **Turn**: dragging the top handle turns the selection about the box's middle, clockwise as the hand goes; within 4° of a quarter turn it is that quarter turn.
- While a handle is dragged, the selected drafts are drawn turned and scaled (their line widths too), saved comments' frames follow, and the box is drawn where it is going; the lasso's buttons hide. Nothing changes until the handle is let go; a cancel changes nothing.
- On release, the selected drafts are replaced by turned and scaled copies whose pen width is scaled the same (so shrunk handwriting is not drawn bolder), as one Undo step that puts the old ones back; they stay selected. Saved comments are turned and scaled by the engine in one undo step ("Resize comments" / "Turn comments": `comments_edit_many` `transform`, `pdfcraft_annot::transform_annotation`): drawings, lines, connected lines and polygons turn and scale with their width; rectangles, ovals, text boxes and stamps are scaled but left out of a turn, with "Rectangles, ovals, text boxes and stamps are resized, not turned."
- Log: `lasso_transform` (`handle`, `scale`, `angle` in whole degrees, `drafts`, `saved`, `not_turned`).
- Tests: annot `a_drawing_turns_and_scales_about_an_anchor_with_its_width` (a quarter turn at twice the size and back, a pressure drawing's widths per point kept, a rectangle scaled but refused a turn, a line turned about its middle, nonsense refused); Android Rust `lasso_turns_and_resizes_many_comments_as_one_undo_step` (view coordinates, one undo, a refused turn changing nothing); JVM `InkLassoTransformTest` (handle places and hits, proportional scale and its bounds, turn angle and snapping, the same turning direction as the engine, a draft's copy with its width scaled and the original left for Undo). Not checked on a phone: the handles' size under a finger and the S Pen, a turn's preview cost on a big selection.
