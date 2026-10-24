//! Firmware update over the band's RPC service, as Meta AI does it (`emg_ota.proto`,
//! `bulk_transfer.proto`): update status, the key (KEK) if the band lacks it, then for the
//! firmware and the ML bundle Start, a bulk transfer and Verify, and at the end either Abort
//! (a trial: nothing is installed) or Commit (the band reboots into the new firmware).
//!
//! Sans-IO like the session: [`OtaJob`] hands out `RpcRequest` bodies and reads the matching
//! `RpcResponse`s; the session frames, encrypts and routes them. Progress goes to [`OtaJob::log`].

use std::collections::BTreeMap;

use crate::proto::{ProtoFields, field_bytes, field_int};

/// Bytes per bulk transfer chunk, as Meta AI sends them.
const CHUNK: usize = 950;
/// A single request's answer (Meta AI waits 5 s, for chunks too).
const REPLY_S: f64 = 5.0;
/// A chunk is sent again this many times before the transfer gives up.
const CHUNK_RETRIES: u32 = 4;
/// Meta AI only sends an ML bundle bigger than this.
const ML_MIN: usize = 10 * 1024;

const ACTIVE_FIRMWARE: u64 = 0;
const LATEST_FIRMWARE: u64 = 1;
const ML_MODEL: u64 = 2;

const OTA_STATES: [&str; 4] = ["UNAVAILABLE", "IDLE", "UPDATE_IN_PROGRESS", "UPDATE_READY"];
const UNAVAILABLE: [&str; 5] = ["none", "no BLE", "disabled on the band", "power (charge the band)", "trial firmware running"];

/// The band's answer to an update-status request (`DeviceInfoResponse`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct OtaStatus {
    /// `OtaState`: 0 unavailable, 1 idle, 2 in progress, 3 ready (staged).
    pub state: u64,
    /// `DeviceState`: 0 stable, 1 trial, 2 recovery firmware.
    pub device: u64,
    pub kek: u64,
    /// `UnavailableReason` when unavailable.
    pub reason: u64,
    /// By item type: active firmware, latest (staged) firmware, ML model.
    pub versions: [Option<u64>; 3],
}

impl OtaStatus {
    pub fn read(info: &ProtoFields) -> Self {
        let mut versions = [None; 3];
        for (field, bytes) in info.byte_fields() {
            if field == 5
                && let Ok(item) = ProtoFields::parse(bytes)
                && let (Ok(kind), Ok(version)) = (item.integer(1), item.integer(2))
                && let Some(slot) = versions.get_mut(kind as usize)
            {
                *slot = Some(version);
            }
        }
        Self {
            state: info.integer(4).unwrap_or(0),
            device: info.integer(3).unwrap_or(0),
            kek: info.integer(6).unwrap_or(0),
            reason: info.integer(7).unwrap_or(0),
            versions,
        }
    }

    /// From a whole `RpcResponse` carrying `OtaResp.deviceInfoResponse`.
    pub fn from_response(fields: &ProtoFields) -> Option<Self> {
        let response = ProtoFields::parse(fields.bytes(8).ok()?).ok()?;
        Some(Self::read(&ProtoFields::parse(response.bytes(2).ok()?).ok()?))
    }

    pub fn state_name(&self) -> &'static str {
        OTA_STATES.get(self.state as usize).unwrap_or(&"?")
    }
}

impl std::fmt::Display for OtaStatus {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let shown = |v: Option<u64>| v.map_or("-".to_string(), |v| v.to_string());
        write!(
            f,
            "{} {}, active {}, staged {}, ML {}, key {}",
            self.state_name(),
            ["stable firmware", "TRIAL firmware", "RECOVERY firmware"].get(self.device as usize).unwrap_or(&"?"),
            shown(self.versions[ACTIVE_FIRMWARE as usize]),
            shown(self.versions[LATEST_FIRMWARE as usize]),
            shown(self.versions[ML_MODEL as usize]),
            self.kek,
        )?;
        if self.state == 0 {
            write!(f, ", unavailable: {}", UNAVAILABLE.get(self.reason as usize).unwrap_or(&"?"))?;
        }
        Ok(())
    }
}

/// Which part of an update to run. Each step is its own command so a person decides on the
/// next: [`Key`](Self::Key), [`Send`](Self::Send), then [`Install`](Self::Install) or
/// [`Cancel`](Self::Cancel).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OtaMode {
    /// Install the package's key on the band if it lacks it. Persistent.
    Key,
    /// Send each item and have the band verify it, then stop: the update stays staged (not
    /// installed). A firmware already staged at this version isn't sent again.
    Send,
    /// Key, Send, then Abort: nothing stays staged.
    Trial,
    /// Abort whatever update the band holds (in progress or staged).
    Cancel,
    /// Commit the staged update: the band reboots into it. Probably one-way.
    Install,
    /// All of it in one connection, as Meta AI does: abort any earlier update, the key if
    /// needed, send and verify each item, then Commit. The band refuses a Commit
    /// (REJECTED_INCORRECT_STATE) once the connection that verified the items is gone.
    Update,
}

pub struct OtaPlan {
    /// The server's `target_version`, sent as both items' version.
    pub version: u64,
    pub firmware: Vec<u8>,
    pub firmware_md5: [u8; 16],
    /// The ML bundle with its MD5, when the package has one.
    pub model: Option<(Vec<u8>, [u8; 16])>,
    /// `(kek_id, encrypted key)`: the package's key, wrapped for this band by Meta.
    pub kek: Option<(u64, Vec<u8>)>,
    pub mode: OtaMode,
    /// Pick up an interrupted update instead of aborting it: no Start, the last item's
    /// transfer resumes where the band says, then Verify (earlier items were verified).
    pub resume: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Ask {
    /// The status before anything else.
    First,
    /// After a staged or half-done update was aborted.
    AfterReset,
    /// After installing the key: it should now match.
    AfterKek,
    /// After the trial's Abort: nothing should be staged.
    Last,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Step {
    Status(Ask),
    Reset,
    Kek,
    Start,
    TransferInfo,
    Verify,
    Abort,
    Commit,
    /// The Abort sent after a failure.
    Rescue,
}

/// One file of an update, for a progress meter.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OtaItemProgress {
    pub name: &'static str,
    pub size: usize,
    /// Bytes the band has acknowledged.
    pub sent: usize,
    /// "waiting", "sending", "checking" or "done" (verified by the band).
    pub stage: &'static str,
}

struct Item {
    kind: u64,
    name: &'static str,
    data: Vec<u8>,
    md5: [u8; 16],
}

struct Sent {
    seq: u64,
    at: f64,
    tries: u32,
}

struct Transfer {
    id: u64,
    /// The next offset not yet sent.
    next: usize,
    window: usize,
    /// Unacknowledged chunks by offset.
    pending: BTreeMap<usize, Sent>,
    started: f64,
    /// Where this transfer began (the band's resume offset): the rate counts from here.
    from: usize,
    shown: usize,
}

pub struct OtaJob {
    plan: OtaPlan,
    items: Vec<Item>,
    item: usize,
    /// The request in flight (not chunks): its id, deadline and what it was.
    waiting: Option<(u64, f64, Step)>,
    transfer: Option<Transfer>,
    transfer_ids: u64,
    log: Vec<String>,
    done: bool,
    /// Commit was sent: from here nothing may be aborted.
    committed: bool,
}

impl OtaJob {
    pub fn new(plan: OtaPlan) -> Self {
        Self {
            plan,
            items: Vec::new(),
            item: 0,
            waiting: None,
            transfer: None,
            transfer_ids: 0,
            log: Vec::new(),
            done: false,
            committed: false,
        }
    }

    pub fn done(&self) -> bool {
        self.done
    }

    /// Whether stopping now is safe: Commit not yet sent. Before it the band keeps running
    /// its firmware whatever happens to the update.
    pub fn cancellable(&self) -> bool {
        !self.committed && !matches!(self.waiting, Some((_, _, Step::Commit)))
    }

    /// The mode this job runs.
    pub fn mode(&self) -> OtaMode {
        self.plan.mode
    }

    /// Each file of the update and how far it is (empty until the band's status chose them).
    pub fn items(&self) -> Vec<OtaItemProgress> {
        self.items
            .iter()
            .enumerate()
            .map(|(index, item)| {
                let size = item.data.len();
                let (sent, stage) = if index < self.item {
                    (size, "done")
                } else if index > self.item {
                    (0, "waiting")
                } else if let Some(transfer) = &self.transfer {
                    (transfer.next.saturating_sub(transfer.pending.len() * CHUNK).min(size), "sending")
                } else if matches!(self.waiting, Some((_, _, Step::Verify))) {
                    (size, "checking")
                } else {
                    (0, "waiting")
                };
                OtaItemProgress { name: item.name, size, sent, stage }
            })
            .collect()
    }

    pub fn log(&mut self) -> Vec<String> {
        std::mem::take(&mut self.log)
    }

    fn say(&mut self, line: impl Into<String>) {
        self.log.push(format!("ota: {}", line.into()));
    }

    /// The first request (the update status, or Abort to cancel). `ids` is the session's
    /// request counter.
    pub fn begin(&mut self, ids: &mut u64, now: f64) -> Vec<Vec<u8>> {
        let kind = match self.plan.mode {
            OtaMode::Key => "key",
            OtaMode::Send => "send and verify (stays staged; not installed)",
            OtaMode::Trial => "trial (ends with Abort; nothing is installed)",
            OtaMode::Cancel => {
                self.say("cancelling the band's update");
                return vec![self.ask(Step::Abort, ids, now)];
            }
            OtaMode::Install => "INSTALL of the staged update (Commit)",
            OtaMode::Update => "UPDATE in one go (send, verify, then Commit)",
        };
        self.say(format!("{kind} of {}", self.plan.version));
        vec![self.ask(Step::Status(Ask::First), ids, now)]
    }

    /// Whether `seq` is one of ours (the session routes the response here then).
    pub fn owns(&self, seq: u64) -> bool {
        self.waiting.is_some_and(|(id, _, _)| id == seq)
            || self.transfer.as_ref().is_some_and(|t| t.pending.values().any(|sent| sent.seq == seq))
    }

    fn ota(&self, ids: &mut u64, request: Vec<u8>) -> (u64, Vec<u8>) {
        *ids += 1;
        (*ids, [field_int(1, *ids), field_bytes(7, &request)].concat())
    }

    fn bulk(ids: &mut u64, request: Vec<u8>) -> (u64, Vec<u8>) {
        *ids += 1;
        (*ids, [field_int(1, *ids), field_bytes(9, &request)].concat())
    }

    fn item_info(&self, kind: u64) -> Vec<u8> {
        [field_int(1, kind), field_int(2, self.plan.version)].concat()
    }

    /// Build and remember the request for `step`.
    fn ask(&mut self, step: Step, ids: &mut u64, now: f64) -> Vec<u8> {
        let request = match step {
            Step::Status(_) => field_bytes(1, &[]),
            Step::Reset | Step::Abort | Step::Rescue => field_bytes(5, &[]),
            Step::Kek => {
                let (id, key) = self.plan.kek.as_ref().expect("checked before");
                field_bytes(6, &[field_int(1, *id), field_int(2, 0), field_bytes(3, key)].concat())
            }
            Step::Start => {
                let item = &self.items[self.item];
                let start = [
                    field_bytes(1, &self.item_info(item.kind)),
                    field_int(2, item.data.len() as u64),
                    field_int(3, 0),
                    field_int(4, 0),
                ]
                .concat();
                field_bytes(2, &start)
            }
            Step::Verify => field_bytes(3, &field_bytes(1, &self.item_info(self.items[self.item].kind))),
            Step::Commit => field_bytes(4, &field_int(1, 0)),
            Step::TransferInfo => unreachable!("sent by start_transfer"),
        };
        let (seq, body) = self.ota(ids, request);
        self.waiting = Some((seq, now + REPLY_S * 3.0, step));
        body
    }

    /// Something went wrong: say so, and unless committed, abort whatever the band holds.
    fn fail(&mut self, why: impl Into<String>, ids: &mut u64, now: f64) -> Vec<Vec<u8>> {
        self.say(format!("FAILED: {}", why.into()));
        self.transfer = None;
        // Only a trial cleans up after itself; otherwise the person decides (cancel or retry).
        if self.committed || !matches!(self.plan.mode, OtaMode::Trial | OtaMode::Update) {
            self.done = true;
            return Vec::new();
        }
        self.say("aborting, so nothing stays staged");
        vec![self.ask(Step::Rescue, ids, now)]
    }

    /// The `Result.resultCode` inside a reply message (0 = OK).
    fn result_code(message: &[u8]) -> Option<u64> {
        let message = ProtoFields::parse(message).ok()?;
        let result = ProtoFields::parse(message.bytes(1).ok()?).ok()?;
        result.integer(1).ok()
    }

    fn result_name(code: u64) -> String {
        let name = match code {
            0 => "OK",
            100 => "INVALID_ITEM",
            200 => "REJECTED_NO_SPACE",
            201 => "REJECTED_POWER_STATE",
            202 => "REJECTED_OLD_VERSION",
            203 => "REJECTED_UPDATE_IN_PROGRESS",
            204 => "REJECTED_NO_UPDATE_IN_PROGRESS",
            205 => "REJECTED_INCORRECT_STATE",
            206 => "REJECTED_RUNNING_TRIAL_FIRMWARE",
            207 => "REJECTED_MISMATCHED_KEK_VERSION",
            208 => "REJECTED_INVALID_KEK",
            209 => "REJECTED_KEK_INSTALL_FAILED",
            300 => "INCORRECT_FILE_CHECKSUM",
            _ => "unknown",
        };
        format!("{name} ({code})")
    }

    /// The response to one of our requests (`fields` = the whole `RpcResponse`).
    pub fn receive(&mut self, fields: &ProtoFields, ids: &mut u64, now: f64) -> Vec<Vec<u8>> {
        let Ok(seq) = fields.integer(1) else { return Vec::new() };
        if fields.contains(10) {
            return self.receive_bulk(fields, seq, ids, now);
        }
        let Some((id, _, step)) = self.waiting else { return Vec::new() };
        if id != seq {
            return Vec::new();
        }
        self.waiting = None;
        let Some(response) = fields.bytes(8).ok().and_then(|b| ProtoFields::parse(b).ok()) else {
            let code = fields.integer(2).unwrap_or(0);
            return self.fail(format!("no update answer to {step:?} (RPC code {code})"), ids, now);
        };
        match step {
            Step::Status(ask) => self.status(&response, ask, ids, now),
            Step::Verify => {
                let code = response.bytes(3).ok().and_then(Self::result_code)
                    .or_else(|| response.bytes(1).ok().and_then(Self::result_code));
                if code != Some(0) {
                    self.say(format!("verify answer: {}", response.describe_nested(0)));
                    let name = code.map_or("no result".into(), Self::result_name);
                    return self.fail(format!("{} didn't verify: {name}", self.items[self.item].name), ids, now);
                }
                self.say(format!("{} verified by the band", self.items[self.item].name));
                self.item += 1;
                self.next_item(ids, now)
            }
            Step::Commit => {
                self.committed = true;
                self.done = true;
                // CommitUpdateResponse{result, willReboot}; the band may answer with a
                // GeneralResponse instead. A Result left at OK can arrive empty.
                let code = response.bytes(4).ok().and_then(Self::result_code)
                    .or_else(|| response.bytes(1).ok().and_then(Self::result_code));
                // Not secret: result codes and flags only. Before the verdict, which the app
                // follows as the update's latest line.
                self.say(format!("commit answer: {}", response.describe_nested(0)));
                match code {
                    Some(0) => self.say("COMMITTED: the band now reboots into the new firmware; the app checks it on reconnecting"),
                    _ => self.say(format!("commit answered {}", code.map_or("no result".into(), Self::result_name))),
                }
                Vec::new()
            }
            _ => {
                let code = response.bytes(1).ok().and_then(Self::result_code);
                self.general(step, code, ids, now)
            }
        }
    }

    /// A `GeneralResponse` to Reset, Kek, Start, Abort or Rescue.
    fn general(&mut self, step: Step, code: Option<u64>, ids: &mut u64, now: f64) -> Vec<Vec<u8>> {
        let shown = code.map_or("no result".into(), Self::result_name);
        match step {
            Step::Reset => {
                self.say(format!("abort of the earlier update: {shown}"));
                vec![self.ask(Step::Status(Ask::AfterReset), ids, now)]
            }
            Step::Kek if code == Some(0) => {
                self.say("key installed; checking");
                vec![self.ask(Step::Status(Ask::AfterKek), ids, now)]
            }
            Step::Kek => self.fail(format!("the band refused the key: {shown}"), ids, now),
            Step::Start if code == Some(0) => {
                self.say(format!("{}: start accepted", self.items[self.item].name));
                self.start_transfer(ids, now)
            }
            Step::Start => self.fail(format!("{}: start refused: {shown}", self.items[self.item].name), ids, now),
            Step::Abort => {
                // REJECTED_INCORRECT_STATE when nothing was staged is harmless.
                self.say(format!("aborted: {shown}; checking nothing is staged"));
                vec![self.ask(Step::Status(Ask::Last), ids, now)]
            }
            Step::Rescue => {
                self.say(format!("abort after the failure: {shown}"));
                self.done = true;
                Vec::new()
            }
            _ => Vec::new(),
        }
    }

    fn status(&mut self, response: &ProtoFields, ask: Ask, ids: &mut u64, now: f64) -> Vec<Vec<u8>> {
        let Some(info) = response.bytes(2).ok().and_then(|b| ProtoFields::parse(b).ok()) else {
            return self.fail("the status answer had no device info", ids, now);
        };
        let status = OtaStatus::read(&info);
        let (state, kek, versions) = (status.state, status.kek, status.versions);
        self.say(format!("status: {status}"));
        match ask {
            Ask::Last => {
                self.done = true;
                let clean = state == 1 && versions[LATEST_FIRMWARE as usize] == versions[ACTIVE_FIRMWARE as usize];
                match (self.plan.mode, clean) {
                    (OtaMode::Trial, true) => self.say("TRIAL OK: transferred and verified, then aborted; nothing is staged"),
                    (_, true) => self.say("CANCELLED: nothing is staged"),
                    _ => self.say("the band doesn't look clean; check the status above"),
                }
                Vec::new()
            }
            Ask::First if self.plan.mode == OtaMode::Key => {
                self.after_status(kek, 0, ids, now)
            }
            Ask::First if self.plan.mode == OtaMode::Install => {
                self.done = true;
                if state != 3 || versions[LATEST_FIRMWARE as usize] != Some(self.plan.version) {
                    self.say(format!("nothing to install: {} isn't staged (send it first)", self.plan.version));
                    return Vec::new();
                }
                // The band refuses a Commit (REJECTED_INCORRECT_STATE) in a later connection than
                // the Verifies: verify each staged item again first, then commit.
                self.done = false;
                self.choose_items(u64::MAX);
                self.next_item(ids, now)
            }
            Ask::First | Ask::AfterReset => {
                if versions[ACTIVE_FIRMWARE as usize] == Some(self.plan.version) {
                    self.done = true;
                    self.say("the band already runs this version; nothing to do");
                    return Vec::new();
                }
                if self.plan.resume && state == 2 {
                    return self.resume(kek, ids, now);
                }
                let staged = versions[LATEST_FIRMWARE as usize] == Some(self.plan.version);
                match state {
                    0 => self.fail("the band says updates are unavailable now", ids, now),
                    // As Meta AI: this firmware is already received and verified; send the rest.
                    3 if staged && self.plan.mode != OtaMode::Update && kek == self.plan.kek.as_ref().map_or(kek, |(id, _)| *id) => {
                        self.say("this firmware is already staged; skipping it");
                        self.choose_items(versions[ML_MODEL as usize].unwrap_or(0));
                        self.item = 1;
                        self.next_item(ids, now)
                    }
                    2 | 3 if !matches!(self.plan.mode, OtaMode::Trial | OtaMode::Update) => {
                        self.done = true;
                        self.say("another update is in progress or staged; cancel it first");
                        Vec::new()
                    }
                    2 | 3 if ask == Ask::First => {
                        self.say("an earlier update is in progress or staged; aborting it first");
                        vec![self.ask(Step::Reset, ids, now)]
                    }
                    1 => self.after_status(kek, versions[ML_MODEL as usize].unwrap_or(0), ids, now),
                    _ => self.fail("the band is still busy with an earlier update", ids, now),
                }
            }
            Ask::AfterKek => {
                let want = self.plan.kek.as_ref().map_or(0, |(id, _)| *id);
                if kek != want {
                    return self.fail(format!("the band still reports key {kek}, not {want}"), ids, now);
                }
                self.say(format!("the band now has key {kek}"));
                if self.plan.mode == OtaMode::Key {
                    self.done = true;
                    return Vec::new();
                }
                self.after_status(kek, versions[ML_MODEL as usize].unwrap_or(0), ids, now)
            }
        }
    }

    /// [`OtaPlan::resume`]: continue the last item of an update the band still holds.
    fn resume(&mut self, kek: u64, ids: &mut u64, now: f64) -> Vec<Vec<u8>> {
        let want = self.plan.kek.as_ref().map_or(kek, |(id, _)| *id);
        if kek != want {
            self.done = true;
            self.say(format!("nothing to resume: the band has key {kek}, not {want}"));
            return Vec::new();
        }
        self.choose_items(u64::MAX);
        self.item = self.items.len() - 1;
        let name = self.items[self.item].name;
        self.say(format!("resuming: asking where the {name} transfer stopped (no Start)"));
        self.start_transfer(ids, now)
    }

    /// The items to send, firmware first; the ML bundle only when newer than the band's `ml`.
    fn choose_items(&mut self, ml: u64) {
        if !self.items.is_empty() {
            return;
        }
        self.items.push(Item {
            kind: ACTIVE_FIRMWARE,
            name: "firmware",
            data: std::mem::take(&mut self.plan.firmware),
            md5: self.plan.firmware_md5,
        });
        match self.plan.model.take() {
            Some((data, md5)) if data.len() > ML_MIN && (ml == u64::MAX || self.plan.version > ml) => {
                self.items.push(Item { kind: ML_MODEL, name: "ML bundle", data, md5 });
            }
            Some(_) => self.say(format!("ML bundle skipped (the band's model {ml} is current)")),
            None => {}
        }
    }

    /// The band is idle: install the key if needed, choose the items, start the first.
    fn after_status(&mut self, kek: u64, ml: u64, ids: &mut u64, now: f64) -> Vec<Vec<u8>> {
        match &self.plan.kek {
            Some((want, _)) if *want != kek && self.plan.mode == OtaMode::Send => {
                let want = *want;
                return self.fail(format!("the band has key {kek}; install key {want} first"), ids, now);
            }
            Some((want, _)) if *want != kek => {
                self.say(format!("installing key {want} (the band has {kek})"));
                return vec![self.ask(Step::Kek, ids, now)];
            }
            None => {
                self.say(format!("no key given; the band has key {kek}, so the package must use it"));
            }
            Some((want, _)) if self.plan.mode == OtaMode::Key => {
                self.done = true;
                self.say(format!("the band already has key {want}"));
                return Vec::new();
            }
            _ => {}
        }
        // An update sends the whole package: after an abort the band can still report the
        // aborted ML bundle's version, so its ML version can't be trusted to skip the bundle.
        self.choose_items(if self.plan.mode == OtaMode::Update { u64::MAX } else { ml });
        self.next_item(ids, now)
    }

    fn next_item(&mut self, ids: &mut u64, now: f64) -> Vec<Vec<u8>> {
        if self.plan.mode == OtaMode::Install {
            if self.item < self.items.len() {
                self.say(format!("{}: asking the band to verify it again", self.items[self.item].name));
                return vec![self.ask(Step::Verify, ids, now)];
            }
            self.say("committing the staged update");
            return vec![self.ask(Step::Commit, ids, now)];
        }
        if self.item < self.items.len() {
            let item = &self.items[self.item];
            self.say(format!("{}: starting ({} bytes)", item.name, item.data.len()));
            return vec![self.ask(Step::Start, ids, now)];
        }
        if self.plan.mode == OtaMode::Trial {
            self.say("all items verified; aborting (trial)");
            return vec![self.ask(Step::Abort, ids, now)];
        }
        if self.plan.mode == OtaMode::Update {
            self.say("all items verified; committing");
            return vec![self.ask(Step::Commit, ids, now)];
        }
        self.done = true;
        self.say(format!("STAGED: {} is received and verified, not installed; install or cancel next", self.plan.version));
        Vec::new()
    }

    fn start_transfer(&mut self, ids: &mut u64, now: f64) -> Vec<Vec<u8>> {
        self.transfer_ids += 1;
        let item = &self.items[self.item];
        let info = [
            field_bytes(1, &item.md5),
            field_int(2, item.data.len() as u64),
            field_int(3, 1),
            field_int(4, self.transfer_ids),
        ]
        .concat();
        let (seq, body) = Self::bulk(ids, field_bytes(1, &info));
        self.waiting = Some((seq, now + REPLY_S, Step::TransferInfo));
        self.transfer = Some(Transfer {
            id: self.transfer_ids,
            next: 0,
            window: 0,
            pending: BTreeMap::new(),
            started: now,
            from: 0,
            shown: 0,
        });
        vec![body]
    }

    fn chunk(&self, offset: usize, ids: &mut u64) -> (u64, Vec<u8>) {
        let data = &self.items[self.item].data;
        let end = (offset + CHUNK).min(data.len());
        let id = self.transfer.as_ref().map_or(0, |t| t.id);
        let chunk = [field_int(1, id), field_int(2, offset as u64), field_bytes(3, &data[offset..end])].concat();
        Self::bulk(ids, field_bytes(2, &chunk))
    }

    /// Send new chunks while the window has room.
    fn fill(&mut self, ids: &mut u64, now: f64) -> Vec<Vec<u8>> {
        let size = self.items[self.item].data.len();
        let mut out = Vec::new();
        loop {
            let Some(transfer) = self.transfer.as_ref() else { break };
            if transfer.pending.len() >= transfer.window || transfer.next >= size {
                break;
            }
            let offset = transfer.next;
            let (seq, body) = self.chunk(offset, ids);
            let transfer = self.transfer.as_mut().expect("present");
            transfer.pending.insert(offset, Sent { seq, at: now, tries: 0 });
            transfer.next = (offset + CHUNK).min(size);
            out.push(body);
        }
        out
    }

    fn receive_bulk(&mut self, fields: &ProtoFields, seq: u64, ids: &mut u64, now: f64) -> Vec<Vec<u8>> {
        let Some(response) = fields.bytes(10).ok().and_then(|b| ProtoFields::parse(b).ok()) else {
            return Vec::new();
        };
        if let Some((id, _, Step::TransferInfo)) = self.waiting
            && id == seq
        {
            self.waiting = None;
            let Some(info) = response.bytes(1).ok().and_then(|b| ProtoFields::parse(b).ok()) else {
                return self.fail("the transfer setup had no info answer", ids, now);
            };
            let state = info.integer(1).unwrap_or(0);
            let memory = info.integer(2).unwrap_or(0) as usize;
            let offset = info.integer(3).unwrap_or(0) as usize;
            let name = self.items[self.item].name;
            self.say(format!("{name}: transfer state {state}, band memory {memory}, resume at {offset}"));
            match state {
                3 => {
                    self.transfer = None;
                    self.say(format!("{name}: the band already has all of it; verifying"));
                    return vec![self.ask(Step::Verify, ids, now)];
                }
                1 | 2 if memory >= CHUNK => {
                    let transfer = self.transfer.as_mut().expect("present");
                    transfer.window = memory / CHUNK;
                    transfer.next = offset.min(self.items[self.item].data.len());
                    transfer.from = transfer.next;
                    transfer.shown = transfer.next * 100 / self.items[self.item].data.len().max(1) / 5 * 5;
                    transfer.started = now;
                    return self.fill(ids, now);
                }
                1 | 2 => return self.fail(format!("{name}: the band has too little memory ({memory})"), ids, now),
                _ => return self.fail(format!("{name}: unknown transfer state {state}"), ids, now),
            }
        }
        let Some(transfer) = self.transfer.as_mut() else { return Vec::new() };
        let Some(offset) = transfer.pending.iter().find(|(_, sent)| sent.seq == seq).map(|(o, _)| *o) else {
            return Vec::new();
        };
        if let Ok(nack) = response.bytes(3) {
            let reason = ProtoFields::parse(nack).ok().and_then(|n| n.text(2)).unwrap_or_default();
            return self.fail(format!("the band refused a chunk at {offset}: {reason:?}"), ids, now);
        }
        let Some(ack) = response.bytes(2).ok().and_then(|b| ProtoFields::parse(b).ok()) else {
            return self.fail(format!("odd answer to the chunk at {offset}"), ids, now);
        };
        let acked = ack.integer(2).unwrap_or(offset as u64) as usize;
        transfer.pending.remove(&acked);
        transfer.pending.remove(&offset);
        let size = self.items[self.item].data.len();
        let done = transfer.next.saturating_sub(transfer.pending.len() * CHUNK).min(size);
        let percent = done * 100 / size.max(1);
        let progress = (percent >= transfer.shown + 5).then(|| {
            transfer.shown = percent / 5 * 5;
            done.saturating_sub(transfer.from) as f64 / (now - transfer.started).max(0.001) / 1024.0
        });
        let finished = transfer.pending.is_empty() && transfer.next >= size;
        let seconds = now - transfer.started;
        let name = self.items[self.item].name;
        if let Some(rate) = progress {
            self.say(format!("{name}: {percent}% ({rate:.1} KiB/s)"));
        }
        if finished {
            self.say(format!("{name}: sent in {seconds:.0} s; verifying"));
            return vec![self.ask(Step::Verify, ids, now)];
        }
        self.fill(ids, now)
    }

    /// Timeouts: resend late chunks, give up on silent requests.
    pub fn tick(&mut self, ids: &mut u64, now: f64) -> Vec<Vec<u8>> {
        if let Some((_, deadline, step)) = self.waiting
            && now >= deadline
        {
            self.waiting = None;
            if step == Step::Rescue {
                self.say("no answer to the abort after the failure; ask the status");
                self.done = true;
                return Vec::new();
            }
            if step == Step::Commit {
                self.committed = true;
                self.done = true;
                self.say("no answer to Commit (the band may be rebooting); reconnect, then ask the status");
                return Vec::new();
            }
            return self.fail(format!("no answer to {step:?}"), ids, now);
        }
        let late: Vec<usize> = match self.transfer.as_ref() {
            Some(t) => t.pending.iter().filter(|(_, s)| now - s.at >= REPLY_S).map(|(o, _)| *o).collect(),
            None => return Vec::new(),
        };
        let mut out = Vec::new();
        for offset in late {
            let tries = self.transfer.as_ref().and_then(|t| t.pending.get(&offset)).map_or(0, |s| s.tries);
            if tries >= CHUNK_RETRIES {
                return self.fail(format!("no answer for the chunk at {offset} after {} tries", tries + 1), ids, now);
            }
            let (seq, body) = self.chunk(offset, ids);
            if let Some(transfer) = self.transfer.as_mut() {
                transfer.pending.insert(offset, Sent { seq, at: now, tries: tries + 1 });
            }
            out.push(body);
        }
        out
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    pub fn reply(seq: u64, field: u32, body: Vec<u8>) -> ProtoFields {
        ProtoFields::parse(&[field_int(1, seq), field_int(2, 1), field_bytes(field, &body)].concat()).unwrap()
    }

    pub fn seq_of(body: &[u8]) -> u64 {
        ProtoFields::parse(body).unwrap().integer(1).unwrap()
    }

    pub fn general(code: u64) -> Vec<u8> {
        field_bytes(1, &field_bytes(1, &field_int(1, code)))
    }

    pub fn status(state: u64, active: u64, latest: u64, kek: u64) -> Vec<u8> {
        let item = |kind, version| field_bytes(5, &[field_int(1, kind), field_int(2, version)].concat());
        let info = [field_int(2, 1), field_int(4, state), item(0, active), item(1, latest), field_bytes(5, &field_int(1, 2)), field_int(6, kek)].concat();
        field_bytes(2, &info)
    }

    pub fn requests(body: &[u8]) -> (u32, ProtoFields) {
        let fields = ProtoFields::parse(body).unwrap();
        for field in [7, 9] {
            if let Ok(inner) = fields.bytes(field) {
                return (field, ProtoFields::parse(inner).unwrap());
            }
        }
        panic!("no request")
    }

    /// The whole trial against a pretend band: key, two items, chunks, verify, abort.
    #[test]
    fn a_trial_installs_the_key_sends_both_items_and_aborts() {
        let firmware: Vec<u8> = (0..5000u32).map(|i| i as u8).collect();
        let model: Vec<u8> = vec![7; 12_000];
        let mut job = OtaJob::new(OtaPlan {
            version: 64,
            firmware: firmware.clone(),
            firmware_md5: [1; 16],
            model: Some((model.clone(), [2; 16])),
            kek: Some((20, vec![9; 32])),
            mode: OtaMode::Trial,
            resume: false,
        });
        let mut ids = 10;
        let mut now = 0.0;
        let mut queue = job.begin(&mut ids, now);
        let mut received: Vec<Vec<u8>> = vec![Vec::new(), Vec::new()];
        let mut kek = 1;
        let mut steps = Vec::new();
        while let Some(body) = queue.pop() {
            now += 0.01;
            let seq = seq_of(&body);
            let (field, request) = requests(&body);
            let answer = if field == 7 {
                let (kind, _) = request.byte_fields()[0];
                steps.push(kind);
                match kind {
                    1 => reply(seq, 8, status(1, 1, 1, kek)),
                    6 => {
                        kek = 20;
                        reply(seq, 8, general(0))
                    }
                    2 | 5 => reply(seq, 8, general(0)),
                    3 => reply(seq, 8, field_bytes(3, &field_bytes(1, &field_int(1, 0)))),
                    _ => panic!("commit in a trial"),
                }
            } else if let Ok(info) = request.bytes(1) {
                let info = ProtoFields::parse(info).unwrap();
                assert_eq!(info.integer(3).unwrap(), 1);
                reply(seq, 10, field_bytes(1, &[field_int(1, 1), field_int(2, 4 * 950)].concat()))
            } else {
                let chunk = ProtoFields::parse(request.bytes(2).unwrap()).unwrap();
                let item = if steps.iter().filter(|&&k| k == 2).count() == 1 { 0 } else { 1 };
                let offset = chunk.integer(2).unwrap() as usize;
                let data = chunk.bytes(3).unwrap();
                assert_eq!(received[item].len(), offset, "in order");
                received[item].extend_from_slice(data);
                reply(seq, 10, field_bytes(2, &[field_int(1, chunk.integer(1).unwrap()), field_int(2, offset as u64)].concat()))
            };
            assert!(job.owns(seq));
            let mut more = job.receive(&answer, &mut ids, now);
            more.reverse();
            queue.splice(0..0, more);
        }
        let log = job.log().join("\n");
        assert!(job.done(), "{log}");
        assert!(log.contains("TRIAL OK"), "{log}");
        assert_eq!(received[0], firmware);
        assert_eq!(received[1], model);
        assert_eq!(steps.iter().filter(|&&k| k == 4).count(), 0);
        assert!(steps.contains(&6) && steps.ends_with(&[5, 1]), "{steps:?}");
    }

    fn plan(mode: OtaMode) -> OtaPlan {
        OtaPlan { version: 64, firmware: vec![0; 2000], firmware_md5: [0; 16], model: None, kek: Some((20, vec![9; 32])), mode, resume: false }
    }

    #[test]
    fn a_refused_start_is_aborted_in_a_trial_and_left_alone_otherwise() {
        for (mode, aborts) in [(OtaMode::Trial, true), (OtaMode::Send, false)] {
            let mut job = OtaJob::new(plan(mode));
            let mut ids = 0;
            let first = job.begin(&mut ids, 0.0);
            let start = job.receive(&reply(seq_of(&first[0]), 8, status(1, 1, 1, 20)), &mut ids, 0.1);
            let after = job.receive(&reply(seq_of(&start[0]), 8, general(202)), &mut ids, 0.2);
            assert_eq!(!after.is_empty(), aborts, "{mode:?}");
            if aborts {
                assert_eq!(requests(&after[0]).1.byte_fields()[0].0, 5, "abort");
                job.receive(&reply(seq_of(&after[0]), 8, general(0)), &mut ids, 0.3);
            }
            let log = job.log().join("\n");
            assert!(job.done() && log.contains("REJECTED_OLD_VERSION"), "{log}");
        }
    }

    #[test]
    fn each_step_does_only_its_part() {
        // Send without the key: refused before anything is started.
        let mut job = OtaJob::new(plan(OtaMode::Send));
        let mut ids = 0;
        let first = job.begin(&mut ids, 0.0);
        assert!(job.receive(&reply(seq_of(&first[0]), 8, status(1, 1, 1, 1)), &mut ids, 0.1).is_empty());
        assert!(job.done());
        // Key: installs it and stops.
        let mut job = OtaJob::new(plan(OtaMode::Key));
        let first = job.begin(&mut ids, 0.0);
        let kek = job.receive(&reply(seq_of(&first[0]), 8, status(1, 1, 1, 1)), &mut ids, 0.1);
        assert_eq!(requests(&kek[0]).1.byte_fields()[0].0, 6);
        let check = job.receive(&reply(seq_of(&kek[0]), 8, general(0)), &mut ids, 0.2);
        assert!(job.receive(&reply(seq_of(&check[0]), 8, status(1, 1, 1, 20)), &mut ids, 0.3).is_empty());
        assert!(job.done());
        // Install: only when this version is staged, and then only Commit.
        let mut job = OtaJob::new(plan(OtaMode::Install));
        let first = job.begin(&mut ids, 0.0);
        assert!(job.receive(&reply(seq_of(&first[0]), 8, status(1, 1, 1, 20)), &mut ids, 0.1).is_empty());
        let mut job = OtaJob::new(plan(OtaMode::Install));
        let first = job.begin(&mut ids, 0.0);
        let verify = job.receive(&reply(seq_of(&first[0]), 8, status(3, 1, 64, 20)), &mut ids, 0.1);
        assert_eq!(requests(&verify[0]).1.byte_fields()[0].0, 3);
        let ok = || field_bytes(3, &field_bytes(1, &field_int(1, 0)));
        let commit = job.receive(&reply(seq_of(&verify[0]), 8, ok()), &mut ids, 0.2);
        assert_eq!(requests(&commit[0]).1.byte_fields()[0].0, 4);
        // Cancel: Abort straight away, then a status check.
        let mut job = OtaJob::new(plan(OtaMode::Cancel));
        let abort = job.begin(&mut ids, 0.0);
        assert_eq!(requests(&abort[0]).1.byte_fields()[0].0, 5);
        let check = job.receive(&reply(seq_of(&abort[0]), 8, general(0)), &mut ids, 0.1);
        job.receive(&reply(seq_of(&check[0]), 8, status(1, 1, 1, 20)), &mut ids, 0.2);
        assert!(job.done() && job.log().join("\n").contains("CANCELLED"));
    }

    /// Send with the firmware staged: only the ML bundle, and it stays staged at the end.
    #[test]
    fn send_finishes_staged() {
        let mut job = OtaJob::new(OtaPlan { model: Some((vec![2; 12_000], [2; 16])), ..plan(OtaMode::Send) });
        let mut ids = 0;
        let first = job.begin(&mut ids, 0.0);
        let start = job.receive(&reply(seq_of(&first[0]), 8, status(3, 1, 64, 20)), &mut ids, 0.1);
        let info = job.receive(&reply(seq_of(&start[0]), 8, general(0)), &mut ids, 0.2);
        let mut queue = job.receive(&reply(seq_of(&info[0]), 10, field_bytes(1, &[field_int(1, 1), field_int(2, 20 * 950)].concat())), &mut ids, 0.3);
        let mut verify = Vec::new();
        while let Some(body) = queue.pop() {
            let (field, request) = requests(&body);
            if field == 7 {
                verify.push(body);
                continue;
            }
            let chunk = ProtoFields::parse(request.bytes(2).unwrap()).unwrap();
            let ack = field_bytes(2, &[field_int(1, 1), field_int(2, chunk.integer(2).unwrap())].concat());
            queue.extend(job.receive(&reply(seq_of(&body), 10, ack), &mut ids, 0.4));
        }
        let end = job.receive(&reply(seq_of(&verify[0]), 8, field_bytes(3, &field_bytes(1, &field_int(1, 0)))), &mut ids, 0.5);
        let log = job.log().join("\n");
        assert!(end.is_empty() && job.done() && log.contains("STAGED"), "{log}");
    }

    #[test]
    fn late_chunks_are_sent_again() {
        let mut job = OtaJob::new(OtaPlan {
            version: 64,
            firmware: vec![0; 3000],
            firmware_md5: [0; 16],
            model: None,
            kek: None,
            mode: OtaMode::Trial,
            resume: false,
        });
        let mut ids = 0;
        let first = job.begin(&mut ids, 0.0);
        let start = job.receive(&reply(seq_of(&first[0]), 8, status(1, 1, 1, 20)), &mut ids, 0.1);
        let info = job.receive(&reply(seq_of(&start[0]), 8, general(0)), &mut ids, 0.2);
        let chunks = job.receive(&reply(seq_of(&info[0]), 10, field_bytes(1, &[field_int(1, 1), field_int(2, 2 * 950)].concat())), &mut ids, 0.3);
        assert_eq!(chunks.len(), 2);
        assert!(job.tick(&mut ids, 1.0).is_empty());
        let again = job.tick(&mut ids, 5.4);
        assert_eq!(again.len(), 2);
        assert!(!job.owns(seq_of(&chunks[0])));
        assert!(job.owns(seq_of(&again[0])));
    }
}

#[cfg(test)]
mod resume_tests {
    use super::*;
    use super::tests::*;

    #[test]
    fn a_resume_skips_start_and_continues_where_the_band_says() {
        let model: Vec<u8> = (0..12_000u32).map(|i| i as u8).collect();
        let mut job = OtaJob::new(OtaPlan {
            version: 64,
            firmware: vec![1; 5000],
            firmware_md5: [1; 16],
            model: Some((model.clone(), [2; 16])),
            kek: Some((20, vec![9; 32])),
            mode: OtaMode::Trial,
            resume: true,
        });
        let mut ids = 0;
        let first = job.begin(&mut ids, 0.0);
        let info = job.receive(&reply(seq_of(&first[0]), 8, status(2, 1, 1, 20)), &mut ids, 0.1);
        let (field, request) = requests(&info[0]);
        assert_eq!(field, 9, "a transfer question, not Start");
        assert!(request.contains(1));
        let resume_at = 950 * 10;
        let chunks = job.receive(&reply(seq_of(&info[0]), 10, field_bytes(1, &[field_int(1, 2), field_int(2, 4 * 950), field_int(3, resume_at)].concat())), &mut ids, 0.2);
        let chunk = ProtoFields::parse(requests(&chunks[0]).1.bytes(2).unwrap()).unwrap();
        assert_eq!(chunk.integer(2).unwrap(), resume_at);
        assert_eq!(chunk.bytes(3).unwrap(), &model[resume_at as usize..resume_at as usize + 950]);
    }
}

#[cfg(test)]
mod staged_tests {
    use super::*;
    use super::tests::*;

    /// As after a trial cut off during the ML bundle: the firmware is staged; only the ML
    /// bundle is started and sent, then the trial aborts.
    #[test]
    fn a_staged_firmware_is_skipped() {
        let mut job = OtaJob::new(OtaPlan {
            version: 64,
            firmware: vec![1; 5000],
            firmware_md5: [1; 16],
            model: Some((vec![2; 12_000], [2; 16])),
            kek: Some((20, vec![9; 32])),
            mode: OtaMode::Trial,
            resume: false,
        });
        let mut ids = 0;
        let first = job.begin(&mut ids, 0.0);
        let start = job.receive(&reply(seq_of(&first[0]), 8, status(3, 1, 64, 20)), &mut ids, 0.1);
        let (field, request) = requests(&start[0]);
        assert_eq!(field, 7);
        let start = ProtoFields::parse(request.bytes(2).unwrap()).unwrap();
        let info = ProtoFields::parse(start.bytes(1).unwrap()).unwrap();
        assert_eq!(info.integer(1).unwrap(), ML_MODEL);
        assert_eq!(start.integer(2).unwrap(), 12_000);
    }
}

#[cfg(test)]
mod update_tests {
    use super::*;
    use super::tests::*;

    /// With an update staged from an earlier connection: abort it, send again, then commit.
    #[test]
    fn an_update_aborts_the_stale_staged_one_and_commits_in_one_go() {
        let mut job = OtaJob::new(OtaPlan {
            version: 64, firmware: vec![1; 1900], firmware_md5: [1; 16], model: None,
            kek: Some((20, vec![9; 32])), mode: OtaMode::Update, resume: false,
        });
        let mut ids = 0;
        let mut queue = job.begin(&mut ids, 0.0);
        let mut aborted = false;
        let mut kinds = Vec::new();
        while let Some(body) = queue.pop() {
            let seq = seq_of(&body);
            let (field, request) = requests(&body);
            let answer = if field == 7 {
                let kind = request.byte_fields()[0].0;
                kinds.push(kind);
                match kind {
                    1 => reply(seq, 8, if aborted { status(1, 1, 1, 20) } else { status(3, 1, 64, 20) }),
                    5 => { aborted = true; reply(seq, 8, general(0)) }
                    2 => reply(seq, 8, general(0)),
                    3 => reply(seq, 8, field_bytes(3, &field_bytes(1, &field_int(1, 0)))),
                    4 => reply(seq, 8, field_bytes(4, &field_bytes(1, &[]))),
                    _ => panic!(),
                }
            } else if request.contains(1) {
                reply(seq, 10, field_bytes(1, &[field_int(1, 1), field_int(2, 4 * 950)].concat()))
            } else {
                let chunk = ProtoFields::parse(request.bytes(2).unwrap()).unwrap();
                reply(seq, 10, field_bytes(2, &[field_int(1, 1), field_int(2, chunk.integer(2).unwrap())].concat()))
            };
            queue.extend(job.receive(&answer, &mut ids, 0.1));
        }
        let log = job.log().join("\n");
        assert_eq!(kinds, vec![1, 5, 1, 2, 3, 4], "{log}");
        assert!(log.contains("COMMITTED"), "{log}");
    }
}
