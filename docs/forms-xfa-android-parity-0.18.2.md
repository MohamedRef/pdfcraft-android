# Android-safe FormCalc/XFA v0.18.2 compatibility checkpoint

Source pin: `storytold/pdfcraft` stable v0.18.2, commit `7faa4f35cefb0dea04137ec475bdc770945c22cd`.

This existing PR #7 backports four Android-relevant correctness fixes:

- XFA SHA-256 image hash parsing rejects signs, whitespace and nonhex characters.
- FormCalc URL decoding accepts only genuine pairs of ASCII hex digits after `%`.
- FormCalc rounding preserves huge finite values rather than overflowing.
- Number/percent formatter avoids emitting `inf` from decimal scaling overflow.

Retargeting checkpoint: parent PR #6 was merged into `main` at `1a673b34f3a7e217f6a7a55d5752214da399525e`, including the earlier backport and audit. PR #7 now targets `main` directly. This commit is documentation only, and intentionally triggers fresh PR checks against the latest main, since earlier [successful tests](https://github.com/MohamedRef/pdfcraft-android/actions/runs/42198084464) were against an older stacked base.

Required gate: Rust engine/JS/forms/XFA suites, Android JVM, arm64 APK smoke, aggregate merge gate, no merge conflicts. (The inherited CI may still run armv7 until a later reviewed owner-device-specific workflow change.) Physical device rendering/form interaction is not represented by JVM or CLI tests. Merge only after green checks on this branch and post-merge main verification.
