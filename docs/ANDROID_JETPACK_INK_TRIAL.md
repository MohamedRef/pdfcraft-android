# Jetpack Ink pens: a Pen lab trial (#569)

The owner asked whether Jetpack Ink (`androidx.ink`, Apache-2.0, built in AOSP) draws better
pens than PdfCraft's own model, and to use it if so. Only his hand on his phone and tablet can
answer that, so it is a switch to compare with, not a replacement.

- **Settings › Diagnostics › Pen lab › Try Jetpack Ink pens** (pref `pen_lab/jetpack`, off by
  default). On, Jetpack Ink draws the ballpoint (its `marker` brush), the fountain pen and the
  brush (its `pressurePen` brush) everywhere ink is written: the page, the zoom pad and the Pen
  lab strip. The pencil and the highlighter stay PdfCraft's.
- While the pen is down, the stroke is Jetpack Ink's own outline (`JetpackInkLine`): its input
  smoothing and its pressure response. Positions go in as dp on the glass, the units its stock
  brushes are tuned for.
- On lift, the outline becomes an ordinary PdfCraft pen stroke (`tubeCentreline`): each point
  of the pen's path moves to the middle of the outline across the stroke, and takes the
  outline's width there. Saving (`/PCWidths`), the eraser, the lasso and Undo work as for every
  stroke; nothing in the engine or the file format changes.
- Only `ink-strokes`, `ink-brush` and `ink-geometry` 1.0.0 are used (not `ink-authoring`, which
  pulls in `graphics-core`, removed in #574). The native `libink.so` adds about 1.3 MB per ABI.
- If the native library cannot load, the stroke is drawn by PdfCraft's model as before.

`InkTubeTest` checks the outline-to-stroke conversion. Jetpack Ink itself needs the device: it is
judged by the owner's hand, and its `stroke` log lines are the same as for any pen.

## The first stroke closed the app (0.0.35-beta)

With the switch on, the first stroke closed the app (three times in the owner's 0.0.35-beta log, `IllegalArgumentException` from `MutableStrokeInputBatch.appendSingle`). The pressure was passed as the fifth positional argument of `MutableStrokeInputBatch.add`, which is the stroke's unit length (`strokeUnitLengthCm`), and Jetpack Ink refused it. The call now names its arguments, so the pressure goes where it belongs. Any later error from Jetpack Ink while drawing or on lift drops it for that stroke, which goes on as PdfCraft's own line, and is logged as `[error] ink jetpack_ink`. Jetpack Ink needs its native library, so this is not covered by the JVM tests; check it on a phone.

## Switched off once after 0.0.36-beta (#604)

The app still closed on the first Jetpack Ink stroke in the owner's 0.0.36-beta log (three times; #593 was merged after that build), so the trial has never drawn a stroke on his phone, and its switch was left on. The build after #602 turns the switch off once (with the Pen lab dials saved for the older pen model), so that PdfCraft's own pens, which #601 changes, are what he tests next. The switch is still in the Pen lab.
