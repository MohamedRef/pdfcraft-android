# Document tabs and split view (#138, #139)

## What the reader sees

- **Tabs.** Opening a document while another is open keeps the first one open in a tab instead of
  closing it. With two or more documents open, a tab strip shows under the top bar: tap a tab to
  bring its document to the front, × to close it, + to open another document from Home. The More
  menu (⋮) also has **Open another document**, so the first second tab can be opened from a
  single document.
- A tab comes back with its page, zoom, Reading Mode and the tool it was left in.
- **Back** with other tabs open goes to Home and keeps every tab. Home then shows "N open
  documents" with **Back to documents** and **Close all**. With a single document, or a document
  another app opened, Back closes it as before.
- **Keyboard (DeX, tablets with a keyboard):** Ctrl+Tab and Ctrl+Shift+Tab switch tabs, Ctrl+W
  closes the tab in front. They follow the Keyboard shortcuts setting.
- **Split view.** The split button at the end of the tab strip shows another open document beside
  the one in front (side by side on a wide window, one above the other on a tall one). The second
  pane scrolls and zooms on its own and is read-only; **Swap** brings it to the front for editing.
  The divider drags (25% to 75%). The pane closes with ×, or the split button again.

## How it works

- `AppViewModel.document` stays the document in front, so every tool still acts on it. The other
  open documents live in `TabsController` (`ui/TabsController.kt`) with their own `OpenDocument`
  (viewer state, controller, search, ink drafts).
- A document only goes behind another after its tool's ink is added and it is saved exactly as on
  leaving the app (`save(trigger = "tab_switch")`), so a background tab has no unsaved edits. A
  document whose changes could not reach its original stays open in its tab; closing it later
  shows the usual Save a copy / Discard prompt (it comes to the front first).
- At most four documents stay open in the engine (`TabPolicy.MAX_HOT`). Older tabs are saved and
  closed ("parked", shown in italics) and open again from their file when chosen. The document in
  front, the split pane's, documents with unsaved or not-written-back changes and documents without
  a reopenable source are never parked. Their page images are trimmed to what is in view when
  they go behind.
- Working copies and access grants of every tab are kept by the clean-up on Home
  (`DocumentFiles.pruneCopies(..., tabs)`, `releaseUnusedAccess`).
- The tab list (sources and titles, no contents) is saved in the app's preferences, so after
  Android closes the app the tabs come back parked.
- The viewer is keyed on the document, so a tab never inherits another document's remembered
  composition state.
- Diagnostics: `tab_*` and `split_*` lines in the `viewer` area (see `ANDROID_DIAGNOSTICS_LOG.md`).

## Not done yet (follow-up issues)

- Dragging text, a snip or ink from one pane to the other with a back-link (#139's drag-to-copy and
  PDF back-links).
- Editing in both panes at once (the second pane is read-only; Swap moves editing across).
- Measured switch time and memory with 4 to 8 tabs on the owner's phone: not measured. The
  `tab_switch ms` and `memory` lines in the diagnostics log will answer it.
