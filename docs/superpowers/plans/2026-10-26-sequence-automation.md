# Send to automation inside a sequence — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A sequence step can be Send to automation (optionally for a Home device); running it sends `gesture=sequence`, `name=<sequence name>`, `device`, `profile`. Ships as 0.9.18 (0.9.17 is skipped).

**Architecture:** Steps are stored as the existing ids (`automation`, `automation@<device id>`). The sequence action id the bridge reports gains the profile (`seq:<id>/<URL-encoded profile>`, same encoding as `Mappings.named`), so `Actions.sequence` knows who started it. `Mappings.Automation.Sequence(name, profile, device)` builds the extras through `Mappings.extras`.

**Spec:** `docs/superpowers/specs/2026-10-26-sequence-automation-design.md`.

**House rules:** branch `controls`; never push/tag; stage files by name; no `cargo fmt` in band-core; dev builds signed with `~/.android/debug.keystore.linux-backup`. Commit trailers:
```
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MLDqGPYx8bTGKJcVLgoyzo
```

---

### Task 1: The event (`Mappings`)

**Files:** `app/src/main/java/dev/kb896/airgestures/Mappings.kt`, `app/src/test/java/dev/kb896/airgestures/MappingsTest.kt`

- [ ] Test first: `Automation.Sequence("Goodnight", "Music", "d1")` with device name `Curtains` → `extras` = `gesture=sequence, name=Goodnight, profile=Music, device=Curtains`; `Sequence("Goodnight", null, null)` → only `gesture`, `name`.
- [ ] Add `data class Sequence(val name: String, override val profile: String? = null, override val device: String? = null) : Automation()` and its `extras` branch (`gesture` = `"sequence"`, `name`).
- [ ] Sequence action ids carry the profile: in `named()` (or a sibling used everywhere `named` is), a `Sequences.PREFIX` id becomes `seq:<id>/<URL-encoded profile>` when a profile is given (profile maps, palm overrides, the calls layer with `CALLS`, a profile's own drawings; shared drawings: none). Add `Sequences.started(action): Pair<String, String?>` (id, decoded profile) that also reads plain `seq:<id>`. Tests: round trip with a profile holding `/`, `:` and spaces; plain id; `profilesJson` puts `seq:s1/Music` on a gesture mapped to `seq:s1` in profile `Music`.
- [ ] Find every place that reads a bridge-reported sequence id (Kotlin and `rust/bridge`, e.g. activity lines, labels, `Actions.run`) and make it accept the `/profile` suffix; cover with tests where the code is tested (Activity line shows "Sequence: Goodnight", not "deleted").
- [ ] `./gradlew :app:testDebugUnitTest` + `cargo test` in `rust/bridge` pass; commit.

### Task 2: Running (`Actions`)

**Files:** `Actions.kt` (+ a JVM test if the sending can be isolated; otherwise test the pure part)

- [ ] `run()` parses the sequence action with `Sequences.started` and passes the profile to `sequence(id, profile)`.
- [ ] In `sequence()`, a step where `Mappings.isAutomation(step)` sends `Automation.Sequence(sequence.name, profile, Mappings.deviceOf(step))` through `send()` (device name looked up as now), instead of `run(step)`. Other steps unchanged.
- [ ] "Try it" (`SequenceActivity` → `runAction("seq:<id>")`) sends no profile.
- [ ] Pure helper for testability, e.g. `Sequences.stepAutomation(sequenceName, profile, step): Automation.Sequence?` (null for non-automation steps), unit-tested; commit.

### Task 3: Picking and showing

**Files:** `GestureScreen.kt`, `SequenceActivity.kt`

- [ ] `pickAction(inSequence = true)` offers `Action.AUTOMATION` (still leaves out NONE, SEQUENCE, call-only). In a sequence, the automation choice goes through `pickDevice` but does NOT call `automationGuide(firstTime = true)`.
- [ ] The step row label is the existing one ("Send to automation · Curtains"; deleted device → "Send to automation"); its subtitle/secondary text says what is sent: `Sends gesture "sequence", name "<sequence name>"`.
- [ ] Rename: when the sequence has an automation step, the rename dialog adds "Home Assistant matches this name; update your automations too." (use the dialog's existing message/hint mechanism; don't invent a new dialog).
- [ ] If step rows can be changed (edit/replace), the same picker rules apply.
- [ ] The in-app automation guide (`automationGuide`) gets a short "From a sequence" paragraph: extras above, HA trigger example `gesture: sequence`, `name: Goodnight`.
- [ ] Build `./gradlew :app:assembleDebug`, lint count not above 149; commit.

### Task 4: Version 0.9.18

- [ ] `CHANGELOG.md`: rename `## 0.9.17` to `## 0.9.18` (one release: everything that was 0.9.17 plus this), add a line "Sequences can send to automation: mix phone actions and Home Assistant in one gesture (`gesture=sequence`, `name`, `device`, `profile`)." Check `release-notes.sh` reads `## 0.9.18`.
- [ ] `versionName` `0.9.17-dev` → `0.9.18-dev` wherever set; grep the repo (README, docs, strings) for `0.9.17` and update what refers to the coming release. Commit.

### Task 5: Emulator check

- [ ] On an emulator (visible, floating, pinned; `-s emulator-<port>`), make a sequence: Flashlight, Send to automation · (a new device "Curtains"), Wait 1 s, Send to automation (no device). Try it. Verify two broadcasts in order with a test receiver or `adb logcat` (add nothing permanent to the app for this). Screenshot the sequence screen.
