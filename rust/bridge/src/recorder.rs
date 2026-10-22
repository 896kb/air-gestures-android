//! Handwriting recordings (debug): the band's raw sEMG and labelled marks, written to a
//! folder for training a letter recogniser off the phone.
//!
//! `emg.bin`: the header line `AGEMG1 <sample_rate> <channels> <samples_per_batch>\n`, then
//! one fixed-size record per batch: `f64` host time (s), `u64` sequence, `u64` band time (µs),
//! then samples × channels `u16`, sample-major, all little-endian.
//! `marks.csv`: `time,event,label` (host time on the same clock): prompts, the writing window,
//! gestures the band reported.

use std::fs::{File, create_dir_all};
use std::io::{BufWriter, Write};
use std::path::Path;

use band_core::events::{EmgBatch, EmgConfig};
use serde::Serialize;

pub struct Recorder {
    emg: BufWriter<File>,
    marks: BufWriter<File>,
    config: Option<EmgConfig>,
    stats: Stats,
    last_sequence: Option<u64>,
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
}

impl Recorder {
    /// Start a recording in `dir` (created if needed).
    pub fn create(dir: &str) -> Result<Self, String> {
        let dir = Path::new(dir);
        create_dir_all(dir).map_err(|e| format!("{}: {e}", dir.display()))?;
        let open = |name: &str| {
            File::create(dir.join(name))
                .map(BufWriter::new)
                .map_err(|e| format!("{name}: {e}"))
        };
        let mut marks = open("marks.csv")?;
        writeln!(marks, "time,event,label").map_err(|e| e.to_string())?;
        Ok(Self {
            emg: open("emg.bin")?,
            marks,
            config: None,
            stats: Stats::default(),
            last_sequence: None,
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
        if let Some(last) = self.last_sequence
            && batch.sequence > last + 1
        {
            self.stats.missing += batch.sequence - last - 1;
        }
        self.last_sequence = Some(batch.sequence);
        self.stats.batches += 1;
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
        let clean = |text: &str| text.replace([',', '\n', '\r'], " ");
        writeln!(self.marks, "{now:.6},{},{}", clean(event), clean(label)).map_err(|e| e.to_string())
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
