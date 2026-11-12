# Scanner: model files on the phone (#289)

How the app gets its model files (the scanner's three and text recognition's two) from the APK
into a folder the engine reads, how it notices that a file changed, and how to see the state of
each model. Where each model comes from and how it was made: `tools/scan-models/README.md`.

## Copying the models out of the app

The engine cannot read files inside the APK, so the app copies `assets/models/*` into
`filesDir/models` and points `PDFCRAFT_MODELS` there. Until this change a file was copied only
when no file of that name was there yet. A model **replaced** under the same name by an update
was never copied: the old file stayed, failed its pinned SHA-256 in `scan/src/models.rs`, and the
feature it serves turned itself off without a word. Text recognition's two files had the same
fault (raised under #125).

Now, for both:

- The build (`OcrModels` in `app/build.gradle.kts`) writes `models.index` next to the models in
  the APK: one line per file, `name<TAB>bytes<TAB>sha256`. It copies the folder when it holds a
  `.rten` or an `.onnx` file (before, only `.rten`).
- `engine/ModelFiles.kt` compares that list with `.installed.index`, the list it wrote into
  `filesDir/models` the last time. A file is copied when it is **new** (no file of that name),
  **changed** (its line in the APK differs from the line recorded), its **size** on disk is not
  the listed size, or it is **unrecorded** (a file left by a build before this list: copied once).
  `Forced` is the testing option below.
- Each copy goes to `<name>.part`, is hashed while it is written, and is renamed into place only
  when its size and SHA-256 match the list. A copy that does not match is deleted and left out of
  the record, so the next start tries again; nothing half-written ever has the model's name.
- `Engine.prepareModels` runs this once per process (later calls return the first answer) and
  `prepareOcrModels` is now a thin wrapper, so every caller (text recognition, the scanner, the
  Models list) shares one mechanism. When anything was copied the engine's loaded models are
  dropped (`releaseMemory`), so a model loaded before it was replaced is read again from the new
  file.
- A build whose APK has no `models.index` (an older build script) hashes the files from the APK
  instead.

The cost when nothing changed is reading two short text files and one `stat` per model. When
something changed, only the changed files are copied.

## Seeing each model's state

Settings → **Scanner models** lists the scanner's models (Page finder, Page flattening, Turning
pages upright) with size, licence and state: **Installed**, **Not installed**, or **Damaged, not
used** (a file of that name that is not the pinned one). Opening the list first runs the copy
above, so it shows what the next scan will find. The states come from the native call
`scan_model_states()` (`src/scan_models.rs`), which reads and hashes each file not loaded yet.

## Testing option, log, kept scans

- **Testing option**: while the diagnostics log is recorded, the Models list shows **Copy the
  models again**, which copies every file out of the app anew (`force`) and says how many were
  copied. It is an action rather than a switch in `ScanLab`: there is no older behaviour worth
  putting back, and what the owner needs on the phone is a way to repair a damaged copy and to
  see the states.
- **Diagnostics log** (`[scan]`): `model_files` after each check, with `copied`, `kept`, `failed`,
  `ms`, how many were copied for each reason (`new`, `changed`, `size`, `unrecorded`, `forced`),
  `listed` (the APK had `models.index`) and `force`. While the log is recorded, `model_states`
  follows with one field per model: `edges=ready flatten=ready upright=ready` (or `missing`,
  `corrupt`).
- **Kept scans**: each page file has `"models": {"edges": "ready", "flatten": "ready", "upright":
  "ready"}`, or `null` when the states could not be read.

## Measured, and not

- JVM tests (`ModelFilesTest`) cover each reason, a copy that does not match its listing, a file
  cut short on disk, the list format and the log line. They run on a desktop JVM with temporary
  folders, not on Android storage.
- The Rust test reads the three models from the repository's `assets/models` and finds all ready.
- **Not verified**: nothing here has run on a phone. Not checked: the time the copy takes on a
  phone, an update from a build before this one (the "unrecorded" path, which copies all five
  files once, about 38 MB), the Settings list on screen.

## Not done yet (#289)

- Optional model packs (section 4 of the issue): `bundled` is in `ModelSpec` and in
  `scan_model_states`, all three models are still bundled, and nothing downloads.
  The three add 25.9 MB to the APK, just over the issue's limit of about 25 MB; the flattening
  model (15.8 MB) is the candidate for a pack.
- `tensor.rs` (section 2) and renaming the Gradle task.
