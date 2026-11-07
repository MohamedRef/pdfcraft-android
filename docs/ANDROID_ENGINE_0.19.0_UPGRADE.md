# Android engine v0.19.0 upgrade

## Live status — 2026-11-07

Dedicated upgrade branch from main `7f597ad45612f53cc01f801cf97f34fb9421f0ee`. The owner authorized importing stable v0.19.0 while preserving every Android feature, stylus/ink, voice, recovery, signing and updater behavior. Work is in progress; the released 0.0.11-beta (11012) remains immutable and uses v0.18.2.

Target: immutable upstream `8c572792d6760bdc903d6f9a032e3e38e8f71cb8` (`v0.19.0`), replacing source pin `7faa4f35cefb0dea04137ec475bdc770945c22cd`. Review context: [upstream impact assessment](UPSTREAM_0.19.0_REVIEW.md).

## Preservation and verification checklist

- [ ] Three-way reconcile selected 32 core crates and nine vendors against the old baseline, retaining all 24 reviewed Android adaptations; explicitly review the twelve overlapping upstream edits.
- [ ] Preserve Android bridge/Kotlin UI, S Pen pressure/history/hover/eraser, Sound/WAV annotations, forms/XFA output, selective note rendering, safe provider copies and updater/signing identity.
- [ ] Pin dependency resolution and update provenance from immutable upstream objects, retaining deliberate 3D/PRC omissions. No arbitrary allowlist expansion.
- [ ] Audit the six new command schemas and compatibility of existing mobile command arguments/results; importing tools does not imply a new mobile workflow.
- [ ] Pass local focused Rust/bridge regressions, full required Rust/provenance CI, Android JVM/ARM64 bindings and title/aggregate merge gates.
- [ ] Record measured private-file host opening results without uploading the fixture; Samsung/Xodo and physical S Pen/provider/save/upgrade tests remain separate and unverified.

No new APK per PR, version/signing rotation or release publication as part of this upgrade PR. Build the next signed release through the existing owner-approved batch process.
