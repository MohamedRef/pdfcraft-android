# Conservative two-stage Android opening

The Android page viewer can publish a read-only reading stage before full document preparation. It uses exact renderer geometry, the same immutable input bytes and the original stored page artwork. The complete preparation still inspects the document, discovers and validates signatures, builds missing appearances and adopts page-only form fields. No root-level absence test is used to declare forms or signatures absent.

## Eligibility and appearance safety

- Android chooses this path for inputs of at least 16 MiB, ordinary page viewing, and no pending editing tool. Reopening directly into reflowed Reading Mode retains complete opening.
- A repaired editing source, renderer recovery warning, XFA entry, nonempty or unreadable form-field tree, unreadable form catalog or mismatched page count falls back to complete opening. Owner-password cases that need the renderer's recovered user password keep the complete path.
- The initial page, including a restored page, must have no annotations or only readable Link dictionaries. A page with widgets, comments, unknown annotations or more than 4,096 annotation entries waits for complete preparation. Later pages receive the same check before provisional rendering. This deliberately favors correctness over extending the fast path to every PDF.
- Stored dimensions and rotations are exact for all pages. No approximate page sizes, reduced-color bitmaps or altered annotation artwork are introduced.

## Pending state and transition

`DocumentInfo.preparing` means metadata and signature results are not ready. Printing, copying, editing, saving and generic tool execution are refused at the native boundary during this stage; the UI shows preparation status rather than claiming the document is unsigned. The signature and properties panels also retain an explicit pending state.

The UI schedules completion after the first canvas submission, with a bounded wait if no canvas arrives. Full preparation runs outside both the native session lock and Kotlin's serial command dispatcher. Rendering takes the session lock only for its normal snapshot lookup. Completion keeps the document id and exact geometry, replaces the reading stage only if its original byte identity still matches, and advances the generation so page and link caches refresh. Existing viewport state is retained.

Closing cancels pending preparation and removes its handle. Cancellation is checked at major native preparation boundaries, not by forcibly interrupting an active parser or signature operation. A late completion cannot register a new document, revive a closed handle or overwrite a ready document. Late renderers are not returned to a closed or superseded source cache.

## Diagnostics and limits

`native_reading_prepare` and `native_reading_ready` describe the early stage. The existing `native_ready` is emitted after full preparation completes. `first_page_raster_ready` remains the first raster milestone; canvas submission is not proof of GPU presentation. Nested and parallel stage durations still overlap.

Input is read once through the existing confined, size-capped reader and shared using `Arc<Vec<u8>>`. This change does not introduce mmap, eliminate the input copy, reduce total inspection work or guarantee physical-device latency. It does not change the existing complete-open API or publish an APK.

Validation covers pixel and geometry equivalence, source-byte preservation, mutation refusal while pending, orphan widgets, standalone timestamps without AcroForm, initial-page fallback, encrypted permissions, XFA/recovery fallback, cancellation and stale results. Host and CI results must be distinguished from physical Android behavior.

## Host validation record

The parser, signatures, forms, engine, renderer, automation and mobile suites passed 2,684 tests, with zero failures and 8 existing ignored tests. The 12 new regressions include late-renderer cache cleanup and a tree-listed widget omitted from page annotations. Source provenance verification also passed.

A manual warm host comparison used an ordinary-xref synthetic PDF of 356,225,451 bytes, 869 pages, 32 unique links per page (27,808 total), and one raw 400×337 RGB image per page. `progressive_reading_timings` first performs an unmeasured complete open/render to warm filesystem and renderer initialization, then three paired trials, complete opening first in each pair. Both paths render page 0 at scale 1.0 (612×792). The repository dev profile is used.

| Trial | Complete open to first raster | Reading stage to first raster | Reading path through full readiness |
| --- | ---: | ---: | ---: |
| 1 | 655 ms | 207 ms | 922 ms |
| 2 | 715 ms | 201 ms | 669 ms |
| 3 | 727 ms | 214 ms | 673 ms |
| Median | 715 ms | 207 ms | 673 ms |

These sequential host trials illustrate early availability, not an Android performance guarantee, a GPU-presentation measurement or proof of reduced total CPU work. Background preparation was invoked immediately after the native raster in this host comparison; Android waits for canvas submission or its bounded fallback. The input copy remains. The manual reader is `apps/pdfcraft-android/examples/progressive_reading_timings.rs`; no original document or user timing report is stored here.
