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
mod jni_api;
pub mod meta;
pub mod metaauth;
pub mod status;

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
}

#[derive(Debug, Clone, Copy)]
struct HapticStep {
    command: HapticsCommand,
    wait_after: f64,
    duration_ms: u64,
    priority: HapticPriority,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum HapticPriority {
    State,
    Profile,
}

#[derive(Debug, Clone, Copy)]
struct HapticFlight {
    sequence: u32,
    sent_at: f64,
    duration_ms: u64,
    wait_after: f64,
    acknowledged: bool,
    priority: HapticPriority,
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
            band_vibration: true,
            haptics_queue: VecDeque::new(),
            haptics_flight: None,
            haptics_next_at: 0.0,
            last_clock: 0.0,
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
        Ok(outgoing)
    }

    /// Call every ~50 ms (held single taps fire on time); returns bytes to write.
    pub fn tick(&mut self, now: f64) -> Result<Vec<u8>, String> {
        self.last_clock = now;
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
            Event::HapticsFinished {
                sequence,
                rpc_success,
                haptics_success,
            } => {
                if let Some(flight) = self.haptics_flight.as_mut()
                    && flight.sequence == *sequence
                {
                    flight.acknowledged = true;
                    if !rpc_success {
                        self.log.push("Band haptics RPC failed".into());
                    } else if haptics_success != &Some(true) {
                        self.log
                            .push("Band omitted or rejected detailed haptics status".into());
                    }
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
    fn control_transitions_queue_exact_order_and_amplitude() {
        let mut connection = connection();
        connection.controls_haptics(true).unwrap();
        let off: Vec<_> = connection.haptics_queue.iter().copied().collect();
        assert_eq!(off.len(), 3);
        assert_eq!(
            off[0].command,
            HapticsCommand::Vibrate {
                duration_ms: 80,
                amplitude: 255
            }
        );
        assert_eq!(off[0].wait_after, DOUBLE_GAP);
        assert_eq!(off[1].command, off[0].command);
        assert_eq!(off[1].wait_after, 0.0);
        assert_eq!(off[2].command, HapticsCommand::Perform { pattern_id: 2 });

        connection.controls_haptics(false).unwrap();
        let on: Vec<_> = connection.haptics_queue.iter().copied().collect();
        assert_eq!(on.len(), 2);
        assert_eq!(on[0].command, HapticsCommand::Perform { pattern_id: 1 });
        assert_eq!(
            on[1].command,
            HapticsCommand::Vibrate {
                duration_ms: 300,
                amplitude: 255
            }
        );
    }

    #[test]
    fn disabled_preference_forces_feedback_off_without_any_pulse() {
        let mut connection = connection();
        connection.set_band_vibration(false).unwrap();
        assert_eq!(connection.haptics_queue.len(), 1);
        assert_eq!(
            connection.haptics_queue[0].command,
            HapticsCommand::Perform { pattern_id: 2 }
        );
        connection.controls_haptics(false).unwrap();
        assert_eq!(connection.haptics_queue.len(), 1);
        assert!(matches!(
            connection.haptics_queue[0].command,
            HapticsCommand::Perform { pattern_id: 2 }
        ));
    }

    #[test]
    fn newer_toggle_replaces_obsolete_unsent_steps_and_waits_for_pulse_duration() {
        let mut connection = connection();
        connection.controls_haptics(true).unwrap();
        connection.haptics_queue.pop_front();
        connection.haptics_flight = Some(HapticFlight {
            sequence: 7,
            sent_at: 1.0,
            duration_ms: 80,
            wait_after: DOUBLE_GAP,
            acknowledged: false,
            priority: HapticPriority::State,
        });
        connection
            .on_event(
                &Event::HapticsFinished {
                    sequence: 7,
                    rpc_success: true,
                    haptics_success: None,
                },
                1.01,
            )
            .unwrap();
        connection.controls_haptics(false).unwrap();
        assert_eq!(connection.haptics_queue.len(), 2);
        connection.retire_completed_haptics(1.179);
        assert!(
            connection.haptics_flight.is_some(),
            "must wait through pulse and gap"
        );
        connection.retire_completed_haptics(1.181);
        assert!(connection.haptics_flight.is_none());
        assert_eq!(
            connection.haptics_queue.front().unwrap().command,
            HapticsCommand::Perform { pattern_id: 1 }
        );
    }

    #[test]
    fn feedback_sync_waits_for_live_stream_and_profile_cues_follow_phone_count() {
        let mut connection = connection();
        assert!(connection.set_band_vibration(true).unwrap().is_empty());
        assert_eq!(
            connection.haptics_queue.len(),
            1,
            "session sync waits until live"
        );
        connection.profile_haptics(false).unwrap();
        assert_eq!(
            connection.haptics_queue.len(),
            1,
            "profile cue cannot overwrite state sync"
        );
        assert_eq!(
            connection.haptics_queue[0].command,
            HapticsCommand::Perform { pattern_id: 1 }
        );
        connection.haptics_queue.clear();
        connection.profile_haptics(false).unwrap();
        assert_eq!(
            connection.haptics_queue[0].command,
            HapticsCommand::Vibrate {
                duration_ms: 40,
                amplitude: 255
            }
        );
        connection.profile_haptics(true).unwrap();
        assert_eq!(connection.haptics_queue.len(), 2);
        assert!(
            connection
                .haptics_queue
                .iter()
                .all(|step| matches!(step.command, HapticsCommand::Vibrate { amplitude: 255, .. }))
        );
    }

    #[test]
    fn fresh_session_syncs_feedback_state_without_a_transition_pulse() {
        let identity = EnrollmentIdentity::new(SigningKey::random(&mut OsRng), None);
        let mut paused = Connection::new(&identity.private_bytes(), 0, true, "", "").unwrap();
        paused.set_band_vibration(true).unwrap();
        assert_eq!(paused.haptics_queue.len(), 1);
        assert_eq!(
            paused.haptics_queue[0].command,
            HapticsCommand::Perform { pattern_id: 2 },
            "a reconnect while controls are paused keeps Band feedback off"
        );
        assert!(
            paused
                .haptics_queue
                .iter()
                .all(|step| matches!(step.command, HapticsCommand::Perform { .. }))
        );

        let mut disabled = Connection::new(&identity.private_bytes(), 0, false, "", "").unwrap();
        disabled.set_band_vibration(false).unwrap();
        assert_eq!(disabled.haptics_queue.len(), 1);
        assert_eq!(
            disabled.haptics_queue[0].command,
            HapticsCommand::Perform { pattern_id: 2 },
            "a reconnect with the preference off never enables feedback"
        );
    }
}
