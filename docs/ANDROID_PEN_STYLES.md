# Android stylus nib profiles and native freehand marker

This is an original, Samsung Notes-inspired tool experience, not a copy of proprietary assets or effects.

## Five functional pen profiles
- **Ballpoint:** preserves existing S Pen pressure response and stored PDF Ink output. This is the default for existing users.
- **Fountain:** pronounced pressure profile with a modestly wider nib.
- **Pencil:** narrower, lighter-opacity graphite-like stroke (no raster paper/grain texture is claimed).
- **Brush:** wider, pressure-responsive ink stroke.
- **Highlighter:** broad, translucent *freehand* markup over any page, including scans. It uses the native engine's `comment_add` type `freehand_highlight` rather than the text-only highlight selection tool.

The on-screen drawing overlay and native PDF annotations use the same style-specific width runs and opacity multipliers. All width runs remain capped by the existing 64-run bound per stroke, pressure/author input is captured at gesture start, and ink-order batching keeps styles separate.

## UI and favorites
- Draw > Favorite pens includes a horizontally scrolling nib-type rail with five choices, stylus tip illustrations, 3 style-aware favorites, a slider and sample stroke, color swatches and opacity control.
- The three persisted appearance slots now store `v2|argb|width|opacity|style`. Existing `v1` slots decode as Ballpoint and do not lose saved colors or widths. Unknown or corrupted v2 styles are rejected.
- The icon and stroke-preview illustrations are drawn with Compose primitives or project-owned icons, no third-party application artwork.

## Eraser parity
- The Android eraser opts in to `erase_highlights=true` on `comment_erase`, which targets unlocked FreehandHighlight Ink strokes in addition to ordinary Ink. Desktop callers retain the prior default; signatures and locked annotations remain protected.
- Added a native regression test for partial freehand highlight erasure, preserving `/IT /FreehandHighlight` after PDF save/reopen. Physical Samsung confirmation is still needed.

## Limits and verification
- Width-run Ink is an approximation of pressure in PDFs, not a variable-width vector brush format. Pencil does not contain grain texture; Fountain does not use a calligraphic angle. On reopening a PDF, width and opacity appearance persist in native annotations, while the original UI nib-type choice is not a portable PDF property.
- Native `freehand_highlight` uses the engine's supported annotation shape. Confirm device preview/export and eraser feedback on a physical S Pen, and verify native PDF reopen before describing full parity.
- Unit tests cover v1/v2 presets, profile differences, pressure, constant-width marker and bounded native width runs. Exact-head Android JVM/Rust/build and signed APK gates required before merging.
- This is a stacked PR built on the shape/toolbar PR #48. Merge #48 first after green checks, then retarget this PR to `main`, verify and merge. No standalone beta for this feature.
