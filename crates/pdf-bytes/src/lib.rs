//! Shared immutable PDF input; parsers retain a source without copying its bytes.
use std::ops::Deref;
use std::sync::Arc;

pub trait PdfSource: Deref<Target = [u8]> + AsRef<[u8]> + std::fmt::Debug + Send + Sync + std::panic::RefUnwindSafe + std::panic::UnwindSafe {
    fn capacity(&self) -> usize;
    fn is_mapped(&self) -> bool {
        false
    }
    fn as_slice(&self) -> &[u8] {
        self.as_ref()
    }
}
impl PdfSource for Vec<u8> {
    fn capacity(&self) -> usize {
        Vec::capacity(self)
    }
}
impl PdfSource for memmap2::Mmap {
    fn capacity(&self) -> usize {
        self.len()
    }
    fn is_mapped(&self) -> bool {
        true
    }
}
pub type PdfBytes = Arc<dyn PdfSource>;

pub fn same_storage<A: ?Sized, B: ?Sized>(a: &Arc<A>, b: &Arc<B>) -> bool {
    Arc::as_ptr(a).cast::<()>() == Arc::as_ptr(b).cast::<()>()
}

/// Sized adapter for renderers whose byte-source parameter is Arc<T>.
#[derive(Clone, Debug)]
pub struct PdfView(pub PdfBytes);
impl AsRef<[u8]> for PdfView {
    fn as_ref(&self) -> &[u8] {
        &self.0
    }
}

/// An immutable anonymous mapping, useful for differential parser and lifetime tests.
pub fn memory_snapshot(bytes: &[u8]) -> std::io::Result<PdfBytes> {
    if bytes.is_empty() {
        return Ok(Arc::new(Vec::new()));
    }
    let mut map = memmap2::MmapMut::map_anon(bytes.len())?;
    map.copy_from_slice(bytes);
    Ok(Arc::new(map.make_read_only()?))
}

use std::fs::File;
use std::io::{self, Read};
use std::path::Path;

/// Read once, mapping only Android's private managed working files. Other inputs retain an
/// owned snapshot. The app must publish every managed-file replacement by atomic rename;
/// mapped source inodes must never be modified in place or shared for writing.
pub fn read_private_input(path: &Path, root: Option<&Path>, cap: u64) -> io::Result<PdfBytes> {
    read_private_with(path, root, cap, map_managed_inode)
}

fn read_private_with(path: &Path, root: Option<&Path>, cap: u64, map: impl FnOnce(&File) -> io::Result<memmap2::Mmap>) -> io::Result<PdfBytes> {
    let cap = cap.min(512 * 1024 * 1024);
    let file = File::open(path)?;
    let metadata = file.metadata()?;
    if metadata.is_file() && metadata.len() > cap {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "input exceeds 512 MiB"));
    }
    if metadata.len() >= 1024 * 1024 && root.is_some_and(|root| managed_private_file(path, root, &metadata)) {
        if let Ok(mapped) = map(&file) {
            return Ok(Arc::new(mapped));
        }
    }
    let mut bytes = Vec::new();
    if metadata.is_file() {
        bytes.try_reserve_exact(metadata.len() as usize + 1).map_err(|e| io::Error::new(io::ErrorKind::OutOfMemory, e))?;
    }
    file.take(cap + 1).read_to_end(&mut bytes)?;
    if bytes.len() as u64 > cap {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "input exceeds 512 MiB"));
    }
    Ok(Arc::new(bytes))
}

#[cfg(any(target_os = "android", all(test, target_os = "linux")))]
fn managed_private_file(path: &Path, root: &Path, opened: &std::fs::Metadata) -> bool {
    use std::os::unix::fs::MetadataExt;
    let Ok(root) = root.canonicalize() else { return false };
    #[cfg(not(test))]
    if !root.to_string_lossy().ends_with("/io.github.mohamedref.pdfcraft/files")
        || !(root.starts_with("/data/user") || root.starts_with("/data/data"))
    {
        return false;
    }
    let Ok(canonical) = path.canonicalize() else { return false };
    let Ok(relative) = canonical.strip_prefix(&root) else { return false };
    let Some(first) = relative.components().next() else { return false };
    if !matches!(first.as_os_str().to_str(), Some("Documents" | "Created")) {
        return false;
    }
    let (Ok(owner), Ok(root_meta), Ok(current)) = (std::fs::metadata("/proc/self"), root.metadata(), canonical.metadata()) else { return false };
    opened.is_file()
        && opened.len() > 0
        && opened.nlink() == 1
        && opened.uid() == owner.uid()
        && opened.uid() == root_meta.uid()
        && opened.mode() & 0o006 == 0
        && (opened.mode() & 0o020 == 0 || opened.gid() == owner.gid())
        && root_meta.mode() & 0o002 == 0
        && opened.ino() == current.ino()
        && opened.dev() == current.dev()
}

#[cfg(not(any(target_os = "android", all(test, target_os = "linux"))))]
fn managed_private_file(_: &Path, _: &Path, _: &std::fs::Metadata) -> bool {
    false
}

/// The only unsafe boundary. Accepted inputs belong to the app's private Documents/Created
/// stores, are not exposed by FileProvider, and all writers replace regular files atomically.
/// The map owns the old inode's lifetime through rename/unlink; arbitrary paths are not mapped.
#[allow(unsafe_code)]
fn map_managed_inode(file: &File) -> io::Result<memmap2::Mmap> {
    // SAFETY: managed_private_file established private ownership and inode identity. The
    // application write audit enforces replacement, never truncation, for these stores.
    unsafe { memmap2::MmapOptions::new().map(file) }
}

impl PartialEq for dyn PdfSource {
    fn eq(&self, other: &Self) -> bool {
        self.as_slice() == other.as_slice()
    }
}
impl Eq for dyn PdfSource {}

#[cfg(all(test, target_os = "linux"))]
mod tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt;
    use std::sync::atomic::{AtomicUsize, Ordering};
    struct Fixture(std::path::PathBuf);
    impl Fixture {
        fn new() -> Self {
            static NEXT: AtomicUsize = AtomicUsize::new(0);
            let root = std::env::temp_dir().join(format!("pdfcraft-source-{}-{}", std::process::id(), NEXT.fetch_add(1, Ordering::Relaxed)));
            std::fs::create_dir_all(root.join("Documents")).unwrap();
            std::fs::set_permissions(&root, std::fs::Permissions::from_mode(0o700)).unwrap();
            Self(root)
        }
        fn write(&self, name: &str, bytes: &[u8]) -> std::path::PathBuf {
            let path = self.0.join(name);
            std::fs::write(&path, bytes).unwrap();
            std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600)).unwrap();
            path
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    #[test]
    fn managed_mapping_survives_atomic_save_unlink_and_owner_drop() {
        let fixture = Fixture::new();
        let original = vec![29u8; 1024 * 1024];
        let path = fixture.write("Documents/reading.pdf", &original);
        let source = read_private_input(&path, Some(&fixture.0), 2 * 1024 * 1024).unwrap();
        assert!(source.is_mapped());
        let retained = source.clone();
        assert!(same_storage(&source, &retained));
        let staged = fixture.write("Documents/staged.pdf", b"new revision");
        std::fs::rename(staged, &path).unwrap();
        assert_eq!(std::fs::read(&path).unwrap(), b"new revision");
        assert_eq!(source.as_slice(), original);
        std::fs::remove_file(&path).unwrap();
        drop(source);
        assert_eq!(retained.as_slice(), original);
    }

    #[test]
    fn arbitrary_sources_remain_owned_and_caps_and_aliases_fail_closed() {
        let fixture = Fixture::new();
        let original = vec![7u8; 1024 * 1024];
        let path = fixture.write("Documents/input.pdf", &original);
        let owned = read_private_input(&path, None, 2 * 1024 * 1024).unwrap();
        assert!(!owned.is_mapped());
        std::fs::write(&path, b"changed in place").unwrap();
        assert_eq!(owned.as_slice(), original);
        assert!(read_private_input(&path, Some(&fixture.0), 4).is_err());
        let outside = fixture.write("outside.pdf", &original);
        let link = fixture.0.join("Documents/link.pdf");
        std::os::unix::fs::symlink(&outside, &link).unwrap();
        assert!(!read_private_input(&link, Some(&fixture.0), 2 * 1024 * 1024).unwrap().is_mapped());
        std::fs::remove_file(link).unwrap();
        let path = fixture.write("Documents/input.pdf", &original);
        std::fs::hard_link(&path, fixture.0.join("alias.pdf")).unwrap();
        assert!(!read_private_input(&path, Some(&fixture.0), 2 * 1024 * 1024).unwrap().is_mapped());
    }

    #[test]
    fn failed_mapping_keeps_the_bounded_owned_reader_as_fallback() {
        let fixture = Fixture::new();
        let original = vec![91u8; 1024 * 1024];
        let path = fixture.write("Documents/fallback.pdf", &original);
        let source =
            read_private_with(&path, Some(&fixture.0), 2 * 1024 * 1024, |_| Err(io::Error::new(io::ErrorKind::Unsupported, "mapping unavailable")))
                .unwrap();
        assert!(!source.is_mapped());
        assert_eq!(source.as_slice(), original);
    }

    #[test]
    fn anonymous_mapped_sources_have_identical_content_and_shared_lifetime() {
        let bytes = b"%PDF-1.7 immutable source";
        let mapped = memory_snapshot(bytes).unwrap();
        assert!(mapped.is_mapped());
        let clone = mapped.clone();
        assert!(same_storage(&mapped, &clone));
        drop(mapped);
        assert_eq!(clone.as_slice(), bytes);
        assert!(!memory_snapshot(&[]).unwrap().is_mapped());
    }
}

pub fn owned_bytes(bytes: Vec<u8>) -> PdfBytes {
    Arc::new(bytes)
}
