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

## Direct-access toolbar update (2026-11-08)

The Draw tool now opens a dedicated always-visible **two-row toolbar**, rather than the old single horizontally scrolling strip. Row 1 exposes **Ballpoint, Fountain, Pencil, Brush, Highlighter, Eraser and Lasso** with real tap actions, original recognizable nib silhouettes, full accessibility descriptions and clear selected states. Row 2 exposes all **three saved pens, Color, Pen Settings, Straight Line, Rectangle and Oval**. Both rows are independently scrollable when the device is too narrow, but never auto-jump when other modes are selected.

- On common phone widths >=336dp, all seven primary actions stay visible. Secondary actions need 384dp, otherwise only that row scrolls. No primary actions are hidden behind More.
- The drawing toolbar is 112dp high, and the page's viewer padding and color/settings sheet offsets are updated to avoid obscuring document content and action buttons. Other toolbars retain their existing height.
- Full tool descriptions remain in Compose semantics, with short readable visible captions. Existing app-owned Lucide icons remain under their ISC attribution; original pen nib artwork is built from Compose primitives. The UX is inspired by Tratto (MIT), Folio (Apache 2.0) and xnotes (MIT), but **their source code and proprietary app artwork were not copied**.
- Native PDF ink and v0.19.0 compatibility are unchanged. Jetpack Ink's more advanced brush/tilt/rendering behavior is a separate engine migration candidate, requiring measured latency, brush-output parity and annotation export/reopen testing before integration.

### Checklist

1. Verify 5 nibs selectable and immediately switch drawing style on S Pen and touch.
2. Verify Eraser and Lasso respond instantly, then return to any pen via direct one-tap selector.
3. Verify presets, Color, Pen Settings and all three shapes respond directly; no unsolicited toolbar auto-scroll.
4. Verify 320dp/360dp/400dp widths, accessibility scaling, Voice Access/TalkBack labels, keyboard focus, RTL and landscape.
5. Verify changing styles, erasing, undo/redo, dot taps, PDF save/reopen and hardware pressure on physical Samsung devices.
