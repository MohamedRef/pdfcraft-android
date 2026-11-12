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
    /// About to read every byte once, in order (a full copy). A mapping asks the kernel to read
    /// ahead (#212): copying a mapped 352 MB file page fault by page fault took about 0.45 s
    /// longer than copying the same bytes from memory on a phone. Only a hint.
    fn will_read_all(&self) {}
    /// These bytes followed by `tail` as one source, without copying these bytes, where the
    /// source can do that; `None` otherwise. Callers use [`joined`], which falls back to a copy.
    fn appended(&self, _tail: &[u8]) -> Option<PdfBytes> {
        None
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
    fn will_read_all(&self) {
        #[cfg(unix)]
        {
            let _ = self.advise(memmap2::Advice::Sequential);
            let _ = self.advise(memmap2::Advice::WillNeed);
        }
    }
}
pub type PdfBytes = Arc<dyn PdfSource>;

/// `source` followed by `tail` as one immutable source, as an incremental update is written
/// (§7.5.6). A managed private file that is mapped is extended instead of copied (#234): its pages
/// stay shared with the file and only the tail, plus the file's last partial page, is new memory.
/// Any other source is copied once, into an allocation of the exact size.
pub fn joined(source: &PdfBytes, tail: &[u8]) -> PdfBytes {
    if tail.is_empty() {
        return source.clone();
    }
    if let Some(spliced) = source.appended(tail) {
        return spliced;
    }
    let mut file = Vec::with_capacity(source.len() + tail.len());
    // Android adaptation (#212): a mapped original is read ahead for this full copy.
    source.will_read_all();
    file.extend_from_slice(source);
    file.extend_from_slice(tail);
    Arc::new(file)
}

/// A managed private file mapped read-only (see [`read_private_input`]). The descriptor stays
/// open so [`PdfSource::appended`] can map the same inode again beneath an appended revision.
#[derive(Debug)]
struct ManagedMap {
    map: memmap2::Mmap,
    #[cfg_attr(not(any(target_os = "android", target_os = "linux")), allow(dead_code))]
    file: File,
}
impl Deref for ManagedMap {
    type Target = [u8];
    fn deref(&self) -> &[u8] {
        &self.map
    }
}
impl AsRef<[u8]> for ManagedMap {
    fn as_ref(&self) -> &[u8] {
        &self.map
    }
}
impl PdfSource for ManagedMap {
    fn capacity(&self) -> usize {
        self.map.len()
    }
    fn is_mapped(&self) -> bool {
        true
    }
    fn will_read_all(&self) {
        self.map.will_read_all();
    }
    fn appended(&self, tail: &[u8]) -> Option<PdfBytes> {
        #[cfg(any(target_os = "android", target_os = "linux"))]
        {
            splice::append(&self.file, self.map.len(), tail).map(|s| Arc::new(s) as PdfBytes)
        }
        #[cfg(not(any(target_os = "android", target_os = "linux")))]
        {
            let _ = tail;
            None
        }
    }
}

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

/// For previews such as file-list thumbnails: a managed private file (see
/// [`read_private_input`]) is mapped up to `cap` without copying it; any other input, or a
/// managed file that cannot be mapped, is read into memory only up to `owned_cap`. A preview of a
/// large working copy then neither fails nor allocates a second full copy beside an open viewer.
pub fn read_private_preview(path: &Path, root: Option<&Path>, cap: u64, owned_cap: u64) -> io::Result<PdfBytes> {
    read_private_bounded(path, root, cap, owned_cap, map_managed_inode)
}

fn read_private_with(path: &Path, root: Option<&Path>, cap: u64, map: impl FnOnce(&File) -> io::Result<memmap2::Mmap>) -> io::Result<PdfBytes> {
    read_private_bounded(path, root, cap, cap, map)
}

fn read_private_bounded(
    path: &Path,
    root: Option<&Path>,
    cap: u64,
    owned_cap: u64,
    map: impl FnOnce(&File) -> io::Result<memmap2::Mmap>,
) -> io::Result<PdfBytes> {
    let cap = cap.min(512 * 1024 * 1024);
    let owned_cap = owned_cap.min(cap);
    let file = File::open(path)?;
    let metadata = file.metadata()?;
    if metadata.is_file() && metadata.len() > cap {
        return Err(input_limit_error(cap));
    }
    if metadata.len() >= 1024 * 1024 && root.is_some_and(|root| managed_private_file(path, root, &metadata)) {
        if let Ok(map) = map(&file) {
            return Ok(Arc::new(ManagedMap { map, file }));
        }
    }
    if metadata.is_file() && metadata.len() > owned_cap {
        return Err(input_limit_error(owned_cap));
    }
    let cap = owned_cap;
    let mut bytes = Vec::new();
    if metadata.is_file() {
        bytes.try_reserve_exact(metadata.len() as usize + 1).map_err(|e| io::Error::new(io::ErrorKind::OutOfMemory, e))?;
    }
    file.take(cap + 1).read_to_end(&mut bytes)?;
    if bytes.len() as u64 > cap {
        return Err(input_limit_error(cap));
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
    // Only the published layouts produced by DocumentFiles qualify. Staged .part/.stage
    // files can still have a live writer and must always use an owned snapshot.
    let mut parts = relative.iter();
    let filename = match parts.next().and_then(|s| s.to_str()) {
        Some("Documents") => {
            let Some(source) = parts.next().and_then(|s| s.to_str()) else { return false };
            if source.len() != 24 || !source.bytes().all(|b| b.is_ascii_hexdigit()) { return false; }
            parts.next()
        }
        Some("Created") => parts.next(),
        _ => return false,
    };
    let Some(filename) = filename.and_then(|s| s.to_str()) else { return false };
    if parts.next().is_some() || filename.starts_with('.') ||
       !std::path::Path::new(filename).extension().and_then(|s| s.to_str()).is_some_and(|s| s.eq_ignore_ascii_case("pdf")) {
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

fn input_limit_error(cap: u64) -> io::Error {
    let limit = if cap.is_multiple_of(1024 * 1024) { format!("{} MiB", cap / (1024 * 1024)) } else { format!("{cap} bytes") };
    io::Error::new(io::ErrorKind::InvalidData, format!("input exceeds {limit}"))
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

/// The second audited unsafe boundary (#234): an incremental revision appended to a managed
/// mapped file without copying the file. One private, read-only region holds the file's own
/// pages (mapped again from the same descriptor) and, right after its last byte, the tail.
#[cfg(any(target_os = "android", target_os = "linux"))]
#[allow(unsafe_code)]
mod splice {
    use std::fs::File;
    use std::os::fd::AsRawFd;
    use std::ptr::NonNull;

    /// Read-only `prefix ++ tail`, unmapped on drop.
    pub(super) struct Spliced {
        ptr: NonNull<u8>,
        len: usize,
        reserved: usize,
    }
    // SAFETY: the region is read-only for its whole life and owned by this value alone.
    unsafe impl Send for Spliced {}
    unsafe impl Sync for Spliced {}
    impl std::panic::RefUnwindSafe for Spliced {}
    impl std::panic::UnwindSafe for Spliced {}

    impl std::ops::Deref for Spliced {
        type Target = [u8];
        fn deref(&self) -> &[u8] {
            // SAFETY: `ptr` addresses `reserved >= len` readable bytes until drop.
            unsafe { std::slice::from_raw_parts(self.ptr.as_ptr(), self.len) }
        }
    }
    impl AsRef<[u8]> for Spliced {
        fn as_ref(&self) -> &[u8] {
            self
        }
    }
    impl std::fmt::Debug for Spliced {
        fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            f.debug_struct("Spliced").field("len", &self.len).finish()
        }
    }
    impl super::PdfSource for Spliced {
        fn capacity(&self) -> usize {
            self.len
        }
        fn is_mapped(&self) -> bool {
            true
        }
    }
    impl Drop for Spliced {
        fn drop(&mut self) {
            // SAFETY: the region was mapped by `append` with exactly this base and length.
            unsafe { libc::munmap(self.ptr.as_ptr().cast(), self.reserved) };
        }
    }

    /// The `prefix` bytes of `file` followed by `tail`, or `None` when the region cannot be made
    /// (the caller then copies). `file` is the descriptor of a managed private file whose mapping
    /// holds `prefix` bytes; the stores replace such files by rename and never write them in
    /// place, so its bytes are still the mapped ones. A length that no longer matches is refused.
    pub(super) fn append(file: &File, prefix: usize, tail: &[u8]) -> Option<Spliced> {
        // SAFETY: sysconf has no preconditions.
        let page = usize::try_from(unsafe { libc::sysconf(libc::_SC_PAGESIZE) }).ok().filter(|p| p.is_power_of_two())?;
        if prefix == 0 || file.metadata().ok()?.len() != prefix as u64 {
            return None;
        }
        let round = |n: usize| n.checked_add(page - 1).map(|n| n & !(page - 1));
        let reserved = round(prefix.checked_add(tail.len())?)?;
        let file_span = round(prefix)?;
        // SAFETY: a fresh private anonymous region; nothing else refers to it.
        let base =
            unsafe { libc::mmap(std::ptr::null_mut(), reserved, libc::PROT_READ | libc::PROT_WRITE, libc::MAP_PRIVATE | libc::MAP_ANONYMOUS, -1, 0) };
        if base == libc::MAP_FAILED {
            return None;
        }
        let fail = |base: *mut libc::c_void| {
            // SAFETY: unmapping the region this call reserved.
            unsafe { libc::munmap(base, reserved) };
            None
        };
        // The file's pages replace the start of that region (MAP_FIXED only over memory this call
        // owns). Private, so the copy-on-write tail page never reaches the file; the bytes of its
        // last page past the end of the file read as zero until the tail covers them.
        // SAFETY: `base..base + file_span` lies inside the reservation; the descriptor is valid.
        let mapped =
            unsafe { libc::mmap(base, file_span, libc::PROT_READ | libc::PROT_WRITE, libc::MAP_PRIVATE | libc::MAP_FIXED, file.as_raw_fd(), 0) };
        if mapped != base {
            return fail(base);
        }
        // SAFETY: `prefix + tail.len() <= reserved`, and the region is writable and ours.
        unsafe { std::ptr::copy_nonoverlapping(tail.as_ptr(), base.cast::<u8>().add(prefix), tail.len()) };
        // SAFETY: the region this call mapped.
        if unsafe { libc::mprotect(base, reserved, libc::PROT_READ) } != 0 {
            return fail(base);
        }
        Some(Spliced { ptr: NonNull::new(base.cast())?, len: prefix + tail.len(), reserved })
    }
}

#[cfg(all(test, target_os = "linux"))]
mod tests {
    use super::*;
    use std::io::Write;
    use std::os::unix::fs::PermissionsExt;
    use std::sync::atomic::{AtomicUsize, Ordering};
    struct Fixture(std::path::PathBuf);
    impl Fixture {
        fn new() -> Self {
            static NEXT: AtomicUsize = AtomicUsize::new(0);
            let root = std::env::temp_dir().join(format!("pdfcraft-source-{}-{}", std::process::id(), NEXT.fetch_add(1, Ordering::Relaxed)));
            std::fs::create_dir_all(root.join("Documents/0123456789abcdef01234567")).unwrap();
            std::fs::set_permissions(&root, std::fs::Permissions::from_mode(0o700)).unwrap();
            Self(root)
        }
        fn path(&self, name: &str) -> std::path::PathBuf {
            match name.strip_prefix("Documents/") {
                Some(name) => self.0.join("Documents/0123456789abcdef01234567").join(name),
                None => self.0.join(name),
            }
        }
        fn write(&self, name: &str, bytes: &[u8]) -> std::path::PathBuf {
            let path = self.path(name);
            std::fs::create_dir_all(path.parent().unwrap()).unwrap();
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
        let link = fixture.path("Documents/link.pdf");
        std::os::unix::fs::symlink(&outside, &link).unwrap();
        assert!(!read_private_input(&link, Some(&fixture.0), 2 * 1024 * 1024).unwrap().is_mapped());
        std::fs::remove_file(link).unwrap();
        let path = fixture.write("Documents/input.pdf", &original);
        std::fs::hard_link(&path, fixture.0.join("alias.pdf")).unwrap();
        assert!(!read_private_input(&path, Some(&fixture.0), 2 * 1024 * 1024).unwrap().is_mapped());
    }

    #[test]
    fn unpublished_staging_files_remain_owned_during_in_place_writes() {
        let fixture = Fixture::new();
        let original = vec![37u8; 1024 * 1024];
        for name in ["Documents/.draft.pdf", "Documents/draft.pdf.part", "Created/.pending.pdf", "Created/source.pdf.part", "Created/staging/source.pdf"] {
            let path = fixture.write(name, &original);
            let source = read_private_input(&path, Some(&fixture.0), 2 * 1024 * 1024).unwrap();
            assert!(!source.is_mapped(), "{name}");
            std::fs::write(path, b"writer is still active").unwrap();
            assert_eq!(source.as_slice(), original);
        }
        let created = fixture.write("Created/published.PDF", &original);
        assert!(read_private_input(&created, Some(&fixture.0), 2 * 1024 * 1024).unwrap().is_mapped());
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

    /// A file-list preview maps a managed working copy beyond the preview copy limit, and still
    /// refuses to copy any other file (or an unmappable managed one) beyond it.
    #[test]
    fn previews_map_large_working_copies_and_keep_the_copy_limit_elsewhere() {
        let fixture = Fixture::new();
        let original = vec![53u8; 3 * 1024 * 1024];
        let path = fixture.write("Documents/large.pdf", &original);
        let preview = read_private_preview(&path, Some(&fixture.0), 8 * 1024 * 1024, 2 * 1024 * 1024).unwrap();
        assert!(preview.is_mapped());
        assert_eq!(preview.as_slice(), original);
        assert!(read_private_preview(&path, None, 8 * 1024 * 1024, 2 * 1024 * 1024).is_err(), "no store root: copy limit");
        let outside = fixture.write("outside.pdf", &original);
        assert!(read_private_preview(&outside, Some(&fixture.0), 8 * 1024 * 1024, 2 * 1024 * 1024).is_err(), "outside the store");
        assert!(read_private_preview(&path, Some(&fixture.0), 1024 * 1024, 2 * 1024 * 1024).is_err(), "over the overall cap");
        let unmappable = read_private_bounded(&path, Some(&fixture.0), 8 * 1024 * 1024, 2 * 1024 * 1024, |_| {
            Err(io::Error::new(io::ErrorKind::Unsupported, "mapping unavailable"))
        });
        assert!(unmappable.is_err(), "a failed mapping keeps the copy limit");
        let small = fixture.write("small.pdf", &vec![1u8; 1024]);
        assert!(!read_private_preview(&small, None, 8 * 1024 * 1024, 2 * 1024 * 1024).unwrap().is_mapped());
    }

    /// #234: a managed mapped file joined with a revision reads exactly as the copy did, at
    /// every alignment of the file's end and the tail's, and the spliced source outlives the
    /// file's replacement and unlinking.
    #[test]
    fn joining_a_managed_mapping_matches_the_copy_and_survives_replacement() {
        let fixture = Fixture::new();
        let page = 4096;
        for (len, tail_len) in
            [(1024 * 1024, 1), (1024 * 1024, page), (1024 * 1024 + 1, page - 1), (1024 * 1024 + 7, 3 * page + 5), (1024 * 1024 + page - 1, 2)]
        {
            let original: Vec<u8> = (0..len).map(|i| (i * 31 % 251) as u8).collect();
            let tail: Vec<u8> = (0..tail_len).map(|i| (i * 17 % 253) as u8 | 1).collect();
            let path = fixture.write("Documents/joined.pdf", &original);
            let source = read_private_input(&path, Some(&fixture.0), 4 * 1024 * 1024).unwrap();
            assert!(source.is_mapped());
            let spliced = source.appended(&tail).expect("a managed mapping is extended");
            let copied = joined(&(Arc::new(original.clone()) as PdfBytes), &tail);
            assert!(!copied.is_mapped());
            assert_eq!(spliced.as_slice(), copied.as_slice(), "len {len} tail {tail_len}");
            assert_eq!(joined(&source, &tail).as_slice(), copied.as_slice());
            assert!(same_storage(&joined(&source, &[]), &source), "an empty tail keeps the source");
            assert_eq!(source.as_slice(), original, "the file mapping is not written");
            let staged = fixture.write("Documents/staged.pdf", b"new revision");
            std::fs::rename(staged, &path).unwrap();
            std::fs::remove_file(&path).unwrap();
            drop(source);
            assert_eq!(spliced.as_slice(), copied.as_slice());
        }
    }

    /// A descriptor whose length no longer matches its mapping is not spliced; owned and
    /// anonymous sources are copied.
    #[test]
    fn joining_other_sources_copies_them() {
        let fixture = Fixture::new();
        let original = vec![5u8; 1024 * 1024];
        let path = fixture.write("Documents/grown.pdf", &original);
        let file = File::open(&path).unwrap();
        let map = map_managed_inode(&file).unwrap();
        std::fs::OpenOptions::new().append(true).open(&path).unwrap().write_all(b"x").unwrap();
        let managed: PdfBytes = Arc::new(ManagedMap { map, file });
        assert!(managed.appended(b"tail").is_none());
        let joined_bytes = joined(&managed, b"tail");
        assert!(!joined_bytes.is_mapped());
        assert_eq!(&joined_bytes[original.len()..], b"tail");
        let anonymous = memory_snapshot(b"%PDF-1.7").unwrap();
        assert!(anonymous.appended(b"x").is_none());
        assert_eq!(joined(&anonymous, b"\n%%EOF").as_slice(), b"%PDF-1.7\n%%EOF");
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
