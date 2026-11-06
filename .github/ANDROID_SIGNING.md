# Android release signing for PdfCraft Custom

This fork uses app ID `io.github.mohamedref.pdfcraft` and an existing, pinned Android signing certificate. **Preserve the original PKCS#12 keystore**: replacing it with a freshly generated key prevents an APK from installing as an update over the currently signed app.

## Required repository Actions secrets

In **Settings > Secrets and variables > Actions**, check these repository secrets:

| Secret | Expected value |
| --- | --- |
| `PDFCRAFT_KEYSTORE_BASE64` | Base64 encoding of the **original** PKCS#12 keystore bytes |
| `PDFCRAFT_KEYSTORE_PASSWORD` | The original `.p12` store password |
| `PDFCRAFT_KEY_ALIAS` | Alias of its signing private key |
| `PDFCRAFT_KEY_PASSWORD` | Password used to unlock that private key |

Use the raw values without wrapping quotes or accidental trailing whitespace. Never put a keystore or password in a git commit, pull request, issue or CI log.

## Diagnose a failed build

The error `Keystore password was incorrect` from `:app:packageRelease` means the configured store password cannot open the uploaded file. On your own computer, with the original file, run:

```sh
keytool -list -v -storetype PKCS12 -keystore /path/to/original-release.p12
```

Enter the password interactively; check that the alias exists and the certificate SHA-256 fingerprint (remove colons, compare case-insensitively) equals:

```text
fcd16ff2e401a4f379c658982a97ce78a9f00bb44e8d20038603889b0d1bfdfa
```

If you still have the **same** original file but need to re-enter its Base64 value, generate the secret on your own computer:

```sh
base64 < /path/to/original-release.p12 | tr -d '\n'
```

Paste this into the `PDFCRAFT_KEYSTORE_BASE64` GitHub secret, not into an issue or chat. Correct the associated password, alias and private-key password secrets in GitHub.

Both the `PdfCraft Custom - tests and APK` and `Release` workflows validate the signing passwords, private key and pinned certificate *before* starting an expensive release build. A failed check must **not** be bypassed by using the debug key or generating a new release identity.

If the original keystore or password has been lost, GitHub cannot recover them from its encrypted repository secrets. You will need the original signing material from a secure backup to issue a compatible update.
