# Annotation passes share one parse during opening (#239)

Complete preparation reads every page annotation of the editing source in several passes:
signature discovery, missing comment appearances, then form discovery (which reads each
annotation twice). All of them read the same `pdfcraft_cos::Document`, whose shared editing cache
is capped at 32 MB. A file with ~28,000 link annotations counts more than 50 MB of parsed objects,
so the cache was cleared at about 17,000 objects, twice in each pass, and every pass parsed every
annotation again.

`Document::open_passes` returns a guard. While any guard on the shared cache lives, the cap is
96 MB; when the last one drops, a cache above 32 MB is emptied, as eviction always did. The
object-count cap (65,536) is unchanged. `open_prework` holds one guard over its passes. Reads are
unchanged: a cached object is what a fresh load gives, and eviction only costs parsing.

## Phone timings that led here

Owner's 0.0.23-beta (23012), Galaxy S24 Ultra, 352 MB / 869 pages, input mapped: first page
0.31–0.64 s, editing ready 2.7–3.0 s. Background stages: inspection 1.2–1.9 s, signatures
0.8–1.5 s, form discovery 0.56–1.04 s, appearances 0.28–0.52 s, display copy 0.2–0.57 s.
Inspection, which runs beside these passes, is the longest single stage. This change does not
shorten it directly; it removes repeated parsing that competes with it for CPU and the allocator.

## Host measurement

Synthetic ordinary-xref PDF, 356,350,823 bytes, 869 pages, 32 links per page (27,808 total),
one raw 400×337 RGB image per page; 4-core x86 host, release profile, owned input (not mapped).
`apps/pdfcraft-android/examples/open_stage_timings.rs`, 8 reading-stage opens per build after a
warm-up, both builds from 0.0.24 main, alternated.

| Stage | Before | After |
| --- | ---: | ---: |
| `native_display_form_discovery` | 190–252 ms | 23–35 ms |
| `native_display_appearances` | 115–176 ms | 107–153 ms |
| `native_signatures` | 41–72 ms | 48–62 ms |
| `native_inspect_lazy` | 286–367 ms | 269–351 ms |
| `native_background_prepare` (median) | 398 ms | 344 ms |

Peak RSS of the whole example process (two opens, owned 356 MB input) was 654–685 MB before and
705–714 MB after: the larger cache is a peak during opening. These are host numbers only; no
phone result is claimed until the in-app open timings show one.
