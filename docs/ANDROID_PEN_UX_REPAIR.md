# Pen UX and functional annotation repair — 2026-11-08

## Why this repair exists

The new pen styles shipped in 0.0.14-beta (14012), but the pen picker reused a grey triangular nib for all five tools, kept important actions off-screen and allowed conflicting selected controls to auto-scroll the toolbar. A pen style selection did not exit Eraser/Lasso, and freehand tap-only gestures were removed because they had only one PDF Ink coordinate. Users could also be stuck in hidden stylus-only mode and see no finger strokes.

## Changes on this feature branch

- Five original, visually distinct nibs: round ballpoint, metal fountain nib, graphite pencil, brush bristles and broad rectangular highlighter; previews use the live chosen ink color/width/opacity.
- Pen and Highlighter are separate primary toolbar actions, and Pen restores the last non-highlighter nib. Shape tools restore writing ink. Eraser, Lasso and unified Pen Settings are promoted ahead of optional shapes.
- Applying a pen or saved favorite closes the picker and returns from Eraser/Lasso/Shapes to freehand drawing. Remaining shape controls scroll horizontally if needed.
- Expose Pen-only and Snap-to-shape states in the same picker as pen settings, with clear touch-vs-S-Pen explanation. No hidden input mode.
- Replace the competing auto-scroll-to-selected behavior with stable, accessible selected states and 54dp slot widths with 54dp height.
- Preserve one-tap pen marks as tiny two-point round-capped strokes accepted by the existing native `comment_add` pathway; reject invalid single-point coordinates. Preserve normal multi-point strokes and per-gesture pressure sampling.

## Verification and remaining limits

- New JVM unit regressions cover single-tap dot behavior and malformed coordinates. Existing ink pressure/preset/native highlighter suites must also pass.
- No native engine, serialization identity, package version, signer, SAF save flow or release changes.
- This does **not** introduce proprietary Photoshop-style textured brush physics, full saved-annotation editing or guarantee parity with Samsung Notes hardware feel. Touch/S Pen, pressure, 1-point dot after save/reopen, highlighter/eraser, varying zoom, tablet and DeX still need real-device verification.
- Follow merge rules: separate PR, exact-head Android JVM and Rust/provenance CI, title and merge gate; do not merge on failed/pending tests. No independent beta for this single feature.
