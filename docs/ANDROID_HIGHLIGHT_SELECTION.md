# Android text markup selection: tap and drag reliability (2026-11-07)

## Root cause

- Previous Markup onDown launched asynchronous glyph-count/anchor queries. If the finger lifted first, onUp cleared state before the anchor could arrive, so tapping Highlight frequently did nothing.
- Every pointer move cancelled and restarted a native query. onUp saved only the last completed preview, not the actual release endpoint; a cancelled query could restore stale preview after release.
- Unbounded nearestGlyph selected text even for a pointer in a blank page area or another line. No explicit word snapping or bounded native rectangle validation existed.

## Change

- A single tap highlights the complete word actually under or near the pointer; tap on blank areas remains a no-op. Empty text-layer PDFs give the existing OCR guidance on tap.
- A drag snaps to whole-word boundaries on the same PDF page in both directions. The wordAt hit test bounds the distance to real glyphs. Live preview uses one conflated collector, and the final range always resolves from finger-up coordinates, regardless of preview timing.
- Cancellation, pen takeover, or a second navigation finger clears transient selection and rejects stale preview writes. Completed gestures use view-model scope so they can commit even if Done is tapped quickly.
- Structural rectangle validation rejects malformed/oversized quad lists rather than producing partial PDF annotations. Existing comment_add, color, opacity, undo, native engine/signing/source-provider safety and PDF save format remain unchanged.

## Test coverage and device QA

- JVM tests: single-word tap, forward/reverse word range, cross-page/invalid ranges, ordered multiline quad geometry, nonfinite/oversized payload refusal.
- Required PR CI: exact-head Rust/provenance, Android JVM and native ARM64 bindings, Conventional title, aggregate merge gate. No APK per PR.
- Manual device checks still required: rapid tap vs long-running first-text-layer parse, same-line and multiline drag in both directions, touch/S Pen, high zoom, blank margins, RTL text, switching tools mid-gesture, two-finger navigation, selection-menu highlight, undo/save/reopen on signed/read-only PDFs. Physical Samsung results are not inferred from JVM CI.

- Stacked on lasso PR #41 until that independent feature has passed and merged. Retarget this PR to main and rerun the current-head gates if the tested tree changes. One future beta only after the approved feature batch.
