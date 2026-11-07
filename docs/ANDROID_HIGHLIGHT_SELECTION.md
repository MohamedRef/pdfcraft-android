# Android text markup selection: tap and drag reliability (2026-11-07)

## Root cause

- Previous Markup onDown launched asynchronous glyph-count/anchor queries. If the finger lifted first, onUp cleared state before the anchor could arrive, so tapping Highlight frequently did nothing.
- Every pointer move cancelled and restarted a native query. onUp saved only the last completed preview, not the actual release endpoint; a cancelled query could restore stale preview after release.
- Unbounded nearestGlyph selected text even for a pointer in a blank page area or another line. No explicit word snapping or bounded native rectangle validation existed.

## Change

- A single tap highlights the complete word actually under or near the pointer; tap on blank areas remains a no-op. Empty text-layer PDFs give the existing OCR guidance on tap.
- A drag snaps to whole-word boundaries on the same PDF page in both directions. The wordAt hit test bounds the distance to real glyphs. Live preview draws an immediate touch-position cue and uses one conflated collector, and the final range always resolves from finger-up coordinates, regardless of preview timing.
- Cancellation, pen takeover, or a second navigation finger clears transient selection and rejects stale preview writes. The last valid preview stays drawn until the native comment_add edit finishes refreshing, so the highlighted region does not disappear while the PDF engine catches up. Completed gestures use view-model scope so they can commit even if Done is tapped quickly.
- A near-line drag endpoint may snap to its nearest glyph only after validating finite glyph geometry and a strict 40pt maximum tolerance. The text-selection handles retain a stable drag anchor as glyph ranges update, and handle lookups are conflated so stale selections cannot reappear after cancellation. Structural rectangle validation rejects malformed/oversized quad lists rather than producing partial PDF annotations. Existing comment_add, color, opacity, undo, native engine/signing/source-provider safety and PDF save format remain unchanged.

## Test coverage and device QA

- JVM tests: single-word tap, forward/reverse word range, cross-page/invalid ranges, ordered multiline quad geometry, nonfinite/oversized payload refusal, near-line glyph tolerance/invalid native geometry. Native transaction timing and finger/handle event delivery still require physical-device QA.
- Required PR CI: exact-head Rust/provenance, Android JVM and native ARM64 bindings, Conventional title, aggregate merge gate. No APK per PR.
- Manual device checks still required: rapid tap vs long-running first-text-layer parse, same-line and multiline drag in both directions, touch/S Pen, high zoom, blank margins, RTL text, switching tools mid-gesture, two-finger navigation, selection-menu highlight, undo/save/reopen on signed/read-only PDFs. Physical Samsung results are not inferred from JVM CI.

- Lasso PR #41 independently passed its exact-head Rust/Android/title/merge-gate CI and merged as `f4dc5b78b2405d4bbf8b8f42d25763209292cb9b`. Highlight PR #43 now targets main and requires fresh checks for its exact final head. Unrelated alternative draft lasso PR #42 is not part of this patch. One future beta only after the approved feature batch.
