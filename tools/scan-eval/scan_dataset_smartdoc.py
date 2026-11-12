"""Turn a local copy of SmartDoc 2015 Challenge 1 into a scanner test set (#288, section 7).

SmartDoc 2015 Challenge 1 (ICDAR 2015, CC BY 4.0) is 150 short videos of six documents on five
backgrounds, filmed with a phone, with the page's four corners marked on every frame. This script
samples every Nth frame and writes the test-set format that `scan-eval` reads:

    <out>/<background>-<document>-<frame>.jpg    the frame
    <out>/<background>-<document>-<frame>.json   {"corners": [...8 shares, TL TR BR BL...],
                                                  "kind": ..., "paper": "a4", "curved": false,
                                                  "notes": "smartdoc2015 ..."}

It downloads nothing. Get the dataset yourself from
https://sites.google.com/site/icdar15smartdoc/challenge-1/dataset and keep it, and what this
script writes, out of the repository.

    python3 tools/scan-eval/scan_dataset_smartdoc.py <smartdoc dir> <out dir> [--every 30]
        [--kind background01=desk ...] [--limit N] [--list]

<smartdoc dir> is either of the two published forms:

- the frames release (https://github.com/jchazalon/smartdoc15-ch1-dataset/releases, frames.tar.gz
  unpacked): `metadata.csv` or `metadata.csv.gz` with one row per frame (`bg_name`, `model_name`,
  `image_path`, `frame_index`, `tl_x` ... `tr_y`) and the frames as JPEG files. Needs only the
  standard library: frames are copied, not decoded.
- the original videos (Zenodo record 1230218): `*.gt.xml` ground-truth files, each one's video the
  file with the same stem beside it (`background01/datasheet001.avi` with
  `background01/datasheet001.gt.xml`). Decoding the videos needs `opencv-python`.

`--list` prints the entries without writing anything.

The dataset's corners name the page's own corners as a reader holds it (its `tl` is the page's
top-left even when the page is upside down in the frame), which is the test set's convention too.

`kind` is `other` unless `--kind` maps a background to one of the test set's kinds: the five
backgrounds were not looked at when this was written, so no default mapping is claimed.
"""
import argparse
import csv
import gzip
import io
import json
import os
import shutil
import struct
import sys
import xml.etree.ElementTree as ET

KINDS = ("desk", "light", "receipt", "book", "shadow", "lowlight", "whiteboard", "screen", "other")
VIDEO_EXTS = (".avi", ".mp4", ".mov", ".mkv")
ORDER = ("tl", "tr", "br", "bl")


class GroundTruth:
    """One video's ground truth: its frames, 1-based, each four (x, y) corners in pixels."""

    def __init__(self, source, frames):
        self.source = source
        self.frames = frames  # list of (index, {"tl": (x, y), ...}); rejected frames left out


def parse_ground_truth(text):
    """Parses a SmartDoc `.gt.xml` (a `seg_result` with `frame index=".." rejected=".."` holding
    four `point name="tl|tr|br|bl" x=".." y=".."`). Frames marked rejected, or missing a corner,
    are left out."""
    root = ET.fromstring(text)
    source = (root.findtext("source_sample_file") or "").strip()
    frames = []
    for frame in root.iter("frame"):
        if frame.get("rejected", "false").strip().lower() == "true":
            continue
        try:
            index = int(frame.get("index", ""))
        except ValueError:
            continue
        points = {}
        for p in frame.iter("point"):
            name = (p.get("name") or "").strip().lower()
            try:
                points[name] = (float(p.get("x", "")), float(p.get("y", "")))
            except ValueError:
                pass
        if all(k in points for k in ORDER):
            frames.append((index, points))
    frames.sort(key=lambda f: f[0])
    return GroundTruth(source, frames)


def sample(frames, every):
    """Frames 1, 1 + every, 1 + 2 * every, ... of those present (indices are 1-based)."""
    if every < 1:
        raise ValueError("--every must be at least 1")
    return [f for f in frames if (f[0] - 1) % every == 0]


def corners_as_shares(points, width, height):
    """TL TR BR BL as 8 shares of the frame, the test set's convention. Not clamped: a corner
    outside the frame stays outside 0..1, as in the app's own kept scans."""
    if width <= 0 or height <= 0:
        raise ValueError("frame size must be positive")
    out = []
    for name in ORDER:
        x, y = points[name]
        out += [round(x / width, 6), round(y / height, 6)]
    return out


def entry_name(background, document, index):
    return f"{background}-{document}-{index:04d}"


def entry_json(points, width, height, kind, background, document, index):
    if kind not in KINDS:
        raise ValueError(f"unknown kind {kind!r}; one of {', '.join(KINDS)}")
    return {
        "corners": corners_as_shares(points, width, height),
        "kind": kind,
        "paper": "a4",
        "curved": False,
        "notes": f"smartdoc2015 {background} {document} frame {index}",
    }


def parse_kind_map(items):
    out = {}
    for item in items or []:
        background, sep, kind = item.partition("=")
        if not sep or not background or kind not in KINDS:
            raise ValueError(f"--kind takes background=kind with kind one of {', '.join(KINDS)}: {item!r}")
        out[background] = kind
    return out


def jpeg_size(data):
    """(width, height) from a JPEG's start-of-frame marker, or None."""
    if data[:2] != b"\xff\xd8":
        return None
    i = 2
    while i + 4 <= len(data):
        if data[i] != 0xFF:
            i += 1
            continue
        marker = data[i + 1]
        if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7 or marker == 0xFF:
            i += 1 if marker == 0xFF else 2
            continue
        (length,) = struct.unpack(">H", data[i + 2 : i + 4])
        if marker in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
            if i + 9 > len(data):
                return None
            h, w = struct.unpack(">HH", data[i + 5 : i + 9])
            return (w, h) if w and h else None
        i += 2 + length
    return None


def read_metadata(root):
    """Rows of the frames release's metadata file, or None when there is none."""
    for name in ("metadata.csv.gz", "metadata.csv"):
        path = os.path.join(root, name)
        if os.path.isfile(path):
            raw = open(path, "rb").read()
            if name.endswith(".gz"):
                raw = gzip.decompress(raw)
            return list(csv.DictReader(io.StringIO(raw.decode("utf-8"))))
    return None


def plan_frames(root, rows, every, kind_map):
    """Entries from the frames release: (name, image path, frame index, points, background, document, kind)."""
    out = []
    for r in rows:
        try:
            index = int(r["frame_index"])
            points = {k: (float(r[k + "_x"]), float(r[k + "_y"])) for k in ORDER}
            background, document, image = r["bg_name"], r["model_name"], r["image_path"]
        except (KeyError, ValueError):
            continue
        if every < 1:
            raise ValueError("--every must be at least 1")
        if (index - 1) % every:
            continue
        kind = kind_map.get(background, "other")
        out.append((entry_name(background, document, index), os.path.join(root, image), index, points, background, document, kind))
    out.sort()
    return out


def copy_frames(entries, out_dir):
    """Writes the frames release's entries: the JPEG copied as it is, and its JSON."""
    os.makedirs(out_dir, exist_ok=True)
    written = 0
    for name, image, index, points, background, document, kind in entries:
        try:
            data = open(image, "rb").read()
        except OSError as e:
            print(f"skipped {name}: {e}", file=sys.stderr)
            continue
        size = jpeg_size(data)
        if size is None:
            print(f"skipped {name}: not a JPEG", file=sys.stderr)
            continue
        shutil.copyfile(image, os.path.join(out_dir, name + ".jpg"))
        with open(os.path.join(out_dir, name + ".json"), "w", encoding="utf-8") as fh:
            json.dump(entry_json(points, size[0], size[1], kind, background, document, index), fh, indent=1)
        written += 1
    return written


def find_videos(root):
    """Every (gt_path, video_path or None, background, document) under root, sorted."""
    found = []
    for d, _dirs, files in os.walk(root):
        for f in files:
            if not f.endswith(".gt.xml"):
                continue
            stem = f[: -len(".gt.xml")]
            video = next((os.path.join(d, stem + e) for e in VIDEO_EXTS if os.path.isfile(os.path.join(d, stem + e))), None)
            background = os.path.basename(d) or "background"
            found.append((os.path.join(d, f), video, background, stem))
    found.sort()
    return found


def plan(root, every, kind_map):
    """(name, video, frame index, points, background, document, kind) for every entry to write."""
    out = []
    for gt_path, video, background, document in find_videos(root):
        with open(gt_path, encoding="utf-8") as fh:
            gt = parse_ground_truth(fh.read())
        kind = kind_map.get(background, "other")
        for index, points in sample(gt.frames, every):
            out.append((entry_name(background, document, index), video, index, points, background, document, kind))
    return out


def write_entries(entries, out_dir):  # pragma: no cover - needs opencv and the videos
    try:
        import cv2
    except ImportError:
        sys.exit("decoding the videos needs opencv-python (pip install opencv-python); --list works without it")
    os.makedirs(out_dir, exist_ok=True)
    written = 0
    by_video = {}
    for e in entries:
        by_video.setdefault(e[1], []).append(e)
    for video, items in by_video.items():
        if video is None:
            print(f"skipped {len(items)} entries: no video beside {items[0][4]}/{items[0][5]}.gt.xml", file=sys.stderr)
            continue
        cap = cv2.VideoCapture(video)
        wanted = {e[2]: e for e in items}
        index = 0
        while wanted:
            ok, frame = cap.read()
            if not ok:
                break
            index += 1
            e = wanted.pop(index, None)
            if e is None:
                continue
            name, _video, _index, points, background, document, kind = e
            h, w = frame.shape[:2]
            cv2.imwrite(os.path.join(out_dir, name + ".jpg"), frame, [cv2.IMWRITE_JPEG_QUALITY, 95])
            with open(os.path.join(out_dir, name + ".json"), "w", encoding="utf-8") as fh:
                json.dump(entry_json(points, w, h, kind, background, document, _index), fh, indent=1)
            written += 1
        cap.release()
        if wanted:
            print(f"{video}: {len(wanted)} frames past the end of the video", file=sys.stderr)
    return written


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("smartdoc")
    ap.add_argument("out")
    ap.add_argument("--every", type=int, default=30, help="keep every Nth frame (default 30)")
    ap.add_argument("--kind", action="append", metavar="BACKGROUND=KIND", help="kind for a background folder")
    ap.add_argument("--limit", type=int, default=0, help="write at most N entries (0: all)")
    ap.add_argument("--list", action="store_true", help="print the entries, decode nothing")
    a = ap.parse_args(argv)
    try:
        kind_map = parse_kind_map(a.kind)
        rows = read_metadata(a.smartdoc)
        entries = plan_frames(a.smartdoc, rows, a.every, kind_map) if rows is not None else plan(a.smartdoc, a.every, kind_map)
    except ValueError as e:
        ap.error(str(e))
    if a.limit > 0:
        entries = entries[: a.limit]
    if not entries:
        print(f"no metadata.csv or *.gt.xml frames found under {a.smartdoc}", file=sys.stderr)
        return 1
    if a.list:
        for name, video, index, _p, _b, _d, kind in entries:
            print(f"{name}\t{kind}\t{'' if video else 'no video'}")  # video: the frame's file in the frames release
        print(f"{len(entries)} entries", file=sys.stderr)
        return 0
    n = copy_frames(entries, a.out) if rows is not None else write_entries(entries, a.out)
    print(f"wrote {n} entries to {a.out}", file=sys.stderr)
    return 0 if n else 1


if __name__ == "__main__":
    sys.exit(main())
