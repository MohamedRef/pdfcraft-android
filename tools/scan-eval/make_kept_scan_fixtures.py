#!/usr/bin/env python3
"""Write the made-up kept-scan exports that test_kept_scan_summary.py reads (#779).

    python3 tools/scan-eval/make_kept_scan_fixtures.py [out_dir]

Without `out_dir` it writes `tools/scan-eval/testdata/kept-scans/`, the copy in the repository.
Every number is invented here: no phone, photo or log was read. The exports hold page files and a
log only, no photos. `current` has the keys 0.0.46-beta writes that the summary reads; `older` has
only what a build of November 2026's first weeks wrote (no `turns`, `encoding`, `crop`, `capture`
or `finding.second_look`), to keep the summary reading old exports.
"""

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
WHOLE = [0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0]
PAGE = [0.1, 0.1, 0.9, 0.1, 0.9, 0.9, 0.1, 0.9]


def moved(corners, index, dx, dy):
    out = list(corners)
    out[2 * index] = round(out[2 * index] + dx, 5)
    out[2 * index + 1] = round(out[2 * index + 1] + dy, 5)
    return out


def current_page(position, corners, found, **more):
    """A page file as 0.0.46-beta writes it, cut down to the keys the summary reads."""
    page = {
        "corners": corners,
        "kind": "other",
        "paper": "unknown",
        "curved": False,
        "notes": "",
        "checked": False,
        "app": "0.0.46-beta (46012)",
        "outcome": "saved",
        "found": found,
        "finding": {"source": "model" if found else "none", "confidence": 0.91 if found else 0.2, "second_look": "not_asked" if found else "kept"},
        "look": "auto",
        "strength": 60,
        "flatten": False,
        "options": {"finder": "auto", "refine": True, "second_look": True, "flatten_auto": True, "upright": True},
        "auto_decisions": {"undone": "none", "fingers_ms": 20 * position},
        "document_type": {"preset": "document"},
        "turns": {"manual": None, "auto": True, "total": 0, "followed": False, "source": "classifier"},
        "preview": {"shown": True, "ms": 300 + 100 * position},
        "crop": None,
        "borders_checked": False,
        "unsure": found is None,
        # the still is 4000 x 3000 turned by 90: the upright photo is 3000 x 4000, diagonal 5000
        "capture": {"source": "in_app", "mode": "batch", "width": 4000, "height": 3000, "rotation": 90, "capture_ms": 500 + 50 * position, "sharpness_ms": 80, "auto_shot": position % 2 == 0, "merge": None},
        "page_look": {"id": "clean"},
        "position": position,
        "made_full_before_save": True,
        "from_recent": False,
        "edited_from_viewer": False,
        "encoding": {"size_choice": "balanced", "bytes": 100000 * position},
        "result": {
            "width": 1000,
            "height": 1000 + 100 * position,
            "format": "jpeg",
            "dpi": 200,
            "paper": "a4",
            "flattened": False,
            "flatten_check": {"kept": False, "why": "notbetter"},
            "line_flatten": None,
            "flatten_reused": False,
            "flatten_skipped_flat": False,
            "orientation": {"sure": True},
        },
    }
    for key, value in more.items():
        target = page
        *path, last = key.split("__")
        for step in path:
            target = target[step]
        target[last] = value
    return page


def current():
    crop = {"visits": 1, "moved_pct": 0.12, "ms": 5000, "snap_ms_max": 90}
    saved = [
        # found and left as found
        current_page(1, PAGE, PAGE, result__flattened=True, result__flatten_check={"kept": True, "why": "straighter"}),
        # found, one corner moved by 6 px of a 5000 px diagonal (0.12 %): touched, yet within "no touch"
        current_page(2, moved(PAGE, 0, 0.002, 0.0), PAGE, crop=crop, borders_checked=True),
        # found, one corner moved by 300 and 400 px (10 % of the diagonal), and turned by hand
        current_page(
            3,
            moved(PAGE, 2, 0.1, 0.1),
            PAGE,
            crop={"visits": 2, "moved_pct": 10.0, "ms": 20000, "snap_ms_max": 300},
            borders_checked=True,
            turns={"manual": 1, "auto": True, "total": 1, "followed": False, "source": "manual"},
        ),
        # not found, and the whole photo was saved
        current_page(4, WHOLE, None, result__paper="custom"),
        # not found, cropped by hand
        current_page(5, PAGE, None, crop={"visits": 1, "moved_pct": 14.1, "ms": 30000, "snap_ms_max": 0}, result__line_flatten={"check": {"kept": True}}),
    ]
    discarded = current_page(1, PAGE, PAGE)
    discarded["outcome"] = "discarded"
    for key in ("look", "strength", "result", "encoding"):
        del discarded[key]
    log = """PdfCraft diagnostics log
Only event names, times, counts and sizes. No file names, paths or document contents.
app: 0.0.46-beta (46012)
engine: 0.21.1
device: example Phone-1
android: 17 (API 37)

Areas (lines · issues):
  [scan] 9 · #286-#307

12-01 08:00:00.000 [scan] photo retake=false bytes=100 edges_found=true find_ms=9000 source=model confidence=0.50 passes=1 decode_ms=9000 detect_ms=9000 finder=auto refine=true
12-01 08:30:00.000 [update] install launched=true
12-01 09:00:01.000 [scan] photo retake=false bytes=100 edges_found=true find_ms=800 source=model confidence=0.90 passes=1 decode_ms=200 detect_ms=400 refine_ms=40 finder=auto refine=true second_look=not_asked
12-01 09:00:02.000 [scan] photo retake=false bytes=100 edges_found=true find_ms=1200 source=lines confidence=0.40 passes=7 decode_ms=300 detect_ms=600 refine_ms=60 finder=auto refine=true second_look=taken second_look_ms=250
12-01 09:00:03.000 [scan] photo retake=false bytes=100 edges_found=false find_ms=2000 source=none confidence=0.20 passes=7 decode_ms=400 detect_ms=1500 finder=auto refine=true second_look=kept second_look_ms=100
12-01 09:00:04.000 [render] page ms=5 scale=2.00
12-01 09:00:05.000 [scan] page index=1 edges_found=true corners_moved=false max_move_pct=0.00 look_ms=700 encode_ms=80 flattened=true flatten_why=straighter markup_ms=-1
12-01 09:00:06.000 [scan] page index=2 edges_found=true corners_moved=true max_move_pct=0.12 look_ms=900 encode_ms=120 flattened=false flatten_why=notbetter markup_ms=-1
12-01 09:00:07.000 [scan] saved pages=5 untouched_pages=1 found_pages=3 ms=300 wait_ms=300 size_choice=balanced bytes_per_page=300000
12-01 09:00:08.000 [scan] tests_shared pages=6 bytes=1000
"""
    files = {f"20261201-090000/page-{n:02}.json": json.dumps(page, indent=1) + "\n" for n, page in enumerate(saved, 1)}
    files["20261201-091500/page-01.json"] = json.dumps(discarded, indent=1) + "\n"
    files["diagnostics.txt"] = log
    return files


def older():
    def page(corners, found):
        return {
            "corners": corners,
            "kind": "other",
            "paper": "unknown",
            "curved": False,
            "notes": "",
            "checked": False,
            "app": "0.0.26-beta (26001)",
            "outcome": "saved",
            "found": found,
            "finding": {"source": "model" if found else "none", "confidence": 0.8 if found else 0.1, "angle": 0, "passes": 1},
            "look": "auto",
            "strength": 60,
            "flatten": False,
            "options": {"finder": "auto", "refine": True},
            "result": {"file": "page.out.jpg", "width": 1654, "height": 2339, "format": "jpeg", "dpi": 200.0, "paper": "a4", "flattened": False},
        }

    # no photo and no capture size: the move is measured in the unit square, 0.03 / sqrt(2) = 2.12 %
    pages = [page(PAGE, PAGE), page(moved(PAGE, 1, 0.03, 0.0), PAGE), page(WHOLE, None)]
    log = """PdfCraft diagnostics log
app: 0.0.26-beta (26001)
device: example Phone-1

11-12 08:15:01.000 [scan] photo retake=false bytes=100 edges_found=true find_ms=900 source=model confidence=0.80 finder=auto refine=true
11-12 08:15:09.000 [scan] page index=1 edges_found=true corners_moved=false filter=cleanscan enhance_ms=1500 out_width=1654 out_height=2339
11-12 08:15:10.000 [scan] saved pages=3 filter=cleanscan bytes=900000
"""
    files = {f"20261112-081500/page-{n:02}.json": json.dumps(p, indent=1) + "\n" for n, p in enumerate(pages, 1)}
    files["diagnostics.txt"] = log
    return files


def exports():
    """{relative path: text} of every fixture file."""
    out = {}
    for name, files in (("current", current()), ("older", older())):
        out.update({f"{name}/{path}": text for path, text in files.items()})
    return out


def main(argv):
    root = argv[1] if len(argv) > 1 else os.path.join(HERE, "testdata", "kept-scans")
    for path, text in exports().items():
        full = os.path.join(root, *path.split("/"))
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)
    print(f"wrote {len(exports())} files under {root}")


if __name__ == "__main__":
    main(sys.argv)
