# Stream-end recovery during native opening

## Confirmed mechanism

A zero editing-parser repair count does not establish that every stream has a correct `/Length`. `parse_indirect_in` tolerates a missing/incorrect length by locating `endstream`; this stream recovery does not add a document repair-log entry.

Opening also discovers standalone document timestamps, including in PDFs without AcroForm signature fields. `DigestCache::timestamp_candidates` calls `scan_object_headers_checked`; its stream-header path resolves the stream's extent. A missing length can therefore build the whole-file `EndstreamIndex` during signature discovery even when the file has no signatures, its xref is healthy and its repair count is zero.

The index used a byte-window comparison at every input position. Use the existing `memchr::memmem` dependency for the index and the non-indexed forward search instead. Keep the token-boundary predicate, CR/LF handling, one-time index, scan accounting and timestamp validation. `endstream` cannot overlap itself, so non-overlapping substring search does not omit an old match.

## Reproduce

Run the manual host example on the parent and changed parser:

```
cargo run --locked -p pdfcraft-android --example open_stream_timings -- 336
```

The example creates two temporary sparse PDFs, each with 869 blank pages, a healthy xref and one roughly 352 MB stream. One gives the stream's length; the other omits it. Both open with zero repair notes. The example removes its temporary files and prints the request-local native stage durations. The range argument is 1–480 MiB, below the native input cap. This is manual benchmark code, not a 352 MB CI test.

One illustrative x86_64 host run (Rust 1.99, repository dev profile; not a release APK):

| Fixture / stage | Original search | Substring search |
| --- | ---: | ---: |
| Length present, native preparation | 6 ms | 5 ms |
| Length missing, signature discovery | 462 ms | 11 ms |
| Length missing, native preparation | 468 ms | 16 ms |

The file-read stage was 71–77 ms and is separate from native preparation. Nested stage durations overlap; do not add them as independent stages.

An additional synthetic image-book check (869 raw 400×337 RGB images, approximately 352 MB) gave signature discovery 481→17 ms and whole `MobileSession::open_with_timings` 603→143 ms when image lengths were omitted. With correct lengths, whole open was 128→130 ms. These are illustrative individual runs, not statistical/device speed-up estimates.

## Remaining costs and limits

Adding 32 unique links per page (27,808 total) to the image book made full inspection, display preparation and field discovery substantially more expensive even with correct stream lengths. In an illustrative run they took about 162, 116 and 72 ms respectively before this change. Compressed object streams showed the same broad behavior. This search change does not defer those all-page passes, remove the full native file read, or make first-page presentation independent of full document preparation.

The original slow PDF has not been reproduced or inspected. This proves and optimizes one slow-open mechanism compatible with a zero repair count; it does not establish that this mechanism caused a particular device report. Native stage timings on the actual file must identify its dominant work. No physical Android performance claim follows from these host results.

## Verification

Differential tests compare both search paths against the original window scan across fixed token/EOL cases, all 256 following-byte values and randomized binary chunks with embedded keywords. Existing parser, timestamp-discovery, encryption, renderer, engine, automation and mobile tests remain the behavioral gates. No signature or repair checks are skipped.
