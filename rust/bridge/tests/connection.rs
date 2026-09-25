use airgestures_bridge::Connection;
use band_core::identity::EnrollmentIdentity;
use band_core::responder::{Responder, SimMode};
use p256::ecdsa::SigningKey;
use rand_core::OsRng;

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

#[test]
fn an_owner_key_connects_and_gestures_come_out_as_lines() {
    let identity = EnrollmentIdentity::new(SigningKey::random(&mut OsRng), None);
    let mut connection = Connection::new(&identity.private_bytes(), 0).unwrap();
    // An enrolled band with no owner recorded adopts the first key that proves trust.
    let mut band = Responder::new(SimMode::Enrolled, SigningKey::random(&mut OsRng));
    let request = connection.request().unwrap();
    pump(&mut connection, &mut band, request, 1.0);
    assert!(connection.take_events().contains(&"connected".to_owned()));

    // Thumb (1) swipe left (8).
    let bytes = band.gesture(8, 1, 0).unwrap();
    let reply = connection.feed(&bytes, 2.0).unwrap();
    assert!(reply.is_empty() || band.respond(&reply).is_ok());
    assert_eq!(connection.take_events(), vec!["gesture swipe left".to_owned()]);

    // Quiet link: tick sends keepalive queries the band answers.
    let keepalive = connection.tick(10.0).unwrap();
    assert!(!keepalive.is_empty());
}

#[test]
fn a_malformed_owner_key_is_refused() {
    assert!(Connection::new(&[0u8; 10], 0).is_err());
}
