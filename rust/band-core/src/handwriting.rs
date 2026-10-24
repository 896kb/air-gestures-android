//! The band's own handwriting: in `deviceState` HANDWRITING_CC (4) it runs its `hw_*` model and
//! sends 100 scores per frame on the RawInference stream. Meta AI reads them as CTC: per frame
//! the best symbol, repeats merged, blanks dropped (`band_encoder_v5/kaldi.json`: greedy, the
//! blank's score lowered by ln 0.2 first). The symbols are `phones.txt`'s, in score order.

/// Scores per frame.
pub const SYMBOLS: usize = 100;
/// The CTC blank's index.
pub const BLANK: usize = 99;
/// `^` in Meta AI's list, but the band's model sends it when the writing pauses (seen between
/// letters, the longer the pause the likelier): kept as a pause, not text. It may mark word gaps.
pub const PAUSE: usize = 87;
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

/// The digit 0, which the band often reads an "o" as.
pub const ZERO: usize = 52;

/// The space symbol, and `_`, which is what the band mostly sends for a drawn space.
pub const SPACE: usize = 96;
pub const UNDERSCORE: usize = 88;

/// Symbols that are one thing as written: a letter and its capital; o, O and 0; and the
/// two spaces.
pub fn group(index: usize) -> usize {
    match index {
        26..=51 => index - 26,
        ZERO => 14,
        UNDERSCORE => SPACE,
        _ => index,
    }
}

/// How sure a frame is of `index`'s [`group`]: its best score in the group over the best
/// outside it. An "o" the band can't tell from "O" or "0" is still a sure "o".
pub fn group_margin(frame: &[f32], index: usize) -> f32 {
    let mut inside = f32::NEG_INFINITY;
    let mut outside = f32::NEG_INFINITY;
    for (i, &score) in frame.iter().enumerate().take(SYMBOLS) {
        let score = if i == BLANK { score + BLANK_BIAS } else { score };
        if group(i) == group(index) {
            inside = inside.max(score);
        } else {
            outside = outside.max(score);
        }
    }
    inside - outside
}

/// Greedy CTC over a stream of frames.
#[derive(Default)]
pub struct Decoder {
    last: Option<usize>,
    /// The symbol whose frames are coming, at its surest frame so far.
    run: Option<Written>,
    /// Symbols whose frames have ended, each at its surest frame: what typing takes. A symbol's
    /// first frame is often unsure (it is only just ahead), so it is judged on all of them; and
    /// frames that swap between one letter's forms ("o", "O", "0") are one symbol.
    pub finished: Vec<Written>,
    /// The text so far (symbols as [`symbol`] spells them).
    pub text: String,
    pub frames: usize,
}

impl Decoder {
    /// One frame of scores; the symbol it adds, if any, with its margin in this first frame.
    pub fn push(&mut self, frame: &[f32]) -> Option<(usize, f32)> {
        self.frames += 1;
        let (index, margin) = best(frame);
        let new = index != BLANK && self.last != Some(index);
        let sure = if index == BLANK { margin } else { group_margin(frame, index) };
        if let Some(run) = &mut self.run
            && index != BLANK
            && group(run.index) == group(index)
        {
            if sure > run.margin {
                *run = Written { index, margin: sure, letters: letter_odds(frame) };
            }
        } else {
            self.finished.extend(self.run.take());
            if index != BLANK {
                self.run = Some(Written { index, margin: sure, letters: letter_odds(frame) });
            }
        }
        self.last = Some(index);
        if !new {
            return None;
        }
        if index != PAUSE {
            self.text.push_str(symbol(index));
        }
        Some((index, margin))
    }
}

/// How likely each of a–z is in a frame (capitals folded into their letter), from the
/// frame's softmax; what the dictionary weighs words by.
pub fn letter_odds(frame: &[f32]) -> [f32; 26] {
    let top = frame.iter().take(SYMBOLS).copied().fold(f32::NEG_INFINITY, f32::max);
    let exp: Vec<f32> = frame.iter().take(SYMBOLS).map(|&s| (s - top).exp()).collect();
    let mut odds = [0f32; 26];
    for (letter, odd) in odds.iter_mut().enumerate() {
        *odd = exp.get(letter).copied().unwrap_or(0.0) + exp.get(letter + 26).copied().unwrap_or(0.0);
    }
    odds[14] += exp.get(ZERO).copied().unwrap_or(0.0);
    let sum: f32 = odds.iter().sum();
    if sum > 0.0 {
        odds.iter_mut().for_each(|odd| *odd /= sum);
    }
    odds
}

/// One symbol the band wrote, for whatever types it: its index, how sure (margin), and the
/// odds of each letter in the frame it came from.
#[derive(Debug, Clone, Copy)]
pub struct Written {
    pub index: usize,
    pub margin: f32,
    pub letters: [f32; 26],
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
        // A pause between letters is a token, not text.
        let mut decoder = Decoder::default();
        for index in [0, PAUSE, 1] {
            decoder.push(&frame(index));
        }
        assert_eq!(decoder.text, "ab");
    }

    #[test]
    fn a_symbol_is_judged_on_its_surest_frame() {
        let sure = |index, score| {
            let mut scores = vec![0.0; SYMBOLS];
            scores[index] = score;
            scores
        };
        let mut decoder = Decoder::default();
        for frame in [sure(4, 0.3), sure(4, 4.0), sure(4, 1.0), frame(BLANK), sure(7, 2.0), sure(0, 1.0)] {
            decoder.push(&frame);
        }
        let finished: Vec<(usize, f32)> = decoder.finished.iter().map(|w| (w.index, w.margin)).collect();
        assert_eq!(finished, [(4, 4.0), (7, 2.0)]);
        // An "o" read as o, O and 0 by turns is one sure o, though each frame is split.
        let mut o = vec![0.0; SYMBOLS];
        o[14] = 3.0;
        o[40] = 2.8;
        o[ZERO] = 2.9;
        let mut decoder = Decoder::default();
        for frame in [o.clone(), sure(40, 3.0), o, frame(BLANK)] {
            decoder.push(&frame);
        }
        assert_eq!(decoder.finished.len(), 1);
        assert_eq!(group(decoder.finished[0].index), 14);
        assert!(decoder.finished[0].margin >= 2.9);
    }

    #[test]
    fn letter_odds_fold_capitals_and_add_up() {
        let mut scores = vec![-10.0; SYMBOLS];
        scores[7] = 2.0; // h
        scores[26 + 7] = 2.0; // H
        scores[4] = 2.0; // e
        let odds = letter_odds(&scores);
        assert!((odds.iter().sum::<f32>() - 1.0).abs() < 1e-4);
        assert!((odds[7] - 2.0 * odds[4]).abs() < 1e-4);
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
