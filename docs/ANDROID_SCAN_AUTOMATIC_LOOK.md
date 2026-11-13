# Scan: the automatic look (#466)

One look that decides everything for each page by itself, on every page, with no taps. Its
visible name is the owner's choice; today it is **Clean scan**, first in the looks strip, and
every new page starts with it. The other looks stay for anyone who wants one: Original,
Auto color, Grayscale, Black and white, Whiteboard.

## What it decides

| Decision | How | Testing option |
|---|---|---|
| Borders | Edge finding as each page joins (#290, #291) | (existing) |
| Upright | Orientation classifier, "Turned upright · Undo" (#339) | (existing) |
| Document type | Clean scan's kinds (#299) | (existing) |
| Tone, paper colour, sharpening | The kind's own settings (#298, #299) | (existing) |
| Grayscale for a receipt | **Applied, not offered**: the page becomes Grayscale, with "Grayscale · Undo" | `autoLook` (on) |
| Flattened or not | **Its own decision**: a page with no Flatten page choice is asked with the guarded check of #302 (kept only when its lines of text come out straighter; the line floor and the text-line fallback apply), with "Flattened · Undo" | `autoLookFlatten` (**off**) |
| Every page, without opening it | The background queue (#465) | `backgroundPages` (on) |

The Flatten page switch stays as the person's override for one page: on or off, it wins, and the
automatic decision is not made for that page. Undo on either chip takes that change back for that
page only, and it is not made again for it (kept in the scan session). Choosing another look by
hand counts as taking Grayscale back. A look the automatic look chose is not passed on to the
next page taken.

With `autoLook` on, the Grayscale suggestion chip is no longer shown (it is applied); with
`autoLookFlatten` on, the Flatten page suggestion chip is no longer shown (it is decided).

## Why flattening is off

On the owner's ten reference photos (comment on #302, 12 November) the automatic decision
flattened two of the three pages that most need it, missed the most bent one, and flattened one
page that needs almost nothing. The review session, which has the owner's photos, sets the
decision (the floor on lines followed, the margin, when the text-line method is preferred) and
reports the figures in #302 in words and numbers only. The default turns on only when that
report says the three bent pages are flattened and the flat ones are left alone, or names the
pages it cannot do.

## What it does not do yet

Moiré (#305), fingers, glare and stains (#306) are only reported in kept scans; the page is not
changed. There is no deblurring: a shaken photo stays shaken (#296 only records a blur score). No
learned enhancement of the whole page (#304). When those reach the point of changing pages and
have been checked on the owner's photos, they become part of this look, not new switches.

## Log and kept scans

- `auto_decisions`, one line per saved page: `index` (from 1), `kind`, `turned`, `flattened`,
  `flatten_why` (the check's word, `chosen`, `undone` or `off`), `suggestion_applied`
  (`greyscale`, `flatten`, both joined by `+`, or `none`), `undone`, `auto_look`,
  `auto_look_flatten`.
- `auto_applied` (`index`, `suggestion`) when a page is made Grayscale; `auto_undone` (`index`,
  `what`) for each Undo.
- `preview` lines: `auto_look_flatten` and `flatten_decided`.
- Page file: `auto_decisions` with the same fields; the options in `options`.

Nothing here has run on a phone; checked with unit tests only.
