//! The Android app's view of a band connection: bytes in, bytes out, plus
//! action names to run and a status snapshot. Kotlin owns the Bluetooth socket
//! and the clock; this owns the band-core session, the keepalive timing (as the
//! desktop daemon's link loop does) and the gesture controller (copied from
//! air-gestures: held taps, the double-tap wait, the toggle hold, the dial).
//! Also the Meta sign-in and the ownership claim (`metaauth`, `meta`, `claim`),
//! ported from the Linux app.

use band_core::events::Event;
use band_core::haptics::HapticsCommand;
use band_core::identity::EnrollmentIdentity;
use band_core::session::BandSession;
use serde::Serialize;
use std::collections::VecDeque;

pub mod claim;
pub mod config;
pub mod controller;
pub mod fieldtext;
pub mod firmware;
mod jni_api;
pub mod meta;
pub mod metaauth;
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
const OFF_PULSE_MS: u64 = 80;
const ON_PULSE_MS: u64 = 300;
const PROFILE_PULSE_MS: u64 = 40;
const VOLUME_PULSE_MS: u64 = 18;
const VOLUME_DETENT_INTERVAL: f64 = 0.100;
const DOUBLE_GAP: f64 = 0.100;
pub struct Connection {
    session: BandSession,
    controller: Controller,
    live: bool,
    last_read: f64,
    last_query: f64,
    next_battery: f64,
    actions: Vec<String>,
    log: Vec<String>,
    band_vibration: bool,
    haptics_queue: VecDeque<HapticStep>,
    haptics_flight: Option<HapticFlight>,
    haptics_next_at: f64,
    last_clock: f64,
    last_volume_detent: f64,
    /// The stream probe is on: the session's notes go to the log.
    probing: bool,
    /// Typing the band's handwriting into the text field in front (see [`Self::set_field_typing`]).
    field: Option<fieldtext::FieldTyping>,
}

#[derive(Debug, Clone, Copy)]
struct HapticStep { command: HapticsCommand, wait_after: f64, duration_ms: u64, priority: HapticPriority }
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum HapticPriority { State, Profile, Detent }
#[derive(Debug, Clone, Copy)]
struct HapticFlight { sequence: u32, sent_at: f64, duration_ms: u64, wait_after: f64, acknowledged: bool, priority: HapticPriority }

#[derive(Serialize)]
struct Snapshot<'a> {
    connected: bool,
    /// The firmware the band runs, as it reports it on connecting (its "software" version).
    firmware: Option<String>,
    /// The band's last update status ("IDLE stable firmware, active …"), the latest line of
    /// the running or last firmware update, and whether one is running.
    ota_status: Option<String>,
    /// The key (KEK) version the band has, from that status.
    ota_kek: Option<u64>,
    ota_progress: Option<&'a str>,
    ota_busy: bool,
    /// Whether cancelling is safe now (Commit not sent).
    ota_cancellable: bool,
    /// Each file of that update: `{"name", "size", "sent", "stage"}`.
    ota_items: Vec<serde_json::Value>,
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
            band_vibration: true,
            haptics_queue: VecDeque::new(),
            haptics_flight: None,
            haptics_next_at: 0.0,
            last_clock: 0.0,
            last_volume_detent: f64::NEG_INFINITY,
            probing: false,
            field: None,
        })
    }

    /// The first bytes to write once the L2CAP channel is open.
    pub fn request(&mut self) -> Result<Vec<u8>, String> {
        self.session.request().map_err(|e| e.to_string())
    }

    /// Bytes read from the band at `now` (seconds, monotonic); returns bytes to write.
    pub fn feed(&mut self, bytes: &[u8], now: f64) -> Result<Vec<u8>, String> {
        self.last_clock = now;
        self.last_read = now;
        let result = self.session.feed(bytes, now).map_err(|e| e.to_string())?;
        let mut outgoing = result.outgoing;
        for event in &result.events {
            outgoing.extend(self.on_event(event, now)?);
        }
        self.type_written(now);
        Ok(outgoing)
    }

    /// Call every ~50 ms (held single taps fire on time); returns bytes to write.
    pub fn tick(&mut self, now: f64) -> Result<Vec<u8>, String> {
        self.last_clock = now;
        let mut outgoing = Vec::new();
        for event in self.session.tick(now) {
            outgoing.extend(self.on_event(&event, now)?);
        }
        outgoing.extend(self.session.ota_tick(now).map_err(|e| e.to_string())?);
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
        outgoing.extend(self.pump_haptics(now)?);
        Ok(outgoing)
    }

    /// Synchronize persistent gesture feedback on a newly opened Band session or
    /// after the user's global band-vibration preference changes. This adds no cue.
    pub fn set_band_vibration(&mut self, enabled: bool) -> Result<Vec<u8>, String> {
        self.band_vibration = enabled;
        self.replace_haptics_state();
        self.push_pattern(if enabled && !self.controller.status().paused {
            1
        } else {
            2
        });
        self.pump_haptics(self.last_clock)
    }

    /// Reassert the built-in controls pattern without a transition pulse.
    pub fn sync_controls_haptics(&mut self, paused: bool) -> Result<Vec<u8>, String> {
        self.replace_haptics_state();
        self.push_pattern(if self.band_vibration && !paused { 1 } else { 2 });
        self.pump_haptics(self.last_clock)
    }

    /// Queue the control transition cue. An active RPC completes before the
    /// replacement sequence starts; later toggles replace queued obsolete steps.
    pub fn controls_haptics(&mut self, paused: bool) -> Result<Vec<u8>, String> {
        self.replace_haptics_state();
        if !self.band_vibration {
            self.push_pattern(2);
        } else if paused {
            self.push_vibration(OFF_PULSE_MS, DOUBLE_GAP, HapticPriority::State);
            self.push_vibration(OFF_PULSE_MS, 0.0, HapticPriority::State);
            self.push_pattern(2);
        } else {
            self.push_pattern(1);
            self.push_vibration(ON_PULSE_MS, 0.0, HapticPriority::State);
        }
        self.pump_haptics(self.last_clock)
    }

    /// Replace the phone's one/two profile buzzes with matching Band pulses.
    pub fn profile_haptics(&mut self, previous: bool) -> Result<Vec<u8>, String> {
        if !self.band_vibration {
            return Ok(Vec::new());
        }
        if self
            .haptics_queue
            .iter()
            .any(|step| step.priority == HapticPriority::State)
            || self
                .haptics_flight
                .is_some_and(|flight| flight.priority == HapticPriority::State)
        {
            return Ok(Vec::new());
        }
        self.haptics_queue
            .retain(|step| step.priority != HapticPriority::Profile);
        self.push_vibration(
            PROFILE_PULSE_MS,
            if previous { DOUBLE_GAP } else { 0.0 },
            HapticPriority::Profile,
        );
        if previous {
            self.push_vibration(PROFILE_PULSE_MS, 0.0, HapticPriority::Profile);
        }
        self.pump_haptics(self.last_clock)
    }

    /// One actual phone-volume detent, dropped during other haptics and rate-limited.
    pub fn volume_detent(&mut self) -> Result<Vec<u8>, String> {
        let now = self.last_clock;
        if !self.band_vibration
            || !self.live
            || !self.session.streams_enabled()
            || self.haptics_flight.is_some()
            || !self.haptics_queue.is_empty()
            || now - self.last_volume_detent < VOLUME_DETENT_INTERVAL
        {
            return Ok(Vec::new());
        }
        self.last_volume_detent = now;
        self.haptics_queue.push_back(HapticStep {
            command: HapticsCommand::Vibrate {
                duration_ms: VOLUME_PULSE_MS,
                amplitude: 128,
            },
            wait_after: 0.0,
            duration_ms: VOLUME_PULSE_MS,
            priority: HapticPriority::Detent,
        });
        self.pump_haptics(now)
    }

    fn push_pattern(&mut self, pattern_id: u32) {
        self.haptics_queue.push_back(HapticStep {
            command: HapticsCommand::Perform { pattern_id },
            wait_after: 0.0,
            duration_ms: 0,
            priority: HapticPriority::State,
        });
    }

    fn push_vibration(&mut self, duration_ms: u64, wait_after: f64, priority: HapticPriority) {
        self.haptics_queue.push_back(HapticStep {
            command: HapticsCommand::Vibrate {
                duration_ms,
                amplitude: 255,
            },
            wait_after,
            duration_ms,
            priority,
        });
    }

    fn replace_haptics_state(&mut self) {
        self.haptics_queue.clear();
    }

    fn pump_haptics(&mut self, now: f64) -> Result<Vec<u8>, String> {
        self.retire_completed_haptics(now);
        if self.haptics_flight.is_some()
            || !self.live
            || now < self.haptics_next_at
            || !self.session.streams_enabled()
            || self.haptics_queue.is_empty()
        {
            return Ok(Vec::new());
        }
        let step = self
            .haptics_queue
            .pop_front()
            .expect("nonempty haptics queue");
        match self.session.send_haptics(step.command, now) {
            Ok(bytes) => {
                let sequence = self.session.configuration_sequence();
                self.haptics_flight = Some(HapticFlight {
                    sequence,
                    sent_at: now,
                    duration_ms: step.duration_ms,
                    wait_after: step.wait_after,
                    acknowledged: false,
                    priority: step.priority,
                });
                Ok(bytes)
            }
            Err(error) => {
                let message = error.to_string();
                self.log.push(format!("haptics unavailable: {message}"));
                // A transient not-ready condition leaves the step queued for the
                // next tick; other errors are logged and discarded to avoid spin.
                if message.contains("live Band control channel") {
                    self.haptics_queue.push_front(step);
                }
                Ok(Vec::new())
            }
        }
    }

    fn retire_completed_haptics(&mut self, now: f64) {
        if let Some(flight) = self.haptics_flight
            && flight.acknowledged
        {
            let due = flight.sent_at + flight.duration_ms as f64 / 1000.0 + flight.wait_after;
            if now >= due {
                self.haptics_flight = None;
                self.haptics_next_at = due;
            }
        }
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
        if !hex.len().is_multiple_of(2) {
            return Err("odd number of hex digits".into());
        }
        let body = (0..hex.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&hex[i..i + 2], 16).map_err(|e| e.to_string()))
            .collect::<Result<Vec<u8>, String>>()?;
        self.probing = true;
        self.session.probe_config(&body, now).map_err(|e| e.to_string())
    }

    /// Debug: ask the band's update status, read only (see `BandSession::ota_status`); returns
    /// the bytes to write. The answer goes to the log.
    pub fn ota_status(&mut self) -> Result<Vec<u8>, String> {
        self.probing = true;
        self.session.ota_status().map_err(|e| e.to_string())
    }

    /// Debug: one step of a firmware update (see [`band_core::ota::OtaMode`]): "key", "send",
    /// "trial", "install" or "update" (all of it in one connection) from the package unzipped into `dir` (see [`firmware::ota_plan`]),
    /// or "cancel". "install" commits only that package's version, and only once it's staged.
    /// Returns the first bytes to write; progress goes to the log.
    pub fn ota_start(&mut self, mode: &str, dir: &str, now: f64) -> Result<Vec<u8>, String> {
        use band_core::ota::{OtaMode, OtaPlan};
        let mode = match mode {
            "key" => OtaMode::Key,
            "send" => OtaMode::Send,
            "trial" => OtaMode::Trial,
            "cancel" => OtaMode::Cancel,
            "install" => OtaMode::Install,
            "update" => OtaMode::Update,
            other => return Err(format!("unknown update step {other}")),
        };
        let plan = if mode == OtaMode::Cancel {
            OtaPlan { version: 0, firmware: Vec::new(), firmware_md5: [0; 16], model: None, kek: None, mode, resume: false }
        } else {
            let (plan, summary) = firmware::ota_plan(dir, mode)?;
            self.log.push(format!("ota: {summary}"));
            plan
        };
        self.probing = true;
        self.session.ota_start(plan, now).map_err(|e| e.to_string())
    }

    /// The band's handwriting into a text field: on puts the band in its handwriting state and
    /// starts a fresh word (`before`: the field's text before the cursor, for capitals); off
    /// puts it back to gestures. Bytes to write.
    pub fn set_field_typing(&mut self, on: bool, before: &str, popup: bool, now: f64) -> Result<Vec<u8>, String> {
        let bytes = self.session.set_band_handwriting(on).map_err(|e| e.to_string())?;
        self.field = on.then(|| {
            let mut field = fieldtext::FieldTyping::new(before, now);
            field.set_popup(popup);
            field
        });
        Ok(bytes)
    }

    /// The edits for the text field since the last call, and whether the pen is down, as JSON:
    /// `{"edits": [{"delete", "insert"}], "pen": bool}`.
    pub fn field_edits(&mut self, now: f64) -> String {
        let Some(field) = self.field.as_mut() else {
            return r#"{"active":false,"edits":[],"pen":false}"#.to_owned();
        };
        field.tick(now);
        let choices = field.choices();
        serde_json::json!({
            "active": true,
            "edits": field.take_edits(),
            "pen": field.pen(),
            "choosing": field.choosing(),
            "next": field.choosing_next(),
            "suggestions": choices.as_ref().map(|(list, _)| list.clone()),
            "highlight": choices.as_ref().map(|(_, pick)| *pick),
        })
        .to_string()
    }

    /// A swipe left in a text field: take back the last word. False when none of ours was left
    /// (the app then trims the field's own text).
    pub fn field_delete_word(&mut self, now: f64) -> bool {
        let words = words::dictionary();
        self.field.as_mut().is_some_and(|field| field.delete_word(words.as_deref(), now))
    }

    /// A swipe up in a text field: the suggestions for the word show in its place. False when
    /// there's nothing to choose for.
    pub fn field_choose(&mut self) -> bool {
        let words = words::dictionary();
        self.field.as_mut().is_some_and(|field| field.choose(words.as_deref()))
    }

    /// While choosing: the next or previous suggestion first.
    pub fn field_choose_step(&mut self, forward: bool) {
        if let Some(field) = &mut self.field {
            field.choose_step(forward);
        }
    }

    /// While choosing: take the first.
    pub fn field_choose_pick(&mut self, now: f64) {
        let words = words::dictionary();
        if let Some(field) = &mut self.field {
            field.choose_pick(words.as_deref(), now);
        }
    }

    /// While choosing: leave it with the word as it was.
    pub fn field_choose_cancel(&mut self, now: f64) {
        let words = words::dictionary();
        if let Some(field) = &mut self.field {
            field.choose_cancel(words.as_deref(), now);
        }
    }

    /// A double tap in a text field: the pen goes down.
    pub fn field_pen_down(&mut self, now: f64) {
        if let Some(field) = &mut self.field {
            field.pen_down(now);
        }
    }

    /// What the band wrote goes to the text field being typed in, if any.
    fn type_written(&mut self, now: f64) {
        let written = self.session.take_written();
        if let Some(field) = &mut self.field {
            let words = words::dictionary();
            for symbol in &written {
                let typed = field.take(symbol, words.as_deref(), now);
                {
                    self.log.push(format!(
                        "handwriting: {} ({}, sure {:.1}){}",
                        band_core::handwriting::symbol(symbol.index),
                        symbol.index,
                        symbol.margin,
                        if typed { "" } else { " not typed" }
                    ));
                }
            }
        }
    }

    /// Debug: the band's own handwriting on or off (see `BandSession::set_band_handwriting`);
    /// returns the bytes to write.
    pub fn set_band_handwriting(&mut self, on: bool) -> Result<Vec<u8>, String> {
        self.session.set_band_handwriting(on).map_err(|e| e.to_string())
    }

    /// Debug: what the band's handwriting has sent, as JSON: `{"on", "text", "tokens":
    /// [{"index", "symbol", "margin"}], "frames", "samples", "pipeline", "floats"}` (`{}` before it ran).
    /// `clear` forgets it afterwards.
    pub fn band_handwriting(&mut self, clear: bool) -> String {
        let json = self.session.band_handwriting().map_or_else(
            || "{}".to_owned(),
            |hw| {
                serde_json::json!({
                    "on": hw.on,
                    "text": hw.decoder.text,
                    "tokens": hw.tokens.iter().map(|(index, symbol, margin)| serde_json::json!({
                        "index": index, "symbol": symbol, "margin": margin,
                    })).collect::<Vec<_>>(),
                    "frames": hw.decoder.frames,
                    "samples": hw.samples,
                    "pipeline": hw.pipeline,
                    "floats": hw.floats,
                })
                .to_string()
            },
        );
        if clear {
            self.session.clear_band_handwriting();
        }
        json
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
                    "models": info.models, "handwriting": info.handwriting(),
                })
                .to_string()
            },
        )
    }

    pub fn set_dial(&mut self, dial: &str) {
        self.controller.set_dial_target(DialTarget::from_name(dial));
    }

    /// `{"connected":…, "paused":…, "battery":…, "charging":…, "hand":…, "dial":…,
    /// "last_gesture":…, "last_action":…, "gestures":…}`.
    pub fn status_json(&self) -> String {
        serde_json::to_string(&Snapshot {
            connected: self.live,
            firmware: self.session.device_info().map(|info| info.software.clone()).filter(|v| !v.is_empty()),
            ota_status: self.session.ota_last_status().map(|status| status.to_string()),
            ota_kek: self.session.ota_last_status().map(|status| status.kek),
            ota_progress: self.session.ota_progress().0,
            ota_busy: self.session.ota_progress().1,
            ota_cancellable: self.session.ota_cancellable(),
            ota_items: self
                .session
                .ota_items()
                .iter()
                .map(|item| serde_json::json!({"name": item.name, "size": item.size, "sent": item.sent, "stage": item.stage}))
                .collect(),
            status: self.controller.status(),
        })
        .expect("status serializes")
    }

    fn on_event(&mut self, event: &Event, now: f64) -> Result<Vec<u8>, String> {
        match event {
            Event::Connected => {
                self.live = true;
                self.log.push("connected".into());
                // Seen after a firmware update: the session's active request switches it.
                if let Some(info) = self.session.device_info()
                    && info.model.starts_with("ia_")
                {
                    self.log.push(format!("the band was in standby on its wake model {}; asked for active", info.model));
                }
            }
            Event::Handedness(hand) => self.log.push(format!("hand {hand:?}").to_lowercase()),
            Event::HapticsFinished { sequence, rpc_success, haptics_success } => {
                if let Some(flight) = self.haptics_flight.as_mut() && flight.sequence == *sequence {
                    flight.acknowledged = true;
                    if !rpc_success { self.log.push("Band haptics RPC failed".into()); }
                    else if haptics_success != &Some(true) { self.log.push("Band omitted or rejected detailed haptics status".into()); }
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

#[cfg(test)]
mod haptics_tests {
    use super::*;
    use p256::ecdsa::SigningKey;
    use rand_core::OsRng;

    fn connection() -> Connection {
        let identity = EnrollmentIdentity::new(SigningKey::random(&mut OsRng), None);
        Connection::new(&identity.private_bytes(), 0, false, "", "").unwrap()
    }

    #[test]
    fn controls_transition_queues_the_verified_cue_order() {
        let mut connection = connection();
        connection.controls_haptics(true).unwrap();
        let off: Vec<_> = connection.haptics_queue.iter().copied().collect();
        assert_eq!(off.len(), 3);
        assert_eq!(off[0].command, HapticsCommand::Vibrate { duration_ms: 80, amplitude: 255 });
        assert_eq!(off[0].wait_after, DOUBLE_GAP);
        assert_eq!(off[1].command, off[0].command);
        assert_eq!(off[2].command, HapticsCommand::Perform { pattern_id: 2 });

        connection.controls_haptics(false).unwrap();
        let on: Vec<_> = connection.haptics_queue.iter().copied().collect();
        assert_eq!(on.len(), 2);
        assert_eq!(on[0].command, HapticsCommand::Perform { pattern_id: 1 });
        assert_eq!(on[1].command, HapticsCommand::Vibrate { duration_ms: 300, amplitude: 255 });
    }

    #[test]
    fn preference_off_keeps_state_quiet_and_detents_do_not_backlog() {
        let mut connection = connection();
        connection.set_band_vibration(false).unwrap();
        assert_eq!(connection.haptics_queue.len(), 1);
        assert_eq!(connection.haptics_queue[0].command, HapticsCommand::Perform { pattern_id: 2 });
        connection.controls_haptics(false).unwrap();
        assert_eq!(connection.haptics_queue.len(), 1);
        connection.last_clock = 1.0;
        assert!(connection.volume_detent().unwrap().is_empty());
        assert_eq!(connection.haptics_queue.len(), 1);
    }

    #[test]
    fn profile_cues_are_one_or_two_pulses_and_yield_to_controls() {
        let mut connection = connection();
        connection.profile_haptics(false).unwrap();
        assert_eq!(connection.haptics_queue.len(), 1);
        assert_eq!(connection.haptics_queue[0].duration_ms, PROFILE_PULSE_MS);
        assert_eq!(connection.haptics_queue[0].priority, HapticPriority::Profile);
        connection.profile_haptics(true).unwrap();
        assert_eq!(connection.haptics_queue.len(), 2);
        connection.controls_haptics(true).unwrap();
        assert_eq!(connection.haptics_queue.len(), 3);
        assert_eq!(connection.haptics_queue[0].priority, HapticPriority::State);
    }
}
