//! The band's own handwriting: in `deviceState` HANDWRITING_CC (4) it runs its `hw_*` model and
//! sends 100 scores per frame on the RawInference stream. Meta AI reads them as CTC: per frame
//! the best symbol, repeats merged, blanks dropped (`band_encoder_v5/kaldi.json`: greedy, the
//! blank's score lowered by ln 0.2 first). The symbols are `phones.txt`'s, in score order.

/// Scores per frame.
pub const SYMBOLS: usize = 100;
/// The CTC blank's index.
pub const BLANK: usize = 99;
/// Added to the blank's score before choosing (ln 0.2), as Meta AI's decoder config says.
const BLANK_BIAS: f32 = -1.609_438;

/// What each score index stands for; the blank is last.
pub fn symbol(index: usize) -> &'static str {
    const SIMPLE: &str = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~";
    match index {
        0..=93 => &SIMPLE[index..index + 1],
        94 => "⌫",
        95 => "⏎",
        96 => "▁",
        97 => "↑",
        98 => "🤏",
        _ => "·",
    }
}

/// One frame's best symbol: its index, and its margin over the runner-up (how sure).
pub fn best(frame: &[f32]) -> (usize, f32) {
    let mut first = (BLANK, f32::NEG_INFINITY);
    let mut second = f32::NEG_INFINITY;
    for (index, &score) in frame.iter().enumerate().take(SYMBOLS) {
        let score = if index == BLANK { score + BLANK_BIAS } else { score };
        if score > first.1 {
            second = first.1;
            first = (index, score);
        } else if score > second {
            second = score;
        }
    }
    (first.0, first.1 - second)
}

/// Greedy CTC over a stream of frames.
#[derive(Default)]
pub struct Decoder {
    last: Option<usize>,
    /// The text so far (symbols as [`symbol`] spells them).
    pub text: String,
    pub frames: usize,
}

impl Decoder {
    /// One frame of scores; the symbol it adds, if any, with its margin.
    pub fn push(&mut self, frame: &[f32]) -> Option<(usize, f32)> {
        self.frames += 1;
        let (index, margin) = best(frame);
        let new = index != BLANK && self.last != Some(index);
        self.last = Some(index);
        if !new {
            return None;
        }
        self.text.push_str(symbol(index));
        Some((index, margin))
    }
}

/// The little-endian float32 scores in a RawInferenceSample's `channels`.
pub fn scores(bytes: &[u8]) -> Vec<f32> {
    bytes.chunks_exact(4).map(|b| f32::from_le_bytes([b[0], b[1], b[2], b[3]])).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn frame(best: usize) -> Vec<f32> {
        let mut scores = vec![0.0; SYMBOLS];
        scores[best] = 5.0;
        scores
    }

    #[test]
    fn repeats_merge_and_blanks_split_them() {
        let mut decoder = Decoder::default();
        for index in [7, 7, BLANK, 4, 11, 11, BLANK, 11, 14, BLANK, BLANK] {
            decoder.push(&frame(index));
        }
        assert_eq!(decoder.text, "hello");
    }

    #[test]
    fn the_blank_needs_a_clear_lead() {
        // Blank 1.0 ahead of "a" loses it after the ln 0.2 bias.
        let mut scores = vec![0.0; SYMBOLS];
        scores[BLANK] = 3.0;
        scores[0] = 2.0;
        assert_eq!(best(&scores).0, 0);
        assert_eq!(symbol(96), "▁");
        assert_eq!(symbol(62), "!");
        assert_eq!(symbol(93), "~");
    }
}
