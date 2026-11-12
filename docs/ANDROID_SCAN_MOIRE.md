# Scanner: moiré on photos of screens and halftones (#305)

**State: research prototype. No page the app makes changes. A testing option reports what it
finds in kept scans (below); nothing else calls it.**
The issue asks for measurements and a recommendation before anything ships; the measurements
that matter (the owner's `screen` photos, his phone's timings) have not been taken.

Code: `apps/pdfcraft-android/scan/src/moire.rs`, tests in `tests_moire.rs`.

## How it works

A screen's pixel grid and a halftone's dots are periodic, so they show as a few narrow peaks in
the spectrum of the page, away from its centre.

- **Finding** (`moire::find`, on the straightened page's grey copy). The page is cut into
  256-pixel squares. A square whose grey levels spread by less than 3 is blank and skipped. In
  each other square, a Hann-windowed FFT (a small radix-2 FFT in the file; `rustfft` is not in
  the lock file) is searched for local maxima more than 8 times the median of their ring
  (`PEAK_RATIO`, the issue's value), outside a disc of 8 bins, and narrow (fewer than 9 bins of
  the 7×7 around them above half their height: the issue's "under 3 bins").
- **Print is not a screen.** Lines of text, ruled tables and dotted backgrounds are periodic too.
  A narrow peak is set aside when it belongs to a family of peaks of comparable magnitude (at
  least half its own) that a long-period pattern makes:
  - another peak on its ray from the centre that is not one of its own harmonics (a comb: lines
    of text repeat their strokes at the line pitch, up to the Nyquist limit);
  - it is 2 to 6 times a peak whose period is over 16 pixels (a ruled table's thin lines);
  - it is a whole combination of two such peaks (a table's crossings, a dotted background's
    diagonals).
  Of the peaks left, the square's frequency is the one nearest the centre among those at least
  half as strong as the strongest (a pattern's harmonics lie further out). Its period must be
  16 pixels or less (`MAX_PERIOD`, added here; the issue's tests put dotted backgrounds at over
  20).
- **Screen or halftone.** Squares with the most common frequency (or a second one at the same
  radius and turned 60–120°, the other direction of a lattice of dots) agree. A **screen** when
  agreeing squares are more than 40 % of the squares that are not blank (the issue's value) and
  at least 70 % of the periodic ones; a **halftone** when at least 4 agreeing squares fill 60 % of
  the rectangle around them (a printed photograph on a page of print).
- **Taking it out** (`moire::remove`, `remove_rgb`). Squares of 256 stepped by 128 over the page
  extended by mirroring, Hann-windowed (the windows add up to exactly one), FFT, Gaussian notches
  (standard deviation 2.5 bins) at the found frequencies and their harmonics up to the 4th, where
  the square has a peak there at least 8 times its ring's median; inverse FFT; added back. Squares
  without the peak are left as they were.

## What was measured, and on what

All on pages drawn in code or the three drawn print pages in `scan/tests/data/` (English, Arabic,
a form; 672 × 896), on a desktop (4 cores, release build). **No photo and no phone.**

- A sinusoidal grating with a period of 3, 4.5, 7 and 12 pixels, at 0°, 30°, 45°, 77° and 120°,
  darkening a page of print by up to 10 % and 30 %: all 40 found as a screen, at their period
  within 6 % and their angle within 3°.
- Not found: the three print pages alone and a 1344 × 1792 sheet of them; ruled tables every 24,
  32, 40 and 57 pixels with 1 and 3 pixel lines; dots every 21, 26 and 34 pixels; blank paper.
- A 768-pixel block of dots every 4 pixels, shaded, on the sheet of print: found as a halftone.
- Notches on the sheet with a grating darkening it by up to 20 %:

  | Period, angle | Grating amplitude before → after (grey levels) | Print sharpness, page without grating → after removal |
  |---|---|---|
  | 3 px, 0° | 24.0 → 0.42 | 16.7 → 19.6 |
  | 5 px, 45° | 23.7 → 0.59 | 16.7 → 19.4 |
  | 8 px, 77° | 23.7 → 0.60 | 16.7 → 18.4 |
  | 12 px, 120° | 23.7 → 0.78 | 16.7 → 18.1 |

  The amplitude falls by 97–98 % (the issue asks for 80 %). Sharpness (mean difference between
  neighbours) does not fall: the print is not blurred. It rises by 9–18 % because the ripple
  that the grating leaves on the print itself (the grating times the print, spread around the
  peak) is not notched; that part is not measured separately.
- Time on a 4000 × 3000 page of the sheet repeated with a grating: `find` 0.61 s, `remove`
  4.6 s (release build, one thread). `remove` is too slow to run on every page as it is.

## Known limits

- A pattern lined up with the page's axes, weaker than twice the print's own peaks on that
  axis, is taken for print: the comb rule cannot tell them apart. Real screen grids have peaks
  on both axes and are usually far stronger than print; this has not been checked on a photo.
- The descreening low-pass for halftones that the issue mentions is not written.
- Moiré itself is often the slow beat between the screen's grid and the camera's, at a long
  period, not the grid. Those beats are inside the long periods this detector leaves alone; it
  finds a grid that the photo resolves.
- The thresholds are the issue's starting values except `MAX_PERIOD`, the family rules and
  `BLOCK_TILES` (4), which were set on the drawn pages above.

## The report in kept scans

A testing option, **Report moiré, fingers, glare and stains in kept scans** (Settings → About →
Diagnostics, off by default), runs this detector on each page a kept scan keeps
(`scan/src/inspect.rs`, called through `scan_inspect_page`). It straightens the page as Save
would (no flattening, no turn) and changes nothing: what it finds goes to the log's `inspect`
line and to the page file's `inspect` key. It runs only while **Keep scans for testing** is on,
and adds the time of one more straightening and the detectors to each kept page.

Log fields: `moire` (`none`, `screen`, `halftone`), `moire_period` (pixels of the straightened
page, -1 without a pattern), `moire_tiles`, `moire_busy_tiles`, `moire_periodic_tiles`, `moire_ms`.

## Not done (needs the owner's photos or phone)

- Question 1's precision and recall on his `screen` photos against the other kinds.
- Question 3, a learned model (ESDNet): not tried. Its weights and the UHDM dataset's licence
  were not checked.
- Question 4, a live hint in the in-app camera: needs #295.
- A recommendation among (a)–(d) of the issue waits for those figures.
