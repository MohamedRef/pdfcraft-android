# Shared Android PDF input

`pdfcraft-bytes::PdfBytes` shares immutable owned bytes or a read-only mapping through COS,
stream views, rendering, editing history, background preparation and automation caches. Hayro
already accepts a shared `AsRef<[u8]>` source; a sized `PdfView` adapter retains the same allocation
without changing the vendored parser. Newly serialized revisions remain owned allocations.

Android supplies its application files directory once at session initialization. Complete and
reading-stage opens keep the same path confinement and 512 MiB input cap. A regular source of
at least 1 MiB is eligible for mapping only inside that application's `Documents` or `Created`
store, with the published hashed-folder or flat-file layout and a nonhidden PDF filename.
Staging files and unrecognized layouts always remain owned, even within those stores.
The guard checks canonical containment, process/file/root ownership, restrictive file
permissions, a single hard link and the opened descriptor's inode/device identity. Arbitrary
paths, aliases outside those stores, shared files, unsupported mappings and non-Android hosts
retain the bounded owned reader. Diagnostics record `native_input_mapped` or `native_input_owned`
as zero-time markers, separately from the enclosing file-input duration. File-list thumbnails
use a separate 100 MiB input cap, matching the existing provider-preview limit, so listing a
large local PDF does not allocate another full input copy beside the viewer.

## Immutability and writers

A read-only descriptor alone does not make a file immutable. The mapping boundary relies on
application ownership of these stores and the following audited writer protocol:

- The provider copy is staged under a separate file in the destination directory. Publication
  uses rename only; failure preserves the previous file. There is no truncating copy-over fallback.
- Automation writes to regular outputs through the platform's synced atomic staging writer.
  The special stream/pipe output path cannot qualify for mapping.
- Native attachment and sound extraction also use that atomic output writer, including when
  asked to replace an existing file. Session saves already use atomic replacement.
- FileProvider exposes separate share/camera copies and verified updater files, not the mapped
  `Documents`/`Created` stores. Provider write-back addresses the external original; direct owned
  file saves do not copy over their own working inode.
- Creating a new staged/export file or changing timestamps does not change a retained source's
  bytes. Replacing or unlinking a pathname leaves the old mapped inode alive until its final
  parser, stream view, history or render-cache owner is released.

`crates/pdf-bytes` is an Android-owned adapter, not an upstream engine change. Its only unsafe
operation is the private file-map constructor after the ownership/store checks. The rest of the
workspace retains its unsafe-code prohibition. Future writers to managed stores must preserve
this replacement protocol; exposing these files for external writing would invalidate it.

## Validation and limits

Regression tests cover mapped readers surviving atomic replacement/unlink and owner drop;
bounded owned fallback, failed mapping, actively rewritten staging files, untrusted symlinks/hard links; anonymous mapped input
through both parsers, pixel equality, reading-stage completion, encryption, editing and saving.
Source-allocation identity and cache-generation checks are retained. Byte-content assertions
compare slices so owned and mapped representations have identical semantics.

Mapping avoids the initial whole-file Vec allocation for eligible managed inputs. It does not
remove page faults, signature hashing or full preparation work; these may eventually touch every
page of the source. No fixed Android opening time, RSS saving or physical-device result is
claimed. Device diagnostics must confirm that the mapping guard selected the intended path.
