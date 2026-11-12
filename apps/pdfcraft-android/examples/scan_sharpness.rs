//! Scan (#287): how sharp a saved scan page is through the old path (photo → PDF → render → warp
//! → JPEG 90) and the new one (`scan_process_page`: decode → straighten → look → JPEG 92, once).
//!
//! A text page is drawn by the engine, photographed synthetically at five tilts into a
//! 3000 × 4000 JPEG, and saved both ways with the same look. Sharpness is the mean of the top 10%
//! absolute Laplacian of the saved page, read back at one image pixel per pixel. Prints only
//! numbers. Run: `cargo run --release -p pdfcraft-android --example scan_sharpness [look]`
//! (look: original, colour, greyscale, bw; default colour).
use pdfcraft_android::MobileSession;
use pdfcraft_android::scan::{ScanLook, ScanPageRequest, scan_process_page, scan_set_root};
use pdfcraft_geom::perspective::Homography;
use serde_json::{Value, json};

const LINES: &str = "The quick brown fox jumps over the lazy dog 0123456789.\n";

fn run(s: &MobileSession, tool: &str, args: Value) -> Result<Value, Box<dyn std::error::Error>> {
    Ok(serde_json::from_str(&s.execute(tool.into(), args.to_string())?)?)
}

/// Grey Laplacian sharpness: the mean of the top 10% absolute 4-neighbour Laplacian.
fn sharpness(rgba: &[u8], w: usize, h: usize) -> f64 {
    let g = |x: usize, y: usize| {
        let i = (y * w + x) * 4;
        0.299 * f64::from(rgba[i]) + 0.587 * f64::from(rgba[i + 1]) + 0.114 * f64::from(rgba[i + 2])
    };
    let mut v = Vec::with_capacity(w * h);
    for y in 1..h - 1 {
        for x in 1..w - 1 {
            v.push((4.0 * g(x, y) - g(x - 1, y) - g(x + 1, y) - g(x, y - 1) - g(x, y + 1)).abs());
        }
    }
    v.sort_by(|a, b| b.total_cmp(a));
    let n = (v.len() / 10).max(1);
    v[..n].iter().sum::<f64>() / n as f64
}

/// The size and sharpness of a saved page image file.
fn file_sharpness(path: &std::path::Path) -> Result<(u32, u32, f64), Box<dyn std::error::Error>> {
    let img = image::open(path)?.into_rgba8();
    let (w, h) = img.dimensions();
    Ok((w, h, sharpness(img.as_raw(), w as usize, h as usize)))
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let look = std::env::args().nth(1).unwrap_or_else(|| "colour".into());
    let (mode, scan_look) = match look.as_str() {
        "original" => ("original", ScanLook::Original),
        "greyscale" => ("greyscale", ScanLook::Greyscale),
        "bw" => ("bw", ScanLook::BlackWhite),
        _ => ("colour", ScanLook::Colour),
    };
    let level = if mode == "bw" { 60 } else { 50 };
    let dir = std::env::temp_dir().join(format!("scan-sharpness-{}", std::process::id()));
    std::fs::create_dir_all(&dir)?;
    let dir = dir.canonicalize()?;
    scan_set_root(dir.to_string_lossy().into_owned());
    let s = MobileSession::new();

    // The page: text drawn by the engine, read at 200 dpi.
    let text = LINES.repeat(40);
    let created = run(&s, "doc_create", json!({ "from": "text", "text": text }))?;
    let id = created["doc"].as_u64().ok_or("no doc")?;
    let page = s.render_page(id, 0, 200.0 / 72.0, None)?;
    run(&s, "doc_close", json!({ "doc": id, "discard_changes": true }))?;
    let (pw, ph) = (page.width as usize, page.height as usize);
    let at = |x: i64, y: i64| {
        let i = (y.clamp(0, ph as i64 - 1) as usize * pw + x.clamp(0, pw as i64 - 1) as usize) * 4;
        [f64::from(page.rgba[i]), f64::from(page.rgba[i + 1]), f64::from(page.rgba[i + 2])]
    };

    let tilts: [[[f64; 2]; 4]; 5] = [
        [[300.0, 250.0], [2700.0, 250.0], [2700.0, 3750.0], [300.0, 3750.0]],
        [[400.0, 300.0], [2650.0, 450.0], [2800.0, 3700.0], [250.0, 3550.0]],
        [[600.0, 500.0], [2400.0, 500.0], [2850.0, 3800.0], [150.0, 3800.0]],
        [[200.0, 600.0], [2600.0, 200.0], [2900.0, 3500.0], [500.0, 3900.0]],
        [[700.0, 900.0], [2300.0, 800.0], [2500.0, 3200.0], [600.0, 3300.0]],
    ];
    println!("look={mode} level={level}");
    println!("photo  old_px          old_sharp  new_px          new_sharp  old_ms  new_ms  passthrough");
    for (n, quad) in tilts.iter().enumerate() {
        let (w, h) = (3000u32, 4000u32);
        let to_page = Homography::from_points(*quad, [[0.0, 0.0], [pw as f64, 0.0], [pw as f64, ph as f64], [0.0, ph as f64]]).ok_or("bad tilt")?;
        let photo = image::RgbImage::from_fn(w, h, |x, y| {
            let mut sum = [0.0; 3];
            for (dx, dy) in [(0.25, 0.25), (0.75, 0.25), (0.25, 0.75), (0.75, 0.75)] {
                let [sx, sy] = to_page.apply([f64::from(x) + dx, f64::from(y) + dy]).unwrap_or([-1.0, -1.0]);
                let c = if sx < 0.0 || sy < 0.0 || sx >= pw as f64 || sy >= ph as f64 {
                    [80.0, 75.0, 70.0]
                } else {
                    let (fx, fy) = (sx - 0.5, sy - 0.5);
                    let (x0, y0) = (fx.floor(), fy.floor());
                    let (ax, ay) = (fx - x0, fy - y0);
                    let (x0, y0) = (x0 as i64, y0 as i64);
                    let (a, b, c, d) = (at(x0, y0), at(x0 + 1, y0), at(x0, y0 + 1), at(x0 + 1, y0 + 1));
                    // Paper under a room light: a little darker and warmer than white.
                    std::array::from_fn(|k| {
                        ((a[k] * (1.0 - ax) + b[k] * ax) * (1.0 - ay) + (c[k] * (1.0 - ax) + d[k] * ax) * ay) * [0.86, 0.84, 0.78][k]
                    })
                };
                for k in 0..3 {
                    sum[k] += c[k] / 4.0;
                }
            }
            image::Rgb(sum.map(|v| v.round().clamp(0.0, 255.0) as u8))
        });
        let shot = dir.join(format!("shot-{n}.jpg"));
        let mut jpeg = Vec::new();
        image::codecs::jpeg::JpegEncoder::new_with_quality(&mut jpeg, 95).encode_image(&photo)?;
        std::fs::write(&shot, &jpeg)?;
        let shares: Vec<f32> = quad.iter().flat_map(|[x, y]| [(*x / f64::from(w)) as f32, (*y / f64::from(h)) as f32]).collect();

        // Old: the photo in a PDF, then Enhance ▸ Camera image on that page.
        let started = std::time::Instant::now();
        let old = run(&s, "doc_create", json!({ "from": "images", "paths": [shot], "name": "Scan" }))?;
        let oid = old["doc"].as_u64().ok_or("no doc")?;
        let info = run(&s, "doc_info", json!({ "doc": oid }))?;
        let (iw, ih) = (info["pages"][0]["width"].as_f64().unwrap_or(1.0), info["pages"][0]["height"].as_f64().unwrap_or(1.0));
        let corners: Vec<f64> = shares.iter().enumerate().map(|(k, v)| f64::from(*v) * if k % 2 == 0 { iw } else { ih }).collect();
        run(&s, "page_enhance_camera", json!({ "doc": oid, "page": 1, "corners": corners, "mode": mode, "level": level }))?;
        let old_pdf = dir.join(format!("old-{n}.pdf"));
        run(&s, "doc_save", json!({ "doc": oid, "path": old_pdf, "full": true }))?;
        let old_ms = started.elapsed().as_millis();
        // The page's picture exactly as the PDF holds it (a JPEG is written unchanged).
        let old_img = dir.join(format!("old-{n}.jpg"));
        run(&s, "image_save", json!({ "doc": oid, "page": 1, "image": 1, "path": old_img }))?;
        run(&s, "doc_close", json!({ "doc": oid, "discard_changes": true }))?;
        let (ow, oh, old_sharp) = file_sharpness(&old_img)?;

        // New: one pass, then the page image in a PDF at its own resolution.
        let started = std::time::Instant::now();
        let r = scan_process_page(ScanPageRequest {
            photo_path: shot.to_string_lossy().into_owned(),
            corners: shares,
            look: scan_look,
            strength: level,
            output_path: dir.join(format!("page-{n}")).to_string_lossy().into_owned(),
            focal_35mm: None,
            snap_paper: false,
            flatten: false,
            flatten_check: false,
            line_flatten: false,
            side_proportions: false,
            upright: false,
            turns: 0,
            sharpen: 30,
            denoise: true,
            preset: "off".into(),
            curved_sides: 0,
            fixed_strength: false,
            paper: "auto".into(),
        })?;
        let new = run(&s, "doc_create", json!({ "from": "images", "paths": [r.path], "dpi": r.dpi }))?;
        let nid = new["doc"].as_u64().ok_or("no doc")?;
        let new_pdf = dir.join(format!("new-{n}.pdf"));
        run(&s, "doc_save", json!({ "doc": nid, "path": new_pdf, "full": true }))?;
        let new_ms = started.elapsed().as_millis();
        let new_img = dir.join(format!("new-{n}.jpg"));
        run(&s, "image_save", json!({ "doc": nid, "page": 1, "image": 1, "path": new_img }))?;
        run(&s, "doc_close", json!({ "doc": nid, "discard_changes": true }))?;
        // The PDF holds the page image's own bytes (JPEG passthrough).
        let same = std::fs::read(&new_img)? == std::fs::read(&r.path)?;
        let (nw, nh, new_sharp) = file_sharpness(&new_img)?;
        println!("{n}      {ow:>5}×{oh:<5}     {old_sharp:>8.2}  {nw:>5}×{nh:<5}     {new_sharp:>8.2}  {old_ms:>6}  {new_ms:>6}  {same}");
    }
    std::fs::remove_dir_all(&dir)?;
    Ok(())
}
