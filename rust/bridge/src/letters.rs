//! Letter recognition (debug): each written letter's sEMG as a short series of features,
//! matched by dynamic time warping against letters already recorded.
//!
//! Features, 25 frames a second: per channel, the signal in two bands (20–120 and 120–450 Hz,
//! a 2nd-order Butterworth high-pass then low-pass each), rectified and averaged over the
//! frame, log(1 + x). A letter is trimmed to its active part and each frame's mean taken out,
//! so the pattern across channels counts rather than how hard it was written. Tuned offline
//! (`scripts/handwriting.py` recordings): with 3 earlier copies of each letter, 51 % read right
//! first time and 68 % among the first three.

use std::collections::VecDeque;
use std::fs;
use std::path::Path;

use serde::Serialize;

const FRAMES_PER_S: usize = 25;
const BANDS: [(f64, f64); 2] = [(20.0, 120.0), (120.0, 450.0)];
/// Live frames kept for the letter being written.
const KEEP_S: f64 = 30.0;
/// Frames kept either side of a letter's active part.
const MARGIN: usize = 3;
/// How many guesses a letter gets.
const GUESSES: usize = 5;

/// A letter: its frames, each a value per band and channel.
pub type Letter = Vec<Vec<f32>>;

/// Features from a stream of sEMG batches.
pub struct Features {
    rate: f64,
    channels: usize,
    step: usize,
    /// Per band and channel: high-pass then low-pass.
    filters: Vec<[Biquad; 2]>,
    origin: Vec<f64>,
    sums: Vec<f64>,
    count: usize,
    frames: VecDeque<(f64, Vec<f32>)>,
    keep: Option<f64>,
}

impl Features {
    /// `keep`: seconds of frames to keep (`None`: all).
    pub fn new(rate: f64, channels: usize, keep: Option<f64>) -> Self {
        let filters = BANDS
            .iter()
            .flat_map(|&(low, high)| {
                (0..channels).map(move |_| [Biquad::high_pass(low, rate), Biquad::low_pass(high, rate)])
            })
            .collect();
        Self {
            rate,
            channels,
            step: (rate as usize / FRAMES_PER_S).max(1),
            filters,
            origin: Vec::new(),
            sums: vec![0.0; BANDS.len() * channels],
            count: 0,
            frames: VecDeque::new(),
            keep,
        }
    }

    /// For letters as they're written: the last [`KEEP_S`] kept.
    pub fn live(rate: f64, channels: usize) -> Self {
        Self::new(rate, channels, Some(KEEP_S))
    }

    /// One batch, sample-major; `time` is when its last sample was taken.
    pub fn push(&mut self, values: &[u16], time: f64) {
        let channels = self.channels;
        if channels == 0 {
            return;
        }
        if self.origin.is_empty() && values.len() >= channels {
            self.origin = values[..channels].iter().map(|&v| f64::from(v)).collect();
        }
        let samples = values.len() / channels;
        for (i, sample) in values.chunks_exact(channels).enumerate() {
            for (band, filters) in self.filters.chunks_exact_mut(channels).enumerate() {
                for (channel, filter) in filters.iter_mut().enumerate() {
                    let x = f64::from(sample[channel]) - self.origin[channel];
                    let high = filter[0].step(x);
                    let y = filter[1].step(high);
                    self.sums[band * channels + channel] += y.abs();
                }
            }
            self.count += 1;
            if self.count == self.step {
                let at = time - (samples - 1 - i) as f64 / self.rate;
                let frame = self.sums.iter().map(|&sum| (sum / self.step as f64).ln_1p() as f32).collect();
                self.frames.push_back((at, frame));
                self.sums.iter_mut().for_each(|sum| *sum = 0.0);
                self.count = 0;
            }
        }
        if let Some(keep) = self.keep {
            while self.frames.front().is_some_and(|(at, _)| *at < time - keep) {
                self.frames.pop_front();
            }
        }
    }

    /// The frames that ended in `start..end`, shaped for matching (empty when too few).
    pub fn letter(&self, start: f64, end: f64) -> Letter {
        let frames: Letter = self
            .frames
            .iter()
            .filter(|(at, _)| (start..end).contains(at))
            .map(|(_, frame)| frame.clone())
            .collect();
        shape(frames)
    }
}

/// The active part (frames over the median energy, with a margin), each frame's mean removed.
fn shape(frames: Letter) -> Letter {
    if frames.len() < 2 {
        return Vec::new();
    }
    let energy: Vec<f32> = frames.iter().map(|frame| frame.iter().sum()).collect();
    let mut sorted = energy.clone();
    sorted.sort_by(f32::total_cmp);
    let n = sorted.len();
    let median = if n % 2 == 1 { sorted[n / 2] } else { (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0 };
    let active: Vec<usize> = (0..n).filter(|&i| energy[i] > median).collect();
    let (first, last) = match (active.first(), active.last()) {
        (Some(&first), Some(&last)) => (first.saturating_sub(MARGIN), (last + MARGIN + 1).min(n)),
        _ => (0, n),
    };
    frames[first..last]
        .iter()
        .map(|frame| {
            let mean = frame.iter().sum::<f32>() / frame.len() as f32;
            frame.iter().map(|v| v - mean).collect()
        })
        .collect()
}

#[derive(Clone, Copy)]
struct Biquad {
    b: [f64; 3],
    a: [f64; 2],
    z: [f64; 2],
}

impl Biquad {
    /// Butterworth (Q = 1/√2), from the audio EQ cookbook.
    fn new(high: bool, cutoff: f64, rate: f64) -> Self {
        let w = std::f64::consts::TAU * cutoff / rate;
        let (cos, alpha) = (w.cos(), w.sin() / std::f64::consts::SQRT_2);
        let a0 = 1.0 + alpha;
        let b = if high {
            [(1.0 + cos) / 2.0, -(1.0 + cos), (1.0 + cos) / 2.0]
        } else {
            [(1.0 - cos) / 2.0, 1.0 - cos, (1.0 - cos) / 2.0]
        };
        Self {
            b: b.map(|v| v / a0),
            a: [-2.0 * cos / a0, (1.0 - alpha) / a0],
            z: [0.0; 2],
        }
    }

    fn high_pass(cutoff: f64, rate: f64) -> Self {
        Self::new(true, cutoff, rate)
    }

    fn low_pass(cutoff: f64, rate: f64) -> Self {
        Self::new(false, cutoff, rate)
    }

    fn step(&mut self, x: f64) -> f64 {
        let y = self.b[0] * x + self.z[0];
        self.z[0] = self.b[1] * x - self.a[0] * y + self.z[1];
        self.z[1] = self.b[2] * x - self.a[1] * y;
        y
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct Guess {
    pub letter: String,
    pub distance: f32,
}

/// Known letters: from earlier recordings, and those written since.
#[derive(Default)]
pub struct Letters {
    known: Vec<(String, Letter)>,
    /// Where the letters written since start (to forget the latest of one).
    first_new: usize,
}

impl Letters {
    /// Every recording in `root` (folders with emg.bin and marks.csv); unreadable ones are skipped.
    pub fn load(root: &Path) -> Self {
        let mut known = Vec::new();
        let mut folders: Vec<_> = fs::read_dir(root)
            .map(|entries| entries.flatten().map(|entry| entry.path()).collect())
            .unwrap_or_default();
        folders.sort();
        for folder in folders {
            if let Ok(letters) = read_recording(&folder) {
                known.extend(letters);
            }
        }
        let first_new = known.len();
        Self { known, first_new }
    }

    pub fn len(&self) -> usize {
        self.known.len()
    }

    pub fn is_empty(&self) -> bool {
        self.known.is_empty()
    }

    /// The nearest letters to `letter`, nearest first, each by its closest copy.
    pub fn rank(&self, letter: &[Vec<f32>]) -> Vec<Guess> {
        let mut best: Vec<Guess> = Vec::new();
        for (label, known) in &self.known {
            let distance = dtw(letter, known);
            match best.iter_mut().find(|guess| &guess.letter == label) {
                Some(guess) => guess.distance = guess.distance.min(distance),
                None => best.push(Guess { letter: label.clone(), distance }),
            }
        }
        best.sort_by(|a, b| a.distance.total_cmp(&b.distance));
        best.truncate(GUESSES);
        best
    }

    pub fn learn(&mut self, label: &str, letter: Letter) {
        if !letter.is_empty() {
            self.known.push((label.to_owned(), letter));
        }
    }

    /// The latest `label` written since start was wrong.
    pub fn forget(&mut self, label: &str) {
        if let Some(i) = (self.first_new..self.known.len()).rev().find(|&i| self.known[i].0 == label) {
            self.known.remove(i);
        }
    }
}

/// Mean squared distance along the best alignment.
fn dtw(a: &[Vec<f32>], b: &[Vec<f32>]) -> f32 {
    if a.is_empty() || b.is_empty() {
        return f32::INFINITY;
    }
    let mut previous = vec![f32::INFINITY; b.len() + 1];
    let mut row = vec![f32::INFINITY; b.len() + 1];
    previous[0] = 0.0;
    for x in a {
        row[0] = f32::INFINITY;
        for (j, y) in b.iter().enumerate() {
            let cost: f32 = x.iter().zip(y).map(|(p, q)| (p - q) * (p - q)).sum();
            row[j + 1] = cost + previous[j + 1].min(row[j]).min(previous[j]);
        }
        std::mem::swap(&mut previous, &mut row);
    }
    previous[b.len()] / (a.len() + b.len()) as f32
}

/// The letters written in one recording (see `recorder`), those marked "discard" left out.
fn read_recording(folder: &Path) -> Result<Vec<(String, Letter)>, String> {
    let data = fs::read(folder.join("emg.bin")).map_err(|e| e.to_string())?;
    let end = data.iter().position(|&b| b == b'\n').ok_or("no header")?;
    let header = std::str::from_utf8(&data[..end]).map_err(|e| e.to_string())?;
    let fields: Vec<&str> = header.split_whitespace().collect();
    let [magic, rate, channels, per_batch] = fields[..] else {
        return Err("bad header".into());
    };
    if magic != "AGEMG1" {
        return Err("not a recording".into());
    }
    let parse = |v: &str| v.parse::<usize>().map_err(|e| e.to_string());
    let (rate, channels, per_batch) = (parse(rate)?, parse(channels)?, parse(per_batch)?);
    let mut features = Features::new(rate as f64, channels, None);
    let size = 24 + per_batch * channels * 2;
    let mut values = vec![0u16; per_batch * channels];
    for record in data[end + 1..].chunks_exact(size) {
        let time = f64::from_le_bytes(record[..8].try_into().expect("8 bytes"));
        for (value, bytes) in values.iter_mut().zip(record[24..].chunks_exact(2)) {
            *value = u16::from_le_bytes([bytes[0], bytes[1]]);
        }
        features.push(&values, time);
    }

    let marks = fs::read_to_string(folder.join("marks.csv")).map_err(|e| e.to_string())?;
    let mut windows: Vec<(String, f64, f64)> = Vec::new();
    let mut started: Vec<(String, f64)> = Vec::new();
    for line in marks.lines().skip(1) {
        let mut parts = line.splitn(3, ',');
        let (Some(time), Some(event), Some(label)) = (parts.next(), parts.next(), parts.next()) else {
            continue;
        };
        let Ok(time) = time.parse::<f64>() else { continue };
        match event {
            "start" => started.push((label.to_owned(), time)),
            "end" => {
                if let Some(i) = started.iter().position(|(l, _)| l == label) {
                    let (_, start) = started.remove(i);
                    windows.push((label.to_owned(), start, time));
                }
            }
            "discard" => {
                if let Some(i) = windows.iter().rposition(|(l, ..)| l == label) {
                    windows.remove(i);
                }
            }
            _ => {}
        }
    }
    Ok(windows
        .into_iter()
        .map(|(label, start, end)| (label, features.letter(start, end)))
        .filter(|(_, letter)| !letter.is_empty())
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_letter_matches_its_own_copy_first() {
        let up: Vec<Vec<f32>> = (0..10).map(|i| vec![i as f32, 0.0]).collect();
        let down: Vec<Vec<f32>> = (0..10).map(|i| vec![0.0, i as f32]).collect();
        let mut letters = Letters::default();
        letters.learn("a", shape(up.clone()));
        letters.learn("b", shape(down));
        // The same shape, written a little slower.
        let slower: Vec<Vec<f32>> = (0..14).map(|i| vec![i as f32 * 10.0 / 14.0, 0.0]).collect();
        let guesses = letters.rank(&shape(slower));
        assert_eq!(guesses[0].letter, "a");
        assert!(guesses[0].distance < guesses[1].distance);
        letters.forget("a");
        assert_eq!(letters.rank(&shape(up))[0].letter, "b");
    }

    #[test]
    fn frames_come_25_a_second_and_the_bands_pass_their_frequencies() {
        let rate = 2048.0;
        let mut features = Features::new(rate, 1, None);
        // One second of 60 Hz, then one of 300 Hz, in batches of 16.
        let samples: Vec<u16> = (0..4096)
            .map(|i| {
                let f = if i < 2048 { 60.0 } else { 300.0 };
                (1000.0 + 500.0 * (std::f64::consts::TAU * f * i as f64 / rate).sin()) as u16
            })
            .collect();
        for (n, batch) in samples.chunks(16).enumerate() {
            features.push(batch, (n + 1) as f64 * 16.0 / rate);
        }
        assert_eq!(features.frames.len(), 4096 / 81);
        let middle = |t: f64| &features.frames.iter().find(|(at, _)| *at >= t).unwrap().1;
        let (low, high) = (middle(0.5), middle(1.5));
        assert!(low[0] > low[1] + 1.0, "60 Hz in the low band: {low:?}");
        assert!(high[1] > high[0] + 1.0, "300 Hz in the high band: {high:?}");
    }
}

/// Offline check on real recordings: `HANDWRITING=<folder of recordings> cargo test -p bridge
/// --release -- --ignored --nocapture read_back`. Each letter read against all the others.
#[cfg(test)]
mod recordings {
    use super::*;

    #[test]
    #[ignore]
    fn read_back() {
        let root = std::env::var("HANDWRITING").expect("HANDWRITING=<folder>");
        let all = Letters::load(Path::new(&root)).known;
        let (mut first, mut three) = (0, 0);
        for (i, (label, letter)) in all.iter().enumerate() {
            let others = Letters { known: all.iter().enumerate().filter(|&(j, _)| j != i).map(|(_, k)| k.clone()).collect(), first_new: 0 };
            let guesses: Vec<String> = others.rank(letter).into_iter().map(|g| g.letter).collect();
            first += usize::from(guesses.first() == Some(label));
            three += usize::from(guesses.iter().take(3).any(|g| g == label));
        }
        let n = all.len().max(1);
        println!("{} letters: {} % first, {} % in the first three", all.len(), 100 * first / n, 100 * three / n);
    }
}
