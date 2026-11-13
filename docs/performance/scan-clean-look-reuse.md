# Clean scan: keeping the light estimate between changes

Issue: #446 (from the first phone log, #286).

## What the phone showed

PdfCraft 0.0.25-beta (25012) on a Galaxy S24 Ultra, previews 1280 pixels on the long side, the
`preview` lines' `preview_look_ms` on a page already straightened (`reused=true`):

| Look | ms per change |
|---|---|
| Colour | 45 to 61 |
| Greyscale | 38 to 43 |
| Whiteboard | 235 |
| Black and white | 255 |
| Clean scan (the default) | 202 to 394 |

#301 asks for under 60 ms per strength change.

## Where the time goes (desktop)

The log had no parts for the look, so they were measured first. `examples/scan_look_timing.rs`
(`cargo run --release -p pdfcraft-scan --example scan_look_timing`) lays the repository's three
drawn test pages (`tests/data/upright-{english,arabic,form}.png`) on a darker table under light
that falls off across the photo, with some grain, saves each as a JPEG, straightens it to a
1280-pixel preview (960 × 1280), and gives it Clean scan with document types on: once, then at
nine strengths with both paper choices. These are stand-ins for phone photos, not phone photos.

With the light measured again for every look, which is what `main` at `156d0262` does (the new
testing option off; Xeon 2.1 GHz, 4 threads by the app's thread rule), per strength or paper
change:

| Part of the look | english | arabic | form |
|---|---|---|---|
| Light estimate (`look_light`, `enhance::background`) | 163 | 145 | 147 |
| Grain (`look_noise`) | 5 | 5 | 5 |
| Sharpening (`look_sharpen`) | 11 | 7 | 7 |
| Tone curve (`look_tone`) | 21 | 20 | 21 |
| **Whole change, median** | **202** | **175** | **178** |

The smoothing step did not run: the stand-in pages' grain is under its 2-grey-level floor. The
light estimate is about 80 % of a change and depends on the page only: not on the strength, the
paper choice or the tone curve.

## Change

`enhance::Prepared` keeps, for one straightened page, the light estimate (with the object mask and
closing radius), the grain, and the page smoothed and sharpened before the curve, the last for the
smoothing, sharpening, resolution and photo mask it was made with. `PageBase::look_kept` holds one,
so the scan screen's kept preview page (#301) gives every later look of the page what was measured
the first time. A saved page uses a fresh one, exactly as before.

Results are unchanged: a look made from what was kept has the same bytes as one measured from
nothing, checked over 216 combinations of strength, paper, tone shape, sharpening, smoothing and
photo mask, in changing order, on each of two pages (with and without grain)
(`a_look_from_what_was_kept_has_the_bytes_of_one_made_from_nothing`), through the preview
(`a_clean_scan_strength_change_keeps_the_light_estimate`), and by the timing example, which checks
every look it times against one made from nothing.

## After (same desktop, same pages)

| | english | arabic | form |
|---|---|---|---|
| Change, light measured again (as before) | 202 | 175 | 178 |
| Change, light kept: median | 20 | 22 | 24 |
| Change, light kept: slowest of 18 | 26 | 28 | 28 |

With the light kept a change is the tone curve alone, about 9 times less on this desktop. A change
of the sharpening testing option sharpens again (about 10 ms here) but keeps the light. The first
Clean scan look of a page is unchanged (it still has to make the estimate once).

Memory: one more page-sized buffer per kept preview page (960 × 1280 × 3 bytes, about 3.7 MB; the
preview store keeps four pages) and the light planes at 1000 pixels.

## Not known

- Nothing here was timed on a phone. The phone figures above are from before the change; whether
  a change there drops under 60 ms needs the next log's `preview_look_tone_ms` and `light_reused`.
- Black and white (255 ms on the phone) has its own pipeline and is not changed.
- The testing option **Keep the page's light estimate between Clean scan changes** (on) puts the
  old behaviour back for a comparison on the phone.
