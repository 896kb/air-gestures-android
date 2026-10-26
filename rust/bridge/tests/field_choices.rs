//! Word choices shown in the field when typing into it ends. Its own test binary: it loads the
//! dictionary, which every connection shares.

use airgestures_bridge::Connection;
use band_core::handwriting::Written;
use band_core::identity::EnrollmentIdentity;
use band_core::responder::{Responder, SimMode};
use p256::ecdsa::SigningKey;
use rand_core::OsRng;

fn pump(connection: &mut Connection, band: &mut Responder, mut to_band: Vec<u8>, now: f64) {
    for _ in 0..64 {
        if to_band.is_empty() {
            return;
        }
        let reply = band.respond(&to_band).unwrap();
        to_band = connection.feed(&reply, now).unwrap();
    }
}

/// Connected with the controls on, typing into a field whose word choices show in it.
fn writing() -> (Connection, Responder) {
    airgestures_bridge::words::load_dictionary("cat 1000\ncar 900\ncan 800\n");
    let identity = EnrollmentIdentity::new(SigningKey::random(&mut OsRng), None);
    let profiles = r#"{"hand":"band","picked":0,"apps":{},"calls":{},"profiles":[{"name":"A"}],
        "band":{"controls":{"gesture":"middle_hold"}}}"#;
    let mut connection = Connection::new(&identity.private_bytes(), 0, false, profiles).unwrap();
    let mut band = Responder::new(SimMode::Enrolled, SigningKey::random(&mut OsRng));
    let request = connection.request().unwrap();
    pump(&mut connection, &mut band, request, 1.0);
    let on = connection.set_field_typing(true, "", false, 2.0).unwrap();
    pump(&mut connection, &mut band, on, 2.0);
    (connection, band)
}

fn letter(c: char) -> Written {
    let index = (c as u8 - b'a') as usize;
    let mut letters = [0.0; 26];
    letters[index] = 1.0;
    Written { index, margin: 3.0, letters }
}

/// The field's text after the edits since the last look.
fn look(connection: &mut Connection, text: &mut String, now: f64) -> serde_json::Value {
    let look: serde_json::Value = serde_json::from_str(&connection.field_edits(now)).unwrap();
    for edit in look["edits"].as_array().unwrap() {
        for _ in 0..edit["delete"].as_u64().unwrap() {
            text.pop();
        }
        text.push_str(edit["insert"].as_str().unwrap());
    }
    look
}

#[test]
fn the_controls_going_off_take_the_word_choices_out_of_the_field() {
    let (mut connection, _band) = writing();
    let mut text = String::new();
    connection.type_symbols(&[letter('c'), letter('a')], 3.0);
    look(&mut connection, &mut text, 3.0);
    let word = text.clone();
    assert!(connection.field_choose());
    look(&mut connection, &mut text, 3.1);
    assert_ne!(text, word, "the choices show in the field");
    connection.set_paused(true, 3.2);
    let last = look(&mut connection, &mut text, 3.3);
    assert_eq!(last["active"], serde_json::json!(false));
    assert_eq!(text, word, "the word as it was");
    // Taken once.
    assert_eq!(look(&mut connection, &mut text, 3.4)["edits"], serde_json::json!([]));
}

#[test]
fn leaving_the_field_takes_the_word_choices_out_too() {
    let (mut connection, _band) = writing();
    let mut text = String::new();
    connection.type_symbols(&[letter('c'), letter('a')], 3.0);
    look(&mut connection, &mut text, 3.0);
    let word = text.clone();
    assert!(connection.field_choose());
    look(&mut connection, &mut text, 3.1);
    connection.set_field_typing(false, "", false, 3.2).unwrap();
    look(&mut connection, &mut text, 3.3);
    assert_eq!(text, word);
}
