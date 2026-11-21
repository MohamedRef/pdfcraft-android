#!/usr/bin/env python3
"""Aggregates of kept-scan exports: counts, shares and percentiles, never a page (#779).

    python3 tools/scan-eval/kept_scan_summary.py <export folder>... [--log diagnostics.txt]
        [--since "MM-DD HH:MM" | --since last-install] [--until "MM-DD HH:MM"]
        [--wrong-accepted N] [--json out.json]

An export is what Settings > Diagnostics > Scanner testing > Share kept scans hands out, unzipped:
one folder per scan with `page-NN.json` (and the photos), and `diagnostics.txt` beside them.
The folders hold someone's documents: keep them outside the repository. What this prints holds no
file name, no corner and nothing of a page, so it can go into an issue or a pull request.

It reads the page files and the log's `[scan]` lines. Of a photo it reads at most the file header,
for the pixel size a corner move is measured against; no pixel is decoded. Without the photo the
`capture` size is used, and without either the move is measured in the unit square and counted
under `size_unknown`.

Every key is optional: a page file from an older build (no `turns`, no `encoding`, no
`finding.second_look`) gives fewer rows, never an error. A count that cannot be known for a page
is left out of that row's `n`.

Words used here, as docs/ANDROID_SCAN_BASELINE.md defines them: a page is *found* when `found` is
not null; *untouched* when it was found and its corners were left exactly as found; *no touch*
when the largest corner move is at most 0.5 % of the photo's diagonal (#288). A page that was not
found is never untouched, whatever its corners.

Needs only Python's standard library.
"""

import argparse
import json
import math
import os
import re
import struct
import sys

NO_TOUCH = 0.005  # of the photo's diagonal, as scan_replay and metrics_geometry have it
WHOLE = [0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0]
PAGE_FILE = re.compile(r"^page-\d+\.json$")
LOG_LINE = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+) \[(\w+)\] (\S+)(?: (.*))?$")
MOVE_BANDS = ((0.0, "0 (untouched)"), (NO_TOUCH, "up to 0.5 %"), (0.02, "0.5 to 2 %"), (0.05, "2 to 5 %"), (math.inf, "over 5 %"))


def quantile(sorted_values, q):
    """Linear interpolation between the sorted values, as eval.rs summarises."""
    if not sorted_values:
        return None
    pos = min(max(q, 0.0), 1.0) * (len(sorted_values) - 1)
    lo, hi = math.floor(pos), math.ceil(pos)
    return sorted_values[lo] + (sorted_values[hi] - sorted_values[lo]) * (pos - lo)


def spread(values):
    """n, median, p90 (from 10 values), p95 (from 20) and the largest of some numbers; None for none."""
    v = sorted(x for x in values if isinstance(x, (int, float)) and not isinstance(x, bool) and math.isfinite(x))
    if not v:
        return None
    # a 90th percentile of fewer than 10 values, or a 95th of fewer than 20, is one of the largest values under another name
    return {
        "n": len(v),
        "p50": quantile(v, 0.5),
        "p90": quantile(v, 0.9) if len(v) >= 10 else None,
        "p95": quantile(v, 0.95) if len(v) >= 20 else None,
        "max": v[-1],
    }


def tally(values):
    """How often each value came, most often first; None is 'unknown'."""
    out = {}
    for value in values:
        key = "unknown" if value is None else str(value).lower() if isinstance(value, bool) else str(value)
        out[key] = out.get(key, 0) + 1
    return dict(sorted(out.items(), key=lambda kv: (-kv[1], kv[0])))


def dig(value, *keys):
    """value[k1][k2]... or None when a step is missing or not an object."""
    for key in keys:
        if not isinstance(value, dict):
            return None
        value = value.get(key)
    return value


def shares(value):
    """Eight numbers, or None."""
    if isinstance(value, list) and len(value) == 8 and all(isinstance(x, (int, float)) and not isinstance(x, bool) for x in value):
        return [float(x) for x in value]
    return None


def jpeg_upright_size(head):
    """(width, height) of a JPEG as it is seen upright, from its header alone, or None.

    The size is the start-of-frame marker's; an EXIF orientation of 5 to 8 swaps it.
    """
    if head[:2] != b"\xff\xd8":
        return None
    i, turned = 2, False
    while i + 4 <= len(head):
        if head[i] != 0xFF:
            i += 1
            continue
        marker = head[i + 1]
        if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7 or marker == 0xFF:
            i += 1 if marker == 0xFF else 2
            continue
        (length,) = struct.unpack(">H", head[i + 2 : i + 4])
        if marker == 0xE1 and head[i + 4 : i + 10] == b"Exif\x00\x00":
            turned = _exif_orientation(head[i + 10 : i + 2 + length]) in (5, 6, 7, 8)
        if marker in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
            if i + 9 > len(head):
                return None
            h, w = struct.unpack(">HH", head[i + 5 : i + 9])
            if not (w and h):
                return None
            return (h, w) if turned else (w, h)
        i += 2 + length
    return None


def _exif_orientation(tiff):
    """The orientation tag (0x0112) of a TIFF block's first directory, or 1."""
    try:
        order = {b"II": "<", b"MM": ">"}[tiff[:2]]
        (first,) = struct.unpack(order + "I", tiff[4:8])
        (count,) = struct.unpack(order + "H", tiff[first : first + 2])
        for n in range(count):
            at = first + 2 + 12 * n
            tag, kind = struct.unpack(order + "HH", tiff[at : at + 4])
            if tag == 0x0112 and kind == 3:
                return struct.unpack(order + "H", tiff[at + 8 : at + 10])[0]
    except (KeyError, struct.error):
        pass
    return 1


def photo_size(json_path, facts):
    """The upright photo's (width, height): from the photo's header, else from `capture`, else None."""
    stem = json_path[: -len(".json")]
    for ext in (".jpg", ".jpeg"):
        if os.path.isfile(stem + ext):
            with open(stem + ext, "rb") as fh:
                size = jpeg_upright_size(fh.read(1 << 18))
            if size:
                return size
    w, h = dig(facts, "capture", "width"), dig(facts, "capture", "height")
    if isinstance(w, (int, float)) and isinstance(h, (int, float)) and w > 0 and h > 0:
        # the still's own size; a rotation of 90 or 270 shows it turned
        return (h, w) if dig(facts, "capture", "rotation") in (90, 270) else (w, h)
    return None


def largest_move(a, b, size):
    """The largest distance between matching corners, as a share of the photo's diagonal."""
    w, h = size or (1.0, 1.0)
    return max(math.hypot((a[2 * i] - b[2 * i]) * w, (a[2 * i + 1] - b[2 * i + 1]) * h) for i in range(4)) / math.hypot(w, h)


def find_pages(folders):
    """Every page file under the folders, sorted, and every diagnostics.txt met on the way."""
    pages, logs = [], []
    for folder in folders:
        for d, dirs, files in os.walk(folder):
            dirs.sort()
            pages += [os.path.join(d, f) for f in sorted(files) if PAGE_FILE.match(f)]
            logs += [os.path.join(d, f) for f in files if f == "diagnostics.txt"]
    return pages, logs


def summarise_pages(paths, wrong_accepted=0):
    """The aggregates of some page files."""
    facts = []
    unreadable = 0
    for path in paths:
        try:
            with open(path, encoding="utf-8") as fh:
                value = json.load(fh)
        except (OSError, ValueError):
            value = None
        if isinstance(value, dict):
            facts.append((path, value))
        else:
            unreadable += 1
    saved = [(p, v) for p, v in facts if v.get("outcome", "saved") != "discarded"]
    moves, bands, size_unknown = [], {name: 0 for _, name in MOVE_BANDS}, 0
    found = untouched = no_touch = not_found = whole_kept = hand_after_miss = judged = 0
    for path, v in saved:
        left, was = shares(v.get("corners")), shares(v.get("found"))
        if left is None:
            continue
        judged += 1
        if was is None:
            not_found += 1
            if all(abs(x - y) < 5e-6 for x, y in zip(left, WHOLE)):
                whole_kept += 1
            else:
                hand_after_miss += 1
            continue
        found += 1
        if all(abs(x - y) < 5e-6 for x, y in zip(was, left)):
            move = 0.0
            untouched += 1
        else:
            size = photo_size(path, v)
            size_unknown += size is None
            move = largest_move(was, left, size)
        no_touch += move <= NO_TOUCH
        moves.append(100.0 * move)
        bands[next(name for limit, name in MOVE_BANDS if move <= limit)] += 1
    wrong = min(max(wrong_accepted, 0), untouched)
    results = [v.get("result") for _, v in saved if isinstance(v.get("result"), dict)]
    out = {
        "pages": len(facts),
        "unreadable_page_files": unreadable,
        "scans": len({os.path.dirname(p) for p, _ in facts}),
        "outcome": tally(v.get("outcome") for _, v in facts),
        "app": tally(v.get("app") for _, v in facts),
        "route": {
            "capture_source": tally(dig(v, "capture", "source") for _, v in facts),
            "camera_mode": tally(dig(v, "capture", "mode") for _, v in facts),
            "from_recent": tally(v.get("from_recent") for _, v in facts),
            "edited_from_viewer": tally(v.get("edited_from_viewer") for _, v in facts),
            "auto_shot": tally(dig(v, "capture", "auto_shot") for _, v in facts),
            # how each capture started and where auto-capture was when the photo was asked for (#781)
            "focus_via": tally(dig(v, "capture", "focus_via") for _, v in facts),
            "auto_phase": tally(dig(v, "capture", "auto_phase") for _, v in facts),
        },
        "options": {
            key: tally(dig(v, "options", key) for _, v in facts) for key in ("finder", "refine", "second_look", "flatten_auto", "upright")
        },
        "borders": {
            "saved_pages_with_corners": judged,
            "found": found,
            "not_found": not_found,
            "not_found_left_as_whole_photo": whole_kept,
            "not_found_cropped_by_hand": hand_after_miss,
            "untouched": untouched,
            "no_touch": no_touch,
            "moved": found - untouched,
            "wrong_accepted_given": wrong,
            "untouched_and_right": untouched - wrong,
            "move_bands_of_found": bands,
            "move_pct_of_diagonal_found": spread(moves),
            "move_pct_of_diagonal_moved_only": spread(m for m in moves if m > 0),
            "size_unknown": size_unknown,
            "finding_source": tally(dig(v, "finding", "source") for _, v in saved),
            "finding_second_look": tally(dig(v, "finding", "second_look") for _, v in saved),
            "finding_confidence": spread(dig(v, "finding", "confidence") for _, v in saved),
            "unsure": tally(v.get("unsure") for _, v in saved),
            "borders_checked": tally(v.get("borders_checked") for _, v in saved),
            "crop_screen_used": sum(isinstance(v.get("crop"), dict) for _, v in saved),
            "crop_moved_pct": spread(dig(v, "crop", "moved_pct") for _, v in saved),
            "crop_ms": spread(dig(v, "crop", "ms") for _, v in saved),
        },
        "turns": {
            "total": tally(dig(v, "turns", "total") for _, v in saved),
            "source": tally(dig(v, "turns", "source") for _, v in saved),
            "manual": sum(dig(v, "turns", "manual") is not None for _, v in saved),
            "orientation_sure": tally(dig(r, "orientation", "sure") for r in results),
        },
        "flatten": {
            "pages_with_result": len(results),
            "flattened": tally(r.get("flattened") for r in results),
            "check_why": tally(dig(r, "flatten_check", "why") for r in results),
            "check_kept": tally(dig(r, "flatten_check", "kept") for r in results),
            "reused": tally(r.get("flatten_reused") for r in results),
            "skipped_flat": tally(r.get("flatten_skipped_flat") for r in results),
            "line_flatten_tried": sum(isinstance(r.get("line_flatten"), dict) for r in results),
            "line_flatten_kept": tally(dig(r, "line_flatten", "check", "kept") for r in results),
            "switch_on": sum(v.get("flatten") is True for _, v in saved),
            "undone": tally(dig(v, "auto_decisions", "undone") for _, v in saved),
        },
        "look": {
            "look": tally(dig(v, "page_look", "id") or v.get("look") for _, v in saved),
            "document_type": tally(dig(v, "document_type", "preset") for _, v in saved),
            "paper": tally(r.get("paper") for r in results),
            "format": tally(r.get("format") for r in results),
            "size_choice": tally(dig(v, "encoding", "size_choice") for _, v in saved),
        },
        "output": {
            "megapixels": spread(r["width"] * r["height"] / 1e6 for r in results if _num(r.get("width")) and _num(r.get("height"))),
            "dpi": spread(r.get("dpi") for r in results),
            "bytes": spread(dig(v, "encoding", "bytes") for _, v in saved),
            "made_full_before_save": tally(v.get("made_full_before_save") for _, v in saved),
        },
        # only what was measured: a preview that was not shown, or a time of 0, is no measurement
        "page_file_ms": {
            "preview.ms": spread(dig(v, "preview", "ms") for _, v in saved if dig(v, "preview", "shown") and dig(v, "preview", "ms")),
            "capture.capture_ms": spread(dig(v, "capture", "capture_ms") for _, v in facts),
            # -1 is "not known" (an import, the first photo of a camera), not a time (#781)
            "capture.focus_wait_ms": spread(x for x in (dig(v, "capture", "focus_wait_ms") for _, v in facts) if _num(x) and x >= 0),
            "capture.shot_ms": spread(x for x in (dig(v, "capture", "shot_ms") for _, v in facts) if _num(x) and x >= 0),
            "capture.since_shot_ms": spread(x for x in (dig(v, "capture", "since_shot_ms") for _, v in facts) if _num(x) and x >= 0),
            "capture.sharpness_ms": spread(dig(v, "capture", "sharpness_ms") for _, v in facts),
            "capture.merge.burst_ms": spread(dig(v, "capture", "merge", "burst_ms") for _, v in facts),
            "capture.merge.merge_ms": spread(dig(v, "capture", "merge", "merge_ms") for _, v in facts),
            "crop.snap_ms_max": spread(dig(v, "crop", "snap_ms_max") for _, v in saved),
            "auto_decisions.fingers_ms": spread(dig(v, "auto_decisions", "fingers_ms") for _, v in saved),
        },
    }
    return out


def _num(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool)


# the fields of [scan] lines counted by value: decisions, not times
LOG_TALLIES = {
    "photo": ("edges_found", "source", "second_look", "passes", "finder", "refine", "retake", "book"),
    "page": ("edges_found", "corners_moved", "flattened", "flatten_why", "flatten_reused", "flatten_skipped_flat", "turn_source", "quarter_turns", "look", "preset", "out_format"),
    "page_ready": ("made", "why", "by", "reused"),
    "full_page_ready": ("why", "flatten_reused"),
    "camera": ("source", "mode", "auto_shot", "live_found", "live_stable", "live_hint", "live_wanted", "merged", "focused", "focus_via", "auto_phase", "signature_known"),
    "camera_auto_phase": ("phase", "why"),
    "camera_auto_rearm": ("why",),
    "camera_sharpness": ("measured", "blurred_unverified", "auto_shot"),
    "crop": ("done", "opened_by", "batch"),
    "crop_detect": ("found", "source", "finder", "other", "skipped", "note"),
    "crop_snap": ("taken", "when"),
    "preview": ("made", "reused", "flattened", "light_reused"),
    "saved": ("size_choice", "ocr"),
    "share": ("size_choice", "ocr"),
    "discard": ("asked", "confirmed"),
}
# the fields that are not times but worth a spread
LOG_SPREADS = {
    "photo": ("confidence",),
    "page": ("max_move_pct",),
    "crop": ("moved_pct",),
    "saved": ("pages", "untouched_pages", "found_pages", "bytes_per_page"),
    "share": ("pages", "bytes_per_page"),
    "scan_ready": ("pages",),
    "live_summary": ("frames", "found", "found_turned"),
    # what the steady test and the gyroscope saw, and how far a page's signature moved while auto-capture waited (#781)
    "live_hints": ("detections", "unsure", "run_max", "steady_times", "runs_stale", "runs_jumped", "gyro_over_pct", "gyro_p50", "gyro_p90"),
    "camera_auto_rearm": ("looks", "diff_last", "diff_p50", "diff_max", "mean_diff_last", "mean_diff_p50", "mean_diff_max"),
    "camera_auto_summary": ("ready", "armed", "lost_page", "lost_ready", "lost_still", "fired", "refused", "same_page", "taken", "failed", "skipped", "adopted", "absent", "differs", "shutter", "shutter_searching", "shutter_arming", "shutter_waiting"),
    "unsure_offer": ("pages", "not_found", "low_confidence", "scan_pages"),
}


def parse_fields(rest):
    """key=value pairs of a log line; a value that is a number becomes one."""
    out = {}
    for pair in (rest or "").split():
        key, eq, value = pair.partition("=")
        if not eq:
            continue
        try:
            out[key] = float(value) if re.fullmatch(r"-?\d+(\.\d+)?", value) else value
        except ValueError:
            out[key] = value
    return out


def summarise_log(text, since=None, until=None):
    """The header of a diagnostics log and the aggregates of its [scan] lines inside a time window.

    Times are the log's own `MM-DD HH:MM:SS.mmm`, compared as text: a log that runs over a new year
    needs its window given by hand.
    """
    header, lines, installs = {}, [], []
    for raw in text.splitlines():
        m = LOG_LINE.match(raw)
        if not m:
            key, colon, value = raw.partition(": ")
            if colon and key in ("app", "engine", "device", "android") and key not in header:
                header[key] = value.strip()
            continue
        when, area, event, rest = m.groups()
        if area == "update" and event == "install":
            installs.append(when)
        if area == "scan":
            lines.append((when, event, rest))
    if since == "last-install":
        since = installs[-1] if installs else None
    window = lambda w: (since is None or w >= since) and (until is None or w < until)  # noqa: E731
    inside = [(w, e, r) for w, e, r in lines if window(w)]
    events = {}
    for _, event, rest in inside:
        events.setdefault(event, []).append(parse_fields(rest))
    out = {
        "header": header,
        "scan_lines": len(lines),
        "scan_lines_in_window": len(inside),
        "window": {"since": since, "until": until, "first": inside[0][0] if inside else None, "last": inside[-1][0] if inside else None},
        # an install inside the window means its lines come from more than one build
        "installs_in_log": len(installs),
        # the install the window starts at is its edge, not inside it
        "installs_in_window": sum(window(w) and w != since for w in installs),
        "events": {event: len(rows) for event, rows in sorted(events.items())},
        "tallies": {},
        "spreads": {},
        "ms": {},
    }
    for event, rows in sorted(events.items()):
        for key in LOG_TALLIES.get(event, ()):
            if any(key in row for row in rows):
                out["tallies"][f"{event}.{key}"] = tally(_plain(row.get(key)) for row in rows)
        for key in LOG_SPREADS.get(event, ()):
            # -1 is the log's "not known" here too (a signature difference with no page to compare, #781)
            got = spread(row.get(key) for row in rows if not (_num(row.get(key)) and row[key] < 0))
            if got:
                out["spreads"][f"{event}.{key}"] = got
        for key in sorted({k for row in rows for k in row if k == "ms" or k.endswith("_ms")}):
            # -1 is the log's "not measured"
            got = spread(row.get(key) for row in rows if _num(row.get(key)) and row[key] >= 0)
            if got:
                out["ms"][f"{event}.{key}"] = got
    return out


def _plain(value):
    """A whole number without its decimal point, as the log wrote it."""
    return int(value) if isinstance(value, float) and value.is_integer() else value


def _counts(counts):
    return ", ".join(f"{k}: {n}" for k, n in counts.items()) or "none"


def _share(part, whole):
    return f"{part} of {whole}" + (f" ({100.0 * part / whole:.0f} %)" if whole else "")


def _fmt(value):
    return "–" if value is None else f"{value:.0f}" if abs(value) >= 100 else f"{value:.1f}" if abs(value) >= 10 else f"{value:.2f}"


def _spread_rows(out, title, rows, unit):
    rows = {k: v for k, v in rows.items() if v}
    if not rows:
        return
    out.append(f"\n### {title}\n\n| {unit} | n | p50 | p90 | p95 | max |\n|---|---|---|---|---|---|")
    for name, s in rows.items():
        out.append(f"| `{name}` | {s['n']} | {_fmt(s['p50'])} | {_fmt(s['p90'])} | {_fmt(s['p95'])} | {_fmt(s['max'])} |")


def _tally_rows(out, title, rows):
    rows = {k: v for k, v in rows.items() if v and set(v) != {"unknown"}}
    if not rows:
        return
    out.append(f"\n### {title}\n\n| what | counts |\n|---|---|")
    for name, counts in rows.items():
        out.append(f"| `{name}` | {_counts(counts)} |")


def report(pages, log):
    """The aggregates as Markdown."""
    out = ["## Kept scans: aggregates"]
    if pages is not None:
        b = pages["borders"]
        n = b["saved_pages_with_corners"]
        out.append(
            f"\n{pages['pages']} pages in {pages['scans']} scans"
            + (f" ({pages['unreadable_page_files']} page files could not be read)" if pages["unreadable_page_files"] else "")
            + f". Outcome: {_counts(pages['outcome'])}. App: {_counts(pages['app'])}."
        )
        out.append("\n### Borders (saved pages)\n\n| what | count |\n|---|---|")
        out.append(f"| found by the app | {_share(b['found'], n)} |")
        out.append(f"| not found | {_share(b['not_found'], n)} |")
        out.append(f"| not found, left as the whole photo | {b['not_found_left_as_whole_photo']} |")
        out.append(f"| not found, cropped by hand | {b['not_found_cropped_by_hand']} |")
        out.append(f"| untouched (found, corners left as found), of all pages | {_share(b['untouched'], n)} |")
        out.append(f"| untouched, of found pages | {_share(b['untouched'], b['found'])} |")
        out.append(f"| no touch (largest move at most 0.5 % of the diagonal), of all pages | {_share(b['no_touch'], n)} |")
        out.append(f"| found and moved by hand | {_share(b['moved'], n)} |")
        if b["wrong_accepted_given"]:
            out.append(f"| untouched but wrong, as given with --wrong-accepted | {b['wrong_accepted_given']} |")
            out.append(f"| untouched and right, of all pages | {_share(b['untouched_and_right'], n)} |")
        else:
            out.append("| untouched but wrong | not known: nobody's judgement was given (--wrong-accepted) |")
        if b["size_unknown"]:
            out.append(f"| moves measured without the photo's size | {b['size_unknown']} |")
        out.append("\n| largest corner move, found pages | pages |\n|---|---|")
        for name, count in b["move_bands_of_found"].items():
            out.append(f"| {name} | {count} |")
        _spread_rows(
            out,
            "Border figures",
            {
                "move, % of diagonal, found pages": b["move_pct_of_diagonal_found"],
                "move, % of diagonal, moved pages only": b["move_pct_of_diagonal_moved_only"],
                "crop.moved_pct": b["crop_moved_pct"],
                "crop.ms": b["crop_ms"],
                "finding.confidence": b["finding_confidence"],
            },
            "figure",
        )
        out.append(f"\nThe crop screen was used on {b['crop_screen_used']} of {n} saved pages.")
        _tally_rows(
            out,
            "Borders: how",
            {
                "finding.source": b["finding_source"],
                "finding.second_look": b["finding_second_look"],
                "unsure": b["unsure"],
                "borders_checked": b["borders_checked"],
            },
        )
        _tally_rows(out, "Route", pages["route"])
        _tally_rows(out, "Testing options in force", {f"options.{k}": v for k, v in pages["options"].items()})
        t, f = pages["turns"], pages["flatten"]
        _tally_rows(out, "Turns", {"turns.total (quarter turns)": t["total"], "turns.source": t["source"], "result.orientation.sure": t["orientation_sure"]})
        out.append(f"\nTurned by hand: {t['manual']} pages.")
        _tally_rows(
            out,
            "Flattening",
            {
                "result.flattened": f["flattened"],
                "result.flatten_check.why": f["check_why"],
                "result.flatten_check.kept": f["check_kept"],
                "result.flatten_reused": f["reused"],
                "result.flatten_skipped_flat": f["skipped_flat"],
                "result.line_flatten.check.kept": f["line_flatten_kept"],
                "auto_decisions.undone": f["undone"],
            },
        )
        out.append(f"\nFlatten page switched on by hand: {f['switch_on']} pages; the text-line fallback was tried on {f['line_flatten_tried']} pages.")
        _tally_rows(out, "Look and output", {**pages["look"], "made_full_before_save": pages["output"]["made_full_before_save"]})
        _spread_rows(
            out, "Output", {"megapixels": pages["output"]["megapixels"], "dpi": pages["output"]["dpi"], "bytes a page": pages["output"]["bytes"]}, "figure"
        )
        _spread_rows(out, "Times in the page files (ms)", pages["page_file_ms"], "field")
    if log is not None:
        w = log["window"]
        out.append("\n## Diagnostics log: `[scan]` lines")
        out.append(
            f"\nHeader: {_counts(log['header'])}. {log['scan_lines_in_window']} of {log['scan_lines']} `[scan]` lines counted"
            + (f", from {w['first']} to {w['last']}" if w["first"] else "")
            + (f" (since {w['since']})" if w["since"] else "")
            + (f" (until {w['until']})" if w["until"] else "")
            + "."
        )
        if log["installs_in_window"]:
            out.append(
                f"\n**{log['installs_in_window']} app install(s) inside this window: its lines come from more than one build.** "
                "Use `--since last-install` for the build named in the header, or `--since`/`--until` for one sitting."
            )
        if log["events"]:
            out.append("\n### Events\n\n| event | lines |\n|---|---|")
            for event, count in log["events"].items():
                out.append(f"| `{event}` | {count} |")
        _tally_rows(out, "Decisions in the log", log["tallies"])
        _spread_rows(out, "Counts and sizes in the log", log["spreads"], "field")
        _spread_rows(out, "Times in the log (ms)", log["ms"], "field")
    return "\n".join(out) + "\n"


def main(argv=None):
    parser = argparse.ArgumentParser(description="Aggregates of kept-scan exports (no page, no file name).")
    parser.add_argument("folders", nargs="*", help="unzipped kept-scan exports")
    parser.add_argument("--log", action="append", default=[], help="a diagnostics.txt (default: every one found under the folders)")
    parser.add_argument("--since", help='count log lines from "MM-DD HH:MM[:SS]" on, or "last-install"')
    parser.add_argument("--until", help='count log lines before "MM-DD HH:MM[:SS]"')
    parser.add_argument("--wrong-accepted", type=int, default=0, help="untouched pages a person judged wrong, taken off the untouched count")
    parser.add_argument("--json", help="also write the aggregates as JSON")
    args = parser.parse_args(argv)
    if not args.folders and not args.log:
        parser.error("give an export folder or --log")
    paths, logs = find_pages(args.folders)
    pages = summarise_pages(paths, args.wrong_accepted) if args.folders else None
    text = ""
    for path in args.log or logs:
        with open(path, encoding="utf-8", errors="replace") as fh:
            text += fh.read() + "\n"
    log = summarise_log(text, args.since, args.until) if text else None
    sys.stdout.write(report(pages, log))
    if args.json:
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump({"pages": pages, "log": log}, fh, indent=1)
    return 0


if __name__ == "__main__":
    sys.exit(main())
