#!/usr/bin/env python3
"""Fail closed on missing or inconsistent owner-reviewed Android release notes."""
from pathlib import Path
import re
import sys

REQUIRED = ("New features", "Fixes", "Improvements", "Known limits and testing", "Installing")


def validate(notes: str, version: str, build: str) -> list[str]:
    problems = []
    first = notes.splitlines()[0].strip() if notes.splitlines() else ""
    if first != f"# PdfCraft Android {version} ({build})":
        problems.append("release heading must contain exact versionName (manifest versionCode)")
    found = re.findall(r"^## (.+)$", notes, flags=re.MULTILINE)
    if found[: len(REQUIRED)] != list(REQUIRED):
        problems.append("sections must appear in New features, Fixes, Improvements, Known limits and testing, Installing order")
    for title in REQUIRED:
        hit = re.search(rf"^## {re.escape(title)}\n(.*?)(?=^## |\Z)", notes, re.MULTILINE | re.DOTALL)
        if not hit or not hit.group(1).strip():
            problems.append(f"missing content in {title}")
    for title in REQUIRED[:4]:
        hit = re.search(rf"^## {re.escape(title)}\n(.*?)(?=^## |\Z)", notes, re.MULTILINE | re.DOTALL)
        if hit and not any(row.strip().startswith("- ") for row in hit.group(1).splitlines()):
            problems.append(f"{title} requires at least one user-facing bullet")
    if "## What's Changed" in notes or "## Merged pull requests" in notes:
        problems.append("generated PR history belongs in the publishing workflow, not the curated summary")
    return problems


def main() -> int:
    if len(sys.argv) not in (1, 3):
        print("usage: verify_android_release_notes.py [notes-file manifest-build]", file=sys.stderr)
        return 2
    props = Path("apps/pdfcraft-android/android/gradle.properties").read_text()
    version = re.search(r"^pdfcraft\.version=(.+)$", props, re.MULTILINE).group(1)
    base = int(re.search(r"^pdfcraft\.versionCode=(\d+)$", props, re.MULTILINE).group(1))
    build = str(base * 10 + 2)
    path = Path("docs/releases") / f"{version}.md"
    if len(sys.argv) == 3:
        path = Path(sys.argv[1])
        if sys.argv[2] != build:
            print(f"manifest build {sys.argv[2]} does not match expected {build}", file=sys.stderr)
            return 1
    if not path.is_file():
        print(f"missing release notes: {path}", file=sys.stderr)
        return 1
    errors = validate(path.read_text(encoding="utf-8"), version, build)
    for error in errors:
        print(f"release notes: {error}", file=sys.stderr)
    if not errors:
        print(f"Validated {version} ({build}) release notes: {path}")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
