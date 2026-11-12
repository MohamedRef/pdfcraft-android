# Gradle lines for Google's document scanner (K05, #307, spec S21)

Not applied: the integrator adds these. The version is the one the official guide names
(https://developers.google.com/ml-kit/vision/doc-scanner/android, read on the day of this work).
It was NOT checked on Google's Maven repository: the proxy refused that host (HTTP 403) from this
machine. Check it there before merging.

## gradle/libs.versions.toml

In `[versions]`, after the `graphicsCore` entry:

```toml
# Google's document scanner, run by Google Play services (not bundled in the app); optional, see ui/scan
mlkitDocumentScanner = "16.0.0"
```

In `[libraries]`, at the end:

```toml
mlkit-document-scanner = { group = "com.google.android.gms", name = "play-services-mlkit-document-scanner", version.ref = "mlkitDocumentScanner" }
```

## app/build.gradle.kts

In `dependencies { }`, after `implementation(libs.androidx.exifinterface)`:

```kotlin
implementation(libs.mlkit.document.scanner)
```

## What it adds

- The guide says the library is about 300 KB. The spec says the scanner's models and logic are
  downloaded by Google Play services, not bundled. Not measured here: the APK size limit (under
  500 KB growth) is not checked.
- Requires Google's Maven repository (`google()`) among the repositories. The repository copy this
  work read does not include the Gradle settings file, so this was not checked.
- The guide's minimum is Android API 21 and 1.7 GB of device RAM (below that the scanner returns
  an `MlKitException` with code `UNSUPPORTED`). The app's minSdk is higher, so only RAM applies.
- ATTRIBUTION.toml: spec S21 asks for an entry with the ML Kit terms of service URL, marked
  proprietary, and a README line. Neither file was in the read-only copy; not done.
