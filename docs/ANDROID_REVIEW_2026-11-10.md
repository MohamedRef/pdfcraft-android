# PdfCraft Android — code review and critique (2026-11-10)

Scope: whole repository on `main` at `6e424f2` (0.0.15-beta), with focus on pens/handwriting, annotation completeness against Flexcil / Squid / Xodo, and performance. This is a source review. Nothing here was measured on a device; items marked **(device)** need an S Pen / Samsung trace to confirm.

## Verdict

The engine side is strong. It is a large pure-Rust PDF stack with real provenance discipline, bounded inputs and thorough tests. Save/recovery, signing and the release pipeline are more careful than most Play-store readers.

The **handwriting layer is the weakest part**, and it is what users of Flexcil/Squid notice first. The feature list looks complete:

- pens, pressure, a lasso, an eraser, shapes, presets.

The way the strokes are produced and stored has structural limits:

- latency;
- one stroke becoming many annotations;
- a preview that differs from the saved result;
- a lasso that only works on unsaved ink.

These limits feature additions won't fix.

## 1. Pens and writing

| # | Finding | Impact | Status |
|---|---|---|---|
| P1 | Every frame rebuilt a screen-space polyline for every point of every uncommitted stroke (`Tools.kt` DrawLayer Canvas), plus width runs. Drafts stay uncommitted until leaving Draw. | Writing gets slower the more you write in one session. Most likely cause of "lag after a page of notes". | **Fixed in PR #63** (cached page-space paths, transform for zoom/scroll). |
| P2 | Preview drew straight `lineTo` segments; the engine saves Catmull-Rom curves. | Stroke visibly changes shape on commit; jagged preview at zoom. | **Fixed in PR #63**. |
| P3 | A pressure stroke is saved as up to **64 separate Ink annotations** (`inkWidthRuns`: one PDF `/BS` width each). | Tapping a saved stroke selects one fragment. Move/recolor/erase act on fragments. The comment list fills with "Ink". The file grows. Translucent ink shows darker beads at every joint in the **saved PDF** (overlapping caps, each with its own `/CA`). | Open. Needs an engine adaptation: one Ink annotation per stroke with a variable-width appearance (filled outline, or per-segment widths in a transparency group). Keep `InkList` for interop, and keep widths in a private key so `comment_edit` regenerates the same look. **Highest-value next PR.** |
| P4 | Pressure is quantized to 8 levels, then capped at 64 runs. | Visible stepping on long, varied strokes; fountain/brush look coarse. | Solved by P3 (per-point width). |
| P5 | Historical pen samples get *interpolated* pressure (Compose `HistoricalChange` has no pressure). | Fast strokes lose real pressure detail between frames. | Open. Read `MotionEvent.getHistoricalPressure` through a `pointerInteropFilter`/`motionEvent` path for stylus only. |
| P6 | No motion prediction or front-buffered (low-latency) drawing. Ink is drawn in the normal Compose frame. | One or two frames more latency than Squid/Flexcil, which use low-latency rendering. **(device)** | Open. `androidx.input:input-motionprediction` (`MotionEventPredictor`) for the wet stroke. Later, `androidx.graphics:graphics-core` `CanvasFrontBufferedRenderer` for the stroke in progress. Adds dependencies, so needs owner approval. |
| P7 | Lasso selects only **uncommitted** draft strokes. Saved ink can only be moved one annotation at a time; ink is excluded from resize. | Core Flexcil/Squid gesture missing: lasso → move/resize/recolor/copy/delete existing handwriting. | Open. Engine already has `comment_edit` (`move`, colour, width) and `comments_copy/paste`. Needs lasso hit-testing on saved Ink geometry (`comment_list`) and a batched multi-comment edit, with one undo step. |
| P8 | Eraser commits all drafts first (`beginErase`), then runs a native erase per gesture. Each erase bumps the document generation. | Every erase re-renders the whole viewport, including tiles (see R1). At zoom this is a visible blur flash. | Partly R1. |
| P9 | No "zoom box"/writing window, no auto page-advance while writing. | Expected by Flexcil/GoodNotes users on phones. | Feature gap. |

## 2. Annotation completeness vs Xodo / Flexcil / Squid

The **engine already supports** most annotation types. Many are not exposed in the Android UI.

| Capability | Engine | Android UI |
|---|---|---|
| Highlight / underline / strikeout | yes | yes |
| Squiggly underline | yes (`comment_add` squiggly) | **no** |
| Freehand highlighter, pens, shapes (line/rect/oval) | yes | yes |
| Arrow / line endings | yes (`arrow`, `endings`) | **no** (only plain line) |
| Polygon / polyline / cloud | yes | **no** |
| Callout | yes | **no** (listed in comment list only) |
| Stamps (standard + dynamic) | yes | **no** |
| Text box, sticky note, voice note | yes | yes |
| Area highlight on scans/images (rect highlight without text) | rect via `quads` | **no** |
| Saved-annotation multi-select / lasso | engine edits exist | **no** (P7) |
| Resize ink / lines | `comment_edit points/rect` | ink excluded |
| Measurement (distance/area) | `crates/measure` | **no** |
| Insert blank/note pages | organize tools | check; not in Draw flow |

Cheap, high-visibility batch:

- arrow;
- squiggly;
- stamps picker;
- polygon/cloud;
- callout.

All of these are UI over existing engine tools; no engine change is needed.

## 3. Performance

| # | Finding | Impact |
|---|---|---|
| R1 | Page images are keyed by the **document-wide** edit generation (`ImageKey.gen`). Any edit invalidates every page and tile. Missing tiles are skipped (`PageCanvas.drawPages` `?: continue`), so after each stroke commit, erase or comment edit, zoomed pages fall back to the low-resolution base image until tiles re-render. | Blur flash and wasted raster work after every edit. Fix: a per-page generation in Kotlin, bumped only for pages a tool touched (`page` arg / result); keep old tiles as stand-ins until replacements arrive. |
| R2 | Every edit produces new `display` bytes; `return_renderer` discards pooled renderers when the bytes `Arc` changes, so the next render builds a new `PageRenderer` and re-parses the file. Open of the 352 MB / 869-page fixture measured ~1.6 s. | Each committed edit on a large file may pay a re-parse before any page redraws **(device)**. Measure first; mitigation belongs upstream-compatible (incremental reparse of the appended update). |
| R3 | Two render workers share one heap budget, plus a 4 MP page cap and 1 MP tiles. | Reasonable. Watch `trim()` on `onTrimMemory`. |
| R4 | `AppViewModel.kt` is 3,592 lines and 228 functions, and owns open/save/tools/signing/voice/OCR/edit. | Not a runtime cost, but recomposition scope and review risk are high. Split by feature (Ink, Signing, Voice, Edit) as code is touched. |

## 4. Engine sync

Upstream desktop **v0.20.0** (2026-11-09) is imported on branch `engine/upstream-0.20.0`:

- three-way merge, all 27 Android adaptations kept;
- five new tools: object copy/paste, hairline fix, text prepare, layout-table to blocks;
- the provenance verifier passes.

See its PR and upgrade note for test evidence.

## 5. Recommended order

1. Engine v0.20.0 PR (in progress) — merge when CI is green.
2. PR #63 draft ink performance/smoothing (open).
3. **Single-annotation variable-width ink (P3/P4)** — engine adaptation plus migration of the Kotlin commit path.
4. Per-page render generation (R1).
5. Expose existing annotation types (arrow, squiggly, stamps, polygon/cloud, callout).
6. Lasso on saved annotations (P7).
7. Real historical pressure (P5), then motion prediction / front buffer (P6), with device latency traces.

Each item is an isolated PR with JVM/Rust tests; device validation (S Pen pressure, palm, zoomed writing, save/reopen in Xodo/Acrobat) stays required before calling any of them done.
