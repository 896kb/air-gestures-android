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
}

impl FieldTyping {
    /// A symbol the band wrote.
    pub fn write(&mut self, written: &Written, words: Option<&Dictionary>) {
        match written.index {
            0..=51 => self.writer.letter(written.letters, words),
            BACKSPACE if self.writer.is_empty() => {
                self.edits.push(Edit { delete: 1, insert: String::new() });
                return;
            }
            BACKSPACE => self.writer.backspace(words),
            SPACE => self.writer.space(words),
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
        self.sync();
    }

    /// The edits since the last call, oldest first.
    pub fn take_edits(&mut self) -> Vec<Edit> {
        std::mem::take(&mut self.edits)
    }

    /// What the field should show of ours now; the difference from what it shows as an edit.
    fn sync(&mut self) {
        let state = self.writer.state();
        let wanted = format!("{}{}", state.text, state.word);
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
