//! Typing into the text field in front with the band's own handwriting: the symbols it writes
//! become letters for [`Writer`], so the dictionary picks the word as the letters come and
//! changes its mind when later letters say otherwise ("w" read as "n", then "here": "where").
//! What the field should show is compared with what it shows, and the difference goes out as
//! edits (characters to delete before the cursor, then text to insert), corrections included.

use band_core::handwriting::{PAUSE, Written, symbol};
use serde::Serialize;

use crate::words::{Dictionary, Writer};

/// Score indexes of the symbols that aren't letters, digits or marks.
const BACKSPACE: usize = 94;
const ENTER: usize = 95;
const SPACE: usize = 96;
/// `_` in Meta AI's list, but what the band sends for a drawn space (seen on build 70).
const UNDERSCORE_SPACE: usize = 89;
/// Where the capitals are in the scores (A–Z).
const CAPITALS: std::ops::RangeInclusive<usize> = 26..=51;

/// One change to the field: delete this many characters before the cursor, then insert.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Edit {
    pub delete: usize,
    pub insert: String,
}

#[derive(Default)]
pub struct FieldTyping {
    writer: Writer,
    /// What this typing has put in the field so far (it never touches what was there before,
    /// except a backspace written with nothing of ours left).
    shown: String,
    edits: Vec<Edit>,
    /// The field's text before ours ends a sentence (or is empty): ours starts with a capital.
    starts_sentence: bool,
    /// Where words the band wrote with a capital first letter begin in our text (characters),
    /// and whether the word under way began with one.
    capitals: Vec<usize>,
    capital_now: bool,
}

impl FieldTyping {
    /// `before` is the field's text before the cursor: whether ours starts a sentence.
    pub fn new(before: &str) -> Self {
        let trimmed = before.trim_end();
        Self {
            starts_sentence: trimmed.is_empty() || trimmed.ends_with(['.', '?', '!']),
            ..Self::default()
        }
    }

    /// A symbol the band wrote.
    pub fn write(&mut self, written: &Written, words: Option<&Dictionary>) {
        let before = self.writer.state().text.chars().count();
        let starting = self.writer.state().letters.is_empty();
        match written.index {
            0..=51 => {
                if starting {
                    self.capital_now = CAPITALS.contains(&written.index);
                }
                self.writer.letter(written.letters, words)
            }
            BACKSPACE if self.writer.is_empty() => {
                self.edits.push(Edit { delete: 1, insert: String::new() });
                return;
            }
            BACKSPACE => self.writer.backspace(words),
            SPACE | UNDERSCORE_SPACE => self.writer.space(words),
            index @ 52..=93 if index != PAUSE => {
                let c = symbol(index).chars().next().unwrap_or(' ');
                if matches!(c, '.' | ',' | '?' | '!') {
                    self.writer.punctuate(c, words);
                } else {
                    self.writer.insert(c, words);
                }
            }
            // Pauses, enter (a field might submit), shift and the pinch: nothing for now.
            PAUSE | ENTER | _ => return,
        }
        // A word taken into the text keeps its capital where it began.
        let text = self.writer.state().text.chars().count();
        if text > before && !starting && self.capital_now {
            self.capitals.push(before);
        }
        if self.writer.state().letters.is_empty() {
            self.capital_now = false;
        }
        self.capitals.retain(|&at| at < text);
        self.sync();
    }

    /// Our text with capitals: a sentence's first word, words the band wrote with a capital,
    /// and "I".
    fn capitalised(&self, text: &str, word: &str) -> String {
        let all: Vec<char> = text.chars().chain(word.chars()).collect();
        let word_at = text.chars().count();
        let mut out = String::with_capacity(all.len());
        let mut sentence = self.starts_sentence;
        let mut i = 0;
        while i < all.len() {
            if !all[i].is_alphabetic() {
                if matches!(all[i], '.' | '?' | '!') {
                    sentence = true;
                }
                out.push(all[i]);
                i += 1;
                continue;
            }
            let end = (i..all.len()).find(|&j| !all[j].is_alphabetic()).unwrap_or(all.len());
            let token: String = all[i..end].iter().collect();
            let capital = sentence
                || self.capitals.contains(&i)
                || (i == word_at && self.capital_now)
                || token == "i";
            let mut chars = token.chars();
            if capital && let Some(first) = chars.next() {
                out.extend(first.to_uppercase());
                out.push_str(chars.as_str());
            } else {
                out.push_str(&token);
            }
            sentence = false;
            i = end;
        }
        out
    }

    /// The edits since the last call, oldest first.
    pub fn take_edits(&mut self) -> Vec<Edit> {
        std::mem::take(&mut self.edits)
    }

    /// What the field should show of ours now; the difference from what it shows as an edit.
    fn sync(&mut self) {
        let state = self.writer.state();
        let wanted = self.capitalised(&state.text, &state.word);
        let common = self.shown.chars().zip(wanted.chars()).take_while(|(a, b)| a == b).count();
        let delete = self.shown.chars().count() - common;
        let insert: String = wanted.chars().skip(common).collect();
        if delete > 0 || !insert.is_empty() {
            self.edits.push(Edit { delete, insert });
        }
        self.shown = wanted;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn letter(c: char, sure: f32) -> Written {
        let index = (c as u8 - b'a') as usize;
        let mut letters = [(1.0 - sure) / 25.0; 26];
        letters[index] = sure;
        Written { index, margin: 1.0, letters }
    }

    fn apply(field: &mut String, edits: Vec<Edit>) {
        for edit in edits {
            for _ in 0..edit.delete {
                field.pop();
            }
            field.push_str(&edit.insert);
        }
    }

    #[test]
    fn a_misread_first_letter_is_corrected_by_the_ones_after() {
        let words = Dictionary::parse("where 100000\nwhen 90000\nnhere 1\nthe 500000\n");
        let mut typing = FieldTyping::default();
        let mut field = String::from("hi ");
        // "w" read as "n" (but "w" a close second), then h-e-r-e read well.
        let mut first = letter('n', 0.55);
        first.letters[(b'w' - b'a') as usize] = 0.4;
        typing.write(&first, Some(&words));
        for c in "here".chars() {
            typing.write(&letter(c, 0.95), Some(&words));
        }
        apply(&mut field, typing.take_edits());
        assert_eq!(field, "hi where");
        typing.write(&Written { index: SPACE, margin: 3.0, letters: [0.0; 26] }, Some(&words));
        apply(&mut field, typing.take_edits());
        assert_eq!(field, "hi where ");
    }

    #[test]
    fn sentences_capitals_and_i_are_capitalised() {
        let mut typing = FieldTyping::new("");
        let mut field = String::new();
        let space = Written { index: SPACE, margin: 3.0, letters: [0.0; 26] };
        for c in "hi".chars() {
            typing.write(&letter(c, 0.95), None);
        }
        typing.write(&Written { index: 52 + 10 + 13, margin: 3.0, letters: [0.0; 26] }, None); // "."
        for c in "i".chars() {
            typing.write(&letter(c, 0.95), None);
        }
        typing.write(&space, None);
        // A capital written by the band (index 26 + letter) keeps its capital mid-sentence.
        let mut capital = letter('b', 0.95);
        capital.index = 26 + 1;
        typing.write(&capital, None);
        typing.write(&letter('o', 0.95), None);
        typing.write(&letter('b', 0.95), None);
        // The band's "_" is its space.
        typing.write(&Written { index: UNDERSCORE_SPACE, margin: 3.0, letters: [0.0; 26] }, None);
        apply(&mut field, typing.take_edits());
        assert_eq!(field, "Hi. I Bob ");
        // Mid-sentence in an existing field: no capital.
        let mut typing = FieldTyping::new("well, ");
        let mut field = String::from("well, ");
        typing.write(&letter('o', 0.95), None);
        typing.write(&letter('k', 0.95), None);
        apply(&mut field, typing.take_edits());
        assert_eq!(field, "well, ok");
    }

    #[test]
    fn backspace_takes_ours_first_then_the_fields() {
        let mut typing = FieldTyping::default();
        let mut field = String::from("ab");
        typing.write(&letter('c', 0.9), None);
        typing.write(&Written { index: BACKSPACE, margin: 3.0, letters: [0.0; 26] }, None);
        typing.write(&Written { index: BACKSPACE, margin: 3.0, letters: [0.0; 26] }, None);
        apply(&mut field, typing.take_edits());
        assert_eq!(field, "a");
    }
}
