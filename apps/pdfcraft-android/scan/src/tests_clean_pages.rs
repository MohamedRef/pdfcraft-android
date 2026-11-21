//! The drawn pages of #784 (`clean_pages`): the same seed gives the same bytes, and each group
//! holds the marks it is there to show.

use crate::clean_pages::{A4_SIZE, Group, RECEIPT_SIZE, generate, mark};

fn share(marks: &[u8], tag: u8) -> f64 {
    marks.iter().filter(|&&t| t == tag).count() as f64 / marks.len() as f64
}

#[test]
fn the_same_seed_gives_the_same_page_and_another_seed_another() {
    for group in [Group::Receipt, Group::Colour] {
        let (a, b) = (generate(group, 3), generate(group, 3));
        assert_eq!(a.jpeg, b.jpeg, "{group:?}");
        assert_eq!(a.marks, b.marks, "{group:?}");
        assert_eq!(a.clean, b.clean, "{group:?}");
        assert_ne!(a.marks, generate(group, 4).marks, "{group:?}");
    }
    for group in Group::ALL {
        assert_eq!(Group::from_name(group.name()), Some(group));
    }
    assert_eq!(Group::from_name("moon"), None);
}

#[test]
fn every_group_holds_the_marks_it_is_there_to_show() {
    let has = |group: Group, tags: &[u8]| {
        let page = generate(group, 1);
        let size = if group == Group::Receipt { RECEIPT_SIZE } else { A4_SIZE };
        assert_eq!((page.width, page.height), size, "{group:?}");
        assert_eq!(page.marks.len(), (page.width * page.height) as usize);
        assert_eq!(page.clean.len(), page.marks.len() * 3);
        // print everywhere: body, thin, dots, and print near every edge
        for tag in [mark::INK, mark::THIN, mark::DOT, mark::EDGE].iter().chain(tags) {
            assert!(share(&page.marks, *tag) > 0.0002, "{group:?} has too little of mark {tag}: {}", share(&page.marks, *tag));
        }
        assert!(share(&page.marks, mark::PAPER) > 0.5, "{group:?}");
        page
    };
    has(Group::Text, &[]);
    has(Group::Receipt, &[mark::FAINT, mark::STAMP]);
    has(Group::Pencil, &[mark::PENCIL, mark::FAINT]);
    has(Group::Colour, &[mark::PEN, mark::HIGHLIGHT, mark::HIGHLIGHT_INK, mark::STAMP, mark::STAMP_PALE]);
    let photo = has(Group::Photo, &[mark::PHOTO, mark::PHOTO_DARK, mark::PHOTO_LIGHT]);
    // the photographs are a good part of the page, and not most of it
    let photos = share(&photo.marks, mark::PHOTO) + share(&photo.marks, mark::PHOTO_DARK) + share(&photo.marks, mark::PHOTO_LIGHT);
    assert!((0.10..0.25).contains(&photos), "{photos}");
}

#[test]
fn print_near_the_edge_is_within_five_millimetres_of_it() {
    let page = generate(Group::Text, 2);
    let (w, h) = (page.width as usize, page.height as usize);
    // 5 mm at 200 dpi is 39 pixels
    let far = (0..w * h).filter(|&i| page.marks[i] == mark::EDGE).map(|i| (i % w).min(w - 1 - i % w).min(i / w).min(h - 1 - i / w)).max();
    assert!(far.is_some_and(|d| d < 40), "{far:?}");
    let near = (0..w * h).filter(|&i| page.marks[i] == mark::EDGE).map(|i| (i % w).min(w - 1 - i % w).min(i / w).min(h - 1 - i / w)).min();
    assert!(near.is_some_and(|d| d >= 12), "{near:?}");
}

#[test]
fn the_shadow_and_low_light_pages_are_the_colour_and_pencil_pages_in_worse_light() {
    let (colour, shadow) = (generate(Group::Colour, 5), generate(Group::Shadow, 5));
    assert_eq!(colour.marks, shadow.marks);
    assert_eq!(colour.clean, shadow.clean);
    assert!(shadow.faults.shadow.is_some() && colour.faults.shadow.is_none());
    let (pencil, dim) = (generate(Group::Pencil, 5), generate(Group::Lowlight, 5));
    assert_eq!(pencil.marks, dim.marks);
    assert!(dim.faults.noise >= 6.0 && dim.faults.dim <= 0.7 && pencil.faults.noise <= 3.0);
}
