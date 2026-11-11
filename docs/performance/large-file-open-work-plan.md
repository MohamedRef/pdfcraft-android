# Large-file opening work plan

The implementation is a sequence of independently tested pull requests. A completed phase does not mean the entire plan is finished. Keep merge order and pass exact-head Rust/provenance and Android JVM gates for every PR. A beta may contain only the verified phases, and must explicitly identify unfinished work.

| Area | Work remaining | State |
| --- | --- | --- |
| Reading-stage coverage | Accept explicit null form entries, classify bounded field trees by affected page, preserve invisible-signature validation, and report fixed fallback reasons | Merged in PR #225; exact-head CI passed |
| Per-page overlays | Load visible-page comments and widgets first; retain complete lists on demand, with generation checks and refresh behavior | Merged in PR #226; exact-head CI passed |
| Rendering and memory | Prioritize visible rendering, limit initial prefetch, request large heap with actual-runtime cache budgets, and provide an opaque RGB565 option with correct native transfer/accounting | Merged in PR #227; exact-head CI passed |
| Shared input bytes | Audit managed-file immutability and all save paths, introduce a shared byte source, implement read-only mapping with owned fallback and atomic replacement, and test mapping lifetime through save/close/reopen | Atomic working-copy publication is covered separately; shared bytes and mmap remain pending |
| Integration and release | Re-run complete suites, provenance, Android JVM and release gates; document measured results and remaining physical-device limits | 0.0.22-beta (22012) covers verified reading/overlay/render/memory work and atomic-publication prerequisite; shared-input integration and physical-device measurements remain pending |

The already published reading stage is deliberately conservative. Rejected attempts must have diagnostic reason markers instead of silently looking like a successful progressive open.

Signature absence must not be inferred from a missing AcroForm: standalone timestamps and page-only widgets are regression cases. Signature state remains pending until complete discovery and validation. Neither elapsed-time thresholds nor a two-point page-count comparison prove constant-time complexity; use bounded-work counters and behavior checks alongside manual timing measurements.

Memory mapping must retain an immutable underlying file for its entire lifetime. Verify regular-file writes and Android working-copy replacement before enabling it; a read-only file descriptor alone does not establish immutability. Previously published APKs remain unchanged.
