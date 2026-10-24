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
/// The full stop: two in a row (a double tap on the surface) put the pen down.
const PERIOD: usize = 75;
/// How sure the band must be for a symbol to count while the pen is down: letters, spaces and
/// backspaces, and the marks idle movement tends to produce (". ," when the hand does other things).
const LETTER_MARGIN: f32 = 1.0;
const MARK_MARGIN: f32 = 3.0;
/// The two dots of a wake-up: each this sure, at most this far apart (s).
const WAKE_MARGIN: f32 = 1.5;
const WAKE_GAP_S: f64 = 1.5;
/// The pen lifts after this long with nothing confident written (s).
const PEN_QUIET_S: f64 = 8.0;
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
    /// Pen down: what the band writes is typed. It lifts after [`PEN_QUIET_S`] without a
    /// confident symbol; two full stops ("..", a double tap) put it down again.
    pen: bool,
    last_confident: f64,
    last_dot: Option<f64>,
    /// The last word taken with a space (as shown, and its letters), to choose again later.
    last_word: Option<(String, Vec<crate::words::LetterOdds>)>,
    /// Choosing among the suggestions (a swipe up): shown in place of the word, the current
    /// pick first; the word went back to "under way" and takes its space again when picked.
    choosing: Option<Choosing>,
    /// The suggestions show in a popup (the app's) instead of in the field: the field keeps
    /// its word until one is picked.
    popup: bool,
}

struct Choosing {
    /// The word had been taken with a space (it gets it back when picked).
    spaced: bool,
    /// The pick when choosing began (a cancel goes back to it).
    from: usize,
}

impl FieldTyping {
    /// `before` is the field's text before the cursor: whether ours starts a sentence. The pen
    /// starts down (the field was just chosen) at `now` (s).
    pub fn new(before: &str, now: f64) -> Self {
        let trimmed = before.trim_end();
        Self {
            starts_sentence: trimmed.is_empty() || trimmed.ends_with(['.', '?', '!']),
            pen: true,
            last_confident: now,
            ..Self::default()
        }
    }

    /// Take back the last word (a swipe left). False when none of ours was left: the field's
    /// own text is then the caller's to trim.
    pub fn delete_word(&mut self, words: Option<&Dictionary>, now: f64) -> bool {
        if !self.writer.delete_word(words) {
            return false;
        }
        self.capitals.retain(|&at| at < self.writer.state().text.chars().count());
        self.capital_now = false;
        self.last_confident = now;
        self.sync();
        true
    }

    /// Start choosing among the suggestions for the word under way, or else the last word taken
    /// with a space (a swipe up): they're shown in its place, the current pick first. False when
    /// there's nothing to choose for.
    pub fn choose(&mut self, words: Option<&Dictionary>) -> bool {
        if self.choosing.is_some() {
            return true;
        }
        let spaced = if !self.writer.letters().is_empty() {
            false
        } else if let Some((word, letters)) = self.last_word.take()
            && self.writer.reopen(&word, letters, words)
        {
            true
        } else {
            return false;
        };
        if self.writer.state().suggestions.len() < 2 {
            if spaced {
                self.writer.space(words);
            }
            return false;
        }
        self.choosing = Some(Choosing { spaced, from: self.writer.state().highlight });
        self.sync();
        true
    }

    /// Show the suggestions in the app's popup rather than in the field.
    pub fn set_popup(&mut self, popup: bool) {
        self.popup = popup;
    }

    /// While choosing: the suggestions and which is picked (for the popup).
    pub fn choices(&self) -> Option<(Vec<String>, usize)> {
        self.choosing.as_ref()?;
        let state = self.writer.state();
        Some((state.suggestions, state.highlight))
    }

    /// While choosing: leave it, with the word as it was (a swipe up again).
    pub fn choose_cancel(&mut self, words: Option<&Dictionary>, now: f64) {
        let Some(from) = self.choosing.as_ref().map(|c| c.from) else { return };
        while self.writer.state().highlight != from {
            self.writer.step(true);
        }
        self.choose_pick(words, now);
    }

    /// While choosing: the next (or previous) suggestion comes first (a swipe right or left).
    pub fn choose_step(&mut self, forward: bool) {
        if self.choosing.is_some() {
            self.writer.step(forward);
            self.sync();
        }
    }

    /// While choosing: take the one shown first (a tap); the list goes and the word stays.
    pub fn choose_pick(&mut self, words: Option<&Dictionary>, now: f64) {
        let Some(choosing) = self.choosing.take() else { return };
        if choosing.spaced {
            let word = self.writer.state().word;
            let letters = self.writer.letters().to_vec();
            self.writer.space(words);
            self.last_word = Some((word, letters));
        }
        self.last_confident = now;
        self.sync();
    }

    /// Whether the suggestions are showing.
    pub fn choosing(&self) -> bool {
        self.choosing.is_some()
    }

    /// Put the pen down (a double tap gesture), as "..." written does.
    pub fn pen_down(&mut self, now: f64) {
        self.pen = true;
        self.last_confident = now;
        self.last_dot = None;
    }

    /// Whether the pen is down (what's written is typed).
    pub fn pen(&self) -> bool {
        self.pen
    }

    /// The pen lifts once nothing confident has come for a while.
    pub fn tick(&mut self, now: f64) {
        if self.pen && now - self.last_confident > PEN_QUIET_S {
            self.pen = false;
            self.last_dot = None;
        }
    }

    /// A symbol the band wrote at `now`: typed if the pen is down and the band was sure of it;
    /// with the pen up, only a double full stop counts (it puts the pen down).
    pub fn take(&mut self, written: &Written, words: Option<&Dictionary>, now: f64) {
        self.tick(now);
        if self.choosing.is_some() {
            return;
        }
        if !self.pen {
            if written.index == PERIOD && written.margin >= WAKE_MARGIN {
                if self.last_dot.is_some_and(|at| now - at <= WAKE_GAP_S) {
                    self.pen = true;
                    self.last_confident = now;
                    self.last_dot = None;
                } else {
                    self.last_dot = Some(now);
                }
            }
            return;
        }
        let needed = match written.index {
            0..=51 | SPACE | UNDERSCORE_SPACE | BACKSPACE => LETTER_MARGIN,
            PAUSE => return,
            _ => MARK_MARGIN,
        };
        if written.margin < needed {
            return;
        }
        self.last_confident = now;
        self.write(written, words);
    }

    /// A symbol the band wrote, typed as it is.
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
            SPACE | UNDERSCORE_SPACE => {
                let word = self.writer.state().word;
                let letters = self.writer.letters().to_vec();
                self.writer.space(words);
                self.last_word = (!letters.is_empty()).then_some((word, letters));
            }
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
        if self.popup && self.choosing.is_some() {
            return;
        }
        let state = self.writer.state();
        let mut wanted = self.capitalised(&state.text, &state.word);
        if self.choosing.is_some() && !self.popup && !state.suggestions.is_empty() {
            // The suggestions in the word's place, the current pick first, in turn after it.
            let shown = wanted.chars().count() - state.word.chars().count();
            let n = state.suggestions.len();
            // Each with the capital the word has where it stands.
            let capital = wanted.chars().nth(shown).is_some_and(char::is_uppercase);
            let list: Vec<String> = (0..n)
                .map(|k| {
                    let word = &state.suggestions[(state.highlight + k) % n];
                    let mut chars = word.chars();
                    match chars.next() {
                        Some(first) if capital => first.to_uppercase().chain(chars).collect(),
                        _ => word.clone(),
                    }
                })
                .collect();
            wanted = wanted.chars().take(shown).collect::<String>() + &list.join(" · ");
        }
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
        let mut typing = FieldTyping::new("", 0.0);
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
        let mut typing = FieldTyping::new("well, ", 0.0);
        let mut field = String::from("well, ");
        typing.write(&letter('o', 0.95), None);
        typing.write(&letter('k', 0.95), None);
        apply(&mut field, typing.take_edits());
        assert_eq!(field, "well, ok");
    }

    #[test]
    fn the_pen_lifts_when_idle_ignores_unsure_marks_and_two_dots_put_it_down() {
        let mut typing = FieldTyping::new("", 0.0);
        let mut field = String::new();
        let dot = |margin| Written { index: PERIOD, margin, letters: [0.0; 26] };
        typing.take(&dot(1.0), None, 1.0); // an unsure "." from movement: dropped
        let mut unsure = letter('l', 0.9);
        unsure.margin = 0.4;
        typing.take(&unsure, None, 1.5);
        typing.take(&letter('a', 0.95), None, 2.0);
        assert!(typing.pen());
        typing.tick(11.0);
        assert!(!typing.pen(), "8 s without a confident symbol");
        typing.take(&letter('b', 0.95), None, 12.0); // pen up: not typed
        typing.take(&dot(2.0), None, 13.0);
        typing.take(&dot(2.0), None, 13.5); // ".." puts it down
        assert!(typing.pen());
        typing.take(&letter('c', 0.95), None, 14.0);
        apply(&mut field, typing.take_edits());
        assert_eq!(field, "Ac");
    }

    #[test]
    fn a_swipe_left_takes_back_the_last_word() {
        let mut typing = FieldTyping::new("x ", 0.0);
        let mut field = String::from("x ");
        for c in "ab".chars() {
            typing.write(&letter(c, 0.95), None);
        }
        typing.write(&Written { index: SPACE, margin: 3.0, letters: [0.0; 26] }, None);
        typing.write(&letter('c', 0.95), None);
        assert!(typing.delete_word(None, 1.0)); // the word under way
        assert!(typing.delete_word(None, 1.0)); // "ab "
        apply(&mut field, typing.take_edits());
        assert_eq!(field, "x ");
        assert!(!typing.delete_word(None, 1.0), "the field's own text is the caller's");
    }

    #[test]
    fn a_swipe_up_lists_the_suggestions_and_a_tap_takes_one() {
        let words = Dictionary::parse("where 100000\nwhen 90000\nwere 80000\nwire 1000\n");
        let mut typing = FieldTyping::new("", 0.0);
        let mut field = String::new();
        for c in "were".chars() {
            typing.write(&letter(c, 0.9), Some(&words));
        }
        typing.write(&Written { index: SPACE, margin: 3.0, letters: [0.0; 26] }, Some(&words));
        apply(&mut field, typing.take_edits());
        assert_eq!(field, "Were ");
        assert!(typing.choose(Some(&words)));
        apply(&mut field, typing.take_edits());
        assert!(field.starts_with("Were · "), "{field}");
        typing.choose_step(true);
        apply(&mut field, typing.take_edits());
        let second = field.split(" · ").next().unwrap().to_owned();
        assert_ne!(second, "Were");
        typing.choose_pick(Some(&words), 1.0);
        apply(&mut field, typing.take_edits());
        assert_eq!(field, format!("{second} "));
        assert!(!typing.choosing());
    }

    #[test]
    fn in_popup_mode_the_field_keeps_its_word_until_the_pick() {
        let words = Dictionary::parse("where 100000\nwhen 90000\nwere 80000\n");
        let mut typing = FieldTyping::new("", 0.0);
        typing.set_popup(true);
        let mut field = String::new();
        for c in "were".chars() {
            typing.write(&letter(c, 0.9), Some(&words));
        }
        apply(&mut field, typing.take_edits());
        let before = field.clone();
        assert!(typing.choose(Some(&words)));
        typing.choose_step(true);
        let (list, pick) = typing.choices().unwrap();
        assert!(typing.take_edits().is_empty(), "the field doesn't change while choosing");
        typing.choose_pick(Some(&words), 1.0);
        apply(&mut field, typing.take_edits());
        let mut chars = list[pick].chars();
        let expected: String = chars.next().unwrap().to_uppercase().chain(chars).collect();
        assert_eq!(field, expected);
        // A cancel puts the word back as it was.
        assert!(typing.choose(Some(&words)));
        typing.choose_step(true);
        typing.choose_cancel(Some(&words), 2.0);
        apply(&mut field, typing.take_edits());
        assert_eq!(field, expected);
        let _ = before;
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
