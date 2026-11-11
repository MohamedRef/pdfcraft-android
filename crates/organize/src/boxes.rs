//! Page boxes (ISO 32000-2 §14.11.2): Acrobat's Set Page Boxes and Crop, execution plan M4.4.
//!
//! The crop, bleed, trim and art boxes default to their "parent" box (crop → media; the others
//! → crop) and are clipped to the media box. Margins are measured inward from the media box in
//! default user space (unrotated), as Acrobat's dialog does.

use pdfcraft_cos::{Document, Object};

use crate::{OrganizeError, check, walk};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PageBox {
    Media,
    Crop,
    Bleed,
    Trim,
    Art,
}

impl PageBox {
    pub fn key(self) -> &'static [u8] {
        match self {
            PageBox::Media => b"MediaBox",
            PageBox::Crop => b"CropBox",
            PageBox::Bleed => b"BleedBox",
            PageBox::Trim => b"TrimBox",
            PageBox::Art => b"ArtBox",
        }
    }

    pub fn from_name(s: &str) -> Option<Self> {
        let lower = s.trim().to_ascii_lowercase();
        match lower.strip_suffix("box").unwrap_or(&lower) {
            "media" => Some(Self::Media),
            "crop" => Some(Self::Crop),
            "bleed" => Some(Self::Bleed),
            "trim" => Some(Self::Trim),
            "art" => Some(Self::Art),
            _ => None,
        }
    }
}

/// What to set a box to.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum BoxSpec {
    /// Back to its default (the parent box).
    Remove,
    /// An absolute rectangle in default user space.
    Rect([f64; 4]),
    /// Margins inward from the media box: left, bottom, right, top (points).
    Margins([f64; 4]),
    /// Media box only: grow it outward by left, bottom, right, top (points; negative shrinks it
    /// back), for a note margin. Crop box edges lying on the media box move with it, so the new
    /// area shows, and the running total is kept on the page as [`MARGIN_KEY`] so taking the
    /// margin off restores the original box.
    Grow([f64; 4]),
}

/// The page key holding the note margin added with [`BoxSpec::Grow`] (left, bottom, right, top
/// in default user space). A private key, ignored by other readers.
pub const MARGIN_KEY: &[u8] = b"PdfCraftMargin";

/// The note margin recorded on each page (zeros where none).
pub fn page_margins(doc: &Document) -> Result<Vec<[f64; 4]>, OrganizeError> {
    Ok(walk(doc)?.iter().map(|(page, _)| margin_of(doc, doc.get(*page).as_dict().and_then(|d| d.get(MARGIN_KEY)))).collect())
}

fn margin_of(doc: &Document, o: Option<&Object>) -> [f64; 4] {
    let mut out = [0.0; 4];
    let Some(o) = o else { return out };
    let o = doc.resolve(o);
    let v: Vec<f64> = o.as_array().map(|a| a.iter().map(|x| doc.resolve(x).as_f64().filter(|v| v.is_finite()).unwrap_or(0.0)).collect()).unwrap_or_default();
    if v.len() == 4 {
        out.copy_from_slice(&v);
    }
    out
}

/// [`BoxSpec::Grow`] on `pages`: validated for every page before any changes.
fn grow_pages(doc: &mut Document, pages: &[usize], g: [f64; 4]) -> Result<(), OrganizeError> {
    let bad = |m: &str| OrganizeError::InvalidBox(m.to_string());
    if !g.iter().all(|x| x.is_finite()) {
        return Err(bad("the margin is not a number"));
    }
    let all = walk(doc)?;
    check(pages, all.len())?;
    let boxes = page_boxes(doc)?;
    let on = |a: f64, b: f64| (a - b).abs() < 1e-6;
    let mut plan = Vec::new();
    for &i in pages {
        let (page, inherited) = &all[i];
        let [media, crop, ..] = boxes[i];
        let d = doc.get(*page).as_dict().cloned().unwrap_or_default();
        let grown = [media[0] - g[0], media[1] - g[1], media[2] + g[2], media[3] + g[3]];
        let (w, h) = (grown[2] - grown[0], grown[3] - grown[1]);
        if !w.is_finite() || !h.is_finite() || w < 1.0 || h < 1.0 {
            return Err(bad(&format!("page {} would be smaller than a point", i + 1)));
        }
        if w > 14_400.0 || h > 14_400.0 {
            return Err(bad(&format!("page {} would be larger than 14400 points", i + 1)));
        }
        // a crop box (the page's own or inherited) follows the media box on the sides it touches
        let has_crop = d.get(b"CropBox").or_else(|| inherited.get(b"CropBox")).is_some();
        let new_crop = has_crop.then(|| {
            let c = [
                if on(crop[0], media[0]) { grown[0] } else { crop[0] },
                if on(crop[1], media[1]) { grown[1] } else { crop[1] },
                if on(crop[2], media[2]) { grown[2] } else { crop[2] },
                if on(crop[3], media[3]) { grown[3] } else { crop[3] },
            ];
            clip(c, grown).unwrap_or(grown)
        });
        let before = margin_of(doc, d.get(MARGIN_KEY));
        let total = [before[0] + g[0], before[1] + g[1], before[2] + g[2], before[3] + g[3]];
        let total = total.iter().any(|v| v.abs() > 1e-6).then_some(total.map(|v| if v.abs() > 1e-6 { v } else { 0.0 }));
        plan.push((*page, grown, new_crop, total));
    }
    let real = |r: &[f64; 4]| Object::Array(r.iter().map(|v| Object::Real(*v)).collect());
    for (page, grown, crop, total) in plan {
        doc.update_dict(page, |d| {
            d.set(b"MediaBox".to_vec(), real(&grown));
            if let Some(c) = crop {
                d.set(b"CropBox".to_vec(), real(&c));
            }
            match total {
                Some(t) => d.set(MARGIN_KEY.to_vec(), real(&t)),
                None => {
                    d.remove(MARGIN_KEY);
                }
            }
        })?;
    }
    Ok(())
}

/// Parses a 4-number PDF rectangle `[x0, y0, x1, y1]` into `[xmin, ymin, xmax, ymax]` when it has
/// finite coordinates and positive width and height.
fn rect_of(o: Option<&Object>, doc: &Document) -> Option<[f64; 4]> {
    let o = doc.resolve(o?);
    let v: Vec<f64> = o.as_array()?.iter().filter_map(|x| doc.resolve(x).as_f64()).collect();
    if v.len() != 4 || !v.iter().all(|x| x.is_finite()) {
        return None;
    }
    let r = [v[0].min(v[2]), v[1].min(v[3]), v[0].max(v[2]), v[1].max(v[3])];
    let (w, h) = (r[2] - r[0], r[3] - r[1]);
    (w > 0.0 && h > 0.0 && w.is_finite() && h.is_finite()).then_some(r)
}

/// Returns the coordinate-wise intersection of two normalized `[xmin, ymin, xmax, ymax]` rectangles.
fn intersect(a: [f64; 4], b: [f64; 4]) -> [f64; 4] {
    [a[0].max(b[0]), a[1].max(b[1]), a[2].min(b[2]), a[3].min(b[3])]
}

/// Clips rectangle `a` to `b`, returning `None` when their intersection has no positive area.
fn clip(a: [f64; 4], b: [f64; 4]) -> Option<[f64; 4]> {
    let r = intersect(a, b);
    (r[2] > r[0] && r[3] > r[1]).then_some(r)
}

/// The effective boxes of every page: media, crop, bleed, trim, art.
pub fn page_boxes(doc: &Document) -> Result<Vec<[[f64; 4]; 5]>, OrganizeError> {
    Ok(walk(doc)?
        .iter()
        .map(|(page, inherited)| {
            let obj = doc.get(*page);
            let d = obj.as_dict().cloned().unwrap_or_default();
            let own = |k: &[u8]| rect_of(d.get(k).or_else(|| inherited.get(k)), doc);
            let media = own(b"MediaBox").unwrap_or([0.0, 0.0, 612.0, 792.0]);
            let crop = own(b"CropBox").and_then(|c| clip(c, media)).unwrap_or(media);
            let child = |k: &[u8]| rect_of(d.get(k), doc).and_then(|c| clip(c, media)).unwrap_or(crop);
            [media, crop, child(b"BleedBox"), child(b"TrimBox"), child(b"ArtBox")]
        })
        .collect())
}

/// Set a box on `pages` (0-based). Margins apply to each page's own media box.
pub fn set_page_box(doc: &mut Document, pages: &[usize], which: PageBox, spec: BoxSpec) -> Result<(), OrganizeError> {
    if let BoxSpec::Grow(g) = spec {
        if which != PageBox::Media {
            return Err(OrganizeError::InvalidBox("only the media box grows".to_string()));
        }
        return grow_pages(doc, pages, g);
    }
    let all = walk(doc)?;
    check(pages, all.len())?;
    let boxes = page_boxes(doc)?;
    let bad = |m: &str| OrganizeError::InvalidBox(m.to_string());
    if which == PageBox::Media && spec == BoxSpec::Remove {
        return Err(bad("the media box can't be removed"));
    }
    // Validate every page first so a failure changes nothing.
    let mut plan = Vec::new();
    for &i in pages {
        let media = boxes[i][0];
        let rect = match spec {
            BoxSpec::Remove => None,
            BoxSpec::Rect(r) => {
                if !r.iter().all(|x| x.is_finite()) {
                    return Err(bad("the rectangle is not a number"));
                }
                let r = [r[0].min(r[2]), r[1].min(r[3]), r[0].max(r[2]), r[1].max(r[3])];
                Some(if which == PageBox::Media { r } else { intersect(r, media) })
            }
            BoxSpec::Grow(_) => return Err(bad("only the media box grows")),
            BoxSpec::Margins(m) => {
                if !m.iter().all(|x| x.is_finite() && *x >= 0.0) {
                    return Err(bad("margins must be zero or more"));
                }
                Some([media[0] + m[0], media[1] + m[1], media[2] - m[2], media[3] - m[3]])
            }
        };
        if let Some(r) = rect {
            let (w, h) = (r[2] - r[0], r[3] - r[1]);
            if !w.is_finite() || !h.is_finite() {
                return Err(bad("the rectangle is not a number"));
            }
            if w < 1.0 || h < 1.0 {
                return Err(bad(&format!("page {} would be smaller than a point", i + 1)));
            }
        }
        plan.push((i, all[i].0, rect, media));
    }
    for &(_, page, rect, _) in &plan {
        doc.update_dict(page, |d| match rect {
            Some(r) => d.set(which.key().to_vec(), Object::Array(r.iter().map(|v| Object::Real(*v)).collect())),
            None => {
                d.remove(which.key());
            }
        })?;
    }
    if spec == BoxSpec::Remove && which == PageBox::Crop {
        // An inherited crop box from an ancestor `/Pages` node would come back after removal:
        // pin the page's media box explicitly only when an ancestor still supplies a valid CropBox.
        let after = walk(doc)?;
        for &(i, page, _, media) in &plan {
            if after.get(i).and_then(|(_, inh)| rect_of(inh.get(b"CropBox"), doc)).is_some() {
                doc.update_dict(page, |d| d.set(b"CropBox".to_vec(), Object::Array(media.iter().map(|v| Object::Real(*v)).collect())))?;
            }
        }
    }
    Ok(())
}
