# Unsaved ink kept through a crash (#680)

Draw keeps its strokes in memory as drafts and hands them to the engine on Done, on the eraser and when the app goes to the background; the engine's copy reaches the file at the next save (pause, tab switch, close). Until then a killed or crashed app, or a phone switched off, lost every stroke written since the app last went to the background: in a lecture written in Draw without leaving it, the whole lecture.

## What is kept

Each open document with a source (a picked, shared or opened file) has a journal in the app's no-backup files, `ink_journal/<sha-256 of the source>.pcjournal` (the name does not show the document). It holds every stroke the file does not have yet, in paint order: strokes the engine took and has not saved, strokes being handed to it, and the drafts. It is written 250 ms after each gesture ends (a stroke, an erase, a lasso move or delete, Undo, Redo, a hand-over to the engine), on one background thread, and flushed to the disk (`fsync`) each time.

The file is append-only: a header (magic `PCJL`, version 1, a hash of the document's page count and sizes), then records of type, length, CRC32 and payload. A record either keeps the first *k* strokes or adds one (page, colour, opacity, width, author, pen, dark paper, whether the width varies, points, widths, a pencil's densities). After a gesture only what changed is written: a new stroke is one added record; an erase in the middle keeps the strokes before it and adds the rest. A save that writes the engine's strokes starts the file over with what is left (or deletes it); the document closing with nothing unsaved, or with its changes discarded, deletes it. A write that fails (the disk full) never stops the app: the next one starts the file over.

## What comes back

When the same document is opened again and can be edited, a journal whose page fingerprint matches is replayed, records in order; a torn or damaged last record (the app died while writing it) ends the replay there, so only the gesture being written can be lost. Its strokes are added back as ink, one undoable step each as Draw saves them, and a message says how many: "N strokes not saved when the app closed were added back." Strokes the engine refuses stay drafts, as Draw's own do (#73). A journal for other pages (another document at that address, or pages added or removed elsewhere) is not replayed and is deleted. A document that does not allow annotations keeps its journal for a later visit and writes none.

## Edits of strokes the engine took (#703)

A stroke the engine took stays in the journal as it was handed over until a save writes it. Draw's later edits of it go to the engine, so before #703 a crash brought it back in its first shape: a cube moved, made smaller and turned with the lasso jumped back, and a stroke deleted with the lasso came back.

- **Followed:** the lasso's move, resize, turn, recolour and delete, and a scratch-out, make the same change to the journal's copy once the engine has made it. The journal finds its strokes among the annotations the edit changed by their rectangles: a stroke counts when every annotation it is saved as has its rectangle there, within 2 points plus half its width.
- **Marked:** an edit the journal can't follow adds a "touched" record (type 3, empty) and leaves the strokes as they are. This covers an eraser cut across a stroke it keeps, an Undo or Redo of the document while it keeps strokes, and an edit of only part of a stroke (a dashed line's piece, say). The restore then says "Changes made to them afterwards (moving, erasing, Undo) may be missing." A save that writes every such stroke clears the mark, and the file is started over without it.

## Testing option and log

- **Settings › Diagnostics › Pen lab › Keep unsaved ink through a crash** (pref `pen_lab/ink_journal`, on). Off: nothing is written or restored, as before. `pen_lab_options` logs `ink_journal`.
- `ink_journal` when a journal is found at open: `outcome` (`restored`, `stale`, `damaged`, `not_allowed`), `strokes`, `points`, `records`, `torn`, `bytes`, `applied` (strokes the engine took back), `ms`, and from #703 `touched` (an edit changed some of them in a way the journal did not follow).
- `ink_journal_closed` when a document that wrote a journal, or still had unsaved strokes, closes: `writes`, `failures`, `slowest_write_ms`, `bytes_written`, `unsaved` (strokes still to save; kept for the next visit when not discarded), `discarded`.

Counts only: never the strokes, their positions or the document's name.

## Tests and limits

`InkJournalTest` (JVM): a stroke of every pen comes back as written; 50 edits of every kind (new strokes, an erase that cuts one in two, a lasso move, Undo, a save) replay to the strokes on screen after each; an unchanged list writes nothing; a record cut short or with a flipped byte loses only itself and what follows; another document's journal and a file that is no journal are not replayed; the name does not show the source; a document's journal keeps the hand-over to the engine without a rewrite, keeps a stroke drawn during a save, is kept at close with a stroke unsaved and deleted when discarded; nothing is written while the strokes found are added back, and a refused one stays in its place as a draft.

`InkJournalFollowsTest` (JVM, #703): a moved, turned and halved, recoloured and deleted stroke comes back as left, not as drawn; an edit of another page or drawing writes nothing; an edit of part of a stroke, an eraser cut and an Undo mark the journal and keep the stroke; the mark lasts through a restore until a save, which keeps later drafts; the rectangle matching of plain and calligraphy strokes.

Not tested here: the wiring in `AppViewModel` (open, save, close) and Draw (`commitTool`), which needs the engine and a device; a real crash, a killed process or a phone switched off; how long a write takes on a phone with thousands of drafts (`slowest_write_ms` in the log says). Only Draw's strokes are kept: text, shapes, comments and other edits are the engine's and its working copy's matter.
