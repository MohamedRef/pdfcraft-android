# Scanner: "Max quality" with DocRes (#304)

**State: groundwork only. The model has not been fetched, exported or run, so the issue's
feasibility gate (seconds per page on the owner's phone) is not measured. Nothing in the app
calls this code.**

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

## Not done, and why

- **The weights.** The authors publish `docres.pkl` on OneDrive and state no licence for it. The
  copies on Hugging Face say MIT, but that is the mirrors' claim, not the authors'. Neither
  OneDrive nor Hugging Face could be reached from the machine this was written on. Until the
  weights' terms are known, the model can be used for measuring only, not shipped, not even as
  an optional download. The research notes on branch `draft/scan-app-blocks`
  (`docs/scan-blocks/research/304.md`) have the sources.
- **Export to ONNX and the operator check in `rten`.** This needs the weights and PyTorch.
  Pixel-unshuffle may export as `SpaceToDepth`, which `rten` does not support; it would be
  rewritten as reshape and transpose.
- **The gate itself:** load time, peak memory and seconds per inference on the owner's phone.
  From the paper's 183 GFLOPs at an unstated size, one pass at 384 × 512 is 137 to 549 GFLOP
  (worked out in the research notes, not measured).
- **Model parity, cancellation, the look in #301's strip, the pack download.** These are step 2
  of the issue and wait for the gate.
- **The testing option, log fields and kept-scan fields.** These come with the model; nothing
  runs yet that could be logged.
