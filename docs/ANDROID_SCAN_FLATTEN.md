# Scan: flattening a curled page (#302)

A four-corner crop assumes the page is flat. A sheet held in the hand, a curled receipt or a
folded letter is not, and its lines of text come out bent. With **Flatten page** on, the page is
drawn flat by a network that says where each point of the flat page is in the photo. Code:
`apps/pdfcraft-android/scan/src/flatten.rs`, used by `process_page` when `PageRequest.flatten` is
set, which `AppViewModel.saveScan` does when the chip beside the looks is on.

**It is off unless the person turns it on, for every page of that scan.** Nothing measured so far
tells a bent page from a flat one well enough to decide for them; see "Measured" below.

## The model

[UVDoc](https://github.com/tanguymagne/UVDoc) (Verhoeven, Magne and Sorkine-Hornung, SIGGRAPH Asia
2023), MIT, Copyright 2023 Tanguy Magne: a fully convolutional network of about 8 million weights,
15.8 MB as shipped. It looks at a picture 488 × 712 pixels and returns a grid of 31 × 45 points.
The network draws no pixels: the grid is used to resample the photo itself, so the text is as
sharp as the camera recorded it. `tools/scan-models/README.md` says how the shipped file was made
from the published checkpoint, and `assets/models/ATTRIBUTION.txt` carries its notice. The file is
pinned by size and SHA-256 like the detector's and runs on `rten`.

The model ships in the APK (15.8 MB more) and is unpacked with the other models. The chip is
shown only when the pinned file is there (`scan_can_flatten`).

## How a page is flattened

1. **What the network is shown.** Not the photo, and not the page's bounding box: the page as the
   plain four-corner straightening draws it, with 8 % of its surroundings kept on every side, at
   488 × 712. A landscape page is turned a quarter first, because the network was trained on
   upright pages. (Showing it the bounding box of the page in the photo was tried first, as the
   issue describes. On a page lying at an angle the result was 11 % of the diagonal away from the
   reference; with the straightened view, 1.3–1.5 %.)
2. **The grid.** The answer, from −1 to 1 across that picture, is carried back through the same
   straightening into photo pixels (and turned back for a landscape page).
3. **Refusals.** A grid with a point that is not a number, or one that folds the page over
   itself (`FlattenGrid::folds`; none of the grids for the ten photos below has one), is not
   used: the page is straightened by its four corners as without the switch, and `flattened` is
   false.
4. **Resampling.** Each output pixel's place in the photo is read from the grid with Catmull-Rom
   interpolation (a bilinear grid has kinks at the cell borders), and the photo is sampled there
   by the same sampler the straightening uses, which averages when it shrinks. The output's edges
   are the grid's edges. Outside the photo is white.
5. The page's size and proportions are the ones worked out from its corners (#293), as without
   flattening.

`PageResult.bend` is how far the grid is from the best perspective fit to it: the 95th percentile
of the distance, as a share of the diagonal. It is recorded, and nothing is decided from it.

## Tests

`scan/src/tests_flatten.rs`, on drawn pages:

| Test | Result |
|---|---|
| A page curled like a cylinder, flattened with the exact grid | 31.6 dB against the flat page; lines wander 0.00 px (14 px when straightened by the corners alone) |
| A grid that is a plain perspective, against `rectify` | mean difference 0.00 grey levels |
| A straight line across cell borders | largest jump 0.0001 px |
| Conventions: −1…1 to pixels, the margin, a landscape page turned and turned back | exact |
| Grids with a hole or a fold | refused |
| The network on curled pages it has not seen (drawn) | lines wander 2.0 px (24.5 px without); a flat page's `bend` is 0.27 % |

## Measured

On the ten private reference photos (the same photo as another scanner drew it; they are not in
the repository). Times are one page on a desktop processor, four threads: the model 0.39–0.55 s,
the resampling 15–132 ms for pages of 0.2 to 2 megapixels. **No phone has been timed.**

Seen side by side, three of the ten pages are visibly bent in the photo (a sheet sagging in the
hand, a curled receipt, an invoice with a fold), and flattening straightens their lines; the
fold is left with a slight wave. The seven others are flat or nearly so.

Two measures were tried and neither is steady enough on the bent pages to carry a number that
means much:

- *Distance from the reference's geometry* (`reference_pairs.py register`: what is left between
  our page and the reference after the best perspective fit, 95th percentile, % of the diagonal).
  The same pipeline, run twice, gave 3.5 and 1.5 for the sagging sheet without flattening, and
  4.2 and 1.9 for the invoice, because the fit depends on which points happen to match. On the
  flat pages it repeats to about 0.1.
- *How level the text lines are, block by block* (no reference needed).

| Page | Distance from the reference, plain → flattened | The same, run again | Line angle off level, plain → flattened |
|---|---|---|---|
| A, a sheet sagging in the hand | 3.48 → 2.27 | 1.47 → 1.18 | 2.0° → 0.5° |
| F, a curled receipt | 3.13 → 1.11 | 2.40 → 1.31 | 1.4° → 0.8° |
| G, an invoice with a fold | 4.20 → 1.90 | 1.86 → 2.36 | 0.7° → 1.7° |
| C, a flat label | 0.09 → 0.35 | 0.09 → 0.31 | 0.0° → 0.3° |
| E, a flat note | 0.25 → 0.88 | 0.29 → 0.83 | 1.1° → 2.1° |
| B | 0.85 → 0.60 | 0.79 → 0.64 | 0.9° → 0.5° |
| D | 0.45 → 1.45 | 0.48 → 1.26 | 0.8° → 0.9° |
| H | 0.55 → 2.60 | 0.47 → 2.29 | 1.3° → 0.9° |
| I | 0.49 → 1.62 | 0.44 → 1.32 | 0.9° → 0.7° |
| J | 0.71 → 0.66 | 0.69 → 0.69 | 0.7° → 0.8° |

(B, D, H, I and J are ordinary sheets on a table or in the hand, one of them lying at an angle.
The line angle is the mean, over the blocks of a 4 × 4 division of the page that hold text, of how
far that block's lines are from level; it is itself rough, and reads 1.1° on a page that looks
level.)

What can be said from this: on a flat page the network moves things the plain straightening
had right, by up to 2 % of the diagonal (C, D, E, H, I, in both runs), so flattening must not be
on for every page. On the sagging sheet and the receipt it helps by both measures. On the invoice
the eye says its rows are more level and the measures do not agree with each other.

## The decision, set on the owner's photos (#466, 13 Nov)

The owner asked for flattening to be better and automatic. The ten reference photos were run
again, this time **with the app's own corners** (the detector and the edge refinement, the whole
photo where no page is found), which is what the app does and what the table above did not: it
used the reference's corners. The photos are private; only figures leave them.

**A steadier measure.** The distance from the reference in the table above repeats badly because
it reads a fitted cubic at places where no points matched. It is replaced here by two parts,
both in % of the page's diagonal, from points matched between our page and the reference's:

- *lean*: how far the best perspective map between the two pages is from a plain stretch along
  each axis. This is what corners that are a little off leave behind: a skew, a keystone, a turn.
- *bend*: how far the matched points still are after that best map (90th percentile).

By this measure the three bent pages are bent by about 0.5 % of the diagonal (A 0.47, F 0.51,
G 0.49) and the flat ones by 0.11 to 0.22. The figures of 2.6 to 4.5 % in this file and in #302
were several times too large.

**What the measure showed:**

- With the reference's corners the network makes every flat page a little worse and helps the
  receipt (as the table above says).
- **With the app's own corners the largest part of the distance is lean, not bend** (up to 3.4),
  and the network takes much of it out, because it finds the page again. That is where
  flattening earns its place: on pages whose corners were found a little off.
- The page's lines of text could not be followed under shade: on the sheet in the shade of a
  tree the guard measured 4 lines on one page and 3 on the other, and called the flattened page
  twice as bad.
- On the one page that is a whole photo because no outline was found, the network's page read
  26 % straighter and was further from the reference.

**What changed:**

1. The guard measures both pages with their light evened out (`page::evened_grey`: each pixel
   over the bright level of its part of the page; what is darker than 0.45 of the page's bright
   level is left dark). On the shaded sheet 15 and 16 lines are followed. The text-line fallback
   finds its lines on the same evened page.
2. Under the floor of 8 lines, a flattened page is still kept when both pages have at least 4
   lines and it reads half or less of the straightened page's figure (reason `muchbetter`). A
   change that large is lean being taken out.
3. In the automatic mode (the check is asked for) a page that is the whole photo is not given to
   the network. Its lines of text may still flatten it. Flatten page by hand still asks the
   network.

**The ten photos, the app's own corners, distance from the reference (lean and bend together):**

| Page | Flattening off | Automatic, before this change | Automatic, now | What now decides |
|---|---|---|---|---|
| A, sheet in the shade of a tree | 0.88 | 0.88 | **0.43** | network, 9.9 → 4.4 px on 15 and 16 lines |
| B, open booklet in the hand | 0.58 | 0.58 | 0.58 | not kept, 3.4 → 5.4 px |
| C, flat label | 0.16 | 0.16 | 0.20 | network, 2.9 → 1.9 px on 8 lines |
| D, parcel label | 0.88 | 0.88 | 0.88 | not kept |
| E, handwritten note | 1.81 | 1.81 | **1.22** | network, 12.2 → 4.0 px on 6 and 7 lines (`muchbetter`) |
| F, curled receipt | 2.15 | 1.83 | 1.83 | network, 9.6 → 7.3 px |
| G, invoice with a fold (whole photo) | 0.83 | 0.83 | 0.83 | network not asked; lines not kept |
| H, a note on another sheet (whole photo) | 0.88 | 0.88 | 0.88 | network not asked; lines not kept |
| I, a page already scanned | 3.44 | 3.44 | 3.44 | not kept, 4 lines |
| J, a page already scanned | 2.38 | 0.78 | 0.78 | network, 13.2 → 6.7 px |
| Mean | 1.40 | 1.21 | **1.11** | |

Five pages are flattened and five are not. Of the five, four are clearly closer to the reference
(two of them new with this change) and one, the flat label, is 0.04 further, which is within
what the measure can tell apart. No page is clearly worse. With the reference's corners only A
and F are flattened, both closer.

**What this does not show:**

- Ten photos. The rules fit them; the 4-line rule rests on one page.
- The distances that remain are mostly lean the network did not take out (F 1.71, I 3.44, E
  1.15): corners found off. That is the detector's and the refinement's work (#290, #291), not
  flattening's.
- The fold's sideways squeeze (G) is not helped by either method.
- Time: 0.9 to 1.7 s a page on a 2-core desktop in the automatic mode, against 0.3 to 0.6 s
  where the network is not asked. **No phone.** The owner's log of 13 Nov has the network alone
  at 352 to 400 ms on his phone.
- Whether the flattened pages look right was checked by eye for three of them only.

**Recommendation:** the automatic mode can be on by default. It is `ScanLab.flattenAuto`, still
off: turning it on, with the notice and the way back that #466 asks for, is that issue's.

## Automatic mode, as a testing option

The issue's first rule (flatten when the grid's `bend` is 0.3 % or more) is not used. On the ten
reference photos `bend` was 0.34–1.30 % for every page, flat ones included, so it would flatten
everything. Whether a side was found curved when the corners were refined (#291) does not separate
them either.

What is built is the second rule, the quality guard:

- With `flatten_check` (`PageRequest`, `ScanPageRequest`), a flattened page is made, and so is the
  page straightened by its corners alone.
- Both are measured with `line_straightness` (`straightness.rs`, from #382), and
  `flatten_guard::guard` keeps the flattened page only when its figure is at least 10 % lower.
- When both are under half a pixel there is nothing to gain, and when either cannot be measured
  (fewer than three lines of text followed) the straightened page is kept.
- A floor on the lines followed (#302 review, see below): a flattened page the figures would keep
  is kept only when at least 8 lines of text were followed on both pages; otherwise the
  straightened page is kept and the reason is `fewlines` (`flatten_guard::guard_with_floor`,
  `PageRequest::flatten_min_lines`, `ScanPageRequest.flatten_min_lines`, 8 by default, 0 for none).
  It applies to the network's page in the automatic mode and to the page flattened from its lines
  of text (#303, part B).
- The result says which page was kept, why (`straighter`, `notbetter`, `unmeasured`, `straight`, `fewlines`, `muchbetter`),
  both figures and the number of lines each had. The extra work is timed as `scan_flatten_check`.
- The preview (#301) makes the same choice, at its own size. Near the 10 % margin the preview and
  the saved page can choose differently, because the figures are in pixels of each size. Both are
  logged.

In the app this is the testing option **Flatten pages automatically when it makes their lines
straighter** (Settings, About, Diagnostics), off by default. With it on, every page goes through
the network and the guard, unless the scan screen's Flatten switch is on, which flattens without
the check. It stays off by default because nothing has run on a phone: how long the network and a
second straightened page take there, and whether the guard decides well on real pages, is what
the log fields below are for.

### The floor on lines followed

A review of `main` at `fa61b265` ran the automatic mode on the owner's ten reference photos (figures
in a comment on #302). On pages straightened by their corners the measure read 4 to 19 px, and on
4 of the 10 it followed only 3 or 4 lines; a 10 % difference between two such readings rests on
very little. The most bent page followed only 3 lines. So the guard now needs 8 lines on both
pages before it may keep a flattened page. On those figures it changes none of the three pages
that were kept (the curled receipt on 13 lines each, the invoice on 34 and 32, the 0.64 % page on
14 each); it stops a future verdict on 3 or 4 lines, like those the four weakest pages gave. The
figures are the review's, not re-run here; nothing was run on a real photo for this change.

The automatic mode stays **off by default**, as the review advises, until the owner's kept scans
(`straightened_px`, `flattened_px` and both line counts are already in them) say otherwise.

Testing option: **Keep a flattened page only when at least 8 lines of text were followed**
(`ScanLab.flattenLineFloor`, `flatten_line_floor`, on). Off is the guard as before.

Log fields on each `page` and `preview` line:

- `flatten_auto` and `flatten_line_floor` (the option);
- `flatten_checked`, `flatten_kept` and `flatten_why`;
- `straight_px` and `straight_flat_px`, in tenths of a pixel, -1 when not measured;
- `straight_lines` and `straight_flat_lines`.

Kept scans: `result.flatten_check` in each page file (its `why` may be `fewlines` or `muchbetter`), and
`options.flatten_line_floor`.

What was measured, on drawn pages only (`a_checked_flattening_is_kept_only_when_the_lines_come_out_straighter`,
with the pinned model):

- a sheet bowed by 24 px, with 25 lines of words: straightened 5.58 px, flattened 1.59 px, so
  the flattened page was kept (`straighter`);
- the same sheet flat: 0.005 px against 0.49 px, so the straightened page was kept (`straight`).

Known limits:

- `line_straightness` decides ink with a single threshold over the whole page, and the guard
  measures the page before its look. A strong shadow across a page could hide its lines and give
  `unmeasured`, which keeps the straightened page.
- Ruled table lines are followed as if they were text lines.
- Both pages are held at full size at once while they are compared.

## Flatten page, one page at a time

The scan screen's **Flatten page** switch is each page's own (`ScanPage.flatten`): it flattens
the page shown and no other. A page taken afterwards starts with the choice of the page before it,
and a retake with the choice of the page it replaces, so a run of book pages needs one tap. A page
nobody chose for is not flattened. The choice is kept with the scan when Android closes the app
(`ScanSession`, the eighth field of a page line), and the page preview (#301) follows it.

- Log: `flatten_page` on each `page` and `preview` line (`on`, `off`, or `none` when the person
  made no choice for the page; `flatten` is still whether the page was flattened), and a
  `flatten_page` line (`index`, `flatten`) for each tap of the switch.
- Kept scans: `flatten_chosen` in the page file (`true`, `false` or `null`); `flatten` is still
  what was done. `scan_replay` reads `flatten`.

## Border trim

The network's grid follows the page's own outline, but it can reach a little past it and leave a
thin strip of table along an edge. With `flatten_trim` (`PageRequest`, `ScanPageRequest`), a
flattened page that is kept is looked at once more (`flatten_guard::border_trim`, from #382): on a
copy reduced four times, from each side inward, the first line where at least 90 % of the pixels
are within 40 of the page's colour (the median of its central half) is the page's edge. At most 2 %
of a side is cut, and a side whose first line is already page is left alone.

What is left is then **drawn again from the photo at the page's own size**
(`flatten::unwarp_trimmed`): the output's corners are moved along the grid to the trimmed edges,
so the photo is sampled once and the page keeps its proportions, rather than being cropped (which
would change them) or cropped and stretched (which would sample it twice). The strip was table,
not page, so stretching what is left over the page's size puts the page's edge where it belongs.
The stage is timed as `scan_flatten_trim`; the result's `trim` gives each side as a share of the
page's size across it (before any turn upright). A trim that would leave less than half a side is
refused, and the untrimmed page is kept.

In the app this is the testing option **Trim the background left along a flattened page's edges**,
on by default: off keeps the flattened page as the grid gives it.

- Log: `flatten_trim` (the option), `trim_looked`, `trimmed`, and `trim_left_pct`, `trim_top_pct`,
  `trim_right_pct`, `trim_bottom_pct` in percent of each side (-1 when no trim was looked for) on
  each `page` and `preview` line.
- Kept scans: `options.flatten_trim`, and `result.flatten_trim` with `left_pct`, `top_pct`,
  `right_pct`, `bottom_pct` (null when no trim was looked for). `scan_replay` reads the option.

What was measured, on drawn pages only:

- `a_strip_of_table_along_a_flattened_pages_edge_is_trimmed_and_the_page_keeps_its_size`
  (`tests_flatten.rs`): a printed page bowed by 20 px on a dark table, flattened along a grid
  2.5 % wider than the page, so every edge column and row of the result is table. The trim found
  a strip on each side within the 2 % limit, the page drawn again is 600 × 848 as before, under
  20 % of each edge is still dark, and its printed frame stays within 1.5 px of straight. The same
  pixels on 1, 2 and 4 threads; no trim gives `unwarp`'s page exactly; the page's exact grid gives
  (almost) no trim.
- With the pinned model (`a_checked_flattening_is_kept_only_when_the_lines_come_out_straighter`
  in `src/scan.rs`): on the drawn bowed sheet the trim is looked for, stays within 2 % of each
  side and adds its stage; a page the check did not keep flattened is not trimmed; the preview
  says the same. Whether the network leaves any strip there is printed, not asserted.

Known limits: the trim moves in steps of 4 pixels of the page (the reduced copy); a page whose
edge is printed to the border (a photo, a dark band) is not trimmed on that side; a strip lighter
than 40 grey levels from the paper (a white table) is taken for page.

## Not done

- The automatic mode is a testing option, off by default (above). Choosing its default needs
  real photos.
- No phone timings; nothing has been run on a device. How often the network leaves a strip on
  real photos, and whether the trim's thresholds suit them, is what its log fields are for.
