# Draw's shapes

The Shapes sheet holds Line, Rectangle, Oval, Arrow, Cloud, Polygon and Connected lines (each saved as its own PDF annotation, #170, #133), and, since #693, a **More shapes** button that opens a sheet of more shapes, each drawn by one drag across its box.

## More shapes (#693)

**Flat shapes**, saved as native annotations other readers can edit:

- Triangle, Right triangle (the right angle at the bottom left), Diamond, Pentagon, Hexagon: a `Polygon` with those corners, the regular ones stretched to fill the box; reshaped by their corners like the polygon tool's.
- Double arrow: a `Line` with an open arrowhead at each end, in the direction it was dragged.
- Axes: an L of connected lines (`PolyLine`) up the left side and along the bottom, an arrowhead at each end.

**Solids**, drawn in an oblique view (depth 0.3 of the box's shorter side, up and to the right; flat ellipses 0.15 of its height): Cube, Prism (triangular), Pyramid (square base), Cylinder, Cone, Sphere (with its equator), Hemisphere, Frustum, Tube (a hollow cylinder). Each is saved as two drawings (`Ink`): its visible edges solid and its hidden edges dashed (`/BS << /S /D /D [3w 2.5w] >>`, at least 2 pt each, w the pen width), added together as one undo step ("Add shape", the engine's `comments_add`). They move, recolour, resize and erase as drawings. Each edge is saved with a point at least every 3 pt (`CATALOG_INK_STEP`) and each ellipse with enough points that no piece is longer (#701): saved ink is smoothed through its points like handwriting, and a cube whose edges held only their corners came out with bowed edges and rounded corners (seen on the owner's tablet with 0.0.43-beta (43012)).

While the box is dragged the shape is drawn as it will be saved, hidden edges dashed. The sheet's buttons and the bar's shape button show pictures drawn from the same geometry. The shapes take the pen's colour, opacity and width.

Engine (#693): `comment_add` takes `dash` (1 to 4 lengths in points) for drawings, lines, arrows, rectangles, ovals, polygons, clouds and connected lines; the appearance already drew `/BS /S /D` and keeps it when redrawn. `comments_add` adds several drawn comments as one undo step, all or none.

Log: the `shape` line's `type` names the shape (`triangle`, `cube`, …).

Tests: annot `a_dashed_shape_is_drawn_dashed_and_stays_dashed`; Android Rust `a_shape_in_parts_is_added_as_one_undo_step`; JVM `InkShapeCatalogTest` (every shape fills its box and no more, too small refused, flat shapes have no hidden edges and solids have both, the cube's hidden edges meet at its back corner, the saved commands of a triangle, hexagon, axes, double arrow and cube, and #701's `aSolidsSavedEdgesStayStraight`: every solid's saved pieces at most 3 pt long, its corners kept and every point on its own edges) and `DrawToolbarLayoutTest.moreShapesSheetShowsEveryShapeAtEveryWidth`. Tried on the owner's tablet (SM-X730) with 0.0.43-beta (43012): the More shapes sheet, a cube (bowed, #701), a cylinder and a triangle, drawn with injected touches; the fix for #701 is not yet tried on a device. Not checked: how the solids read with the S Pen, and the sheet on the owner's phone.

Not done: dashed lines for the first seven shapes following the pen's Line choice (#682); shading or filling solids; turning a solid in 3D after it is drawn.
