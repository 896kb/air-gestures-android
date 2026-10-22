//! The Android app's view of a band connection: bytes in, bytes out, plus
//! action names to run and a status snapshot. Kotlin owns the Bluetooth socket
//! and the clock; this owns the band-core session, the keepalive timing (as the
//! desktop daemon's link loop does) and the gesture controller (copied from
//! air-gestures: held taps, the double-tap wait, the toggle hold, the dial).
//! Also the Meta sign-in and the ownership claim (`metaauth`, `meta`, `claim`),
//! ported from the Linux app.

use band_core::events::Event;
use band_core::identity::EnrollmentIdentity;
use band_core::session::BandSession;
use serde::Serialize;

pub mod claim;
pub mod config;
pub mod controller;
pub mod firmware;
mod jni_api;
pub mod meta;
pub mod metaauth;
pub mod letters;
pub mod recorder;
pub mod status;
pub mod words;

use config::{Config, DIAL_TOGGLE, DialTarget};
use controller::{Command, Controller};
use status::Status;

fn config_with(mapping: &str) -> Config {
    let mut config = Config::default();
    config.apply_mapping(mapping);
    config
}

/// Battery query period and the quiet time before a stream-state query.
const BATTERY_EVERY: f64 = 5.0;
const QUIET_QUERY: f64 = 2.0;
/// How often the raw sEMG rate goes to the log while it streams (debug).
const EMG_RATE_EVERY: f64 = 5.0;

pub struct Connection {
    session: BandSession,
    controller: Controller,
    live: bool,
    last_read: f64,
    last_query: f64,
    next_battery: f64,
    actions: Vec<String>,
    log: Vec<String>,
    /// The stream probe is on: the session's notes go to the log.
    probing: bool,
    /// Raw sEMG frames and bytes since the last rate line, and when that was (debug).
    emg_frames: usize,
    emg_bytes: usize,
    emg_since: f64,
    /// The band's sEMG layout, once it reported one.
    emg_config: Option<band_core::events::EmgConfig>,
    /// A handwriting recording in progress (debug).
    recorder: Option<recorder::Recorder>,
}

#[derive(Serialize)]
struct Snapshot<'a> {
    connected: bool,
    #[serde(flatten)]
    status: &'a Status,
}

impl Connection {
    /// `owner_key` is the desktop's `owner.key` (32 or 97 bytes); `scheme_guess`
    /// its `band.json` value (0 for current firmware). `paused` and `dial` carry
    /// the app's settings over from the previous connection.
    pub fn new(
        owner_key: &[u8],
        scheme_guess: usize,
        paused: bool,
        dial: &str,
        mapping: &str,
    ) -> Result<Self, String> {
        let (private, band) = match owner_key.len() {
            32 => (owner_key, None),
            97 => (&owner_key[..32], Some(&owner_key[32..])),
            length => return Err(format!("owner.key must be 32 or 97 bytes, not {length}")),
        };
        let identity = EnrollmentIdentity::from_bytes(private, band).map_err(|e| e.to_string())?;
        let mut controller = Controller::new(config_with(mapping));
        if paused {
            controller.pause();
        }
        // An empty `dial` keeps what the mapping chose.
        if !dial.is_empty() {
            controller.set_dial_target(DialTarget::from_name(dial));
        }
        Ok(Self {
            session: BandSession::new(Some(identity), None, false).with_scheme_guess(scheme_guess),
            controller,
            live: false,
            last_read: 0.0,
            last_query: 0.0,
            next_battery: 0.0,
            actions: Vec::new(),
            log: Vec::new(),
            probing: false,
            emg_frames: 0,
            emg_bytes: 0,
            emg_since: 0.0,
            emg_config: None,
            recorder: None,
        })
    }

    /// The first bytes to write once the L2CAP channel is open.
    pub fn request(&mut self) -> Result<Vec<u8>, String> {
        self.session.request().map_err(|e| e.to_string())
    }

    /// Bytes read from the band at `now` (seconds, monotonic); returns bytes to write.
    pub fn feed(&mut self, bytes: &[u8], now: f64) -> Result<Vec<u8>, String> {
        self.last_read = now;
        let result = self.session.feed(bytes, now).map_err(|e| e.to_string())?;
        let mut outgoing = result.outgoing;
        for event in &result.events {
            outgoing.extend(self.on_event(event, now)?);
        }
        Ok(outgoing)
    }

    /// Call every ~50 ms (held single taps fire on time); returns bytes to write.
    pub fn tick(&mut self, now: f64) -> Result<Vec<u8>, String> {
        let mut outgoing = Vec::new();
        for event in self.session.tick(now) {
            outgoing.extend(self.on_event(&event, now)?);
        }
        if self.controller.held_tap_due().is_some_and(|due| now >= due) {
            for command in self.controller.fire_held_tap(now) {
                outgoing.extend(self.apply(command, now)?);
            }
        }
        if self.session.streams_enabled() && now >= self.next_battery {
            self.next_battery = now + BATTERY_EVERY;
            outgoing.extend(
                self.session
                    .query_battery_status(now)
                    .map_err(|e| e.to_string())?,
            );
        }
        if self.session.streams_enabled()
            && now - self.last_read >= QUIET_QUERY
            && now - self.last_query >= QUIET_QUERY
        {
            self.last_query = now;
            outgoing.extend(
                self.session
                    .query_stream_state()
                    .map_err(|e| e.to_string())?,
            );
        }
        Ok(outgoing)
    }

    /// Action names to run since the last call ("media.next", "volume.up", …).
    pub fn take_actions(&mut self) -> Vec<String> {
        std::mem::take(&mut self.actions)
    }

    /// Log lines since the last call ("connected", "gesture swipe left", …).
    pub fn take_log(&mut self) -> Vec<String> {
        if self.probing {
            self.log.extend(self.session.take_diagnostics());
        }
        std::mem::take(&mut self.log)
    }

    pub fn set_paused(&mut self, paused: bool, now: f64) {
        if paused {
            self.controller.pause();
        } else {
            self.controller.resume(now);
        }
    }

    /// Replace the gesture and dial mapping (see `Config::apply_mapping`).
    pub fn set_mapping(&mut self, mapping: &str) {
        let paused = self.controller.status().paused;
        for command in self.controller.set_config(config_with(mapping)) {
            if let Command::Run(action) = command {
                self.actions.push(action);
            }
        }
        // A new config resets the controller's pause state; keep the app's.
        if paused {
            self.controller.pause();
        }
    }

    /// Before the first `request`: also ask the band for these stream flags (comma-separated,
    /// e.g. `1,4,5,7`) and log what it sends that the session doesn't know. Empty = off.
    pub fn set_probe(&mut self, fields: &str) {
        let fields: Vec<u32> = fields.split(',').filter_map(|field| field.trim().parse().ok()).collect();
        self.probing = !fields.is_empty();
        self.session.set_probe(fields);
    }

    /// Probe only: write a ConfigReq given as hex (see `BandSession::probe_config`); returns
    /// the bytes to write. The answer and the config read back go to the log.
    pub fn probe_config(&mut self, hex: &str, now: f64) -> Result<Vec<u8>, String> {
        let hex: String = hex.chars().filter(|c| !c.is_whitespace()).collect();
        if hex.len() % 2 != 0 {
            return Err("odd number of hex digits".into());
        }
        let body = (0..hex.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&hex[i..i + 2], 16).map_err(|e| e.to_string()))
            .collect::<Result<Vec<u8>, String>>()?;
        self.probing = true;
        self.session.probe_config(&body, now).map_err(|e| e.to_string())
    }

    /// Debug: turn the band's raw sEMG stream on or off (alongside gestures); returns the
    /// bytes to write. While it runs, the log gets its rate every [`EMG_RATE_EVERY`] s.
    pub fn set_raw_emg(&mut self, on: bool, now: f64) -> Result<Vec<u8>, String> {
        self.emg_frames = 0;
        self.emg_bytes = 0;
        self.emg_since = now;
        self.log.push(format!("emg: asking for raw sEMG {}", if on { "on" } else { "off" }));
        self.session.set_raw_emg_enabled(on, now).map_err(|e| e.to_string())
    }

    /// Debug: record raw sEMG and marks into `dir` until [`record_stop`](Self::record_stop)
    /// (see `recorder`). Turn the sEMG stream on with [`set_raw_emg`](Self::set_raw_emg).
    pub fn record_start(&mut self, dir: &str) -> Result<(), String> {
        let mut recorder = recorder::Recorder::create(dir)?;
        if let Some(config) = self.emg_config {
            recorder.configure(config)?;
        }
        self.recorder = Some(recorder);
        self.log.push(format!("recording to {dir}"));
        Ok(())
    }

    /// A labelled moment in the recording (nothing when none runs).
    pub fn record_mark(&mut self, event: &str, label: &str, now: f64) -> Result<(), String> {
        match &mut self.recorder {
            Some(recorder) => recorder.mark(event, label, now),
            None => Ok(()),
        }
    }

    /// What the band said about itself (`{}` before it has): firmware, software, hardware,
    /// serial, release branch. For the firmware archive; the serial isn't logged.
    pub fn device_info(&self) -> String {
        self.session.device_info().map_or_else(
            || "{}".into(),
            |info| {
                serde_json::json!({
                    "firmware": info.firmware, "software": info.software, "hardware": info.hardware,
                    "serial": info.serial, "release_branch": info.release_branch,
                })
                .to_string()
            },
        )
    }

    /// The recording's counts as JSON (`{}` when none runs).
    pub fn record_stats(&self) -> String {
        self.recorder
            .as_ref()
            .map_or_else(|| "{}".into(), |recorder| serde_json::to_string(recorder.stats()).expect("stats serialize"))
    }

    /// The letter written in `start..end` read against the known ones, as JSON
    /// (`[{"letter", "distance"}…]`, nearest first; `[]` when none runs). With a `label`
    /// it's known from then on.
    pub fn record_guess(&mut self, label: &str, start: f64, end: f64) -> String {
        let guesses = self.recorder.as_mut().map(|recorder| recorder.guess(label, start, end)).unwrap_or_default();
        serde_json::to_string(&guesses).expect("guesses serialize")
    }

    /// Words mode: the letter written in `start..end` joins the word under way. The writer's
    /// state as JSON (`{}` when no recording runs).
    pub fn record_write(&mut self, start: f64, end: f64) -> String {
        let words = words::dictionary();
        let state = self.recorder.as_mut().map(|recorder| recorder.write(start, end, words.as_deref()));
        state.map_or_else(|| "{}".into(), |state| serde_json::to_string(&state).expect("state serializes"))
    }

    /// Words mode: `space`, `backspace`, `next`, `previous` or `clear`; the writer's state as
    /// for [`record_write`](Self::record_write).
    pub fn record_edit(&mut self, edit: &str, now: f64) -> String {
        let words = words::dictionary();
        let state = self.recorder.as_mut().map(|recorder| recorder.edit(edit, now, words.as_deref()));
        state.map_or_else(|| "{}".into(), |state| serde_json::to_string(&state).expect("state serializes"))
    }

    pub fn record_stop(&mut self) -> Result<(), String> {
        if let Some(recorder) = self.recorder.take() {
            let stats = recorder.finish()?;
            self.log.push(format!("recording saved: {stats:?}"));
        }
        Ok(())
    }

    pub fn set_dial(&mut self, dial: &str) {
        self.controller.set_dial_target(DialTarget::from_name(dial));
    }

    /// `{"connected":…, "paused":…, "battery":…, "charging":…, "hand":…, "dial":…,
    /// "last_gesture":…, "last_action":…, "gestures":…}`.
    pub fn status_json(&self) -> String {
        serde_json::to_string(&Snapshot {
            connected: self.live,
            status: self.controller.status(),
        })
        .expect("status serializes")
    }

    fn on_event(&mut self, event: &Event, now: f64) -> Result<Vec<u8>, String> {
        match event {
            Event::Connected => {
                self.live = true;
                self.log.push("connected".into());
            }
            Event::Handedness(hand) => self.log.push(format!("hand {hand:?}").to_lowercase()),
            Event::RawEmgConfiguration(config) => {
                self.log.push(format!("emg: {config:?}"));
                self.emg_config = Some(*config);
                if let Some(recorder) = &mut self.recorder {
                    recorder.configure(*config)?;
                }
            }
            Event::Gesture(message) => {
                if let Some(recorder) = &mut self.recorder {
                    let label = format!("{} {} {}", message.finger, message.action, message.derived_action);
                    recorder.mark("gesture", &label, now)?;
                }
            }
            Event::RawEmgState(on) => self.log.push(format!("emg: raw sEMG {}", if *on { "on" } else { "off" })),
            Event::RawEmgFailure(message) => self.log.push(format!("emg: {message}")),
            Event::RawEmgFrame(bytes) => {
                if let Some(recorder) = &mut self.recorder {
                    recorder.batch(bytes, now)?;
                }
                self.emg_frames += 1;
                self.emg_bytes += bytes.len();
                let span = now - self.emg_since;
                if span >= EMG_RATE_EVERY {
                    self.log.push(format!(
                        "emg: {:.0} frames/s, {:.1} kB/s, {} gestures so far",
                        self.emg_frames as f64 / span,
                        self.emg_bytes as f64 / span / 1000.0,
                        self.controller.status().gestures,
                    ));
                    self.emg_frames = 0;
                    self.emg_bytes = 0;
                    self.emg_since = now;
                }
            }
            _ => {}
        }
        let gestures = self.controller.status().gestures;
        let mut outgoing = Vec::new();
        for command in self.controller.on_event(event, now) {
            outgoing.extend(self.apply(command, now)?);
        }
        let status = self.controller.status();
        if status.gestures != gestures
            && let Some(gesture) = &status.last_gesture
        {
            self.log.push(format!("gesture {gesture}"));
        }
        Ok(outgoing)
    }

    fn apply(&mut self, command: Command, now: f64) -> Result<Vec<u8>, String> {
        match command {
            Command::Run(action) if action == DIAL_TOGGLE => {
                self.controller.cycle_dial();
                self.log
                    .push(format!("dial {}", self.controller.status().dial.name()));
                self.actions.push(format!(
                    "dial.changed.{}",
                    self.controller.status().dial.name()
                ));
            }
            Command::Run(action) => {
                self.log.push(format!("action {action}"));
                self.actions.push(action);
            }
            Command::SetHand(hand) => {
                return self
                    .session
                    .set_handedness(hand, now)
                    .map_err(|e| e.to_string());
            }
        }
        Ok(Vec::new())
    }
}
