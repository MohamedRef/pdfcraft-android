//! Byte buffers that cross the UniFFI boundary in one copy (#86).
//!
//! UniFFI 0.32 lowers a `Vec<u8>` field one byte at a time into a buffer that starts empty and
//! grows as it goes. For a 4-megapixel page (16 MiB) that took about 22 ms on a desktop host,
//! more than half the time of the render itself, after the renderer's pixels had already been
//! copied once into that `Vec<u8>`. [`Bytes`] keeps the renderer's own buffer and writes it with
//! one `memcpy`. It has the exact wire format and metadata of `Vec<u8>`, so the generated Kotlin
//! is unchanged: the field is still a `ByteArray`.

use std::ops::Deref;
use std::sync::Arc;

/// Bytes owned by any buffer (a renderer's pixels, a `Vec<u8>`), sent across the FFI as
/// UniFFI's `Vec<u8>`.
#[derive(Clone)]
pub struct Bytes(Arc<dyn AsRef<[u8]> + Send + Sync>);

impl Bytes {
    pub fn new(bytes: impl AsRef<[u8]> + Send + Sync + 'static) -> Self {
        Self(Arc::new(bytes))
    }
}

impl From<Vec<u8>> for Bytes {
    fn from(v: Vec<u8>) -> Self {
        Self::new(v)
    }
}

impl Deref for Bytes {
    type Target = [u8];

    fn deref(&self) -> &[u8] {
        (*self.0).as_ref()
    }
}

impl PartialEq for Bytes {
    fn eq(&self, other: &Self) -> bool {
        **self == **other
    }
}

impl Eq for Bytes {}

impl PartialEq<Vec<u8>> for Bytes {
    fn eq(&self, other: &Vec<u8>) -> bool {
        **self == other[..]
    }
}

impl std::fmt::Debug for Bytes {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "Bytes({} bytes)", self.len())
    }
}

/// Appends `bytes` as UniFFI writes a `Vec<u8>`: a big-endian `i32` count, then the bytes.
fn write_vec_u8(bytes: &[u8], buf: &mut Vec<u8>) {
    // UniFFI limits sequences to i32::MAX items; page buffers are capped far below (16 MiB).
    let len = i32::try_from(bytes.len()).unwrap_or(i32::MAX);
    let bytes = &bytes[..len as usize];
    buf.reserve(4 + bytes.len());
    buf.extend_from_slice(&len.to_be_bytes());
    buf.extend_from_slice(bytes);
}

// SAFETY: the FFI type and every read and write are `Vec<u8>`'s own (the same `RustBuffer`, the
// same serialized form, the same type metadata), so foreign code sees exactly a `Vec<u8>`.
#[allow(unsafe_code)]
unsafe impl<UT> uniffi::FfiConverter<UT> for Bytes {
    type FfiType = uniffi::RustBuffer;

    fn lower(obj: Self) -> uniffi::RustBuffer {
        let mut buf = Vec::new();
        write_vec_u8(&obj, &mut buf);
        uniffi::RustBuffer::from_vec(buf)
    }

    fn try_lift(buf: uniffi::RustBuffer) -> uniffi::Result<Self> {
        <Vec<u8> as uniffi::Lift<UT>>::try_lift(buf).map(Self::from)
    }

    fn write(obj: Self, buf: &mut Vec<u8>) {
        write_vec_u8(&obj, buf);
    }

    fn try_read(buf: &mut &[u8]) -> uniffi::Result<Self> {
        <Vec<u8> as uniffi::Lift<UT>>::try_read(buf).map(Self::from)
    }

    const TYPE_ID_META: uniffi::MetadataBuffer = <Vec<u8> as uniffi::TypeId<UT>>::TYPE_ID_META;
}

#[allow(unsafe_code)]
mod derived {
    use super::Bytes;
    uniffi::derive_ffi_traits!(blanket Bytes);
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Tag;

    /// The serialized form, the lowered buffer and the metadata are `Vec<u8>`'s, byte for byte.
    #[test]
    fn same_wire_format_and_metadata_as_vec_u8() {
        for data in [Vec::new(), vec![7u8], (0..70_000u32).map(|i| (i * 31 % 251) as u8).collect::<Vec<_>>()] {
            let mut ours = vec![1, 2, 3];
            let mut theirs = vec![1, 2, 3];
            <Bytes as uniffi::Lower<Tag>>::write(Bytes::from(data.clone()), &mut ours);
            <Vec<u8> as uniffi::Lower<Tag>>::write(data.clone(), &mut theirs);
            assert_eq!(ours, theirs);
            let lowered = <Bytes as uniffi::Lower<Tag>>::lower(Bytes::from(data.clone())).destroy_into_vec();
            assert_eq!(lowered, <Vec<u8> as uniffi::Lower<Tag>>::lower(data.clone()).destroy_into_vec());
            let mut slice = &theirs[3..];
            let back = <Bytes as uniffi::Lift<Tag>>::try_read(&mut slice).expect("reads");
            assert_eq!(back, data);
            assert!(slice.is_empty());
        }
        assert_eq!(<Bytes as uniffi::TypeId<Tag>>::TYPE_ID_META.as_ref(), <Vec<u8> as uniffi::TypeId<Tag>>::TYPE_ID_META.as_ref());
    }
}
