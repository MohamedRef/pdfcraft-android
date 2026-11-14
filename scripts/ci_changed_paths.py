"""Which test jobs a pull request needs, from the files it changes (issue #562).

android-update.yml runs this in its "Changed paths" job and skips a test job only when every changed
file is one that job cannot be affected by. Anything not listed below runs everything, so a new
folder or file type is tested until someone decides otherwise. Main pushes, manual runs and release
pull requests ("chore(release): publish ...") always run every job.

Usage: python3 scripts/ci_changed_paths.py <event> <pr title> < changed-files
Prints "rust=true|false" and "android=true|false" lines for $GITHUB_OUTPUT.
"""
import re
import sys

# Files that cannot change what the Rust jobs build or test. No Rust source, build script or test
# reads these (checked with grep for include_str!/include_bytes!/fs reads, 2026-11-14).
RUST_UNAFFECTED = (
    r"apps/pdfcraft-android/android/",  # the Kotlin app, its resources and Gradle files
    r"docs/",  # handovers, release notes, provenance lock: the repository checks job covers these
    r"fastlane/",  # store listings
    r"scripts/",  # Python checks (repository checks job) and the Gradle retry wrapper
    r"tools/",  # Python dataset and evaluation helpers (repository checks job)
    r"\.github/(ISSUE_TEMPLATE/|release\.yml$|[^/]+\.md$|workflows/pr-title\.yml$|workflows/release\.yml$)",
    r"[^/]+\.md$",  # top-level Markdown (README, AGENTS, ATTRIBUTION.md); crate READMEs still count
)

# Files that cannot change what the Android JVM, UI and lint job builds or tests. Rust changes are
# NOT here: that job cross-compiles the engine for arm64 and generates the Kotlin bindings from it.
ANDROID_UNAFFECTED = (
    r"docs/",
    r"fastlane/",
    r"scripts/(?!gradle_with_network_retries\.sh$)",
    r"tools/",
    r"\.github/(ISSUE_TEMPLATE/|release\.yml$|[^/]+\.md$|workflows/pr-title\.yml$|workflows/release\.yml$)",
    r"[^/]+\.md$",
)

RELEASE_TITLE = "chore(release): publish "


def _unaffected(path, patterns):
    return any(re.match(pattern, path) for pattern in patterns)


def needs(event, title, files):
    """{"rust": bool, "android": bool} for an event, pull request title and changed files."""
    files = [f.strip() for f in files if f.strip()]
    if event != "pull_request" or title.startswith(RELEASE_TITLE) or not files:
        return {"rust": True, "android": True}
    return {
        "rust": not all(_unaffected(f, RUST_UNAFFECTED) for f in files),
        "android": not all(_unaffected(f, ANDROID_UNAFFECTED) for f in files),
    }


def main(argv):
    event, title = argv[1], argv[2]
    result = needs(event, title, sys.stdin.read().splitlines())
    for key, value in result.items():
        print(f"{key}={'true' if value else 'false'}")


if __name__ == "__main__":
    main(sys.argv)
