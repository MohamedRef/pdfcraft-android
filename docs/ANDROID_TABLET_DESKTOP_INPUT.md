# Adaptive tablet reader and desktop input (2026-11-06)

## Existing behavior preserved (already on main before this PR)

- `PageCanvas.kt`: mouse wheel scroll; Ctrl + wheel zoom around pointer; mouse primary-button drag selects text on a single PDF page; right/middle button drag pans; mouse hover near lower-right corner exposes zoom controls on desktop.
- `ZoomButtons.kt`: zoom buttons for Samsung DeX / ChromeOS / Android PC, shown with viewer bars or on pointer hover.
- `MainActivity.kt` and `AppViewModel.viewerKey`: keyboard navigation (N/P, Page Up/Down, arrows), Ctrl+P print, Ctrl+plus/minus zoom. The Keyboard Shortcuts setting defaults **off** and must be enabled in Preferences. Browser-like Ctrl+wheel zoom is independent of that setting.
- No native engine, signing, SAF, PDF performance, S Pen, or annotation changes.

## New functionality in this PR

- At expanded widths (>= 840dp), show a persistent, collapsible **page navigation pane** alongside the existing reader when running on a >=600dp smallest-width tablet or a detected desktop.
- Keep phone portrait/landscape, narrow split-screen, and compact DeX windows on the original one-pane interface. Reevaluate current window configuration on rotation/resize.
- The rail offers virtualized page rows, selected/current page, direct navigation, zoom in/out, and fit-width reset. It reuses the existing `ViewerState`; no second document renderer, embedded thumbnails, or duplicate native session.
- Keep full-screen reader modes, tools, selection, annotations, overlays, and modal page organizer in the original reader viewport. Hide the rail in Reading Mode and while the full page organizer is open.
- Unit tests: expanded breakpoint and reader viewport resize/page navigation.

## Acceptance checks / outstanding

- [ ] Android JVM unit tests and full CI aggregate merge gate pass on **exact PR head**.
- [ ] Manual Samsung tablet portrait/landscape, foldable, split-screen, DeX window resize, Chromebook mouse/keyboard tests.
- [ ] Verify keyboard shortcuts while editing form fields and searching do not steal typed keys (current `Activity.onKeyDown` behavior predates this PR; no new keys added).
- [ ] Verify on-device scroll-wheel, Ctrl+wheel, text drag, selection copy, zoom and S Pen coexist with tool overlays.
- [ ] Screen-reader labels, large fonts, multi-window/insets and RTL layout on device.
- [ ] Cold/warm PDF opening time remains unresolved vs Xodo; PR #24 diagnoses but is **not a performance fix**.
- [ ] Do not merge without exact-head CI and no conflicts. No independent beta release; publish one batch beta only when other queued features have passed and merged.

## Scope and limitations

- This is a **page list** layout, not image thumbnail generation or desktop-grade dual-page viewing.
- The original text selection is limited to one PDF page; cross-page selections need separate engine/UI changes.
- All input behaviors cited as existing are **code inspection evidence**, not measured real-device success.

## Review correction: retain the viewer during resize

Compact and expanded layouts now keep `ViewerScreen` at one stable composition call site.
The original conditional moved it into/out of a Row, which disposes remembered tool state;
Draw stores provisional strokes locally and only commits them when the tool is left. A
breakpoint transition could therefore discard ink before native save/Undo. The rail alone
changes visibility, preserving the reader subtree. This is a source-level fix, not witnessed
hardware QA. Required manual regression: draw without leaving Draw, resize repeatedly across
840dp, collapse/expand the rail, verify strokes and local Undo/Redo, then save/reopen. Also test
text selection, form focus, RTL and multi-window. Existing JVM breakpoint tests do not verify
Compose lifetime or physical-device behavior.


### Batch integration review

Synchronized the reviewed layout branch with main `952cc4a723f8fa3c1194a2312b504ce892ee95db` after export #25 merged. Existing mouse/keyboard behavior is preserved, rather than counted as a new feature. Required per-PR gates remain current-head Rust/provenance, Android JVM, title and aggregate merge success with no conflicts; physical tablet/DeX/accessibility/input checks remain outstanding for the single batch APK, per the owner’s no-per-PR-APK policy. Prior green CI 42282145245 is not evidence for this new integration head. No new release version is selected here.


Final batch synchronization: main `380b77d6389188516c75810d94a0f2e732270c9e` includes sequentially merged outline export #25, import #26 and copy #28, with staging/native-source cancellation fixes. Current tablet integration includes that complete bookmark stack and requires a fresh exact-head run; the previous f74cb42 run 42285637888 passed before this final synchronization. Keep next-batch #30/#31/#32 drafts out of the current release. No manual Samsung tests have been completed here.

## Keyboard and mouse annotation workflow (#153, 2026-11-11)

- Keyboard shortcuts now default **on** (only a physical keyboard sends these keys). The Preferences switch still turns them off.
- Tools: P pen, H highlight text, E eraser, L lasso, T text, N note, S shapes (again for the next shape). While drawing: 1-9 pen presets, `[` and `]` pen width.
- Esc steps back: selection, selected comment, open sheet, Find, then the active tool. Ctrl+Z undo, Ctrl+Shift+Z or Ctrl+Y redo, Ctrl+F find, Ctrl+B bookmark, Delete removes the selected comment, Ctrl+P print, Ctrl and + or − zoom, Ctrl+/ lists them.
- Page Down / Page Up / Space / Shift+Space turn pages (two at a time with two-page spreads). **N and P no longer turn pages**: they are now Note and Pen.
- The viewer's shortcuts are offered to the system Meta+/ helper (`onProvideKeyboardShortcuts`).
- Right click on the page: a comment's menu, else what a long press gives (word selection, picture, page menu). A right-button drag still pans.
- Keys reach the viewer only through `Activity.onKeyDown`, i.e. when no focused Compose text field consumed them. Not yet verified on a device with a hardware keyboard.
