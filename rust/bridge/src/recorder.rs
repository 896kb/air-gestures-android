//! Handwriting recordings (debug): the band's raw sEMG and labelled marks, written to a
//! folder for training a letter recogniser off the phone.
//!
//! `emg.bin`: the header line `AGEMG1 <sample_rate> <channels> <samples_per_batch>\n`, then
//! one fixed-size record per batch: `f64` host time (s), `u64` sequence, `u64` band time (µs),
//! then samples × channels `u16`, sample-major, all little-endian.
//! `marks.csv`: `time,event,label` (host time on the same clock): prompts, the writing window,
//! gestures the band reported.
//! Letters written are also read as they end (see `letters`), against the earlier recordings
//! beside this one and those written since. In words mode they go to a [`Writer`]; when a
//! word is taken whole its letters are marked `wstart`/`wend` with the word's letters, so
//! free writing labels itself.

use std::fs::{File, create_dir_all};
use std::io::{BufWriter, Write};
use std::path::Path;

use band_core::events::{EmgBatch, EmgConfig};
use serde::Serialize;

use crate::letters::{Features, Guess, Letters};
use crate::words::{Dictionary, Writer, WriterState};

pub struct Recorder {
    detector: Detector,
    emg: BufWriter<File>,
    marks: BufWriter<File>,
    config: Option<EmgConfig>,
    stats: Stats,
    last_sequence: Option<u64>,
    features: Option<Features>,
    letters: Letters,
    writer: Writer,
    /// When each letter of the word under way was written.
    word_windows: Vec<(f64, f64)>,
}

/// What the recording screen shows: how much arrived and how much the band dropped.
#[derive(Debug, Default, Clone, Serialize)]
pub struct Stats {
    pub batches: u64,
    /// Batches missing by sequence number (dropped by the band or the link).
    pub missing: u64,
    /// Batches that couldn't be read (an unsupported layout); not written.
    pub unreadable: u64,
    pub marks: u64,
    /// The writing detector: muscles active now, the envelope over the resting level, and
    /// when activity last began and ended (host time, s).
    pub active: bool,
    pub level: f64,
    pub onset: Option<f64>,
    pub offset: Option<f64>,
    /// Letters known to read new ones against, and whether a trained model reads them instead.
    pub known: usize,
    pub model: bool,
    /// Sensors (1-8) resting far noisier than the others: poor skin contact, fix the fit.
    pub noisy: Vec<usize>,
}

impl Recorder {
    /// Start a recording in `dir` (created if needed).
    pub fn create(dir: &str) -> Result<Self, String> {
        let dir = Path::new(dir);
        let letters = dir.parent().map(Letters::load).unwrap_or_default();
        create_dir_all(dir).map_err(|e| format!("{}: {e}", dir.display()))?;
        let open = |name: &str| {
            File::create(dir.join(name))
                .map(BufWriter::new)
                .map_err(|e| format!("{name}: {e}"))
        };
        let mut marks = open("marks.csv")?;
        writeln!(marks, "time,event,label").map_err(|e| e.to_string())?;
        Ok(Self {
            detector: Detector::default(),
            emg: open("emg.bin")?,
            marks,
            config: None,
            stats: Stats { known: letters.len(), model: letters.has_model(), ..Stats::default() },
            last_sequence: None,
            features: None,
            letters,
            writer: Writer::default(),
            word_windows: Vec::new(),
        })
    }

    /// The band's EMG layout; the header goes out with the first one.
    pub fn configure(&mut self, config: EmgConfig) -> Result<(), String> {
        if self.config.is_none() {
            writeln!(
                self.emg,
                "AGEMG1 {} {} {}",
                config.sample_rate, config.channels, config.samples_per_batch
            )
            .map_err(|e| e.to_string())?;
        }
        if self.config != Some(config) {
            self.features = Some(Features::live(config.sample_rate as f64, config.channels as usize));
        }
        self.config = Some(config);
        Ok(())
    }

    /// One raw sEMG batch as the band sent it, received at `now`.
    pub fn batch(&mut self, payload: &[u8], now: f64) -> Result<(), String> {
        let Some(config) = self.config else {
            self.stats.unreadable += 1;
            return Ok(());
        };
        let Ok(batch) = EmgBatch::parse(payload, &config) else {
            self.stats.unreadable += 1;
            return Ok(());
        };
        let gap = self.last_sequence.is_some_and(|last| batch.sequence > last + 1);
        if let Some(last) = self.last_sequence
            && gap
        {
            self.stats.missing += batch.sequence - last - 1;
        }
        self.last_sequence = Some(batch.sequence);
        self.stats.batches += 1;
        let rate = config.sample_rate as f64;
        let channels = config.channels as usize;
        if let Some(change) = self.detector.batch(&batch.values, channels, rate, gap) {
            if change {
                self.stats.onset = Some(now);
            } else {
                self.stats.offset = Some(now);
            }
        }
        self.stats.active = self.detector.active;
        self.stats.level = self.detector.level();
        if !self.detector.active {
            self.stats.noisy = self.detector.noisy();
        }
        if let Some(features) = &mut self.features {
            features.push(&batch.values, now);
        }
        let mut record = Vec::with_capacity(24 + batch.values.len() * 2);
        record.extend(now.to_le_bytes());
        record.extend(batch.sequence.to_le_bytes());
        record.extend(batch.timestamp_us.to_le_bytes());
        for value in &batch.values {
            record.extend(value.to_le_bytes());
        }
        self.emg.write_all(&record).map_err(|e| e.to_string())
    }

    /// A labelled moment: `prompt a`, `start a`, `end a`, `gesture index tap`, …
    pub fn mark(&mut self, event: &str, label: &str, now: f64) -> Result<(), String> {
        self.stats.marks += 1;
        if event == "discard" {
            self.letters.forget(label);
            self.stats.known = self.letters.len();
        }
        let clean = |text: &str| text.replace([',', '\n', '\r'], " ");
        writeln!(self.marks, "{now:.6},{},{}", clean(event), clean(label)).map_err(|e| e.to_string())
    }

    /// Read the letter written in `start..end` (host time): the nearest known letters, nearest
    /// first (none when too little arrived). With a `label` it's known from then on.
    pub fn guess(&mut self, label: &str, start: f64, end: f64) -> Vec<Guess> {
        let Some(features) = &self.features else { return Vec::new() };
        let letter = features.letter(start, end);
        if letter.is_empty() {
            return Vec::new();
        }
        let guesses = self.letters.rank(&letter);
        if !label.is_empty() {
            self.letters.learn(label, letter);
            self.stats.known = self.letters.len();
        }
        guesses
    }

    /// Words mode: the letter written in `start..end` joins the word under way; a stroke or
    /// mark (a line right for space, left to delete, punctuation) does what it stands for.
    pub fn write(&mut self, start: f64, end: f64, words: Option<&Dictionary>) -> WriterState {
        let Some(features) = &self.features else { return self.writer.state() };
        let letter = features.letter(start, end);
        if letter.is_empty() {
            return self.writer.state();
        }
        match self.letters.symbol(&letter).as_deref() {
            Some(edit @ ("space" | "backspace")) => return self.edit(edit, end, words),
            Some(mark @ ("period" | "comma" | "question")) => {
                let _ = self.mark("edit", mark, end);
                self.take_word(end);
                self.writer.punctuate(
                    match mark {
                        "period" => '.',
                        "comma" => ',',
                        _ => '?',
                    },
                    words,
                );
            }
            _ => {
                self.writer.letter(self.letters.odds(&letter), words);
                self.word_windows.push((start, end));
            }
        }
        self.writer.state()
    }

    /// The highlighted word is being taken: mark it, and its letters when it's as long.
    fn take_word(&mut self, now: f64) {
        let word = self.writer.state().word;
        if !word.is_empty() {
            let _ = self.mark("word", &word, now);
        }
        if word.len() == self.word_windows.len() {
            for (letter, (start, end)) in word.chars().zip(std::mem::take(&mut self.word_windows)) {
                let _ = self.mark("wstart", &letter.to_string(), start);
                let _ = self.mark("wend", &letter.to_string(), end);
            }
        }
        self.word_windows.clear();
    }

    /// Words mode: `space`, `backspace`, `next`, `previous` or `clear` (anything else: nothing).
    pub fn edit(&mut self, edit: &str, now: f64, words: Option<&Dictionary>) -> WriterState {
        let _ = self.mark("edit", edit, now);
        match edit {
            "space" => {
                self.take_word(now);
                self.writer.space(words);
            }
            "backspace" => {
                self.word_windows.pop();
                self.writer.backspace(words);
            }
            "next" => self.writer.step(true),
            "previous" => self.writer.step(false),
            "clear" => {
                self.word_windows.clear();
                self.writer.clear();
            }
            _ => {}
        }
        self.writer.state()
    }

    pub fn stats(&self) -> &Stats {
        &self.stats
    }

    pub fn finish(mut self) -> Result<Stats, String> {
        self.emg.flush().map_err(|e| e.to_string())?;
        self.marks.flush().map_err(|e| e.to_string())?;
        Ok(self.stats)
    }
}

/// Writing detection from the sEMG envelope, causal and per batch: each channel's drift is
/// taken out (a 20 ms mean), the rectified rest averaged over channels and smoothed (50 ms);
/// the resting level follows it slowly (3 s) while nothing is written. Writing starts when the
/// envelope passes [`ON`] × the resting level and ends below [`OFF`] ×. Tuned offline on
/// recorded sessions: every letter seen, active during ~3 % of the waits between them.
/// After batches went missing the drift restarts from the next sample (the jump across the
/// gap isn't muscle), and activity lasting over [`STUCK_S`] is taken as the new resting level
/// (the band shifting on the wrist, not a letter).
#[derive(Default)]
struct Detector {
    mean: Vec<f64>,
    /// Each channel's activity, and its resting level.
    envelope: Vec<f64>,
    rest: Vec<f64>,
    active: bool,
    /// How long the muscles have been active (s).
    active_for: f64,
}

const ON: f64 = 2.5;
const OFF: f64 = 1.6;
const DRIFT_S: f64 = 0.02;
const ENVELOPE_S: f64 = 0.05;
const REST_S: f64 = 3.0;
const STUCK_S: f64 = 4.0;
/// A sensor resting this many times noisier than the middle one has poor contact (a good fit
/// keeps them within about 3).
const NOISY: f64 = 6.0;

impl Detector {
    /// One batch (sample-major); `Some(true)` when writing started, `Some(false)` when it stopped.
    /// Each channel is measured against its own resting level and the middle of those counts,
    /// so one electrode with poor contact (noise many times the others) can't hide the writing.
    fn batch(&mut self, values: &[u16], channels: usize, rate: f64, gap: bool) -> Option<bool> {
        if channels == 0 || values.len() < channels {
            return None;
        }
        let samples = values.len() / channels;
        let first = self.mean.len() != channels;
        if first || gap {
            self.mean = values[..channels].iter().map(|&v| f64::from(v)).collect();
        }
        let drift = 1.0 - (-1.0 / (rate * DRIFT_S)).exp();
        let mut energy = vec![0.0; channels];
        for sample in values.chunks_exact(channels) {
            for ((mean, &value), energy) in self.mean.iter_mut().zip(sample).zip(&mut energy) {
                let value = f64::from(value);
                *mean += (value - *mean) * drift;
                *energy += (value - *mean).abs();
            }
        }
        energy.iter_mut().for_each(|e| *e /= samples as f64);
        let dt = samples as f64 / rate;
        if first {
            self.rest = energy.iter().map(|e| e.max(1e-6)).collect();
            self.envelope = energy;
            return None;
        }
        let follow = 1.0 - (-dt / ENVELOPE_S).exp();
        let settle = 1.0 - (-dt / REST_S).exp();
        for ((envelope, rest), energy) in self.envelope.iter_mut().zip(&mut self.rest).zip(&energy) {
            *envelope += (energy - *envelope) * follow;
            if !self.active && *envelope < *rest * 1.2 {
                *rest = (*rest + (*envelope - *rest) * settle).max(1e-6);
            }
        }
        let level = self.level();
        if !self.active && level > ON {
            self.active = true;
            self.active_for = 0.0;
            return Some(true);
        }
        if self.active {
            self.active_for += dt;
            if self.active_for > STUCK_S {
                self.rest = self.envelope.iter().map(|e| e.max(1e-6)).collect();
            }
            if self.level() < OFF {
                self.active = false;
                return Some(false);
            }
        }
        None
    }

    /// The sensors (numbered from 1) resting [`NOISY`] times above the middle one.
    fn noisy(&self) -> Vec<usize> {
        let mut rest = self.rest.clone();
        if rest.is_empty() {
            return Vec::new();
        }
        rest.sort_by(f64::total_cmp);
        let middle = (rest[(rest.len() - 1) / 2] + rest[rest.len() / 2]) / 2.0;
        self.rest.iter().enumerate().filter(|(_, r)| **r > middle * NOISY).map(|(i, _)| i + 1).collect()
    }

    /// The middle channel's activity over its resting level.
    fn level(&self) -> f64 {
        let mut ratios: Vec<f64> = self.envelope.iter().zip(&self.rest).map(|(e, r)| e / r).collect();
        if ratios.is_empty() {
            return 0.0;
        }
        ratios.sort_by(f64::total_cmp);
        let n = ratios.len();
        (ratios[(n - 1) / 2] + ratios[n / 2]) / 2.0
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A batch of 16 samples × 8 channels alternating ±`swing` around 1000.
    fn batch(swing: u16, phase: usize) -> Vec<u16> {
        (0..128).map(|i| if (i / 8 + phase) % 2 == 0 { 1000 + swing } else { 1000 - swing }).collect()
    }

    #[test]
    fn a_burst_over_the_resting_level_starts_and_ends_writing() {
        let mut detector = Detector::default();
        let mut changes = Vec::new();
        let mut feed = |swing, batches| {
            for i in 0..batches {
                if let Some(change) = detector.batch(&batch(swing, i), 8, 2048.0, false) {
                    changes.push(change);
                }
            }
        };
        feed(10, 400); // 3 s of rest
        feed(80, 60); // half a second of writing
        feed(10, 200); // rest again
        assert_eq!(changes, vec![true, false]);
    }

    #[test]
    fn activity_that_never_ends_becomes_the_resting_level() {
        let mut detector = Detector::default();
        let mut changes = Vec::new();
        for (swing, batches) in [(10, 400), (80, 1200), (80, 200), (240, 60), (80, 200)] {
            for i in 0..batches {
                if let Some(change) = detector.batch(&batch(swing, i), 8, 2048.0, false) {
                    changes.push(change);
                }
            }
        }
        // Stuck on, then let go; writing over the new level is still seen.
        assert_eq!(changes, vec![true, false, true, false]);
    }

    #[test]
    fn one_noisy_channel_does_not_hide_the_writing() {
        // Channel 7 with poor contact: noise 50 times the others all along.
        let noisy = |swing: u16, phase: usize| -> Vec<u16> {
            let mut values = batch(swing, phase);
            for (i, value) in values.iter_mut().enumerate().filter(|(i, _)| i % 8 == 6) {
                *value = if (i / 8 + phase) % 2 == 0 { 1500 } else { 500 };
            }
            values
        };
        let mut detector = Detector::default();
        let mut changes = Vec::new();
        for (swing, batches) in [(10, 400), (80, 60), (10, 200)] {
            for i in 0..batches {
                if let Some(change) = detector.batch(&noisy(swing, i), 8, 2048.0, false) {
                    changes.push(change);
                }
            }
        }
        assert_eq!(changes, vec![true, false]);
        assert_eq!(detector.noisy(), vec![7]);
    }
}
