# Favorite pen presets

## Unlimited favorites, recent colours, hex entry, finer widths (#149)

Favorites are one list (`ink.presets.v2`, up to 48 entries) instead of three fixed slots; the three old `ink.preset.v1.N` slots seed the list once. In Pen settings, **Add current pen** appends the selected pen; a long press on a favorite offers Replace with current pen, Move earlier, Move later and Delete. The pen popover shows the last eight ink colours (`ink.recentColors`), each announced by its hex value, and a hex field that accepts `#RRGGBB` or `#AARRGGBB` and rejects anything else with a message. Pen widths now range from 0.1 to 20 pt in 0.1 pt steps. **Pick colour from page** in the pen popover arms an eyedropper: the next tap takes the exact colour of the nearest unsaved stroke, or of the saved ink (else other coloured comment) under the finger.

## Palettes and custom colours (#149)

The pen style sheet chooses which palette it shows: **Standard** (the 18 comment swatches), **Colour-blind safe** (the eight Okabe–Ito colours, each with a spoken name) or one of the user's own named palettes (`ink.palettes.v1`, up to 32 palettes of up to 64 colours). **New palette** starts a palette from the current pen colour; **Add current colour** adds to the shown palette; a long press on a colour removes it (TalkBack offers the same as a custom action). **Export** saves the palette as a small JSON file (`{"format":"pdfcraft-ink-palette","version":1,"name":…,"colors":[{"color":"#AARRGGBB","name":…}]}`) through the system's file picker, and **Import palette** reads one back: files over 64 KB, files of another format or a newer version, and files with no valid colour are refused with a message; bad colours are skipped, names are cleaned and shortened to 60 characters, and a name already in use gets a number. **Custom colour…** opens a saturation/brightness square and a hue slider; the pen takes the colour when a tap, drag or slide ends, so a drag does not fill the recent colours with in-between steps.

## Per-pen settings (#187, #188)

Each of the five pens (Ball, Fountain, Pencil, Brush, Marker) keeps its own colour, width and opacity under `ink.<style>.color`, `ink.<style>.width` and `ink.<style>.opacity`. Switching pens never restyles another pen, and each toolbar nib preview shows its own pen's colour and width. Fresh installs start from per-pen defaults (black ball, blue fountain, grey pencil, red brush, wide yellow marker). On the first launch after the update, the old shared `ink.color`/`ink.width`/`ink.opacity` move to the pen that was last used, so that pen looks the same; the migration is written immediately so it does not move again. A favorite is a full snapshot: using it restyles its own pen type and selects it.

Tapping the already selected pen opens a compact popover with that pen's width chips, a half-point slider, colours and opacity (the same second-tap pattern as the eraser size). The row-2 button is now **Settings** (sheet title **Pen settings**) and holds only Pen only, Snap to shape and favorites; the duplicated pen-style row, thickness, colour and opacity were removed from it.

Draw → Favorite pens offers three persistent slots containing ink color, width and opacity. Use pen applies the selected appearance; Replace with current pen explicitly saves the current drawing settings into that slot. Author, pen-only routing, pressure/history/hover, eraser mode and snap-to-shape remain independent. Existing strokes and PDF contents are not edited by choosing a favorite.

## Persistence and input boundaries

Each slot uses a bounded, versioned preferences string. Malformed/unknown/oversized values, invalid ARGB, non-finite widths/opacities and values outside existing mobile UI ranges fall back to that slot's factory preset. Existing global drawing preferences remain compatible. Saving a slot changes only that preset key; using it writes ink color/width/opacity in one preferences transaction. No document path, author or personal text is serialized in a profile.

The Draw layer's existing immutable appearance snapshot at stroke start is unchanged, so completed/in-progress stroke appearances are not retroactively changed by later settings. The preset picker scrolls for compact windows; the Draw toolbar already scrolls horizontally.

## Verification

JVM cases cover ARGB/width/opacity round-trips, corrupted/future/oversized/non-finite data and locale-independent storage. Existing ink appearance, pressure, stylus routing/history and eraser regressions remain intact. Current-head Rust/provenance, Android JVM/ARM64 bindings, title and aggregate gate are required before merge. Physical S Pen behavior, app restart, RTL/accessibility, DeX/landscape and switching favorites during drawing still need device QA. These presets are one part of the larger stylus backlog, not full Xodo parity.

## S Pen side button (#144)

Pen settings has two choices. **While the S Pen button is held**: Eraser (default, the earlier behaviour), Marker (that stroke uses the highlighter's own settings, then the selected pen is back) or Nothing. **S Pen button click while hovering**: Nothing (default), Undo, or Switch to previous pen. The mode of a stroke is decided at contact: pressing the button mid-stroke never cancels, splits or erases the stroke being written. The hardware eraser end always erases. A click is a press and release within 600 ms while the pen hovers without touching. Hover-click behaviour depends on the device reporting the button during hover and still needs checking on a Galaxy S Pen device.

## Writing hand and palm guard (#156)

Pen settings has **Writing hand** (Right or Left) and **Ignore palm while writing** (off by default) with a 60–240 dp area size. Choosing Left moves the quick rail to the right edge (it can still be dragged) and, on desktop layouts, puts the zoom buttons in the physical bottom-right corner in every language. With the guard on, a finger touch that starts inside the area beside and below the pen tip on the writing-hand side, within 1.5 s of the last pen hover or stroke sample, neither draws, scrolls, zooms nor counts as a two-finger tap; a pen arriving during that touch still writes. The area is drawn faintly while the pen hovers. Not done yet: guarding toolbar taps, a separate calibration screen and a toolbar that follows the writing position.

## Ruler (#150)

Draw ▸ **Ruler** shows a screen-space ruler with 5 dp ticks and its angle. One or two fingers on the ruler move it; two fingers also turn it about their centre. The angle snaps to 15° steps within 2° with a haptic tick. A freehand pen or finger stroke that starts within 24 dp of an edge follows that edge for the whole stroke (offset by half the stroke width), so the saved ink is straight. The ruler is only an overlay: it is kept per open document while it stays open and is never written to the PDF, exports or prints.

**Protractor**: Draw ▸ **Protractor** shows a half disc with 1°, 5° and 10° ticks and labels every 30°; one or two fingers move it, two turn it (snapping to 15° with a haptic tick, like the ruler). A freehand stroke that starts within 24 dp of its arc follows the circle for the whole stroke, so arcs of any length (even a full circle) are exact; a stroke that starts on its centre draws a straight ray whose direction locks to a whole degree once the pen leaves the centre. While drawing, a label beside the pen shows the ray's angle or the angle the arc has swept. A stroke that follows the ruler or the protractor is never changed into another shape by holding at its end, so hold-to-snap and the guides do not fight. Like the ruler, the protractor is viewer-only.

## Smart pen gestures (#148)

**Smart pen gestures** in Pen settings is off by default. When on, a freehand stroke is classified only after lift, so the live stroke gains no latency. A zig-zag with at least four horizontal reversals deletes the unsaved strokes and the saved, unlocked Ink comments mostly (60 %) under it; unsaved strokes come back with one Undo, saved ones with the document's Undo. A straight marker stroke (within 8 % of its length, within 12° of horizontal) over a text line becomes a Highlight of those words in the marker's colour; a straight pen stroke within 4 pt below a line becomes an Underline. Anything else stays ink. Not done yet: circle-then-hold lasso, and the two-step undo back to the raw stroke.

## Zoom writing box (#130)

Draw ▸ **Zoom box** docks a 200 dp writing pad at the bottom of the page area and draws its frame on the page. The pad shows that part of the page (from the page's latest whole-page image) and its ink, magnified to the pad's width. Writing in the pad adds ink at the matching page points with the selected pen. Lifting the pen in the shaded right-hand fifth moves the box 60 % of its width along the line; past the right margin it goes to the start of the next line, and past the bottom to the next page (the view follows). **New line** jumps there directly; dragging the frame on the page with a finger moves it (a horizontal drag also sets where new lines start). The frame and pad are viewer-only. The pad's background starts from the page's whole-page image and, about 120 ms after the box settles, is replaced by a render of just the box's region at the pad's own magnification (one pixel per pad pixel, at most 24 pixels per point), so small print stays sharp; that render is not cached and is redone when the page changes.

## Low-latency ink (#128, experimental)

**Low-latency ink (experimental)** in Pen settings is off by default. When on, Draw adds a transparent `SurfaceView` driven by `androidx.graphics:graphics-core` `CanvasFrontBufferedRenderer`: each new segment of an opaque, non-marker freehand stroke is drawn straight into the front buffer, while Compose still draws and saves the stroke as before; the front buffer is cleared when the stroke ends or is cancelled. Translucent pens, the marker, shapes and the zoom pad keep the normal path. No latency improvement is claimed: touch-to-photon latency before and after, tearing and the match with the saved stroke still have to be measured on a Galaxy S Pen device before it can be turned on by default.
