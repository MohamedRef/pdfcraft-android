# Large-file opening work plan

The implementation is a sequence of independently tested pull requests. A completed phase does not mean the entire plan is finished. Keep merge order and pass exact-head Rust/provenance and Android JVM gates for every PR. A beta may contain only the verified phases, and must explicitly identify unfinished work.

| Area | Work remaining | State |
| --- | --- | --- |
| Reading-stage coverage | Accept explicit null form entries, classify bounded field trees by affected page, preserve invisible-signature validation, and report fixed fallback reasons | Merged in PR #225; exact-head CI passed |
| Per-page overlays | Load visible-page comments and widgets first; retain complete lists on demand, with generation checks and refresh behavior | Merged in PR #226; exact-head CI passed |
| Rendering and memory | Prioritize visible rendering, limit initial prefetch, request large heap with actual-runtime cache budgets, and provide an opaque RGB565 option with correct native transfer/accounting | Merged in PR #227; exact-head CI passed |
| Complete-open passes and annotated pages | Reading stage accepts pages whose comments carry their own appearance; editing cache keeps a file's annotations within its 32 MB cap; signature discovery and appearance/form discovery overlap the renderer inspection; integer and name parser fast paths | Merged in PR #231; exact-head CI passed; host measurements only |
| Shared input bytes | Audit managed-file immutability and all save paths, introduce a shared byte source, implement read-only mapping with owned fallback and atomic replacement, and test mapping lifetime through save/close/reopen | Shared byte source and private read-only mapping in PR #230, integrated with #231; atomic publication merged in PR #228 |
| Annotation passes during complete open | Keep the editing cache's parsed annotations across signature, appearance and form-discovery passes (#239); inspection (1.2–1.9 s on the owner's phone in 0.0.23) remains the longest stage | Merged in PR #249; host measurements only |
| Inspection and display copy | Inspection sub-stages and off-path release of inspected objects (#284, PR #310, merged); display copy drawn beside inspection (#317, PR #318, merged); display copy of a mapped working copy spliced onto the file instead of copied, no second full-size allocation (#234) | Host measurements only |
| Open in place (#453) | A provider's regular file is mapped from its descriptor instead of being copied into `files/Documents`; incremental saves are appended to the original (never truncated while mapped); a save that rewrites the file is written at close; no copy is kept after close, and copies earlier versions kept are removed. Diagnostics switch "Open PDFs in place" to compare with the copy path | Host tests only; not measured on a phone |
| Integration and release | Re-run complete suites, provenance, Android JVM and release gates; document measured results and remaining physical-device limits | 0.0.22-beta (22012) covers verified reading/overlay/render/memory work, the complete-open and annotated-page work (#231) and the atomic-publication prerequisite; shared-input integration and physical-device measurements remain pending |

The already published reading stage is deliberately conservative. Rejected attempts must have diagnostic reason markers instead of silently looking like a successful progressive open.

Signature absence must not be inferred from a missing AcroForm: standalone timestamps and page-only widgets are regression cases. Signature state remains pending until complete discovery and validation. Neither elapsed-time thresholds nor a two-point page-count comparison prove constant-time complexity; use bounded-work counters and behavior checks alongside manual timing measurements.

Memory mapping must retain an immutable underlying file for its entire lifetime. Verify regular-file writes and Android working-copy replacement before enabling it; a read-only file descriptor alone does not establish immutability. Previously published APKs remain unchanged.

## Phone measurements: 0.0.25-beta (25012)

From the owner's diagnostics log (Samsung SM-S928B, Android 17, engine 0.21.1), one 352 MB, 869-page file opened seven times on 2026-11-13. Only what that log shows:

| Open | Working copy | First page drawn | Complete (`native_ready`) |
| --- | --- | --- | --- |
| First after app start (cold file cache) | reused, 31 ms | 727 ms | 1940 ms |
| Copy made again, after closing the edited read-only file | `provider_descriptor_copy` 1126 ms | 1315 ms | not reached (closed) |
| Same file tapped twice while opening (#439) | reused, 33 ms | 3.0 s after the first tap | 1119 ms after the second tap |
| After 15 min in the background (cold file cache) | reused, 29 ms | 682 ms | 1993 ms |
| Warm | reused, 16 ms | 168 ms | 1437 ms |
| Warm | reused, 13 ms | 297 ms | 1536 ms |

- Warm reading stage: `native_reading_prepare` 89–92 ms, of which `native_reading_pages` is 57–58 ms (#235). Cold, `native_preview_cos` is 411–455 ms instead of 28–32 ms: the first open after a start or a long pause waits on reading the file from storage.
- Mapped input works on the phone: `native_file_read` and `native_input_mapped` are 0–3 ms, and the native heap is 62 MB at the reading stage and 226 MB at most for a 352 MB file (#212).
- Every open has one render of the same 1,390,319-pixel image taking 426–566 ms, as a visible page or a prefetch, also when nothing else is running (#236).
- Saving after one deleted comment: `doc_save` 3962 ms, because a save writes the whole file through the atomic staging writer even when the change is incremental (#97).

## Open in place (#453)

The 0.0.25 log above shows the cost the copy path adds to a first open: `provider_descriptor_copy` 1126 ms for 352 MB, and the app keeps that copy (Data was 391 MB). With #453 a provider's regular file is opened from its descriptor: `provider_open_in_place` replaces `provider_and_working_copy`, and `open_source` says `in_place` or names why a copy was made. The engine maps the descriptor as it maps a private working copy, so the reading stage and the display splice (#234) are unchanged.

Saving: the engine still saves to a working file in `files/Documents` (the recovery copy of #101-#103), then only the bytes past the original's length are appended to the original through a read-write descriptor (`write_back` `mode=append`), instead of rewriting all of it. The working file is deleted when the document closes.

Expected on the phone, not yet measured: no `provider_descriptor_copy` on a first open, App info > Storage > Data back to its size without PDFs, and a smaller `write_back` time for an incremental save (the `doc_save` part of #97 is unchanged). Compare with the diagnostics switch off.
