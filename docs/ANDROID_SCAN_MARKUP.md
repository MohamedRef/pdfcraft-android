# Scanner: markup on a scanned page (#495)

The owner asked on 0.0.26-beta for markup on a scan page and a way to remove things by hand. Until
then a page could only be cropped, turned and given a look; writing on it was possible only after
Save PDF, in the viewer, and nothing let the person remove a mark, a stain or a finger the
automatic search missed.

**Nothing here has run on a phone.** It is checked with unit tests on pages drawn in code only.

## What the person sees

- Enhance's **More** has **Markup** and **Clear markup** (behind the testing option, see below).
  Markup needs the page as it will be saved (the preview), so it is greyed out while the
  preview is off or not made yet; Clear markup only when the page has marks.
- Markup opens the page full screen with four tools: **Erase**, **Pen**, **Highlighter**,
  **Text**. Each has three sizes; Pen, Highlighter and Text have their own colours. One finger
  draws or rubs, two fingers move and zoom (as the viewer's Draw); an S Pen's pressure changes
  the pen's width. Undo, Redo and Clear are at the top, Done at the bottom. Back (the arrow or
  Android's Back gesture) keeps the marks, as Done does (#531, the owner's choice): Undo and Clear
  take them away. Before #531 Back was Cancel and threw them away without asking.
- **Colours (#503)**, behind their own testing option: each tool's row keeps its few swatches,
  shows the colour in use when it is another one, and ends with **More colors** (a palette icon)
  and the **eyedropper**. More colors opens the viewer's colour sheet: the recent colours, the
  palettes (the standard 18, the colour-blind-safe set and the person's own named palettes with
  new, add, export, import and delete) and a custom colour. It is the same store as the viewer's
  pen, so a palette made in either is in both, and a colour picked in Markup joins the recent ones.
- **Eyedropper (#503)**: touch the page and move; a loupe above the finger shows the page three
  times closer, the disc being averaged and, as its ring, the colour it would take. Lifting the
  finger takes the mean colour of that disc of the made page's pixels (about a 250th of its longer
  side across) for the tool in use, puts it in the recent colours and ends the eyedropper. With
  Erase it also turns the fill to Color.
- **Erase's fill (#503)**, a row of chips above its sizes: **Paper around it** (the default, as in
  #495), **Color** (the picked colour, opaque, at the eraser's sizes with its soft edge: with the
  eyedropper, "paint with the colour I picked from the page") and **Follow the edges** (behind its
  own testing option while it is judged). The hint says what the chosen fill does, e.g. "Rub over
  something to paint the paper around it over it."
- **Text**: a tap on the page asks for the words, a size and a colour, and places the box there.
  A tap on a box picks it (it can be dragged, edited or deleted); an empty text removes it.
- A **black-and-white** page (#300) has no Highlighter (it has no grey to multiply): pen and
  text are drawn black and the eraser makes paper. Erase's Color is black or paper only, and
  More colors and the eyedropper are not offered there. A highlighter drawn while the page had
  another look is left out on that page and counted (`skipped`).
- New borders (Borders, Crop) or a change of flattening (the Flatten page switch, a flattening
  suggestion, "Flattened · Undo") on a page with marks first ask **"Remove this page's markup?"**;
  Keep is the safe answer. Borders removes the marks only when the corners really change on Done.
  A quarter turn (Rotate, "Turned upright · Undo") keeps them: they turn with the page.

## How it is kept

Marks are data, not pixels (`ui/scan/ScanMarks.kt`): strokes as points in shares of the page as it
was shown (x, y, pressure), the width as a share of the page's longer side, the colour and tool;
text boxes as their words, centre, letter height (share of the longer side) and colour. With them
is `turns`, the page's quarter turns in all (classifier and person) when they were made. They are
kept

- in `ScanPage.marks`;
- in the scan session (`ScanSession`, a `marks` line after the page line and before its `look`
  line, so a build from before #495 ignores it). A session longer than 200 000 characters is
  written to `cacheDir/scan-state.txt` and the saved state names the file, since Android refuses
  a saved state of about 1 MB;
- in a kept scan of Recent scans (#461), which is a session file, so a scan opened again has its
  marks and can change them.

Points closer than 15 % of the stroke's width to the last one kept are dropped when a stroke ends,
which keeps a session small without changing the line.

## How it is drawn

`apps/pdfcraft-android/scan/src/markup.rs`, called from `page::page_look_kind` after the look and
after the finger step (#466), so the preview, the background queue (#465), Save PDF, Share and the
gallery (#470) all draw the same marks from the same request (`ScanPageRequest.markup`). A whole
photo in Original with marks is decoded and drawn on rather than copied. The engine:

- turns each point by the page's turns now minus the marks' `turns`, so a later quarter turn moves
  them exactly;
- draws a **pen** stroke as round-capped segments whose radius follows the pressure (35 % of the
  width at no pressure), anti-aliased over one pixel; the stroke's coverage is found once, so a
  stroke crossing itself is laid down once;
- draws a **highlighter** by multiplying the page with its colour at 85 % where it fully covers,
  so print under it stays dark;
- **erases** by painting the paper around the stroke: the page near the rubbed area is cut into
  blocks of about a 64th of its longer side; each block's paper is the mean of its lighter half
  (print, stains and shadows are the darker part) leaving out what the eraser covers; blocks
  under the eraser take their neighbours' paper, ring by ring; the fill follows those blocks
  bilinearly, with the paper's own grain (the median spread of the blocks' lighter halves, at most
  12 levels) added back as seeded noise, and a smooth edge a quarter of the brush's radius wide.
  On a tinted page whose light falls off by 30 % across it, a dark blot rubbed out comes within
  4 grey levels of the clean paper at every pixel (test `the_eraser_paints_the_paper_around_it`).
  Consecutive eraser strokes are one fill;
- paints the eraser's **Color** fill (#503) as the picked colour blended in by the eraser's own
  weight (opaque inside, the same soft edge); on a black-and-white page it is ink or paper,
  whichever the colour is nearer;
- **follows the edges** (#503) by cutting the area around the strokes into blocks of about half the
  brush's radius (3 to 32 pixels); a block at most a quarter under the brush keeps the median of
  its pixels per channel (a thin print line does not move a median); the blocks under the brush
  take the smooth surface that meets all of those (each the mean of its four neighbours until
  nothing moves by a twentieth of a level, a membrane), so a gradient across the brush is carried
  through instead of filled ring by ring; the fill follows the blocks bilinearly, with the grain
  measured around the brush (the spread of each pixel against the mean of its four neighbours,
  which a smooth shading does not change, at most 12 levels) added back as seeded noise. On paper
  shaded from 240 to 100 across a band, a dark blot rubbed out with it comes within 5 levels of the
  clean page at every pixel, where the paper fill is 38 off (test
  `the_edge_fill_continues_a_shaded_band`); on the tinted page it is 5 off
  (`the_edge_fill_also_paints_plain_paper`). It does not continue lines or texture (a table's
  rules, a dotted line): that is patch-based fill, its own issue (#506). Consecutive strokes
  of the same fill are one fill;
- draws **text** from a mask of coverage the app makes with Android's own font (the engine has
  none), at the letter height it has on a page 3 000 pixels long, scaled into the box.

The Markup screen asks the engine for the page with its strokes after each stroke ends (the
eraser's paper is the engine's own) and draws the stroke being made, and those the engine has not
drawn yet, on top. It draws the text boxes itself from the same mask, so they can be moved.

## Testing option, log and kept page file

- Testing options **Markup colors** (`markupColours`, key `markup_colours`, on: More colors, the
  eyedropper and Erase's Color) and **Erase: Follow the edges** (`markupEdge`, key `markup_edge`,
  on, while it is judged). Off, those are not offered; strokes already made with them are drawn
  as they are (#503).
- Testing option **Markup on scan pages** (`markup`, key `markup`, on). Off: More has no Markup
  or Clear markup, and no page is drawn with marks (they stay with the page and come back when the
  option is on again).
- Log: `markup_open` (`index`, `marks`); `markup_done` (`index`, `strokes`, `pen`, `highlighter`,
  `erase`, `text`, `erased_share`, `ms` in the screen, `cancelled`, `changed`; since #503
  `custom_colours` (marks in a colour that is not their tool's own swatch), `eyedropper` (colours it
  picked), `erase_colour` and `erase_edge` (strokes) and `erase_fill` (`paper`, `colour` or `edge`,
  Erase's fill when the screen closed); since #531 `left_by` (`done`, `back`, `gesture`) and
  `discarded` (marks made in the session and not kept, 0 now that every way out keeps them)); `markup_clear`
  (`index`, `why`: `clear`, `borders` or `flatten`, `marks`); `markup_ask` and `markup_kept`
  (`index`, `why`); on `page` and `preview` lines `markup` (the option), `marks`, `marks_pen`,
  `marks_highlighter`, `marks_erase`, `marks_text`, `marks_erase_colour`, `marks_erase_edge`, `marks_custom_colours`, `erased_share` (share of the page the eraser
  painted, 0–1) and `markup_ms` (-1 when no marks were drawn).
- Kept page file: `marks` with `pen`, `highlighter`, `erase`, `text`, `total` and
  `erased_share`, and since #503 `erase_paper`, `erase_colour`, `erase_edge`, `custom_colours` and
  `eyedropper` (its picks on that page in this scan; a kept scan opened again starts from 0), or
  `null` for a page without marks. **Never the strokes or the words.**

## Not done, and not checked

- A session or kept scan with `erase_colour` (`c`) or `erase_edge` (`g`) strokes opened by a build
  from before #503: those strokes are left out (an unknown tool is not guessed), the others kept.

- The saved PDF has the marks in the page's picture (the same pixels the gallery gets). Writing
  them as real PDF annotations is a question for the owner.
- The old single scan screen (testing option `steps` off) has no Markup entry.
- Signature and stamp on a scan page, mosaic, removing handwriting automatically: each its own
  issue if the owner asks.
- Not checked: anything on a phone (drawing lag, S Pen pressure, the eraser on real photos of
  stains and fingers, the time to draw marks on a 12 MP page on Save), TalkBack on the Markup
  screen, the screen in the seven languages on a real phone, a wide screen.
