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

## Not changed, measured for follow-up

- A comment edit clears the comment-appearance cache, so every comment without its own appearance is drawn and compressed again (about 18% of the edit before this change). About 12% of the edit is zlib setup for these small streams.
- Keeping valid entries would need to know which annotations, and which objects they reference, the edit touched.
