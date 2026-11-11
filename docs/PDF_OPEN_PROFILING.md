# PDF opening latency: first-page baseline profiling

**Status:** Outstanding performance issue, **not resolved**. The owner reports that identical large PDFs still take noticeably longer to open in PdfCraft Android than Xodo. No repeatable Samsung device comparison has yet demonstrated an improvement.

This is diagnostic groundwork only. There are **no changes** to direct file access, working-copy durability, PDF parsing, decryption, signing or rendering algorithms in this instrumentation branch. Never infer a performance improvement from a successful CI run.

## Enable measurements on the Android device

```sh
adb shell setprop log.tag.PdfCraftOpen DEBUG
adb logcat -c
adb logcat -s PdfCraftOpen:D
```

Open one PDF in PdfCraft, wait for the first page to become visible, and capture the `PdfCraftOpen` stage lines. Repeat with ADB logging enabled and with logging disabled (default) to detect instrumentation overhead. Disable logging afterwards:

```sh
adb shell setprop log.tag.PdfCraftOpen INFO
```

Only fixed stage names, monotonic elapsed milliseconds, byte counts and page counts are logged. No filename, URI, password, contents or other identifying data should be included in issue reports or benchmark artifacts. Do not collect user documents without explicit permission.

## Stage meanings

| Marker | Clock and meaning | Caveat |
| --- | --- | --- |
| `provider_metadata duration_ms` | Query display name from SAF / content provider | Not included for an already-owned `file://` copy |
| `provider_descriptor_copy duration_ms` | Copy through seekable asset descriptor or bounded stream | Providers may supply pipes or remote files; stream fallback is allowed |
| `provider_stream_fallback duration_ms` | Retry via provider input stream after unsupported/failing descriptor | Source copied read-only; target is still a safe local working copy |
| `provider_and_working_copy duration_ms` | End-to-end access and safe working-copy preparation | Includes metadata and rename, not just bulk transfer |
| `working_copy_ready elapsed_ms` | Elapsed time from open tap to available working bytes | `bytes` is local PDF size, not heap allocation |
| `previous_document_closed elapsed_ms` | End-to-end elapsed after saving/closing an earlier open PDF, if any | A blocked write-back may dominate switches between two PDFs |
| `native_open duration_ms` | Open/decrypt/parse using Rust `MobileSession` | Only measures this engine call; not first page |
| `native_ready elapsed_ms` | Cumulative elapsed time after the Rust document is created | Includes safe copy, optional earlier document close and parse |
| `source_access_ready elapsed_ms` | End-to-end elapsed after Recent metadata/SAF write-capability checks | Isolates permission/provider query delay from native parsing and overlays |
| `widgets` / `comments` / `signatures duration_ms` | Separate overlay/validation operations | These are currently awaited before publishing viewer model |
| `overlays_ready elapsed_ms` | Cumulative elapsed time after overlay metadata | Compare with `native_ready` to identify blocking overlay work |
| `viewer_model_ready elapsed_ms` | First state publication of `OpenDocument` to Compose | **Not** the first visible page |
| `first_page_requested elapsed_ms` | First nonempty visible-page raster request sent by Compose | Layout and scheduling may delay this after model publication |
| `page_render duration_ms` | A whole page at the zoom-1 scale, for a visible page | Before 0.0.25 this name covered tiles, low-resolution pages and prefetches too |
| `page_render_lowres duration_ms` | A whole page at half scale, while zoomed far out | Many fit on screen; cheaper per page |
| `tile_render duration_ms` | One tile of a visible page at a deeper zoom level | Several per page when zoomed in |
| `*_prefetch duration_ms` | The same kinds, for pages just outside the view | Do not delay what is on screen unless the workers are busy |
| `slow_render_pixels count` | Pixels of the render just above it, when it took 250 ms or more | Tells a large tile or page from a slow small one (#236) |
| `first_page_raster_ready elapsed_ms` | First whole-page bitmap becomes available in image cache | Includes bitmap conversion, not GPU display |
| `first_page_canvas_ms` | First raster submitted to Compose Canvas | **Not** a guaranteed on-screen/GPU frame |
 
**Missing measurement:** verified GPU first-paint/presentation timestamp. Use an external Android trace or screen recording / Perfetto for reliable user-perceived first visible page. Do not mislabel the Canvas submission as GPU presentation.

## Comparison protocol (no fabricated numbers)

1. Choose a representative dataset with no sensitive content: small local PDF, large image-heavy PDF, encrypted/password PDF, many-page text PDF, and a remote/non-seekable SAF provider sample. If only private documents reproduce the issue, benchmark locally without uploading them.
2. Use one Samsung S24 Ultra or Galaxy Tab S11 model consistently. Record Android/One UI version, PDF size, page count, file provider, storage/cache state and whether the app was cold-started. Avoid switching providers between repetitions.
3. Record **at least five cold and five warm opens** per document for PdfCraft. Measure the first page's user-visible moment. For Xodo, repeat on the same file/device/provider, using consistent cold/warm start and screen recording or Perfetto.
4. Report median and p95 user-perceived time-to-first-visible-page and exact individual trials. Record per-stage PdfCraft traces; do not compare the developer's Apple Silicon warm parser timings directly against Samsung.
5. Distinguish file access, mandatory working-copy bytes, Rust parse/decrypt, overlays, rasterization and frame presentation. Choose the largest *measured* bottleneck before implementing a behavior change. Confirm the same-file improvement on device after the change.
6. Do not optimize by loading arbitrary PDFs fully in RAM, writing directly over a provider file, disabling signature checks, skipping forms/annotations, weakening recovery markers, or bypassing safe incremental saves.

## First follow-up decision

If `provider_and_working_copy` dominates, investigate read-only, seekable provider FD lifetime and provider-specific copy-on-write design in a separate implementation PR, preserving durable save/recovery and fallback for remote/unseekable providers. If `native_open` dominates, profile the Rust parser/decrypt and timestamp scanning with realistic protected fixtures. If `overlays_ready` is dominant, investigate deferred nonessential overlay calculation **without** exposing unsafe edit interactions or losing widget/signature behavior. If render/layout dominates, profile first-page scheduling and high-resolution tiles.

**Integration / release status (2026-11-06):** Diagnostic [PR #24](https://github.com/MohamedRef/pdfcraft-android/pull/24) merged to main as `8d2d17dc258415ae6263890e3def3412708968d2` after [exact-head CI 42266990919](https://github.com/MohamedRef/pdfcraft-android/actions/runs/42266990919) passed Rust/provenance, Android JVM and the aggregate merge gate. It merged **after** the signed [0.0.10-beta (10012)](https://github.com/MohamedRef/pdfcraft-android/releases/tag/v0.0.10-beta) release from `da4bc2881214080bd55eaf96a6a781808fb33093`. These timers are therefore on main but **absent from that APK**; use a later verified build containing #24 to collect these stage logs. Do not replace the published APK in place.

**Performance closure gate:** Device measurements remain mandatory. The merged timers do not establish a speedup. Implement a follow-up optimization in its own PR only after identifying the measured bottleneck, pass its current-head Rust/provenance and Android JVM gates, then repeat same-file Samsung/Xodo measurements. Leave the opening-latency issue outstanding until those results support closure.

**Published diagnostic build (2026-11-07):** [0.0.11-beta (11012)](https://github.com/MohamedRef/pdfcraft-android/releases/tag/v0.0.11-beta), source `d81ef5d9060a0008d798b7a1f26e571274655c4c`, now includes #24's stage timers. [Publishing CI](https://github.com/MohamedRef/pdfcraft-android/actions/runs/42345606702) and independent manifest/signature/hash checks passed. Use this verified build for device traces; the opening-speed objective is still unresolved.
