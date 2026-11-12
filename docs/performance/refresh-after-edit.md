# The refresh after an edit

Issue: #131.

## What the cost is

On a large file, the time after a comment edit is spent in the engine's refresh (`Session::refresh_scoped`), not in re-parsing the renderer. The first repaint after an edit takes about as long as any other repaint (21–59 ms on the fixtures below).

Callgrind over three ink `comment_add` calls on the 869-page link fixture, on `main` at 74a4b47, shows:

- 91% of the edit is in `refresh_scoped`.
- 45% of the edit is COS parsing, spread over 840,930 object loads.

The refresh reads every page annotation in three passes:

1. Display appearances and fields (`display_bytes_collect_fields`).
2. Form discovery (`pdfcraft_forms::fields`).
3. The comment list (`comment_list`).

The ~28,000 annotations need more than the editing cache's ordinary 32 MB. The cache was therefore emptied during every pass, and each pass parsed every annotation again. Opening had the same problem and fixed it with an `OpenPasses` guard (#239).

## Change

`refresh_scoped` holds an `OpenPasses` guard for the length of the refresh, as opening does:

- The passes share one parse.
- The cache may grow to the opening cap (96 MB) while the refresh runs.
- When the guard drops, the cache is trimmed back under 32 MB.

Results and output are unchanged, because a cached object is the one a fresh load returns.

## Measurement

`session_contention_timings edit`, release profile, on a 4-core cloud host. Three ink comments, two runs each, before = `main` at 74a4b47. These are host numbers, not phone results.

| Fixture | Before | After |
| --- | ---: | ---: |
| 869 pages, 32 links and 2 comments a page, with images (357,797,013 bytes) | 1,354–1,556 ms | 839–955 ms |
| Same pages without images (6.2 MB) | 803–1,190 ms | 435–539 ms |

## Keeping comment appearances (#322)

Before #322, a comment or form edit emptied the comment-appearance cache. Every comment without its own appearance was then drawn and compressed again. That was about 18% of the edit, and about 12% of the edit was zlib setup for these small streams.

Each cache entry is now checked against everything its appearance is drawn from:

- the crop box,
- the annotation dictionary,
- the values its references lead to (colours, `/QuadPoints` and so on).

An annotation the edit changed is drawn again. Every other annotation reuses its entry. The reference budget is spent for every annotation, cached or not, so output is byte-identical to an uncached pass. An annotation whose followed dictionary still holds a reference that was not followed is never cached. A full refresh still empties the cache.

`cached_appearances_match_a_fresh_pass_after_every_edit` (crates/annot) checks this. After each of several edits it compares the cached display copy, byte for byte, with a fresh uncached pass. The edits are:

- a colour change,
- a change to a referenced colour,
- a change to referenced quad points,
- a smaller crop box,
- a shorter annotation list,
- a contents change.

The test also checks that unchanged annotations were reused.

Measured with `session_contention_timings edit`, release profile, on the same kind of host. Three ink comments, two runs each, before = `main` at 93125b4:

| Fixture | Before | After |
| --- | ---: | ---: |
| 869 pages, 32 links and 2 comments a page, without images (6.2 MB) | 557–705 ms | 465–553 ms |
| Image pages with no comments | 227–241 ms | 210–248 ms (no comments to keep, unchanged) |

These are host numbers, not phone results.
