# Scanner: "Max quality" with DocRes (#304)

**State: groundwork, a desktop measurement, and a testing option that times a model file picked on
the phone. The issue's feasibility gate (seconds per page on the owner's phone) is not measured
yet: nothing has run on a phone. No page the app makes uses DocRes.**

Code: `apps/pdfcraft-android/scan/src/enhance/learned.rs`, tests in `enhance/tests_learned.rs`,
fixtures made by `tools/scan-models/docres_prompts.py`.

## What exists

- **The two prompts DocRes is given** for the tasks #304 uses, ported from DocRes's
  `inference.py` (https://github.com/ZZZHANG-jx/DocRes, MIT, Copyright (c) 2024 Jiaxin Zhang;
  fetched from its master branch on 12 November 2026, SHA-256 of that file
  `32dcebbebb98f55a1b1e1022f61519662489807ddf146e76d4fa7b09c55b6ded`):
  - `deshadow_prompt`: each channel resized to 1024 × 1024, dilated over 7 × 7, median over
    21 × 21, resized back: the paper under the light.
  - `appearance_prompt`: `255 − |channel − that background|`, stretched to 0–255, resized back.

  The port follows OpenCV 5.0's arithmetic: bilinear sampling with single-precision positions
  and weights in 1/2048 combined as its vector code does, the median with the edge repeated, and
  the stretch in single precision rounded half to even.
- **The correction map** (`correction_map`, `apply_correction`), as the issue's step 2 sets out.
  The model only ever sees a small copy. The map is `(output + 0.02) / (input + 0.02)` per
  channel, limited to 0.4–5. It is cleaned on a half-size copy, each value the middle of its 2 × 2
  (a median rather than the issue's percentile-then-minimum, because a re-drawn stroke moves the
  ratio both ways): a median over 2 % of the longer side, then two box blurs. The cleaned map is
  scaled up and multiplied into the full page. The model never draws the saved page's pixels.

## What was measured, and on what

The pages are drawn: the three print pages in `scan/tests/data/` at a third of their size, under
a warm light falling to one corner with a soft dark blob. There is no photo, no model and no phone.
Timings are from a desktop (release build, one thread).

- **Prompts against DocRes's own Python** (OpenCV 5.0.0, NumPy 2.5.4): the deshadowing prompt
  is the same bytes on all three pages. The appearance prompt is the same bytes on the English
  page and the form, and one level off in a few pixels of the Arabic page. The issue allows one
  level.
- **Cleaning:** a model output equal to its input except for sharpened print (an unsharp mask
  of strength 1) gives a map within 2 % of 1.0 everywhere. This is the issue's test.
- **A perfect model:** suppose the model took the drawn shadow off a copy shrunk three times,
  exactly. The map then carries that to the full 672 × 896 page. Paper comes back within 4 grey
  levels of the page before the shadow (median), and print sharpness is within 3 % of the clean
  page's. This is the issue's figure, measured here against the clean page, since no Auto
  output of a model exists.
- **Time at 384 × 512:** both prompts 0.65 s, the map 0.07 s.

## Timing it on a phone

Settings → About → Diagnostics, while the log is recorded: **Time a document model from a file
(testing only)**. The person picks a model file (ONNX, fixed input size). The app copies it into
its cache, loads it, fills its first input with a fixed smooth pattern of the declared size, runs
it twice (the first run warms the runtime up), deletes the copy and shows the load time, the run
times and the peak memory. Nothing else is done with the file: it is never kept, never used on a
page, and neither the app nor this repository carries one.

For DocRes the file is made on a computer with `tools/scan-models/export_docres.py` from the
authors' weights (pinned by SHA-256 there). Code: `scan/src/model_timing.rs` (tests in
`tests_model_timing.rs`, on the bundled page detector, the one model file the tests can count on),
`src/scan_model_timing.rs`, `ui/ScanLabController.kt` (`timeModel`) and `ui/scan/ScanModelTiming.kt`.

Before the run a `model_timing_start` line (`bytes`) is written to the file, so a model that gets the app closed still leaves a trace. After it, the log line is `model_timing`: `bytes`, `sha` (`h` and the first 16 hex digits of the file's
SHA-256), `inputs`, `input_dims` and `input_0`… (the shape run), `output_dims` and `output_0`…,
`output_finite`, `threads` (0: the runtime's own pool, one thread per core), `cores`, `load_ms`,
`run_1_ms`, `run_2_ms`, `peak_mb_before` and `peak_mb_after` (the process's peak resident memory,
-1 where the system does not say). A file that cannot be timed is a `failed` line with stage
`scan_time_model`.

## On a desktop

DocRes at 1 × 6 × 512 × 384, exported as above (15.2 million parameters; `rten` 0.26.0's output
within 3.2e-6 of PyTorch's on the same input), on the build machine of 12 November 2026, an x86-64
cloud container:

| Runtime | Threads | One run |
|---|---|---|
| PyTorch (CPU) | 4 | 11.6 s |
| `rten` 0.26.0 | 4 | 8.8 to 9.7 s |
| `rten` 0.26.0 | 1 | 22.5 to 23.8 s |

Peak memory about 2.1 GB, loading 0.05 s. A phone is slower than this machine, so the 6 s gate looks
unlikely at this size; only the phone can say.

## Not done, and why

- **The weights.** The authors publish `docres.pkl` on OneDrive and state no licence for it. The
  copies on Hugging Face say MIT, but that is the mirrors' claim, not the authors'. The desktop
  numbers above used the copy at `DaVinciCode/doctra-docres-main` (revision 1e63bad), which has the
  pinned SHA-256. With no licence from the authors the file is not committed and not shipped, not
  even as an optional download. The research notes on branch `draft/scan-app-blocks`
  (`docs/scan-blocks/research/304.md`) have the sources.
- **The gate itself:** load time, peak memory and seconds per inference on the owner's phone; the
  testing option above measures them once the file is picked there.
  From the paper's 183 GFLOPs at an unstated size, one pass at 384 × 512 is 137 to 549 GFLOP
  (worked out in the research notes, not measured).
- **Model parity, cancellation, the look in #301's strip, the pack download.** These are step 2
  of the issue and wait for the gate.
- **A page cleaned by the model, its option and kept-scan fields.** These come with step 2, after
  the gate.
