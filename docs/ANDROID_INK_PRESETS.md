# Favorite pen presets

## Unlimited favorites, recent colours, hex entry, finer widths (#149)

Favorites are one list (`ink.presets.v2`, up to 48 entries) instead of three fixed slots; the three old `ink.preset.v1.N` slots seed the list once. In Pen settings, **Add current pen** appends the selected pen; a long press on a favorite offers Replace with current pen, Move earlier, Move later and Delete. The pen popover shows the last eight ink colours (`ink.recentColors`), each announced by its hex value, and a hex field that accepts `#RRGGBB` or `#AARRGGBB` and rejects anything else with a message. Pen widths now range from 0.1 to 20 pt in 0.1 pt steps. Still open in #149: eyedropper, HSV wheel and named palette import/export.

## Per-pen settings (#187, #188)

Each of the five pens (Ball, Fountain, Pencil, Brush, Marker) keeps its own colour, width and opacity under `ink.<style>.color`, `ink.<style>.width` and `ink.<style>.opacity`. Switching pens never restyles another pen, and each toolbar nib preview shows its own pen's colour and width. Fresh installs start from per-pen defaults (black ball, blue fountain, grey pencil, red brush, wide yellow marker). On the first launch after the update, the old shared `ink.color`/`ink.width`/`ink.opacity` move to the pen that was last used, so that pen looks the same; the migration is written immediately so it does not move again. A favorite is a full snapshot: using it restyles its own pen type and selects it.

Tapping the already selected pen opens a compact popover with that pen's width chips, a half-point slider, colours and opacity (the same second-tap pattern as the eraser size). The row-2 button is now **Settings** (sheet title **Pen settings**) and holds only Pen only, Snap to shape and favorites; the duplicated pen-style row, thickness, colour and opacity were removed from it.

Draw → Favorite pens offers three persistent slots containing ink color, width and opacity. Use pen applies the selected appearance; Replace with current pen explicitly saves the current drawing settings into that slot. Author, pen-only routing, pressure/history/hover, eraser mode and snap-to-shape remain independent. Existing strokes and PDF contents are not edited by choosing a favorite.

## Persistence and input boundaries

Each slot uses a bounded, versioned preferences string. Malformed/unknown/oversized values, invalid ARGB, non-finite widths/opacities and values outside existing mobile UI ranges fall back to that slot's factory preset. Existing global drawing preferences remain compatible. Saving a slot changes only that preset key; using it writes ink color/width/opacity in one preferences transaction. No document path, author or personal text is serialized in a profile.

The Draw layer's existing immutable appearance snapshot at stroke start is unchanged, so completed/in-progress stroke appearances are not retroactively changed by later settings. The preset picker scrolls for compact windows; the Draw toolbar already scrolls horizontally.

## Verification

JVM cases cover ARGB/width/opacity round-trips, corrupted/future/oversized/non-finite data and locale-independent storage. Existing ink appearance, pressure, stylus routing/history and eraser regressions remain intact. Current-head Rust/provenance, Android JVM/ARM64 bindings, title and aggregate gate are required before merge. Physical S Pen behavior, app restart, RTL/accessibility, DeX/landscape and switching favorites during drawing still need device QA. These presets are one part of the larger stylus backlog, not full Xodo parity.
