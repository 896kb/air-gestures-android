//! Claiming a band in pairing mode for this phone (air-gesturesd
//! `pairing.rs`): the ownership ceremony over the
//! band's L2CAP channel, with Meta's two HTTP steps run right where the
//! session asks for them (blocking; the band waits), and the new owner key
//! saved in the app's files directory.
//!
//! Key-file rule (as the desktop's store): `owner.pending.key` is written
//! before the bytes that let the band commit (FinishChangeOwner) leave, so a
//! lost confirmation still leaves a key the band may accept; on
//! `CeremonyCompleted` the key becomes `owner.key` and the pending file goes.
//! An explicit ownership rejection means the band did not commit: the pending
//! file is dropped then. Kotlin writes `band.json` (it knows the device).

use std::fs::{self, File, OpenOptions};
use std::io::{self, Write};
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};

use band_core::BandError;
use band_core::airshield::SCHEME_26_GUESSES;
use band_core::ceremony::{CeremonyHttpRequest, OwnershipCeremony};
use band_core::events::Event;
use band_core::identity::EnrollmentIdentity;
use band_core::session::BandSession;

use crate::meta::{HttpPairClient, MetaSession, PairClient, PairError};

pub const OWNER: &str = "owner.key";
pub const PENDING_OWNER: &str = "owner.pending.key";

/// The whole ceremony, including two HTTP round trips (air-gesturesd's link).
const CEREMONY_TIMEOUT: f64 = 180.0;
/// A band that hasn't sent one authenticated packet by now isn't answering
/// this key guess (or isn't in pairing mode).
const HANDSHAKE_TIMEOUT: f64 = 30.0;
/// After the band confirmed the owner: how long to wait for the owner
/// connection before calling it done anyway (the key is saved).
const SETTLE: f64 = 10.0;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ClaimState {
    Running,
    /// The owner key is saved.
    Done,
    Failed(String),
    /// The Meta session is expired or revoked: sign in again.
    Expired(String),
}

impl std::fmt::Display for ClaimState {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ClaimState::Running => f.write_str("running"),
            ClaimState::Done => f.write_str("done"),
            ClaimState::Failed(message) => write!(f, "failed: {message}"),
            ClaimState::Expired(message) => write!(f, "expired: {message}"),
        }
    }
}

pub struct Claim {
    session: BandSession,
    client: Box<dyn PairClient>,
    dir: PathBuf,
    guess: usize,
    state: ClaimState,
    log: Vec<String>,
    started: Option<f64>,
    /// `owner.pending.key` was written this run (the band may have committed).
    pending_written: bool,
    /// When `owner.key` was written.
    saved_at: Option<f64>,
    /// A Meta step failed (a different key guess wouldn't help).
    meta_failed: bool,
}

impl Claim {
    /// `session_json` is what `signinFinish` returned; `dir` the app's files directory.
    pub fn new(session_json: &str, dir: impl Into<PathBuf>, guess: usize) -> Result<Self, String> {
        let session: MetaSession = serde_json::from_str(session_json)
            .map_err(|_| "the saved meta sign-in is unreadable. sign in again.".to_string())?;
        let client = HttpPairClient::new(session).map_err(|error| error.to_string())?;
        Ok(Self::with_client(Box::new(client), dir, guess))
    }

    pub fn with_client(client: Box<dyn PairClient>, dir: impl Into<PathBuf>, guess: usize) -> Self {
        Self {
            session: BandSession::new(None, Some(OwnershipCeremony::default()), false)
                .with_scheme_guess(guess),
            client,
            dir: dir.into(),
            guess,
            state: ClaimState::Running,
            log: Vec::new(),
            started: None,
            pending_written: false,
            saved_at: None,
            meta_failed: false,
        }
    }

    pub fn state(&self) -> &ClaimState {
        &self.state
    }

    /// Progress lines since the last call.
    pub fn take_log(&mut self) -> Vec<String> {
        std::mem::take(&mut self.log)
    }

    /// The first bytes to write once the L2CAP channel is open.
    pub fn request(&mut self) -> Vec<u8> {
        match self.session.request() {
            Ok(bytes) => bytes,
            Err(error) => {
                self.band_failed(error);
                Vec::new()
            }
        }
    }

    /// Bytes read from the band at `now` (seconds, monotonic); returns bytes to
    /// write. May block on Meta's HTTP steps.
    pub fn feed(&mut self, bytes: &[u8], now: f64) -> Vec<u8> {
        self.started.get_or_insert(now);
        if self.state != ClaimState::Running {
            return Vec::new();
        }
        let result = match self.session.feed(bytes, now) {
            Ok(result) => result,
            Err(error) => {
                self.band_failed(error);
                return Vec::new();
            }
        };
        let mut outgoing = result.outgoing;
        for event in result.events {
            if self.state != ClaimState::Running {
                break;
            }
            outgoing.extend(self.on_event(event, now));
        }
        outgoing
    }

    /// Call every ~50 ms; returns bytes to write.
    pub fn tick(&mut self, now: f64) -> Vec<u8> {
        let started = *self.started.get_or_insert(now);
        if self.state != ClaimState::Running {
            return Vec::new();
        }
        if let Some(saved) = self.saved_at {
            if now - saved >= SETTLE {
                self.state = ClaimState::Done;
            }
            return Vec::new();
        }
        if self.session.authenticated_packets() == 0 && now - started >= HANDSHAKE_TIMEOUT {
            self.fail("the band didn't answer. hold its button for 3 seconds until it flashes, then try again.");
            return Vec::new();
        }
        if now - started >= CEREMONY_TIMEOUT {
            self.fail("the claim took too long. put the band back in pairing mode and try again.");
            return Vec::new();
        }
        let mut outgoing = Vec::new();
        for event in self.session.tick(now) {
            outgoing.extend(self.on_event(event, now));
        }
        outgoing
    }

    /// The channel closed (or failed) with the claim still running.
    pub fn closed(&mut self, message: &str) {
        if self.state != ClaimState::Running {
            return;
        }
        if self.saved_at.is_some() {
            self.state = ClaimState::Done;
        } else if message.is_empty() {
            self.fail("the band closed the connection");
        } else {
            self.fail(message);
        }
    }

    /// After a failure: the next key guess worth a fresh connection, if any.
    /// Newer band firmware uses an undocumented encryption scheme (26); a guess
    /// the band drops before a single packet authenticates is harmless (nothing
    /// was decrypted, so nothing can have been claimed), as on the desktop.
    pub fn next_guess(&self) -> Option<usize> {
        let retry = matches!(self.state, ClaimState::Failed(_))
            && self.session.scheme_guess().is_some()
            && self.session.authenticated_packets() == 0
            && !self.pending_written
            && self.saved_at.is_none()
            && !self.meta_failed
            && self.guess + 1 < SCHEME_26_GUESSES.len();
        retry.then_some(self.guess + 1)
    }

    fn on_event(&mut self, event: Event, now: f64) -> Vec<u8> {
        match event {
            Event::CeremonyStage(stage) => {
                self.log.push(stage);
                Vec::new()
            }
            Event::CeremonyHttp(CeremonyHttpRequest::PairRequest(request)) => {
                self.log.push("asking Meta to approve the claim".into());
                match self.client.pair_request(&request) {
                    Ok(pending) => self
                        .session
                        .ceremony_pair_request_completed(&pending.signature, &pending.receipt)
                        .unwrap_or_else(|error| {
                            self.band_failed(error);
                            Vec::new()
                        }),
                    Err(error) => {
                        self.meta_error(error);
                        Vec::new()
                    }
                }
            }
            Event::CeremonyHttp(CeremonyHttpRequest::Pair(pair)) => {
                self.log.push("confirming the claim with Meta".into());
                let done = match self.client.pair(&pair) {
                    Ok(done) => done,
                    Err(error) => {
                        self.meta_error(error);
                        return Vec::new();
                    }
                };
                let bytes = match self.session.ceremony_pair_completed(
                    &done.signature,
                    &done.receipt,
                    done.device_public_key.as_deref(),
                ) {
                    Ok(bytes) => bytes,
                    Err(error) => {
                        self.band_failed(error);
                        return Vec::new();
                    }
                };
                // The band may commit as soon as it reads these bytes: the key
                // must be on disk first, or the claim is abandoned here.
                if let Some(identity) = self.session.pending_identity() {
                    if let Err(error) = write_identity(&self.dir, PENDING_OWNER, &identity) {
                        self.fail(&format!("could not save the pending owner key: {error}"));
                        return Vec::new();
                    }
                    self.pending_written = true;
                }
                bytes
            }
            Event::CeremonyCompleted(identity) => {
                match write_identity(&self.dir, OWNER, &identity)
                    .and_then(|()| remove(&self.dir, PENDING_OWNER))
                {
                    Ok(()) => {
                        self.saved_at = Some(now);
                        self.log
                            .push("the band accepted this phone as its owner".into());
                    }
                    Err(error) => self.fail(&format!("could not save the owner key: {error}")),
                }
                Vec::new()
            }
            Event::Connected => {
                self.log.push("connected as the owner".into());
                if self.saved_at.is_some() {
                    self.state = ClaimState::Done;
                }
                // Close politely; the service reconnects as the owner.
                self.session.stop().unwrap_or_default()
            }
            _ => Vec::new(),
        }
    }

    fn meta_error(&mut self, error: PairError) {
        self.meta_failed = true;
        match error {
            PairError::SessionInvalid(message) => {
                self.log.push(message.clone());
                self.state = ClaimState::Expired(message);
            }
            PairError::Failed(message) => self.fail(&message),
        }
    }

    fn band_failed(&mut self, error: BandError) {
        match error {
            // The band refused the claim, so it did not commit: this run's
            // pending key would never connect.
            BandError::OwnershipRejected { message, .. } => {
                if self.pending_written && self.saved_at.is_none() {
                    let _ = remove(&self.dir, PENDING_OWNER);
                    self.pending_written = false;
                }
                self.fail(&message);
            }
            BandError::IdentityMismatch { message, .. }
            | BandError::Protocol(message)
            | BandError::Busy(message) => self.fail(&message),
        }
    }

    fn fail(&mut self, message: &str) {
        if self.saved_at.is_some() {
            // The key is saved; a hiccup after that doesn't undo the claim.
            self.state = ClaimState::Done;
            return;
        }
        self.log.push(message.to_owned());
        self.state = ClaimState::Failed(message.to_owned());
    }
}

/// One file: the 32-byte private key, optionally followed by the 65-byte
/// X9.63 band key (32 or 97 bytes; air-gesturesd `store.rs` `write_identity`).
pub fn write_identity(dir: &Path, name: &str, identity: &EnrollmentIdentity) -> io::Result<()> {
    let mut bytes = identity.private_bytes().to_vec();
    if let Some(band_key) = identity.band_key_x963() {
        bytes.extend(band_key);
    }
    write_secret(dir, name, &bytes)
}

/// Atomically: a 0600 temp file, fsynced, renamed over `name`, then the directory fsynced.
fn write_secret(dir: &Path, name: &str, bytes: &[u8]) -> io::Result<()> {
    let temp = dir.join(format!(".{name}.tmp"));
    let _ = fs::remove_file(&temp);
    {
        let mut file = OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(true)
            .mode(0o600)
            .open(&temp)?;
        file.write_all(bytes)?;
        file.sync_all()?;
    }
    fs::rename(&temp, dir.join(name))?;
    File::open(dir)?.sync_all()
}

fn remove(dir: &Path, name: &str) -> io::Result<()> {
    match fs::remove_file(dir.join(name)) {
        Err(error) if error.kind() != io::ErrorKind::NotFound => Err(error),
        _ => File::open(dir)?.sync_all(),
    }
}
