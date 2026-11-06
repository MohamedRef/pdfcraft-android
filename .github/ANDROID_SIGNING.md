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
| `PDFCRAFT_CERT_SHA256` (**Actions variable**, not secret) | Public certificate SHA-256 fingerprint from the original PKCS#12; colons are allowed |

Use the raw values without wrapping quotes or accidental trailing whitespace. Never put a keystore or password in a git commit, pull request, issue or CI log.

## Diagnose a failed build

The error `Keystore password was incorrect` from `:app:packageRelease` means the configured store password cannot open the uploaded file. On your own computer, with the original file, run:

```sh
keytool -list -v -storetype PKCS12 -keystore /path/to/original-release.p12
```

Enter the password interactively; check that the alias exists. Copy the certificate SHA-256 fingerprint to **Settings > Secrets and variables > Actions > Variables** as `PDFCRAFT_CERT_SHA256`. Its value is public and safe to share; the private keystore and passwords are not.

If you still have the **same** original file but need to re-enter its Base64 value, generate the secret on your own computer:

```sh
base64 < /path/to/original-release.p12 | tr -d '\n'
```

Paste this into the `PDFCRAFT_KEYSTORE_BASE64` GitHub secret, not into an issue or chat. Correct the associated password, alias and private-key password secrets in GitHub.

Both the `PdfCraft Custom - tests and APK` and `Release` workflows validate the signing passwords, private key and pinned certificate *before* starting an expensive release build. A failed check must **not** be bypassed by using the debug key or generating a new release identity.

If the original keystore or password has been lost, GitHub cannot recover them from its encrypted repository secrets. You will need the original signing material from a secure backup to issue a compatible update.

If **no APK has been distributed or installed**, you can rotate to a new signing identity. Generate a fresh PKCS#12 on a trusted local machine, replace all four signing secrets, and update the `PDFCRAFT_CERT_SHA256` Actions variable with its new public fingerprint before building. Back up the PKCS#12 securely for future updates. An exposed keystore and password should not be reused for a public release.

## Pull requests and merge protection

The `PdfCraft Custom - tests and APK` workflow runs against every pull request targeting `main`:

- `rust-tests` tests the Rust COS, engine, and Android library.
- `android-tests` runs Android JVM tests.
- `APK smoke (arm64-v8a)` and `APK smoke (armeabi-v7a)` build **release-mode** packages and validate their debug-only CI signatures, application ID, version code and bundled Rust native library. These APKs are **not** suitable for distribution or for updating an installed release.
- `Merge gate (Rust, Android, ABI APKs)` must succeed; it fails if any required job fails, is cancelled, or is skipped. `Conventional title` is a separate PR-title check.

In **Settings > Rules > Rulesets** (or **Branches > Branch protection rules**), add protection for `main`. Require a pull request before merging, require the **Merge gate (Rust, Android, ABI APKs)** and **Conventional title** status checks, require branches to be up to date, and disable direct push/bypass where your GitHub permissions allow. Branch protection settings cannot be enforced by simply committing a YAML file.

Do not merge while any required check is pending, failed, skipped, or cancelled. The production `signed-apk` job uses the private release keystore and runs only on trusted `main` pushes/manual dispatch; it is intentionally skipped for untrusted PR code. A passing PR gate proves both APK variants can be compiled, but it **does not claim to verify production signing**.

If the production `signed-apk` job fails, correct the repository signing secrets and fingerprint variable and rerun it. Never turn production signing checks off to make `main` appear green.
