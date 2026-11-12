# Scan: comparing PdfCraft's scanner with Google's (#307)

**State: the protocol and its ranking script only. No comparison has been run. Google's scanner
is not part of PdfCraft (see "Why Google's scanner is not in the app" below); it is used from
Google's own apps.**

The point of #307 is a yardstick: scan the same documents with PdfCraft and with Google's
scanner, rank the results without knowing which is which, and turn every document where PdfCraft
came last into the next thing to fix.

## Where Google's scanner comes from

Google's document scanner (ML Kit Document Scanner) is the scanner in the **Google Drive** app's
Scan button and in **Files by Google**. Google says so on the day it opened the scanner to other
apps: "The ML Kit Document Scanner API is already used by Google Drive Android application and
the Google Pixel Camera"
(https://android-developers.googleblog.com/2024/02/ml-kit-document-scanner-api.html). Its models
come through Google Play services, so the same phone gives the same scanner in either app.

Use Drive's ordinary scanner. If Drive offers its "Workspace Labs" scanner, leave that off: it is
a different, newer flow, and the comparison should stay with one scanner from run to run. Write
down the Google Play services version (Settings → Apps → Google Play services) with each run,
because the scanner changes with it.

## Why Google's scanner is not in the app

The issue planned an optional Google engine inside PdfCraft, through the
`play-services-mlkit-document-scanner` library. That library is closed source and part of Google
Play services, and PdfCraft is built for F-Droid as a reproducible build (`docs/fdroid/README.md`):
F-Droid builds the same APK we publish, and F-Droid does not accept an app that depends on Google
Play services. Adding the library would end the F-Droid build, or split PdfCraft into two
different APKs. The owner was asked which he wants (12 November 2026). Until he chooses to add it,
the comparison uses Google's own apps, which compare the same scanner.

## The protocol

1. **Documents.** About 30, covering the kinds in the test set (#288): printed text, Arabic
   text, a form, a receipt, a page from a bound book, a glossy page, a page with handwriting and
   stamps, a page photographed on a busy surface, in dim light, and at an angle. Keep the list
   (kind only, no content) with the run.
2. **Scan each document three ways** from the same position and the same light, one straight
   after the other:
   - PdfCraft, with the scanner's defaults (no testing option changed);
   - Google's scanner from the Drive app, with its defaults (Auto filter);
   - any third scanner app you use, as an ordinary user of it, if you want a third yardstick.
     Its name stays out of the repository and the app; call its folder `other`.
3. **Note while scanning** for each scanner and document whether you had to move a corner by
   hand. That is the "no-touch" rate.
4. **Export the pages as JPEG** into one folder per scanner, the same file name for the same
   document: `scans/pdfcraft/doc-01.jpg`, `scans/google/doc-01.jpg`, `scans/other/doc-01.jpg`.
   From PdfCraft, the page images of a kept scan (Settings → About → Diagnostics → Keep scans for
   testing) or the saved PDF's pages as images; from Drive, download the scan as images.
5. **Rank blind** with `tools/scan-compare/blind.py` on your computer:
   ```
   python3 tools/scan-compare/blind.py shuffle scans blind
   ```
   It copies each document's pages to `blind/doc-01-A.jpg`, `-B.jpg`, `-C.jpg` in a random order
   and writes the answer to `blind/key.csv`; do not open that file yet. Fill in
   `blind/adjusted.csv` from your notes (yes or no per scanner and document). Then look at each
   document's letters in a gallery and fill in `blind/ranks.csv`: the letters best to worst
   (`BAC`), and for the one that came last, what was worse: `edges`, `shadow`, `sharpness`,
   `colour` or `flatness`.
6. **Score:**
   ```
   python3 tools/scan-compare/blind.py score blind
   ```
   prints, per scanner, the share of documents where no corner was moved and the mean rank (1 is
   best), and for each document where PdfCraft came last, the fault you wrote.

The script uses only Python's own library and uploads nothing. `python3
tools/scan-compare/blind.py self-test` checks it on made-up files.

## What is kept, and where

The photos and pages are yours and never go into the repository, an issue or a pull request, nor
does anything made from them. What can be posted on the tracking issue #286, if you want it there,
is the score's summary only: the no-touch rate and mean rank per scanner, the count of documents,
the Play services version, and the faults where PdfCraft came last (one word each, no images).

## When

The first run, before the new pipeline's pieces reach a release, is the baseline. Repeat after
each milestone of #286, with the same documents where you still have them.
