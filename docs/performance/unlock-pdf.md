# Unlock PDF on a large file

Issue: #550.

## What the phone showed

Owner's diagnostics log, 0.0.29-beta (29012), Samsung SM-S928B, one restrictions-only file (352,090,743 bytes, 869 pages): Unlock PDF took 22.6 s. `doc_unprotect` took 10,136 ms and `doc_save` 8,731 ms.

## Where the time went

`UnlockPdf.kt` opened a hidden copy, then called `doc_unprotect`, `doc_save` (`full: true`) and `doc_close`. On the host fixture below, release profile, with temporary timers:

| Step | Host ms | Needed for the output? |
| --- | ---: | --- |
| `doc_unprotect`: refresh after the edit, full decrypted rewrite for the view | 600 | No |
| `doc_unprotect`: refresh, inspect, forms, signatures, display copy | 400 | No |
| `doc_save`: `write_full` | 680–790 | Yes |
| `doc_save`: check before writing | 6–8 | Yes |
| `doc_save`: writing the file (fsync) | 480–580 | Yes |
| `doc_save`: `mark_saved`, reopen and full refresh | 720–1,000 | No |

About half of the time rebuilt the view of a copy that nobody sees and that is closed straight after.

## Change

`doc_unprotect` takes an optional `out`. With it, the tool:

1. applies the same edit through `Session::apply_before_closing`, which is `Session::apply` without the view refresh;
2. runs Will Save scripts, `save_full_bytes`, `check_save` and the atomic write, exactly as `doc_save` with `full: true` does;
3. closes the document instead of rebasing it on the saved file, also when a step fails.

`UnlockPdf.kt` now makes this one call instead of `doc_unprotect`, `doc_save` and `doc_close`.

The bytes are unchanged. `unprotect_to_a_file_writes_what_unprotect_and_save_write` compares both paths byte for byte with a fixed clock, for AES-256, AES-128, RC4-128 and RC4-40, with and without an open password, on a file that has a comment and a form field.

## Measurement

`examples/unlock_timings.rs`, release profile, 4-core cloud host. The fixture is 869 pages, each with a 389 KB image and 32 links, protected with a permissions password only (AES-256, 338,602,252 bytes). Three rounds of each. These are host numbers, not phone results.

| | Before (unprotect, save, close) | After (unprotect with `out`) |
| --- | ---: | ---: |
| Unprotect and save | 3,553–4,066 ms | 1,551–1,597 ms |
| Whole Unlock, open included | 4,504–4,991 ms | 2,308–2,449 ms |

The phone result needs a diagnostics log from a build with this change.
