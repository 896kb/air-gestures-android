//! The Android app's view of a band connection: bytes in, bytes out, plus
//! action names to run and a status snapshot. Kotlin owns the Bluetooth socket
//! and the clock; this owns the band-core session, the keepalive timing (as the
//! desktop daemon's link loop does) and band-core's shared controls: taps, the holds,
//! the band controls above the profiles, the pinch (dials, drags and sliders), the palm
//! position and its calibration, and draw sessions matched against taught drawings, fed
//! the app's profiles as JSON (`profiles`). The screens ask band-core's rules without a
//! connection (`report`, `teach`). Also the Meta sign-in and the ownership claim
//! (`metaauth`, `meta`, `claim`), ported from the Linux app.

use band_core::controls::{Controls, Finger, Output};
use band_core::events::{Event, Hand};
use band_core::identity::EnrollmentIdentity;
use band_core::session::BandSession;
use serde::Serialize;

pub mod claim;
pub mod fieldtext;
pub mod firmware;
mod jni_api;
pub mod meta;
pub mod metaauth;
mod palmlive;
pub mod profiles;
pub mod report;
pub mod status;
pub mod teach;
pub mod words;

use status::Status;

/// Battery query period and the quiet time before a stream-state query.
const BATTERY_EVERY: f64 = 5.0;
const QUIET_QUERY: f64 = 2.0;
pub struct Connection {
    session: BandSession,
    controls: Controls<String>,
    /// The hand setting and both wrists' calibrations (see `profiles::Setup`).
    setup: profiles::Setup,
    /// A hand the controls asked for without a clock (`set_profiles`): `tick` writes it.
    pending_hand: Option<Hand>,
    live: bool,
    last_read: f64,
    last_query: f64,
    next_battery: f64,
    actions: Vec<String>,
    log: Vec<String>,
    /// The stream probe is on: the session's notes go to the log.
    probing: bool,
    /// Typing the band's handwriting into the text field in front (see [`Self::set_field_typing`]).
    field: Option<fieldtext::FieldTyping>,
    /// The band's roll right now, for the calibration screen (see [`Self::palm_now`]).
    roll: palmlive::LiveRoll,
}

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
    /// A draw session is open (a reconnecting app closes a pad left open).
    drawing: bool,
    #[serde(flatten)]
    status: &'a Status,
}

impl Connection {
    /// `owner_key` is the desktop's `owner.key` (32 or 97 bytes); `scheme_guess`
    /// its `band.json` value (0 for current firmware). `paused` carries the app's
    /// lock over from the previous connection; `profiles` is the app's profiles JSON
    /// (see `profiles::parse`).
    pub fn new(owner_key: &[u8], scheme_guess: usize, paused: bool, profiles: &str) -> Result<Self, String> {
        let (private, band) = match owner_key.len() {
            32 => (owner_key, None),
            97 => (&owner_key[..32], Some(&owner_key[32..])),
            length => return Err(format!("owner.key must be 32 or 97 bytes, not {length}")),
        };
        let identity = EnrollmentIdentity::from_bytes(private, band).map_err(|e| e.to_string())?;
        let (setup, profiles) = profiles::parse(profiles)?;
        let log = setup.notes.clone();
        let mut controls = Controls::new(setup.options(None), profiles);
        if paused {
            controls.pause();
        }
        Ok(Self {
            session: BandSession::new(Some(identity), None, false).with_scheme_guess(scheme_guess),
            controls,
            setup,
            pending_hand: None,
            live: false,
            last_read: 0.0,
            last_query: 0.0,
            next_battery: 0.0,
            actions: Vec::new(),
            log,
            probing: false,
            field: None,
            roll: palmlive::LiveRoll::default(),
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
        self.type_written(now);
        Ok(outgoing)
    }

    /// Call every ~50 ms (held single taps fire on time); returns bytes to write.
    pub fn tick(&mut self, now: f64) -> Result<Vec<u8>, String> {
        let mut outgoing = Vec::new();
        for event in self.session.tick(now) {
            outgoing.extend(self.on_event(&event, now)?);
        }
        outgoing.extend(self.session.ota_tick(now).map_err(|e| e.to_string())?);
        let due = self.controls.poll(now);
        outgoing.extend(self.take(due, Some(now))?);
        if let Some(hand) = self.pending_hand.take() {
            outgoing.extend(self.session.set_handedness(hand, now).map_err(|e| e.to_string())?);
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

    /// Action names to run since the last call ("media.next", "volume.up", …), and the
    /// controls' events, starting with `@` (see [`line`]):
    ///
    /// | Line | From |
    /// |---|---|
    /// | `@gesture <label>` | `Output::Gesture` |
    /// | `@controls on` / `@controls off` | `Output::Controls` |
    /// | `@profile <index> <step>` | `Output::Profile` |
    /// | `@held <app>` | `Output::ProfileHeld` |
    /// | `@practiced <action>` | `Output::Practiced` |
    /// | `@draw-started` | `Output::DrawStarted` |
    /// | `@pen down` / `@pen up` | `Output::Pen { down }`: down just before a stroke's first point, up when the pen lets go or its stroke drops |
    /// | `@draw-point <stroke> <x> <y>` | `Output::DrawPoint` (degrees, 2 decimals, x right, y up); the first after `@pen down` starts its stroke, and a dropped stroke's number comes again (redraw it) |
    /// | `@draw-finished <json>` | `Output::DrawFinished`: `[[[x,y],…],…]` (strokes) |
    /// | `@drawn <name>` | `Output::Drawn` (the distance goes to the log) |
    /// | `@not-drawn` | `Output::NotDrawn` |
    /// | `@draw-cancelled` | `Output::DrawCancelled` |
    /// | `@drag-sample <index\|middle> <x> <y> <z> <roll>` | `Output::DragSample`, plus `Controls::prototypes().palm_down_roll` (what `Prototypes::taught` needs) |
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

    /// The controls off or on from the app. It knows, so `@controls` isn't reported back; what
    /// else that brings (turning off closes an open drawing: `@draw-cancelled`) is.
    pub fn set_paused(&mut self, paused: bool, now: f64) {
        let mut outputs = if paused { self.controls.pause() } else { self.controls.resume(now) };
        outputs.retain(|output| !matches!(output, Output::Controls { .. }));
        // Without a clock nothing is written to the band here (a hand waits for `tick`), so it can't fail.
        let _ = self.take(outputs, None);
    }

    /// New profiles, hand or call layer from the app (see `profiles::parse`). The app knows
    /// which profile it picked: a switch this causes isn't reported back.
    pub fn set_profiles(&mut self, json: &str) -> Result<(), String> {
        let (setup, profiles) = profiles::parse(json)?;
        self.log.extend(setup.notes.iter().cloned());
        let options = setup.options(self.controls.status().hand);
        self.setup = setup;
        let mut outputs = Vec::new();
        let current = self.controls.options();
        if options.hand != current.hand {
            // A new hand setting: asked for afresh (set_options also re-arms the dial). Not on
            // every send: the app sends its profiles again on every foreground or call change.
            outputs.extend(self.controls.set_options(options));
        } else if options.palm != current.palm || options.drags != current.drags {
            // Only the calibration (calibrated or taught again): the hand and its request stay.
            self.controls.set_calibration(options.palm, options.drags);
        }
        outputs.extend(self.controls.set_profiles(profiles));
        outputs.retain(|output| !matches!(output, Output::Profile { step: 0, .. }));
        self.take(outputs, None)?;
        Ok(())
    }

    /// The app in front (`""`: none known); its own profile, if it has one, becomes active.
    pub fn set_foreground(&mut self, app: &str) -> Result<(), String> {
        let mut outputs = self.controls.set_foreground((!app.is_empty()).then(|| app.to_owned()));
        // The app follows the foreground itself.
        outputs.retain(|output| !matches!(output, Output::Profile { .. }));
        self.take(outputs, None)?;
        Ok(())
    }

    /// A call is ringing or running: the call layer goes on top of the active profile.
    pub fn set_in_call(&mut self, in_call: bool) {
        self.controls.set_in_call(in_call);
    }

    /// Practice: gestures and what they'd run come as `@gesture` / `@practiced`, nothing runs.
    pub fn set_practice(&mut self, practice: bool) {
        self.controls.set_practice(practice);
    }

    /// The drawings (`[{"name", "tries"}]`, see `teach::library`) draw sessions match against.
    /// Bad JSON is an error and keeps the ones before.
    pub fn set_library(&mut self, json: &str) -> Result<(), String> {
        self.controls.set_library(teach::library(json)?);
        Ok(())
    }

    /// Open a draw session from the app (the teach screen): `@draw-started`.
    pub fn start_drawing(&mut self, now: f64) -> Result<(), String> {
        let outputs = self.controls.start_drawing(now);
        self.take(outputs, None).map(drop)
    }

    /// The pad's close button: `@draw-cancelled` (nothing with no session).
    pub fn cancel_drawing(&mut self) -> Result<(), String> {
        let outputs = self.controls.cancel_drawing();
        self.take(outputs, None).map(drop)
    }

    /// Teaching drawings: sessions report their strokes (`@draw-finished`) and match nothing.
    pub fn set_teaching(&mut self, on: bool) {
        self.controls.set_teaching(on);
    }

    /// Teaching drags: every pinch that moves reports `@drag-sample`; nothing acts. Turning it
    /// on cancels an open draw session.
    pub fn set_drag_teaching(&mut self, on: bool) -> Result<(), String> {
        let outputs = self.controls.set_drag_teaching(on);
        self.take(outputs, None).map(drop)
    }

    /// `{"palm": "down" | "sideways" | "up" | null, "roll": degrees (to 0.1) | null}` right
    /// now: both null without a fresh orientation; the palm as band-core reads it
    /// (`Controls::palm`: down for an uncalibrated left wrist).
    pub fn palm_now(&self, now: f64) -> String {
        let roll = self.roll.fresh(now);
        serde_json::json!({"roll": roll, "palm": roll.map(|_| self.controls.palm(now).key())}).to_string()
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

    /// What the band wrote goes to the text field being typed in, if any. The log says how
    /// many symbols, never which: what's written can be a password.
    fn type_written(&mut self, now: f64) {
        let written = self.session.take_written();
        if let Some(field) = &mut self.field {
            let words = words::dictionary();
            let untyped = written.iter().filter(|symbol| !field.take(symbol, words.as_deref(), now)).count();
            if let Some(note) = written_note(written.len(), untyped) {
                self.log.push(note);
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

    /// `{"connected":…, "paused":…, "battery":…, "charging":…, "hand":…,
    /// "last_gesture":…, "last_action":…, "gestures":…}` and the firmware update's state.
    pub fn status_json(&self) -> String {
        let c = self.controls.status();
        let status = Status {
            paused: c.paused,
            battery: c.battery,
            charging: c.charging,
            hand: c.hand.map(|h| format!("{h:?}").to_lowercase()),
            last_gesture: c.last_gesture.map(str::to_owned),
            last_action: c.last_action.clone(),
            gestures: c.gestures,
        };
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
            drawing: self.controls.drawing(),
            status: &status,
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
            _ => {}
        }
        let outputs = self.controls.on_event(event, now);
        // The band said which wrist: that wrist's calibration and taught drags, the hand
        // state untouched (set_options would ask for the hand again).
        if let Event::Handedness(hand) = event {
            let options = self.setup.options(Some(*hand));
            let current = self.controls.options();
            if options.palm != current.palm || options.drags != current.drags {
                self.controls.set_calibration(options.palm, options.drags);
            }
        }
        self.roll.on_event(event, now);
        self.take(outputs, Some(now))
    }

    /// What the controls asked for: actions and events for the app, hand writes for the band
    /// (`now`: none, e.g. from `set_profiles`: the next `tick` writes it).
    fn take(&mut self, outputs: Vec<Output<String>>, now: Option<f64>) -> Result<Vec<u8>, String> {
        let mut outgoing = Vec::new();
        for output in outputs {
            match output {
                Output::Run(action) => {
                    self.log.push(format!("action {action}"));
                    self.actions.push(action);
                }
                Output::SetHand(hand) => match now {
                    Some(now) => outgoing.extend(self.session.set_handedness(hand, now).map_err(|e| e.to_string())?),
                    None => self.pending_hand = Some(hand),
                },
                other => {
                    match &other {
                        Output::Gesture(gesture) => self.log.push(format!("gesture {}", gesture.label())),
                        Output::Drawn { name, distance } => self.log.push(format!("drawn {name} ({distance:.2})")),
                        _ => {}
                    }
                    let roll = self.controls.prototypes().palm_down_roll;
                    self.actions.extend(line(&other, roll));
                }
            }
        }
        Ok(outgoing)
    }
}

/// The `@` line the app follows for an output (`None`: `Run` and `SetHand`, which go as they
/// are). `roll`: the prototypes' palm-down roll, which a drag sample was read in.
pub fn line(output: &Output<String>, roll: f64) -> Option<String> {
    Some(match output {
        Output::Gesture(gesture) => format!("@gesture {}", gesture.label()),
        Output::Controls { enabled } => format!("@controls {}", if *enabled { "on" } else { "off" }),
        Output::Profile { index, step } => format!("@profile {index} {step}"),
        Output::ProfileHeld { app } => format!("@held {app}"),
        Output::Practiced(action) => format!("@practiced {action}"),
        Output::DrawStarted => "@draw-started".into(),
        Output::Pen { down } => format!("@pen {}", if *down { "down" } else { "up" }),
        Output::DrawPoint { stroke, x, y } => format!("@draw-point {stroke} {x:.2} {y:.2}"),
        Output::DrawFinished { strokes } => {
            format!("@draw-finished {}", serde_json::to_string(strokes).unwrap_or_else(|_| "[]".into()))
        }
        Output::Drawn { name, .. } => format!("@drawn {name}"),
        Output::NotDrawn => "@not-drawn".into(),
        Output::DrawCancelled => "@draw-cancelled".into(),
        Output::DragSample { finger, direction: [x, y, z] } => {
            format!("@drag-sample {} {x:.4} {y:.4} {z:.4} {roll:.2}", finger_key(*finger))
        }
        Output::Run(_) | Output::SetHand(_) => return None,
    })
}

/// A finger's name in the `@` lines and the report.
pub(crate) fn finger_key(finger: Finger) -> &'static str {
    match finger {
        Finger::Index => "index",
        Finger::Middle => "middle",
    }
}

/// The log line for symbols the band wrote: how many, and how many didn't go in.
fn written_note(written: usize, untyped: usize) -> Option<String> {
    let noun = if written == 1 { "symbol" } else { "symbols" };
    match (written, untyped) {
        (0, _) => None,
        (_, 0) => Some(format!("handwriting: {written} {noun} typed")),
        _ => Some(format!("handwriting: {written} {noun}, {untyped} not typed")),
    }
}

#[cfg(test)]
mod tests {
    use super::written_note;

    #[test]
    fn the_handwriting_note_counts_letters_and_never_names_them() {
        assert_eq!(written_note(0, 0), None);
        assert_eq!(written_note(1, 0).as_deref(), Some("handwriting: 1 symbol typed"));
        assert_eq!(written_note(3, 1).as_deref(), Some("handwriting: 3 symbols, 1 not typed"));
    }
}
