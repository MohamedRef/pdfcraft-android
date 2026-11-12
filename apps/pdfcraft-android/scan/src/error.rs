//! What can go wrong while turning a photo into a page, as sentences a person can act on.

/// A scan step that failed.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum ScanError {
    /// The file is not a JPEG or PNG picture, or it is damaged.
    #[error("the photo could not be read")]
    Unreadable,
    /// The photo has more pixels than a page is ever made from.
    #[error("the photo is too large ({0} megapixels; at most 64)")]
    TooLarge(u64),
    /// The four corners don't enclose a page.
    #[error("the four corners must enclose an area (a convex quadrilateral inside the photo)")]
    BadCorners,
    /// A camera frame that cannot be looked at (#295): its size, its bytes or its turn.
    #[error("the camera frame is not usable: {0}")]
    BadFrame(&'static str),
    /// A burst to merge has too few or too many photos (#297).
    #[error("a merge takes 2 to 4 photos of the page")]
    FrameCount,
    /// The page could not be written as a picture.
    #[error("the page could not be saved as a picture")]
    Encode,
}
