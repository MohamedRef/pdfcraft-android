# Page pixels across the FFI in one copy

Issue: #86.

## Before

A page or tile raster reached Kotlin through four full-size copies: the renderer's `Pixels` (`Vec<u32>`) copied into a `Vec<u8>` (`to_vec`); UniFFI 0.32.2 lowering that `Vec<u8>` one byte at a time into a buffer that starts empty and grows; the generated Kotlin reading it into a `ByteArray`; and `copyPixelsFromBuffer` into the `Bitmap`. `render_page_compact` made the `to_vec` copy too whenever it kept RGBA8, which is every tile.

## Change

`apps/pdfcraft-android/ffi-bytes` (`pdfcraft-android-ffi-bytes`) defines `Bytes`, which holds the renderer's own buffer and implements UniFFI's converter traits with `Vec<u8>`'s exact wire format and type metadata, writing the bytes with one `extend_from_slice` into a buffer reserved at full size. The generated Kotlin is unchanged (`ByteArray`). `RenderedImage.rgba` and `CompactImage.pixels` use it, so the `to_vec` copy and the per-byte serialization are gone. The crate exists because these trait impls are `unsafe impl`; the bridge itself keeps `#![forbid(unsafe_code)]`. A unit test checks that the serialized form, the lowered buffer and the metadata equal `Vec<u8>`'s byte for byte.

The Java `ByteArray` and the `Bitmap` copy remain; removing them needs a JNI entry point that renders into the `Bitmap` directly.

## Measurement

`session_contention_timings lower` on the 869-page synthetic fixture (`session-lock-and-text-cache.md`), page 1 at the 4 MP cap (16,453,524 bytes), release profile, 4-core cloud host. Host numbers, not phone results.

| | Before | After (3 runs) |
| --- | ---: | ---: |
| UniFFI lowering of the `RenderedImage` | 22.0 ms | 8.9–9.0 ms |

The `to_vec` copy, which was inside `render_page` before, is also gone and is not included in the lowering figure.
