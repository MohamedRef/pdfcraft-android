//! The Whiteboard look's own tone (#301): after Auto's light steps, an S-curve on brightness,
//! `0.5 − 0.5·cos(Y^0.75·π)`, which keeps black and white where they are, lifts the board's
//! pale greys to white and leaves marker strokes dark; then the colour of each pixel, its
//! distance from grey, is multiplied (1.5 by default) so faded markers read again.
//!
//! The curve and the factor are #301's starting values, chosen on paper; nothing was measured on
//! photos of real boards.

/// How much the colour of a pixel is multiplied by.
pub const WHITEBOARD_COLOUR_FACTOR: f32 = 1.5;

/// The S-curve as a table: brightness 0–255 in, 0–255 out.
fn curve() -> [u8; 256] {
    let mut table = [0u8; 256];
    for (i, out) in table.iter_mut().enumerate() {
        let y = i as f64 / 255.0;
        let v = 0.5 - 0.5 * (y.powf(0.75) * std::f64::consts::PI).cos();
        *out = (v * 255.0).round().clamp(0.0, 255.0) as u8;
    }
    table
}

/// Give RGB8 pixels the Whiteboard tone in place: the brightness through the S-curve, the colour
/// multiplied by `colour_factor` (1 keeps it). The same bytes come out for the same bytes in.
pub fn whiteboard_tone(rgb: &mut [u8], colour_factor: f32) {
    let table = curve();
    let k = if colour_factor.is_finite() { colour_factor.clamp(0.0, 4.0) } else { 1.0 };
    for px in rgb.as_chunks_mut::<3>().0 {
        let (r, g, b) = (f32::from(px[0]), f32::from(px[1]), f32::from(px[2]));
        let y = 0.299 * r + 0.587 * g + 0.114 * b;
        let index = y.round().clamp(0.0, 255.0) as usize;
        let to = f32::from(table.get(index).copied().unwrap_or(0));
        for (c, v) in px.iter_mut().zip([r, g, b]) {
            *c = (to + (v - y) * k).round().clamp(0.0, 255.0) as u8;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn black_and_white_stay_and_pale_grey_goes_whiter() {
        let mut px = vec![0, 0, 0, 255, 255, 255, 200, 200, 200, 40, 40, 40];
        whiteboard_tone(&mut px, 1.5);
        assert_eq!(&px[..6], &[0, 0, 0, 255, 255, 255]);
        // a board's pale grey is lifted toward white
        assert!(px[6] > 200, "{px:?}");
        // a dark marker stroke stays dark
        assert!(px[9] <= 45, "{px:?}");
        // a grey pixel stays grey
        assert!(px[6] == px[7] && px[7] == px[8]);
    }

    #[test]
    fn colour_is_multiplied_and_the_same_bytes_come_out() {
        let red = [200u8, 60, 60];
        let mut plain = red.to_vec();
        whiteboard_tone(&mut plain, 1.0);
        let mut strong = red.to_vec();
        whiteboard_tone(&mut strong, WHITEBOARD_COLOUR_FACTOR);
        // red minus green, the colour, is larger with the factor
        assert!(i32::from(strong[0]) - i32::from(strong[1]) > i32::from(plain[0]) - i32::from(plain[1]));
        let mut again = red.to_vec();
        whiteboard_tone(&mut again, WHITEBOARD_COLOUR_FACTOR);
        assert_eq!(strong, again);
        // a factor that is not a number keeps the colour as it is
        let mut odd = red.to_vec();
        whiteboard_tone(&mut odd, f32::NAN);
        assert_eq!(odd, plain);
    }
}
