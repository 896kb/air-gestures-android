use airgestures_bridge::Connection;
use band_core::identity::EnrollmentIdentity;
use band_core::responder::{Responder, SimMode};
use p256::ecdsa::SigningKey;
use rand_core::OsRng;

/// Band gesture codes: thumb=1 index=2 middle=3; actions tap=3 doubletap=4
/// up=6 down=7 left=8 right=9; derived buttonHold=3.
const THUMB: u64 = 1;
const INDEX: u64 = 2;
const MIDDLE: u64 = 3;

/// Ping-pong until neither side has anything to say.
fn pump(connection: &mut Connection, band: &mut Responder, mut to_band: Vec<u8>, now: f64) {
    for _ in 0..64 {
        if to_band.is_empty() {
            return;
        }
        let reply = band.respond(&to_band).unwrap();
        to_band = connection.feed(&reply, now).unwrap();
    }
}

/// Today's band controls (Mapping.kt / BandControls.standard()).
const STANDARD: &str = r#"{"controls":{"gesture":"middle_hold"},"next":{"gesture":"index_triple"},"previous":{"gesture":"middle_triple"}}"#;

/// The app's default first profile (see Mapping.kt), these keys changed, and `extra` empty
/// profiles after it, with the standard band controls.
fn json(map: &[(&str, &str)], extra: usize) -> String {
    json_full("band", STANDARD, map, extra)
}

fn json_full(hand: &str, band: &str, map: &[(&str, &str)], extra: usize) -> String {
    let mut gestures = serde_json::json!({
        "swipe_up": "media.previous",
        "swipe_down": "media.next",
        "index_tap": "media.play_pause",
        "middle_double": "volume.mute",
    });
    for (key, id) in map {
        gestures[*key] = serde_json::json!(id);
    }
    let mut profiles = vec![serde_json::json!({
        "name": "Profile 1",
        "map": gestures,
        "index": {"dial": {"up": "volume.up", "down": "volume.down"}, "drags": "gestures"},
    })];
    for n in 0..extra {
        profiles.push(serde_json::json!({"name": format!("Extra {n}")}));
    }
    let band: serde_json::Value = serde_json::from_str(band).unwrap();
    serde_json::json!({"hand": hand, "picked": 0, "apps": {}, "band": band, "calls": {}, "profiles": profiles}).to_string()
}

fn connected(paused: bool) -> (Connection, Responder) {
    connected_with(paused, &json(&[], 0))
}

fn connected_with(paused: bool, profiles: &str) -> (Connection, Responder) {
    let identity = EnrollmentIdentity::new(SigningKey::random(&mut OsRng), None);
    let mut connection = Connection::new(&identity.private_bytes(), 0, paused, profiles).unwrap();
    // An enrolled band with no owner recorded adopts the first key that proves trust.
    let mut band = Responder::new(SimMode::Enrolled, SigningKey::random(&mut OsRng));
    let request = connection.request().unwrap();
    pump(&mut connection, &mut band, request, 1.0);
    assert!(connection.take_log().contains(&"connected".to_owned()));
    assert!(connection.status_json().contains(r#""connected":true"#));
    (connection, band)
}

fn gesture(
    connection: &mut Connection,
    band: &mut Responder,
    action: u64,
    finger: u64,
    derived: u64,
    now: f64,
) {
    let bytes = band.gesture(action, finger, derived).unwrap();
    let reply = connection.feed(&bytes, now).unwrap();
    pump(connection, band, reply, now);
}

/// The actions since the last call, split: what to run, and the `@` events (without the
/// Activity screen's `@activity` lines: see [activities]).
fn taken(connection: &mut Connection) -> (Vec<String>, Vec<String>) {
    connection.take_actions().into_iter().filter(|line| !line.starts_with("@activity ")).partition(|line| !line.starts_with('@'))
}

/// The Activity screen's lines since the last call, as JSON.
fn activities(connection: &mut Connection) -> Vec<serde_json::Value> {
    connection
        .take_actions()
        .iter()
        .filter_map(|line| line.strip_prefix("@activity "))
        .map(|json| serde_json::from_str(json).unwrap())
        .collect()
}

#[test]
fn a_swipe_down_plays_the_next_track() {
    let (mut connection, mut band) = connected(false);
    gesture(&mut connection, &mut band, 7, THUMB, 0, 3.0);
    assert_eq!(taken(&mut connection), (vec!["media.next".to_owned()], vec!["@gesture swipe down".to_owned()]));
}

#[test]
fn the_activity_screen_hears_each_gesture_with_its_palm_and_what_it_did() {
    let (mut connection, mut band) = connected(false);
    gesture(&mut connection, &mut band, 7, THUMB, 0, 3.0);
    // No orientation yet: palm down, and no calibration of the user's own.
    assert_eq!(
        activities(&mut connection),
        vec![serde_json::json!({"what": "swipe down", "palm": "down", "calibrated": false, "run": "media.next"})]
    );
    gesture(&mut connection, &mut band, 0, MIDDLE, 3, 4.0);
    let held = &activities(&mut connection)[0];
    assert_eq!((held["what"].clone(), held["band"].clone(), held["result"].clone()), (serde_json::json!("middle hold"), serde_json::json!("controls"), serde_json::json!("off")));
    // Off: a gesture says so (band-core's Unused::ControlsOff).
    gesture(&mut connection, &mut band, 7, THUMB, 0, 5.0);
    assert_eq!(activities(&mut connection)[0]["off"], serde_json::json!(true));
    // On again; a swipe left is bound to nothing (Unused::Unbound).
    gesture(&mut connection, &mut band, 0, MIDDLE, 3, 7.0);
    activities(&mut connection);
    gesture(&mut connection, &mut band, 8, THUMB, 0, 9.0);
    let left = &activities(&mut connection)[0];
    assert_eq!((left["what"].clone(), left["nothing"].clone()), (serde_json::json!("swipe left"), serde_json::json!(true)));
    // The palm band-core resolved with: turned palm up (−142.3° on its right-hand calibration).
    roll_to(&mut connection, &mut band, -142.3, 11.0);
    gesture(&mut connection, &mut band, 7, THUMB, 0, 11.1);
    let down = &activities(&mut connection)[0];
    assert_eq!((down["palm"].clone(), down["run"].clone()), (serde_json::json!("up"), serde_json::json!("media.next")));
}

#[test]
fn an_index_tap_plays_or_pauses_once_its_double_tap_window_passes() {
    let (mut connection, mut band) = connected_with(false, &json(&[("index_double", "volume.mute")], 0));
    gesture(&mut connection, &mut band, 3, INDEX, 0, 3.0);
    assert!(taken(&mut connection).0.is_empty(), "held for a possible double tap");
    connection.tick(3.2).unwrap();
    assert!(connection.take_actions().is_empty());
    connection.tick(3.45).unwrap();
    assert_eq!(
        taken(&mut connection),
        (vec!["media.play_pause".to_owned()], vec!["@gesture index tap".to_owned()])
    );
}

#[test]
fn a_middle_hold_turns_the_controls_off_and_on() {
    let (mut connection, mut band) = connected(false);
    gesture(&mut connection, &mut band, 0, MIDDLE, 3, 3.0);
    assert_eq!(taken(&mut connection).1, vec!["@gesture middle hold".to_owned(), "@controls off".to_owned()]);
    assert!(connection.status_json().contains(r#""paused":true"#));
    // Off: gestures do nothing…
    gesture(&mut connection, &mut band, 7, THUMB, 0, 5.0);
    assert!(taken(&mut connection).0.is_empty());
    // …except the hold, which turns them back on.
    gesture(&mut connection, &mut band, 0, MIDDLE, 3, 7.0);
    assert!(taken(&mut connection).1.contains(&"@controls on".to_owned()));
    assert!(connection.status_json().contains(r#""paused":false"#));
}

#[test]
fn quiet_links_send_keepalive_queries() {
    let (mut connection, _band) = connected(false);
    assert!(!connection.tick(10.0).unwrap().is_empty());
}

#[test]
fn a_malformed_owner_key_or_profiles_json_is_refused() {
    assert!(Connection::new(&[0u8; 10], 0, false, "{}").is_err());
    let identity = EnrollmentIdentity::new(SigningKey::random(&mut OsRng), None);
    assert!(Connection::new(&identity.private_bytes(), 0, false, "{").is_err());
}

#[test]
fn remapped_gestures_run_their_new_actions() {
    let (mut connection, mut band) = connected(false);
    connection.set_profiles(&json(&[("swipe_down", "volume.up"), ("swipe_left", "media.next")], 0)).unwrap();
    gesture(&mut connection, &mut band, 7, THUMB, 0, 3.0);
    assert_eq!(taken(&mut connection).0, vec!["volume.up".to_owned()]);
    gesture(&mut connection, &mut band, 8, THUMB, 0, 4.0);
    assert_eq!(taken(&mut connection).0, vec!["media.next".to_owned()]);
}

#[test]
fn an_action_on_a_band_control_gesture_waits_until_the_band_control_moves() {
    let (mut connection, mut band) = connected_with(false, &json(&[("middle_hold", "media.next")], 0));
    gesture(&mut connection, &mut band, 0, MIDDLE, 3, 3.0);
    assert_eq!(taken(&mut connection), (vec![], vec!["@gesture middle hold".to_owned(), "@controls off".to_owned()]));
    gesture(&mut connection, &mut band, 0, MIDDLE, 3, 5.0);
    assert!(taken(&mut connection).1.contains(&"@controls on".to_owned()));
    let moved = r#"{"controls":{"gesture":"swipe_left"},"next":{"gesture":"index_triple"},"previous":{"gesture":"middle_triple"}}"#;
    connection.set_profiles(&json_full("band", moved, &[("middle_hold", "media.next")], 0)).unwrap();
    gesture(&mut connection, &mut band, 0, MIDDLE, 3, 7.0);
    assert_eq!(taken(&mut connection).0, vec!["media.next".to_owned()]);
    assert!(connection.status_json().contains(r#""paused":false"#));
}

#[test]
fn an_unmapped_gesture_does_nothing() {
    let (mut connection, mut band) = connected(false);
    connection.set_profiles(&json(&[("swipe_down", "")], 0)).unwrap();
    gesture(&mut connection, &mut band, 7, THUMB, 0, 3.0);
    assert_eq!(taken(&mut connection), (vec![], vec!["@gesture swipe down".to_owned()]));
}

#[test]
fn a_band_on_the_other_wrist_is_set_to_the_chosen_hand_after_connecting() {
    // The simulated band starts as right-handed.
    let (mut connection, mut band) = connected_with(false, &json_full("left", STANDARD, &[], 0));
    for step in 0..20 {
        let now = 2.0 + f64::from(step) * 0.05;
        let out = connection.tick(now).unwrap();
        pump(&mut connection, &mut band, out, now);
    }
    assert_eq!(band.hand, 1, "the band stores left");
    assert!(connection.status_json().contains(r#""hand":"left""#), "{}", connection.status_json());
}

#[test]
fn a_hand_chosen_while_connected_is_written_on_the_next_tick() {
    let (mut connection, mut band) = connected(false);
    connection.set_profiles(&json_full("left", STANDARD, &[], 0)).unwrap();
    for step in 0..20 {
        let now = 2.0 + f64::from(step) * 0.05;
        let out = connection.tick(now).unwrap();
        pump(&mut connection, &mut band, out, now);
    }
    assert_eq!(band.hand, 1, "the band stores left");
}

#[test]
fn a_triple_tap_with_two_profiles_reports_the_switch() {
    let (mut connection, mut band) = connected_with(false, &json(&[], 1));
    for now in [3.0, 3.2, 3.4] {
        gesture(&mut connection, &mut band, 3, INDEX, 0, now);
    }
    connection.tick(4.0).unwrap();
    let (run, events) = taken(&mut connection);
    assert!(run.is_empty(), "{run:?}");
    assert_eq!(events, vec!["@gesture index triple tap".to_owned(), "@profile 1 1".to_owned()]);
}

#[test]
fn practice_reports_what_would_run() {
    let (mut connection, mut band) = connected(false);
    connection.set_practice(true);
    gesture(&mut connection, &mut band, 3, INDEX, 0, 3.0);
    connection.tick(4.0).unwrap();
    assert_eq!(
        taken(&mut connection),
        (vec![], vec!["@gesture index tap".to_owned(), "@practiced media.play_pause".to_owned()])
    );
}

#[test]
fn the_stream_probe_logs_the_flags_and_every_gesture_in_full() {
    let identity = EnrollmentIdentity::new(SigningKey::random(&mut OsRng), None);
    let mut connection = Connection::new(&identity.private_bytes(), 0, false, &json(&[], 0)).unwrap();
    connection.set_probe("1, 4,7,x");
    let mut band = Responder::new(SimMode::Enrolled, SigningKey::random(&mut OsRng));
    let request = connection.request().unwrap();
    pump(&mut connection, &mut band, request, 1.0);
    let log = connection.take_log();
    assert!(log.contains(&"connected".to_owned()));
    assert!(log.iter().any(|line| line.starts_with("probe: stream flags (request 3): ")), "{log:?}");
    gesture(&mut connection, &mut band, 7, THUMB, 0, 3.0);
    let log = connection.take_log();
    assert!(log.iter().any(|line| line.starts_with("probe gesture thumb down unknown: 1=")), "{log:?}");
}

#[test]
fn without_the_probe_the_session_notes_stay_out_of_the_log() {
    let (mut connection, mut band) = connected(false);
    gesture(&mut connection, &mut band, 7, THUMB, 0, 3.0);
    assert!(!connection.take_log().iter().any(|line| line.starts_with("probe")));
}

#[test]
fn the_app_opens_and_cancels_a_drawing_and_an_idle_one_ends_by_itself() {
    let (mut connection, _band) = connected(false);
    connection.start_drawing(3.0).unwrap();
    assert_eq!(taken(&mut connection).1, vec!["@draw-started".to_owned()]);
    connection.cancel_drawing().unwrap();
    assert_eq!(taken(&mut connection).1, vec!["@draw-cancelled".to_owned()]);
    connection.start_drawing(4.0).unwrap();
    taken(&mut connection);
    connection.tick(4.0 + band_core::controls::DRAW_IDLE).unwrap();
    assert!(taken(&mut connection).1.contains(&"@draw-cancelled".to_owned()));
}

#[test]
fn teaching_drags_cancels_an_open_drawing_and_opens_none() {
    let (mut connection, _band) = connected(false);
    connection.start_drawing(3.0).unwrap();
    taken(&mut connection);
    connection.set_drag_teaching(true).unwrap();
    assert_eq!(taken(&mut connection).1, vec!["@draw-cancelled".to_owned()]);
    connection.start_drawing(4.0).unwrap();
    assert!(taken(&mut connection).1.is_empty(), "never while teaching drags");
}

#[test]
fn a_bad_library_is_refused() {
    let (mut connection, _band) = connected(false);
    assert!(connection.set_library("[").is_err());
    connection.set_library(r#"[{"name":"Circle","tries":[[[[0,0],[10,0],[10,10],[0,10],[0,0]]]]}]"#).unwrap();
}

#[test]
fn the_palm_readout_follows_the_bands_orientation() {
    let (mut connection, mut band) = connected(false);
    assert_eq!(connection.palm_now(3.0), r#"{"palm":null,"roll":null}"#);
    // Roll −142.3° (turned about the band's y): palm up on the right hand's measured calibration
    // (palm down −11.3°, palm up −142.3° since band-core put them the right way round).
    roll_to(&mut connection, &mut band, -142.3, 3.0);
    assert_eq!(connection.palm_now(3.1), r#"{"palm":"up","roll":-142.3}"#);
    assert_eq!(connection.palm_now(4.0), r#"{"palm":null,"roll":null}"#, "stale");
}

/// [json_full] with a `calibration` (per wrist, as the app stores it).
fn with_calibration(json: String, calibration: serde_json::Value) -> String {
    let mut value: serde_json::Value = serde_json::from_str(&json).unwrap();
    value["calibration"] = calibration;
    value.to_string()
}

/// The band at a roll of `roll` degrees (turned about its y; see palmlive's tests).
fn roll_to(connection: &mut Connection, band: &mut Responder, roll: f64, now: f64) {
    let half = (-roll).to_radians() / 2.0;
    let bytes = band.orientation([half.cos() as f32, 0.0, half.sin() as f32, 0.0]).unwrap();
    let reply = connection.feed(&bytes, now).unwrap();
    pump(connection, band, reply, now);
}

#[test]
fn the_palm_follows_the_calibration_of_the_wrist_in_use() {
    // A right wrist calibrated the other way round: −11.3° is palm up there.
    let right = serde_json::json!({"right": {"palm": {"down": -150.0, "up": -10.0}}});
    let (mut connection, mut band) = connected_with(false, &with_calibration(json(&[], 0), right));
    roll_to(&mut connection, &mut band, -11.3, 3.0);
    assert_eq!(connection.palm_now(3.1), r#"{"palm":"up","roll":-11.3}"#);
    // Calibrated away: band-core's own right hand, where −11.3° is palm down.
    connection.set_profiles(&json(&[], 0)).unwrap();
    roll_to(&mut connection, &mut band, -11.3, 3.2);
    assert_eq!(connection.palm_now(3.3), r#"{"palm":"down","roll":-11.3}"#);
}

#[test]
fn the_band_moving_to_the_other_wrist_brings_that_wrists_calibration() {
    let both = serde_json::json!({
        "right": {"palm": {"down": -10.0, "up": -150.0}},
        "left": {"palm": {"down": -150.0, "up": -10.0}},
    });
    let (mut connection, mut band) = connected_with(false, &with_calibration(json(&[], 0), both.clone()));
    roll_to(&mut connection, &mut band, -11.3, 2.0);
    assert_eq!(connection.palm_now(2.1), r#"{"palm":"down","roll":-11.3}"#);
    // Set to the left wrist: once the band says so, the left calibration reads the same roll.
    connection.set_profiles(&with_calibration(json_full("left", STANDARD, &[], 0), both)).unwrap();
    for step in 0..20 {
        let now = 2.2 + f64::from(step) * 0.05;
        let out = connection.tick(now).unwrap();
        pump(&mut connection, &mut band, out, now);
    }
    assert_eq!(band.hand, 1, "the band stores left");
    roll_to(&mut connection, &mut band, -11.3, 3.5);
    assert_eq!(connection.palm_now(3.6), r#"{"palm":"up","roll":-11.3}"#);
}

#[test]
fn turning_the_controls_off_from_the_app_closes_an_open_drawing() {
    let (mut connection, _band) = connected(false);
    connection.start_drawing(3.0).unwrap();
    taken(&mut connection);
    connection.set_paused(true, 3.5);
    assert_eq!(taken(&mut connection).1, vec!["@draw-cancelled".to_owned()], "and no @controls: the app knows");
}
