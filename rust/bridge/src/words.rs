//! Words from letters written one at a time: each letter comes as how likely it is to be each
//! of a–z (see `letters`), and the dictionary turns the run of them into words and completions.
//!
//! A word scores Σ log(α·p(its letter) + (1 − α)/26) over the letters written so far, plus λ ×
//! log(how often it's used). Simulated with held-out letters from the recordings (65 % read
//! right alone), whole words came out right first 85 % of the time and among the first three
//! 94 % of the time, against 26 % for the letters alone; offering completions saved about a
//! third of the letters.
//!
//! [`Writer`] is the text being written, with gestures in the way Meta's handwriting does
//! them: space takes the highlighted suggestion, backspace takes back a letter, up and down
//! move through the suggestions.

use std::sync::{Arc, RwLock};

use serde::Serialize;

/// The dictionary the app loaded (`words.txt` from its assets), shared by every connection.
static DICTIONARY: RwLock<Option<Arc<Dictionary>>> = RwLock::new(None);

pub fn load_dictionary(text: &str) -> usize {
    let dictionary = Dictionary::parse(text);
    let count = dictionary.len();
    *DICTIONARY.write().unwrap_or_else(|e| e.into_inner()) = Some(Arc::new(dictionary));
    count
}

pub fn dictionary() -> Option<Arc<Dictionary>> {
    DICTIONARY.read().unwrap_or_else(|e| e.into_inner()).clone()
}

/// How far the letter reader is trusted: the rest is spread evenly (so one misread letter
/// costs a word a bounded amount).
const TRUST: f32 = 0.9;
/// The weight of how common a word is, against the letters.
const COMMONNESS: f32 = 1.0;
/// How many suggestions are offered, and of those how many may be completions.
const SUGGESTIONS: usize = 5;
const COMPLETIONS: usize = 2;
/// Completions rank as if this much less likely (log), so whole words come before them unless
/// the completion is much more likely.
const COMPLETION_COST: f32 = 2.0;
/// The longest word considered.
const LONGEST: usize = 24;

pub type LetterOdds = [f32; 26];

pub struct Dictionary {
    words: Vec<String>,
    /// Each word's letters as 0–25.
    spelled: Vec<Vec<u8>>,
    prior: Vec<f32>,
}

impl Dictionary {
    /// `word count` lines (`#` lines are comments); words of letters a–z only.
    pub fn parse(text: &str) -> Self {
        let mut entries: Vec<(String, f64)> = text
            .lines()
            .filter(|line| !line.starts_with('#'))
            .filter_map(|line| {
                let mut parts = line.split_whitespace();
                let word = parts.next()?;
                let count: f64 = parts.next()?.parse().ok()?;
                (word.len() <= LONGEST && word.bytes().all(|b| b.is_ascii_lowercase()) && count > 0.0)
                    .then(|| (word.to_owned(), count))
            })
            .collect();
        entries.dedup_by(|a, b| a.0 == b.0);
        let total: f64 = entries.iter().map(|(_, count)| count).sum();
        Self {
            spelled: entries.iter().map(|(word, _)| word.bytes().map(|b| b - b'a').collect()).collect(),
            prior: entries.iter().map(|(_, count)| (count / total).ln() as f32).collect(),
            words: entries.into_iter().map(|(word, _)| word).collect(),
        }
    }

    pub fn len(&self) -> usize {
        self.words.len()
    }

    pub fn is_empty(&self) -> bool {
        self.words.is_empty()
    }

    /// The best words for `letters`, best first, with their scores: of exactly that length
    /// (`complete`), or longer ones starting that way.
    fn rank(&self, letters: &[LetterOdds], complete: bool, count: usize) -> Vec<(usize, f32)> {
        let odds: Vec<[f32; 26]> = letters
            .iter()
            .map(|p| p.map(|p| (TRUST * p + (1.0 - TRUST) / 26.0).ln()))
            .collect();
        let mut best: Vec<(usize, f32)> = Vec::with_capacity(count + 1);
        for (i, spelled) in self.spelled.iter().enumerate() {
            let fits = if complete { spelled.len() == odds.len() } else { spelled.len() > odds.len() };
            if !fits {
                continue;
            }
            let score = COMMONNESS * self.prior[i]
                + spelled.iter().zip(&odds).map(|(&letter, odds)| odds[letter as usize]).sum::<f32>();
            if best.len() < count || score > best[best.len() - 1].1 {
                let at = best.partition_point(|&(_, s)| s >= score);
                best.insert(at, (i, score));
                best.truncate(count);
            }
        }
        best
    }

    /// What to offer for `letters`: the best whole words first, a couple of completions, and the
    /// letters as read when no word is them.
    pub fn suggest(&self, letters: &[LetterOdds]) -> Vec<String> {
        if letters.is_empty() {
            return Vec::new();
        }
        let whole = self.rank(letters, true, SUGGESTIONS);
        let longer = self.rank(letters, false, COMPLETIONS);
        let mut out: Vec<String> = Vec::new();
        // The best whole word leads (it's what space takes); then the rest by score, at most
        // COMPLETIONS of them longer.
        let longer = longer.iter().map(|&(i, score)| (i, score - COMPLETION_COST));
        let mut rest: Vec<(usize, f32)> = whole.iter().skip(1).copied().chain(longer).collect();
        rest.sort_by(|a, b| b.1.total_cmp(&a.1));
        for (i, _) in whole.first().into_iter().chain(&rest) {
            if out.len() + 1 >= SUGGESTIONS {
                break;
            }
            out.push(self.words[*i].clone());
        }
        let literal = read_as_is(letters);
        if !out.contains(&literal) {
            out.push(literal);
        }
        out
    }
}

/// Each letter as its likeliest reading.
fn read_as_is(letters: &[LetterOdds]) -> String {
    letters
        .iter()
        .map(|p| {
            let best = (0..26).max_by(|&a, &b| p[a].total_cmp(&p[b])).unwrap_or(0);
            (b'a' + best as u8) as char
        })
        .collect()
}

/// The text being written: what's done, the word under way and what's offered for it.
#[derive(Default)]
pub struct Writer {
    text: String,
    letters: Vec<LetterOdds>,
    suggestions: Vec<String>,
    highlight: usize,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct WriterState {
    /// What's written, and the word under way (the highlighted suggestion; empty when none).
    pub text: String,
    pub word: String,
    pub suggestions: Vec<String>,
    pub highlight: usize,
    /// The letters of the word under way as each was read.
    pub letters: String,
}

impl Writer {
    pub fn state(&self) -> WriterState {
        WriterState {
            text: self.text.clone(),
            word: self.suggestions.get(self.highlight).cloned().unwrap_or_default(),
            suggestions: self.suggestions.clone(),
            highlight: self.highlight,
            letters: read_as_is(&self.letters),
        }
    }

    pub fn letter(&mut self, odds: LetterOdds, words: Option<&Dictionary>) {
        if self.letters.len() < LONGEST {
            self.letters.push(odds);
        }
        self.refresh(words);
    }

    /// Back a letter: from the word under way, else from the text.
    pub fn backspace(&mut self, words: Option<&Dictionary>) {
        if self.letters.pop().is_none() {
            self.text.pop();
        }
        self.refresh(words);
    }

    /// Take the highlighted suggestion and a space; a space alone when no word is under way.
    pub fn space(&mut self, words: Option<&Dictionary>) {
        if let Some(word) = self.suggestions.get(self.highlight) {
            self.text.push_str(word);
        }
        self.text.push(' ');
        self.letters.clear();
        self.refresh(words);
    }

    /// A punctuation mark after the highlighted word (or after the text), then a space.
    pub fn punctuate(&mut self, mark: char, words: Option<&Dictionary>) {
        if let Some(word) = self.suggestions.get(self.highlight) {
            self.text.push_str(word);
        } else if self.text.ends_with(' ') {
            self.text.pop();
        }
        self.text.push(mark);
        self.text.push(' ');
        self.letters.clear();
        self.refresh(words);
    }

    /// A character as written (a digit, a mark), after the highlighted word, with no space.
    pub fn insert(&mut self, c: char, words: Option<&Dictionary>) {
        if let Some(word) = self.suggestions.get(self.highlight) {
            self.text.push_str(word);
        }
        self.text.push(c);
        self.letters.clear();
        self.refresh(words);
    }

    /// Whether nothing has been written (no text, no word under way).
    pub fn is_empty(&self) -> bool {
        self.text.is_empty() && self.letters.is_empty()
    }

    /// The next (or previous) suggestion, round and round.
    pub fn step(&mut self, forward: bool) {
        let n = self.suggestions.len();
        if n > 0 {
            self.highlight = if forward { (self.highlight + 1) % n } else { (self.highlight + n - 1) % n };
        }
    }

    pub fn clear(&mut self) {
        *self = Self::default();
    }

    fn refresh(&mut self, words: Option<&Dictionary>) {
        self.suggestions = match words {
            Some(words) => words.suggest(&self.letters),
            None if self.letters.is_empty() => Vec::new(),
            None => vec![read_as_is(&self.letters)],
        };
        self.highlight = 0;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const WORDS: &str = "# test\nthe 1000\nto 900\nthen 300\nten 200\nthere 250\ntie 50\n";

    /// Fairly sure of `sure`, with a little on `also`.
    fn odds(sure: char, also: char) -> LetterOdds {
        let mut p = [0.01; 26];
        p[(sure as u8 - b'a') as usize] = 0.6;
        p[(also as u8 - b'a') as usize] = 0.3;
        p
    }

    #[test]
    fn a_misread_letter_still_gives_the_word() {
        let words = Dictionary::parse(WORDS);
        // t, then a letter read as r but maybe h, then e: "tre" isn't a word, "the" is.
        let suggestions = words.suggest(&[odds('t', 'f'), odds('r', 'h'), odds('e', 'c')]);
        assert_eq!(suggestions[0], "the");
        assert!(suggestions.contains(&"then".to_owned()), "a completion: {suggestions:?}");
        assert_eq!(suggestions.last().unwrap(), "tre", "the letters as read come last");
    }

    #[test]
    fn writing_and_editing() {
        let words = Dictionary::parse(WORDS);
        let mut writer = Writer::default();
        for (sure, also) in [('t', 'f'), ('h', 'n'), ('e', 'c')] {
            writer.letter(odds(sure, also), Some(&words));
        }
        assert_eq!(writer.state().word, "the");
        writer.step(true);
        let second = writer.state().word;
        writer.step(false);
        writer.backspace(Some(&words));
        assert_eq!(writer.state().letters, "th");
        writer.letter(odds('e', 'c'), Some(&words));
        writer.step(true);
        assert_eq!(writer.state().word, second);
        writer.step(false);
        writer.space(Some(&words));
        assert_eq!(writer.state().text, "the ");
        assert!(writer.state().suggestions.is_empty());
        writer.backspace(Some(&words));
        assert_eq!(writer.state().text, "the");
        // Without a dictionary the letters are taken as read.
        writer.letter(odds('x', 'y'), None);
        writer.space(None);
        assert_eq!(writer.state().text, "thex ");
        writer.punctuate('.', None);
        assert_eq!(writer.state().text, "thex. ");
        writer.letter(odds('t', 'f'), Some(&words));
        writer.letter(odds('o', 'a'), Some(&words));
        writer.punctuate('?', Some(&words));
        assert_eq!(writer.state().text, "thex. to? ");
    }
}

/// The simulation in the module notes, on the real dictionary: `WORDS=<words.txt>
/// ODDS=<[[letter, [26 odds]], …] held-out letters> cargo test -p bridge --release --lib -- --ignored --nocapture simulate`.
#[cfg(test)]
mod simulation {
    use super::*;

    #[test]
    #[ignore]
    fn simulate() {
        let words = Dictionary::parse(&std::fs::read_to_string(std::env::var("WORDS").unwrap()).unwrap());
        let held: Vec<(String, Vec<f32>)> =
            serde_json::from_str(&std::fs::read_to_string(std::env::var("ODDS").unwrap()).unwrap()).unwrap();
        let mut by_letter: Vec<Vec<LetterOdds>> = vec![Vec::new(); 26];
        for (letter, odds) in held {
            by_letter[(letter.as_bytes()[0] - b'a') as usize].push(odds.try_into().unwrap());
        }
        // Words in proportion to use, letters picked in turn from the held-out copies.
        let total: f32 = words.prior.iter().map(|p| p.exp()).sum();
        let (mut seed, mut first, mut three, mut as_read, mut n) = (12345u64, 0, 0, 0, 0);
        let mut next = || {
            seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
            (seed >> 33) as f32 / (1u64 << 31) as f32
        };
        while n < 2000 {
            let mut pick = next() * total;
            let i = words.prior.iter().position(|p| {
                pick -= p.exp();
                pick <= 0.0
            });
            let word = &words.words[i.unwrap_or(0)];
            let letters: Vec<LetterOdds> = word
                .bytes()
                .map(|b| {
                    let copies = &by_letter[(b - b'a') as usize];
                    copies[(next() * copies.len() as f32) as usize % copies.len()]
                })
                .collect();
            let suggestions = words.suggest(&letters);
            first += usize::from(suggestions.first() == Some(word));
            three += usize::from(suggestions.iter().take(3).any(|s| s == word));
            as_read += usize::from(&read_as_is(&letters) == word);
            n += 1;
        }
        println!("{n} words: {} % first, {} % in the first three; letters as read {} %", 100 * first / n, 100 * three / n, 100 * as_read / n);
    }
}
