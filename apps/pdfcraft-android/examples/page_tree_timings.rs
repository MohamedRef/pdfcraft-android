//! Manual host benchmark, not a device test (#235): the reading stage's page-tree walk
//! (`pdfcraft_annot::page_refs`, and `page_refs_skimming` that the reading stage uses since #235)
//! on synthetic PDFs drawn here, never a real document. Each round opens a fresh document.
//!
//! cargo run --release -p pdfcraft-android --example page_tree_timings -- [pages]
//!
//! Three files of the same shape (a two-level page tree, each page with its own resources,
//! 32 link annotations and a content stream): plain objects, the same with every page in an
//! object stream, the same again with the resources inline in each page dictionary, and a
//! plain file whose pages carry their 32 links inline.
use std::fmt::Write as _;
use std::time::Instant;

use pdfcraft_cos::{Document, SaveOptions, owned_bytes, write_full};

fn fixture(pages: usize, inline_resources: bool) -> Vec<u8> {
    fixture_with(pages, inline_resources, false)
}

fn fixture_with(pages: usize, inline_resources: bool, inline_annots: bool) -> Vec<u8> {
    let mut objects: Vec<String> = Vec::new();
    // 1 catalog, 2 root pages, 3 font, 4 shared content, then the intermediate nodes and pages
    let per_node = 25;
    let nodes = pages.div_ceil(per_node);
    let first_node = 5;
    let first_page = first_node + nodes;
    let first_annot = first_page + pages;
    let annots_per_page = 32;
    objects.push("<< /Type /Catalog /Pages 2 0 R >>".into());
    let mut kids = String::new();
    for n in 0..nodes {
        let _ = write!(kids, "{} 0 R ", first_node + n);
    }
    objects.push(format!("<< /Type /Pages /Count {pages} /Kids [{kids}] /MediaBox [0 0 612 792] >>"));
    objects.push("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".into());
    objects.push("<< /Length 44 >>\nstream\nBT /F1 12 Tf 72 720 Td (Synthetic page) Tj ET\nendstream".into());
    for n in 0..nodes {
        let from = n * per_node;
        let to = (from + per_node).min(pages);
        let mut kids = String::new();
        for p in from..to {
            let _ = write!(kids, "{} 0 R ", first_page + p);
        }
        objects.push(format!("<< /Type /Pages /Parent 2 0 R /Count {} /Kids [{kids}] >>", to - from));
    }
    for p in 0..pages {
        let node = first_node + p / per_node;
        let mut annots = String::new();
        for a in 0..annots_per_page {
            if inline_annots {
                let y = 700 - a * 20;
                let _ = write!(annots, "<< /Type /Annot /Subtype /Link /Rect [72 {y} 300 {}] /Border [0 0 0] /A << /S /URI /URI (https://example.invalid/{p}/{a}) >> >> ", y + 12);
            } else {
                let _ = write!(annots, "{} 0 R ", first_annot + p * annots_per_page + a);
            }
        }
        let resources = if inline_resources {
            let mut fonts = String::new();
            for f in 0..12 {
                let _ = write!(fonts, "/F{f} 3 0 R ");
            }
            format!("<< /Font << {fonts}>> /ProcSet [/PDF /Text /ImageB /ImageC] /ExtGState << /G0 << /CA 1 /ca 1 /BM /Normal >> >> >>")
        } else {
            "<< /Font << /F1 3 0 R >> >>".into()
        };
        objects.push(format!(
            "<< /Type /Page /Parent {node} 0 R /MediaBox [0 0 612 792] /CropBox [0 0 612 792] /Rotate 0 /Resources {resources} /Contents 4 0 R /Annots [{annots}] /StructParents {p} >>"
        ));
    }
    for p in 0..pages {
        for a in 0..annots_per_page {
            let y = 700 - a * 20;
            objects.push(format!(
                "<< /Type /Annot /Subtype /Link /Rect [72 {y} 300 {}] /Border [0 0 0] /A << /S /URI /URI (https://example.invalid/{p}/{a}) >> >>",
                y + 12
            ));
        }
    }
    let mut out = b"%PDF-1.7\n".to_vec();
    let mut offsets = Vec::with_capacity(objects.len());
    for (i, o) in objects.iter().enumerate() {
        offsets.push(out.len());
        out.extend_from_slice(format!("{} 0 obj\n{o}\nendobj\n", i + 1).as_bytes());
    }
    let xref = out.len();
    let mut table = format!("xref\n0 {}\n0000000000 65535 f \n", objects.len() + 1);
    for off in offsets {
        let _ = writeln!(table, "{off:010} 00000 n ");
    }
    let _ = write!(table, "trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n", objects.len() + 1);
    out.extend_from_slice(table.as_bytes());
    out
}

fn time(name: &str, bytes: &[u8]) -> Result<(), Box<dyn std::error::Error>> {
    let mut walks = Vec::new();
    let mut skims = Vec::new();
    let mut count = 0;
    for round in 0..8 {
        // a fresh document each time, as each open makes one
        let doc = Document::open(owned_bytes(bytes.to_vec()))?;
        let started = Instant::now();
        let refs = pdfcraft_annot::page_refs(&doc)?;
        let ms = started.elapsed().as_secs_f64() * 1000.0;
        count = refs.len();
        let doc = Document::open(owned_bytes(bytes.to_vec()))?;
        let started = Instant::now();
        let skimmed = pdfcraft_annot::page_refs_skimming(&doc)?;
        let skim_ms = started.elapsed().as_secs_f64() * 1000.0;
        assert_eq!(skimmed, refs, "the skimming walk must find the same pages");
        if round > 0 {
            walks.push(ms);
            skims.push(skim_ms);
        }
    }
    walks.sort_by(f64::total_cmp);
    skims.sort_by(f64::total_cmp);
    println!(
        "{name}: bytes={} pages={count} page_refs_ms min={:.2} median={:.2} max={:.2} page_refs_skimming_ms min={:.2} median={:.2} max={:.2}",
        bytes.len(),
        walks[0],
        walks[walks.len() / 2],
        walks[walks.len() - 1],
        skims[0],
        skims[skims.len() / 2],
        skims[skims.len() - 1]
    );
    Ok(())
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let pages = std::env::args().nth(1).map(|s| s.parse::<usize>()).transpose()?.unwrap_or(869);
    let plain = fixture(pages, false);
    time("plain", &plain)?;
    let doc = Document::open(owned_bytes(plain.clone()))?;
    let packed = write_full(&doc, &SaveOptions { object_streams: true, ..SaveOptions::default() })?;
    time("object_streams", &packed)?;
    let inline = fixture(pages, true);
    let doc = Document::open(owned_bytes(inline))?;
    let packed_inline = write_full(&doc, &SaveOptions { object_streams: true, ..SaveOptions::default() })?;
    time("object_streams_inline_resources", &packed_inline)?;
    let heavy = fixture_with(pages, true, true);
    time("inline_annotations", &heavy)?;
    Ok(())
}
