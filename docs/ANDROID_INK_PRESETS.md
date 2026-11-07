# Favorite pen presets

Draw → Favorite pens offers three persistent slots containing ink color, width and opacity. Use pen applies the selected appearance; Replace with current pen explicitly saves the current drawing settings into that slot. Author, pen-only routing, pressure/history/hover, eraser mode and snap-to-shape remain independent. Existing strokes and PDF contents are not edited by choosing a favorite.

## Persistence and input boundaries

Each slot uses a bounded, versioned preferences string. Malformed/unknown/oversized values, invalid ARGB, non-finite widths/opacities and values outside existing mobile UI ranges fall back to that slot's factory preset. Existing global drawing preferences remain compatible. Saving a slot changes only that preset key; using it writes ink color/width/opacity in one preferences transaction. No document path, author or personal text is serialized in a profile.

The Draw layer's existing immutable appearance snapshot at stroke start is unchanged, so completed/in-progress stroke appearances are not retroactively changed by later settings. The preset picker scrolls for compact windows; the Draw toolbar already scrolls horizontally.

## Verification

JVM cases cover ARGB/width/opacity round-trips, corrupted/future/oversized/non-finite data and locale-independent storage. Existing ink appearance, pressure, stylus routing/history and eraser regressions remain intact. Current-head Rust/provenance, Android JVM/ARM64 bindings, title and aggregate gate are required before merge. Physical S Pen behavior, app restart, RTL/accessibility, DeX/landscape and switching favorites during drawing still need device QA. These presets are one part of the larger stylus backlog, not full Xodo parity.
