//! A claim against band-core's simulated band in pairing mode, with Meta's two
//! steps answered by a fake client (as air-gesturesd's pairing tests do).

use std::path::Path;
use std::sync::{Arc, Mutex};

use airgestures_bridge::Connection;
use airgestures_bridge::claim::{Claim, ClaimState, OWNER, PENDING_OWNER};
use airgestures_bridge::meta::{Final, PairClient, PairError, Pending};
use band_core::airshield::SCHEME_26_GUESSES;
use band_core::ceremony::{CeremonyPair, CeremonyPairRequest};
use band_core::responder::{Responder, SimMode};
use p256::ecdsa::SigningKey;
use rand_core::OsRng;

/// Answers both steps without a network; records which steps ran.
struct FakeMeta {
    device_point: Option<Vec<u8>>,
    fail: Option<PairError>,
    calls: Arc<Mutex<Vec<&'static str>>>,
}

impl PairClient for FakeMeta {
    fn pair_request(&self, _request: &CeremonyPairRequest) -> Result<Pending, PairError> {
        self.calls.lock().unwrap().push("pair_request");
        if let Some(error) = &self.fail {
            return Err(error.clone());
        }
        Ok(Pending {
            signature: vec![0x30, 0x44, 1],
            receipt: r#"{"receipt_type":"ServerPendingOwnershipReceipt"}"#.into(),
        })
    }

    fn pair(&self, _pair: &CeremonyPair) -> Result<Final, PairError> {
        self.calls.lock().unwrap().push("pair");
        Ok(Final {
            signature: vec![0x30, 0x44, 2],
            receipt: r#"{"receipt_type":"ServerFinalOwnershipReceipt"}"#.into(),
            device_public_key: self.device_point.clone(),
        })
    }
}

fn band() -> Responder {
    Responder::new(SimMode::Ceremony, SigningKey::random(&mut OsRng))
}

fn claim_for(band: &Responder, dir: &Path, fail: Option<PairError>, guess: usize) -> Claim {
    let meta = FakeMeta {
        device_point: Some(band.band_point()),
        fail,
        calls: Arc::default(),
    };
    Claim::with_client(Box::new(meta), dir, guess)
}

/// Ping-pong until neither side has anything to say, the claim ends, or
/// `stop_when` holds after a feed. A band that can't read the host's bytes
/// drops the channel, as a real one does.
fn pump(claim: &mut Claim, band: &mut Responder, stop_when: impl Fn(&Claim) -> bool) {
    let mut to_band = claim.request();
    for _ in 0..64 {
        if to_band.is_empty() || *claim.state() != ClaimState::Running || stop_when(claim) {
            return;
        }
        let reply = match band.respond(&to_band) {
            Ok(reply) => reply,
            Err(_) => {
                claim.closed("the band closed the connection");
                return;
            }
        };
        if reply.is_empty() {
            return;
        }
        to_band = claim.feed(&reply, 1.0);
    }
}

fn run(claim: &mut Claim, band: &mut Responder) {
    pump(claim, band, |_| false);
}

#[test]
fn a_claim_saves_the_owner_key_and_the_key_connects_as_owner() {
    let dir = tempfile::tempdir().unwrap();
    let band_key = SigningKey::random(&mut OsRng);
    let mut band = Responder::new(SimMode::Ceremony, band_key.clone());
    let mut claim = claim_for(&band, dir.path(), None, 0);
    run(&mut claim, &mut band);
    assert_eq!(*claim.state(), ClaimState::Done);
    let log = claim.take_log();
    for line in [
        "reading the band identity",
        "asking Meta to approve the claim",
        "confirming the claim with Meta",
        "the band accepted this phone as its owner",
    ] {
        assert!(log.iter().any(|l| l == line), "{line} missing from {log:?}");
    }
    assert!(!dir.path().join(PENDING_OWNER).exists());
    let key = std::fs::read(dir.path().join(OWNER)).unwrap();
    assert_eq!(key.len(), 97, "the private key and the band's key");
    assert!(band.owner().is_some());

    // The next connection (the service) authenticates with the saved key.
    let mut owned = Responder::new(SimMode::Enrolled, band_key);
    owned.set_owner(band.owner());
    let mut connection = Connection::new(&key, 0, false, "{}").unwrap();
    let mut to_band = connection.request().unwrap();
    for _ in 0..64 {
        if to_band.is_empty() {
            break;
        }
        let reply = owned.respond(&to_band).unwrap();
        to_band = connection.feed(&reply, 2.0).unwrap();
    }
    assert!(connection.take_log().contains(&"connected".to_owned()));
}

#[test]
fn a_band_of_another_account_is_reported_and_nothing_is_kept() {
    let dir = tempfile::tempdir().unwrap();
    let mut band = band();
    band.reject_claim = Some(0x03001042);
    let mut claim = claim_for(&band, dir.path(), None, 0);
    run(&mut claim, &mut band);
    match claim.state() {
        ClaimState::Failed(message) => assert!(message.contains("different meta account")),
        other => panic!("expected a failure, got {other:?}"),
    }
    assert!(!dir.path().join(OWNER).exists());
    assert!(!dir.path().join(PENDING_OWNER).exists());
    assert!(claim.next_guess().is_none());
}

#[test]
fn a_finish_the_band_rejects_drops_the_pending_key() {
    let dir = tempfile::tempdir().unwrap();
    let mut band = band();
    band.reject_finish = Some(0x03001040);
    let mut claim = claim_for(&band, dir.path(), None, 0);
    run(&mut claim, &mut band);
    assert!(
        matches!(claim.state(), ClaimState::Failed(_)),
        "{:?}",
        claim.state()
    );
    assert!(!dir.path().join(OWNER).exists());
    assert!(!dir.path().join(PENDING_OWNER).exists());
}

#[test]
fn a_lost_confirmation_keeps_the_pending_key() {
    let dir = tempfile::tempdir().unwrap();
    let mut band = band();
    let mut claim = claim_for(&band, dir.path(), None, 0);
    let pending = dir.path().join(PENDING_OWNER);
    // The channel drops right after FinishChangeOwner was handed over.
    pump(&mut claim, &mut band, |_| pending.exists());
    assert!(pending.exists(), "written before the band could commit");
    claim.closed("");
    assert!(matches!(claim.state(), ClaimState::Failed(_)));
    assert!(pending.exists(), "kept: the band may have committed");
    assert!(!dir.path().join(OWNER).exists());
    assert!(claim.next_guess().is_none());
}

#[test]
fn an_expired_meta_session_asks_for_a_fresh_sign_in() {
    let dir = tempfile::tempdir().unwrap();
    let mut band = band();
    let fail = Some(PairError::SessionInvalid(
        "your meta session expired.".into(),
    ));
    let mut claim = claim_for(&band, dir.path(), fail, 0);
    run(&mut claim, &mut band);
    assert!(matches!(claim.state(), ClaimState::Expired(_)));
    assert!(claim.state().to_string().starts_with("expired: "));
    assert!(!dir.path().join(OWNER).exists() && !dir.path().join(PENDING_OWNER).exists());
}

#[test]
fn a_silent_band_times_out() {
    let dir = tempfile::tempdir().unwrap();
    let band = band();
    let mut claim = claim_for(&band, dir.path(), None, 0);
    claim.request();
    claim.tick(100.0);
    assert_eq!(*claim.state(), ClaimState::Running);
    claim.tick(131.0);
    assert!(
        claim
            .state()
            .to_string()
            .starts_with("failed: the band didn't answer")
    );
}

#[test]
fn newer_firmware_is_claimed_after_trying_the_key_guesses_in_turn() {
    let dir = tempfile::tempdir().unwrap();
    let key = SigningKey::random(&mut OsRng);
    let mut guess = 0;
    let mut tried = Vec::new();
    loop {
        tried.push(guess);
        // A fresh channel per guess; the band keeps its key and stays in pairing mode.
        let mut band = Responder::new(SimMode::Ceremony, key.clone());
        band.firmware = Some(SCHEME_26_GUESSES[3]);
        let mut claim = claim_for(&band, dir.path(), None, guess);
        run(&mut claim, &mut band);
        if claim.state() == &ClaimState::Running {
            claim.closed("the band closed the connection");
        }
        match claim.next_guess() {
            Some(next) => guess = next,
            None => {
                let state = claim.state().clone();
                assert_eq!(state, ClaimState::Done, "{:?}", claim.take_log());
                break;
            }
        }
    }
    assert_eq!(tried, vec![0, 1, 2, 3]);
    assert!(dir.path().join(OWNER).exists());
}
