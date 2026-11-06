#!/usr/bin/env python3
"""Check mobile Rust engine provenance against the immutable released PdfCraft source.

Requires only Python 3.11+ and Git. Checks every tracked core/vendor file, not
network access or downstream Android runtime compatibility. Update the checked
baseline from an explicitly reviewed upstream *stable* release, never main.
"""
from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
INVENTORY = ROOT / "docs/android-engine-source.json"
LOCK = ROOT / "docs/android-engine-provenance-lock.json"


def git_blob_sha(path: Path) -> str:
    # Git hashes files as SHA-1("blob <decimal size>\0" + raw bytes).
    h = hashlib.sha1()
    size = path.stat().st_size
    h.update(f"blob {size}\0".encode("ascii"))
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def tracked_paths() -> set[str]:
    raw = subprocess.run(
        ["git", "ls-files", "--cached", "-z", "--", "crates", "vendor"],
        cwd=ROOT,
        check=True,
        stdout=subprocess.PIPE,
    ).stdout
    return {value.decode("utf-8") for value in raw.split(b"\0") if value}


def main() -> int:
    inventory = json.loads(INVENTORY.read_text(encoding="utf-8"))
    lock = json.loads(LOCK.read_text(encoding="utf-8"))
    blobs: dict[str, str] = lock["upstream_blobs"]
    allowed: dict[str, str] = lock["allowed_adaptations"]
    omitted: dict[str, str] = lock["intentionally_omitted"]
    additions: dict[str, str] = lock["allowed_local_additions"]
    crates: set[str] = set(inventory["crates"])
    vendors: set[str] = set(inventory["vendors"])
    errors: list[str] = []
    for key in ("source_repository", "source_commit", "source_tag"):
        if lock.get(key) != inventory.get(key):
            errors.append(f"source pin mismatch for {key}")
    if lock.get("schema") != 1:
        errors.append("unsupported provenance schema")
    if set(omitted) - set(blobs):
        errors.append("omitted files absent from the upstream baseline")
    if set(allowed) - set(blobs):
        errors.append("allowed adaptation not declared in the upstream baseline")
    if set(allowed) & set(omitted):
        errors.append("adaptation may not also be an omission")
    ws = tomllib.loads((ROOT / "Cargo.toml").read_text(encoding="utf-8"))
    members = set(ws["workspace"]["members"])
    if {"apps/pdfcraft-android", "apps/pdfcraft-android/bindgen"} - members:
        errors.append("Android native and UniFFI bindgen workspace members must be retained")
    for crate in crates:
        if f"crates/{crate}" not in members:
            errors.append(f"missing Rust workspace member: {crate}")
    if members & {"crates/ui-egui", "crates/prc", "crates/prc-render", "apps/pdfcraft"}:
        errors.append("desktop/PRC-only packages were added to the mobile workspace")

    def selected(name: str) -> bool:
        bits = name.split("/")
        return len(bits) > 2 and (
            (bits[0] == "crates" and bits[1] in crates)
            or (bits[0] == "vendor" and bits[1] in vendors)
        )

    tracked = {p for p in tracked_paths() if selected(p)}
    same = changed = 0
    for name, sha in blobs.items():
        if not selected(name):
            errors.append(f"baseline contains an out-of-scope file: {name}")
            continue
        if name in omitted:
            if name in tracked:
                errors.append(f"intentionally omitted file is present: {name}")
            continue
        if name not in tracked:
            errors.append(f"source file missing: {name}")
            continue
        path = ROOT / name
        if not path.is_file():
            errors.append(f"tracked source not readable: {name}")
            continue
        actual = git_blob_sha(path)
        if actual == sha:
            same += 1
        elif name in allowed:
            changed += 1
        else:
            errors.append(f"unreviewed core source drift: {name} (local {actual}, upstream {sha})")
    for name in sorted(tracked - set(blobs)):
        if name not in additions:
            errors.append(f"new core source without a reviewed provenance entry: {name}")
    for name in additions:
        if name not in tracked:
            errors.append(f"declared local addition not found: {name}")
    print(
        f"Pinned {lock['source_tag']} ({lock['source_commit'][:12]}): "
        f"{same} exact core/vendor files, {changed} reviewed Android adaptations, "
        f"{len(omitted)} deliberate 3D omissions, {len(additions)} additions."
    )
    for name in sorted(allowed):
        print(f"  Android adaptation: {name} — {allowed[name]}")
    if errors:
        print("\nSOURCE PROVENANCE FAILED:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        print("Update the Android engine handoff log and provenance allowlist only after review.", file=sys.stderr)
        return 1
    print("Source provenance OK. Device runtime, signatures and SAF writes still need separate tests.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
