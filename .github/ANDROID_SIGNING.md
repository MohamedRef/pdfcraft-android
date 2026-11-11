# Android release signing for PdfCraft Custom

This fork uses app ID `io.github.mohamedref.pdfcraft` and an existing, pinned Android signing certificate. **Preserve the original PKCS#12 keystore**: replacing it with a freshly generated key prevents an APK from installing as an update over the currently signed app.

## Required repository Actions secrets and variable

In **Settings > Secrets and variables > Actions**, check these repository secrets:

| Secret | Expected value |
| --- | --- |
| `PDFCRAFT_KEYSTORE_BASE64` | Base64 encoding of the **original** PKCS#12 keystore bytes |
| `PDFCRAFT_KEYSTORE_PASSWORD` | The original `.p12` store password |
| `PDFCRAFT_KEY_ALIAS` | Alias of its signing private key |
| `PDFCRAFT_KEY_PASSWORD` | Password used to unlock that private key |
| `PDFCRAFT_CERT_SHA256` (**optional Actions variable**, not secret) | Pins the public certificate SHA-256 fingerprint after the first published build; colons are allowed |

Use the raw values without wrapping quotes or accidental trailing whitespace. Never put a keystore or password in a git commit, pull request, issue or CI log.

## Diagnose a failed build

The error `Keystore password was incorrect` from `:app:packageRelease` means the configured store password cannot open the uploaded file. On your own computer, with the original file, run:

```sh
keytool -list -v -storetype PKCS12 -keystore /path/to/original-release.p12
```

Enter the password interactively; check that the alias exists. After publishing your first APK, pin the public SHA-256 fingerprint at **Settings > Secrets and variables > Actions > Variables** as `PDFCRAFT_CERT_SHA256`. This pin is optional until the first distribution, but strongly recommended afterward. The private keystore and passwords must remain secret.

If you still have the **same** original file but need to re-enter its Base64 value, generate the secret on your own computer:

```sh
base64 < /path/to/original-release.p12 | tr -d '\n'
```

Paste this into the `PDFCRAFT_KEYSTORE_BASE64` GitHub secret, not into an issue or chat. Correct the associated password, alias and private-key password secrets in GitHub.

The signed job in `android-update.yml` (`PdfCraft Android - tests, signed APK and release`) is the only workflow that holds the signing secrets or publishes a release. It validates signing passwords and the private key *before* starting an expensive release build. It calculates the certificate fingerprint from that same verified keystore, export it between steps using GitHub Actions' environment file, and checks the built APK against the fingerprint and against the certificate hard-pinned in `scripts/verify_android_apk_certificate.py`; if an optional `PDFCRAFT_CERT_SHA256` pin is configured, it must also match. A failed check must **not** be bypassed by using the debug key or generating a new release identity.

If the original keystore or password has been lost, GitHub cannot recover them from its encrypted repository secrets. You will need the original signing material from a secure backup to issue a compatible update.

If **no APK has been distributed or installed**, you can rotate to a new signing identity. Generate a fresh PKCS#12 on a trusted local machine, replace all four signing secrets, and replace the optional `PDFCRAFT_CERT_SHA256` Actions variable with its new public fingerprint if one is already set. After the first release, configure the pin so future releases cannot accidentally change signing identity. Back up the PKCS#12 securely for future updates. An exposed keystore and password should not be reused for a public release.

## One-time release trigger (moved for every release)

Each release preparation PR moves a one-time, exact-squash-commit-title trigger in `android-update.yml` to its own title, `chore(release): publish X (N*10+2)`, together with the property checks for `pdfcraft.version=X` and `pdfcraft.versionCode=N`. Only that title on a `main` push, or a manual `workflow_dispatch` on `main`, runs the signed job; every other push runs tests only. `scripts/test_android_release_workflow.py` checks that the title and the property checks name the same version.

Before decoding the signing key, a push run stops if the tag `vX` already exists, so a later commit that reuses an old release title never re-signs a published version. Published releases and APKs are immutable. The job runs after the Rust and Android JVM tests, validates the original signer, and verifies the APK's certificate, package, versionName and versionCode and the curated notes in `docs/releases/X.md` before it creates the tag and the release titled `PdfCraft X (N*10+2)`. If a gate fails, stop and fix it rather than manufacturing a release.

`release.yml` is a manual, debug-signed dry run only. It runs on no tag and holds no signing secret, so a hand-pushed `v*` tag publishes nothing.

Third-party actions in jobs are pinned to full commit SHAs; first-party `actions/*` use major tags.

## Pull requests and merge protection

Owner policy (2026-11-06): work in batches of three PRs. Each PR must pass `rust-tests` (including provenance), `android-tests` (JVM/regressions) and `Merge gate (Rust and Android)` plus the title check. JVM tests may compile the native library to generate matching UniFFI bindings; they do not package a release APK.

After a batch is merged, dispatch `android-update.yml` on `main` once to run tests, build the permanent-key signed ARM64 APK, verify certificate/package/manifest, hash and publish the release. No APK smoke packaging per PR and no signed build on ordinary main pushes. Do not skip failed tests. APK/device checks are release gates, not PR evidence.

If branch protection is configured, require `Merge gate (Rust and Android)` and `Conventional title`; retire the legacy `Merge gate (Rust, Android, ABI APKs)` requirement. At policy change, GitHub reported main was not branch-protected. Production signing checks remain required and may never be bypassed.
