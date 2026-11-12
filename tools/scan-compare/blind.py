#!/usr/bin/env python3
"""Blind ranking for the scanner comparison (#307, docs/ANDROID_SCAN_COMPARISON.md).

Runs on the owner's computer only. The pages it reads are his scans, and they never go into the
repository, an issue or a pull request. Nothing here uploads anything.

    # 1. one folder per scanner, the same file name for the same document:
    #      scans/pdfcraft/doc-01.jpg  scans/google/doc-01.jpg  scans/other/doc-01.jpg ...
    python3 blind.py shuffle scans blind
    #    blind/doc-01-A.jpg, doc-01-B.jpg, doc-01-C.jpg ... and blind/key.csv (do not open it yet)
    #    blind/adjusted.csv to fill in from your notes while scanning: yes where you had to move
    #    a corner by hand, no where you did not
    #    and blind/ranks.csv to fill in: for each document, the letters best to worst, and
    #    for the scanner that came last, the fault (edges, shadow, sharpness, colour, flatness)

    # 2. after ranking every document:
    python3 blind.py score blind
    #    prints the no-touch rate and mean rank per scanner and, per document where pdfcraft
    #    came last, the fault

    python3 blind.py self-test   # checks the two steps on made-up files

No library beyond Python 3.9's own is needed.
"""

import csv
import random
import shutil
import sys
import tempfile
from pathlib import Path

LETTERS = "ABCDEFGH"
FAULTS = ("edges", "shadow", "sharpness", "colour", "flatness")
OWN = "pdfcraft"


def shuffle(source: Path, target: Path, seed=None) -> None:
    """Copy each document's pages under letters in a random order, and write the key."""
    scanners = sorted(p.name for p in source.iterdir() if p.is_dir())
    if len(scanners) < 2 or len(scanners) > len(LETTERS):
        raise SystemExit(f"expected 2 to {len(LETTERS)} scanner folders in {source}, found {scanners}")
    documents = sorted({f.name for s in scanners for f in (source / s).iterdir() if f.is_file()})
    target.mkdir(parents=True, exist_ok=False)
    rng = random.Random(seed)
    key_rows, rank_rows, adjusted_rows = [], [], []
    for doc in documents:
        present = [s for s in scanners if (source / s / doc).is_file()]
        if len(present) != len(scanners):
            print(f"skipped {doc}: missing from {sorted(set(scanners) - set(present))}")
            continue
        order = present[:]
        rng.shuffle(order)
        stem, suffix = Path(doc).stem, Path(doc).suffix
        for letter, scanner in zip(LETTERS, order):
            shutil.copyfile(source / scanner / doc, target / f"{stem}-{letter}{suffix}")
            key_rows.append({"document": stem, "letter": letter, "scanner": scanner})
        rank_rows.append({"document": stem, "best_to_worst": "", "last_fault": ""})
        adjusted_rows.extend({"document": stem, "scanner": s, "adjusted": ""} for s in scanners)
    with open(target / "key.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["document", "letter", "scanner"])
        w.writeheader()
        w.writerows(key_rows)
    with open(target / "ranks.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["document", "best_to_worst", "last_fault"])
        w.writeheader()
        w.writerows(rank_rows)
    with open(target / "adjusted.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["document", "scanner", "adjusted"])
        w.writeheader()
        w.writerows(adjusted_rows)
    print(f"{len(rank_rows)} documents, {len(scanners)} scanners: fill in {target / 'ranks.csv'}")


def score(folder: Path) -> dict:
    """No-touch rate and mean rank per scanner, and the fault for each document where PdfCraft came last."""
    key = {}
    with open(folder / "key.csv", newline="") as f:
        for row in csv.DictReader(f):
            key[(row["document"], row["letter"])] = row["scanner"]
    ranks, lasts, problems = {}, [], []
    with open(folder / "ranks.csv", newline="") as f:
        for row in csv.DictReader(f):
            doc, letters = row["document"], row["best_to_worst"].strip().upper().replace(" ", "")
            expected = sorted(l for (d, l) in key if d == doc)
            if sorted(letters) != expected:
                problems.append(f"{doc}: ranking '{letters}' is not the letters {''.join(expected)}")
                continue
            for place, letter in enumerate(letters, start=1):
                ranks.setdefault(key[(doc, letter)], []).append(place)
            if key[(doc, letters[-1])] == OWN:
                fault = row["last_fault"].strip().lower()
                if fault and fault not in FAULTS:
                    problems.append(f"{doc}: fault '{fault}' is not one of {', '.join(FAULTS)}")
                lasts.append((doc, fault or "(not given)"))
    untouched = {}
    adjusted_file = folder / "adjusted.csv"
    if adjusted_file.is_file():
        with open(adjusted_file, newline="") as f:
            for row in csv.DictReader(f):
                answer = row["adjusted"].strip().lower()
                if answer in ("yes", "no"):
                    untouched.setdefault(row["scanner"], []).append(answer == "no")
                elif answer:
                    problems.append(f"{row['document']} {row['scanner']}: adjusted '{answer}' is not yes or no")
    result = {
        "mean_rank": {s: sum(r) / len(r) for s, r in sorted(ranks.items())},
        "no_touch": {s: sum(u) / len(u) for s, u in sorted(untouched.items())},
        "own_last": lasts,
        "problems": problems,
    }
    for line in problems:
        print("problem:", line)
    for scanner, mean in result["mean_rank"].items():
        print(f"{scanner}: mean rank {mean:.2f} over {len(ranks[scanner])} documents")
    for scanner, rate in result["no_touch"].items():
        print(f"{scanner}: no corner moved on {rate:.0%} of {len(untouched[scanner])} documents")
    for doc, fault in lasts:
        print(f"{OWN} last on {doc}: {fault}")
    return result


def self_test() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        for scanner in (OWN, "google", "other"):
            (root / "scans" / scanner).mkdir(parents=True)
            for n in (1, 2):
                (root / "scans" / scanner / f"doc-0{n}.jpg").write_bytes(f"{scanner}{n}".encode())
        (root / "scans" / "google" / "doc-03.jpg").write_bytes(b"only one")
        shuffle(root / "scans", root / "blind", seed=7)
        blind = root / "blind"
        assert not (blind / "doc-03-A.jpg").exists(), "a document missing from a scanner is skipped"
        key = {}
        with open(blind / "key.csv", newline="") as f:
            for row in csv.DictReader(f):
                key[(row["document"], row["letter"])] = row["scanner"]
                # each letter's file is that scanner's page
                page = (blind / f"{row['document']}-{row['letter']}.jpg").read_bytes()
                assert page == f"{row['scanner']}{row['document'][-1]}".encode()
        letter_of = {(d, s): l for (d, l), s in key.items()}
        # doc-01: pdfcraft best; doc-02: pdfcraft last, for its shadow
        rows = [
            {"document": "doc-01", "best_to_worst": letter_of[("doc-01", OWN)] + letter_of[("doc-01", "google")] + letter_of[("doc-01", "other")], "last_fault": ""},
            {"document": "doc-02", "best_to_worst": letter_of[("doc-02", "google")] + letter_of[("doc-02", "other")] + letter_of[("doc-02", OWN)], "last_fault": "shadow"},
        ]
        with open(blind / "ranks.csv", "w", newline="") as f:
            w = csv.DictWriter(f, fieldnames=["document", "best_to_worst", "last_fault"])
            w.writeheader()
            w.writerows(rows)
        with open(blind / "adjusted.csv", newline="") as f:
            template = list(csv.DictReader(f))
        assert len(template) == 6, template
        for row in template:
            # PdfCraft needed a hand on doc-02 only; the others never
            row["adjusted"] = "yes" if (row["scanner"], row["document"]) == (OWN, "doc-02") else "no"
        with open(blind / "adjusted.csv", "w", newline="") as f:
            w = csv.DictWriter(f, fieldnames=["document", "scanner", "adjusted"])
            w.writeheader()
            w.writerows(template)
        result = score(blind)
        assert result["mean_rank"] == {"google": 1.5, "other": 2.5, OWN: 2.0}, result
        assert result["no_touch"] == {"google": 1.0, "other": 1.0, OWN: 0.5}, result
        assert result["own_last"] == [("doc-02", "shadow")], result
        assert result["problems"] == [], result
    print("self-test passed")


def main(argv) -> None:
    if len(argv) == 4 and argv[1] == "shuffle":
        shuffle(Path(argv[2]), Path(argv[3]))
    elif len(argv) == 3 and argv[1] == "score":
        score(Path(argv[2]))
    elif len(argv) == 2 and argv[1] == "self-test":
        self_test()
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
