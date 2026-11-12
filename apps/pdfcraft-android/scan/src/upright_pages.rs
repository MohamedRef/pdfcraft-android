//! Which way up each page of a multi-page scan is, when the network was not sure of one page,
//! and how a page is turned by quarter turns (#339, spec S22 design steps 5 and 7).
//!
//! Two rules live here. `settle` lets an unsure page follow its two neighbours when both of
//! them are confident and agree, so one blurry page in a scan of ten is not left sideways.
//! `turn_corners` turns a page by reordering its four corners, which is how the Rotate button
//! and its undo will turn a page without resampling it again.
//!
//! Pure arithmetic: no picture is read, and there is no clock or randomness. Every function
//! takes any input without panicking.

/// Quarter turns in a full turn.
const TURNS: u8 = 4;
/// The number of `f32` values that make one corner: its x and its y.
const VALUES_PER_CORNER: usize = 2;

/// Both neighbours must be at least this confident for an unsure page to follow them (S22
/// step 7: "confidence above 0.95"). A neighbour that is less sure, or not sure at all, does not
/// count, so the rule never copies a guess.
pub const NEIGHBOUR_CONFIDENCE: f32 = 0.95;

/// The network's answer for one page: the quarter turns clockwise that would stand it upright
/// (0–3), how confident it was (0–1), and whether that was enough to act on.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct PageTurn {
    /// Quarter turns clockwise that stand the page upright (0–3).
    pub quarter_turns: u8,
    /// How confident the network was (0–1).
    pub confidence: f32,
    /// Whether the answer was sure enough to act on.
    pub sure: bool,
}

/// Each page's turn and whether that turn came from its neighbours. Both `settle` and
/// `followed` read this one rule, so they can never disagree.
fn decide(pages: &[Option<PageTurn>]) -> Vec<(u8, bool)> {
    (0..pages.len()).map(|i| decide_one(pages, i)).collect()
}

/// The turn and the follow flag of the page at `i`. A page that is not in `pages` gets 0.
fn decide_one(pages: &[Option<PageTurn>], i: usize) -> (u8, bool) {
    let Some(page) = page_at(pages, Some(i)) else {
        return (0, false);
    };
    if page.sure {
        return (page.quarter_turns % TURNS, false);
    }
    let before = page_at(pages, i.checked_sub(1)).filter(|p| confident(*p));
    let after = page_at(pages, i.checked_add(1)).filter(|p| confident(*p));
    match (before, after) {
        (Some(a), Some(b)) if a.quarter_turns % TURNS == b.quarter_turns % TURNS => (a.quarter_turns % TURNS, true),
        _ => (0, false),
    }
}

/// The page at index `i`, if there is one and it was asked about (`None` in the list means it
/// was not, and it counts as no page at all).
fn page_at(pages: &[Option<PageTurn>], i: Option<usize>) -> Option<PageTurn> {
    i.and_then(|j| pages.get(j)).copied().flatten()
}

/// Whether a page is sure of its answer and confident enough to be a neighbour: the bar of
/// `NEIGHBOUR_CONFIDENCE`. A NaN confidence is not confident.
fn confident(page: PageTurn) -> bool {
    page.sure && page.confidence >= NEIGHBOUR_CONFIDENCE
}

/// The turn each page of a scan gets, in the order the pages came (issue #339, S22 step 7).
///
/// `None` means the page was not asked about: it gets 0 and is nobody's neighbour. A sure page
/// gets its own turn. An unsure page between two sure neighbours that are both at least
/// `NEIGHBOUR_CONFIDENCE` confident and agree gets their turn. Every other page gets 0. Only
/// the network's own answers count as neighbours: a page that followed its neighbours does not
/// pass the turn on. The first and last page have one neighbour and never follow.
pub fn settle(pages: &[Option<PageTurn>]) -> Vec<u8> {
    decide(pages).into_iter().map(|(turn, _)| turn).collect()
}

/// Whether each page's turn came from its neighbours. Same length and order as `settle`'s
/// result, so the app can show the "Turned upright" chip on the pages that were followed.
pub fn followed(pages: &[Option<PageTurn>]) -> Vec<bool> {
    decide(pages).into_iter().map(|(_, followed)| followed).collect()
}

/// The corners (x1, y1 … x4, y4: top left, top right, bottom right, bottom left) of the same
/// page seen turned clockwise by `quarter_turns` (any value, taken modulo 4). After one turn the
/// corner that was bottom left is the top left. Straightening with the result gives the page
/// turned, in one resampling, so the Rotate button needs no second pass over the photo (S22
/// step 5).
pub fn turn_corners(corners: [f32; 8], quarter_turns: u8) -> [f32; 8] {
    let mut out = corners;
    // Each clockwise turn moves every corner one place back along TL, TR, BR, BL, so the
    // values rotate right by one corner (two values) per turn.
    out.rotate_right(VALUES_PER_CORNER * usize::from(quarter_turns % TURNS));
    out
}

/// The turn that undoes `quarter_turns`: the undo of the Rotate button (S22 step 5).
pub fn turn_back(quarter_turns: u8) -> u8 {
    (TURNS - quarter_turns % TURNS) % TURNS
}

/// A page's total turn: the network's `auto` turn plus the person's presses of Rotate `user`
/// (which may be negative), modulo 4 (S22: "the page shown is turned by their sum"). Computed in
/// 64 bits, so no input can overflow it.
pub fn total_turn(auto: u8, user: i32) -> u8 {
    let sum = i64::from(auto) + i64::from(user);
    u8::try_from(sum.rem_euclid(i64::from(TURNS))).unwrap_or(0)
}
