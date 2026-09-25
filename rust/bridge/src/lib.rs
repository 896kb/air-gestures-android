//! The Android app's view of a band connection: bytes in, bytes and event
//! lines out. Kotlin owns the Bluetooth socket and the clock; this owns the
//! band-core session, the keepalive timing (as the desktop daemon's link loop
//! does) and gesture de-duplication.

use band_core::events::Event;
use band_core::gestures::{GestureRouter, Recognized, Swipe, Tap};
use band_core::identity::EnrollmentIdentity;
use band_core::session::BandSession;

mod jni_api;

/// Battery query period and the quiet time before a stream-state query.
const BATTERY_EVERY: f64 = 5.0;
const QUIET_QUERY: f64 = 2.0;

pub struct Connection {
    session: BandSession,
    router: GestureRouter,
    last_read: f64,
    last_query: f64,
    next_battery: f64,
    events: Vec<String>,
}

impl Connection {
    /// `owner_key` is the desktop's `owner.key` (32 or 97 bytes); `scheme_guess`
    /// its `band.json` value (0 for current firmware).
    pub fn new(owner_key: &[u8], scheme_guess: usize) -> Result<Self, String> {
        let (private, band) = match owner_key.len() {
            32 => (owner_key, None),
            97 => (&owner_key[..32], Some(&owner_key[32..])),
            length => return Err(format!("owner.key must be 32 or 97 bytes, not {length}")),
        };
        let identity = EnrollmentIdentity::from_bytes(private, band).map_err(|e| e.to_string())?;
        Ok(Self {
            session: BandSession::new(Some(identity), None, false).with_scheme_guess(scheme_guess),
            router: GestureRouter::default(),
            last_read: 0.0,
            last_query: 0.0,
            next_battery: 0.0,
            events: Vec::new(),
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
        for event in &result.events {
            self.describe(event, now);
        }
        Ok(result.outgoing)
    }

    /// Call every ~250 ms; returns bytes to write (keepalive queries).
    pub fn tick(&mut self, now: f64) -> Result<Vec<u8>, String> {
        for event in self.session.tick(now) {
            self.describe(&event, now);
        }
        let mut outgoing = Vec::new();
        if self.session.streams_enabled() && now >= self.next_battery {
            self.next_battery = now + BATTERY_EVERY;
            outgoing.extend(self.session.query_battery_status(now).map_err(|e| e.to_string())?);
        }
        if self.session.streams_enabled()
            && now - self.last_read >= QUIET_QUERY
            && now - self.last_query >= QUIET_QUERY
        {
            self.last_query = now;
            outgoing.extend(self.session.query_stream_state().map_err(|e| e.to_string())?);
        }
        Ok(outgoing)
    }

    /// Event lines since the last call, e.g. `connected`, `gesture swipe left`,
    /// `battery 85`, `dial on`, `turn 1.5`.
    pub fn take_events(&mut self) -> Vec<String> {
        std::mem::take(&mut self.events)
    }

    fn describe(&mut self, event: &Event, now: f64) {
        let line = match event {
            Event::Connected => "connected".to_owned(),
            Event::Gesture(message) => match self.router.gesture(message, now) {
                Some(gesture) => format!("gesture {}", label(gesture)),
                None => return,
            },
            Event::BatteryStatus(Some(status)) => format!("battery {}", status.level),
            Event::DialState(on) => format!("dial {}", if *on { "on" } else { "off" }),
            Event::DialTurn(rotation) => format!("turn {rotation:.2}"),
            Event::Handedness(hand) => format!("hand {hand:?}").to_lowercase(),
            _ => return,
        };
        self.events.push(line);
    }
}

pub fn label(gesture: Recognized) -> &'static str {
    match gesture {
        Recognized::Swipe(Swipe::Left) => "swipe left",
        Recognized::Swipe(Swipe::Right) => "swipe right",
        Recognized::Swipe(Swipe::Up) => "swipe up",
        Recognized::Swipe(Swipe::Down) => "swipe down",
        Recognized::Tap(Tap::IndexTap) => "index tap",
        Recognized::Tap(Tap::IndexDoubleTap) => "index double tap",
        Recognized::Tap(Tap::MiddleTap) => "middle tap",
        Recognized::Tap(Tap::MiddleDoubleTap) => "middle double tap",
        Recognized::Tap(Tap::MiddleHold) => "middle hold",
    }
}
