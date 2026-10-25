# Shared controls, plan C: the Android app on band-core's `controls`

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The bridge drops its own `controller.rs` and `config.rs` and runs band-core's `Controls<String>`. The app then sends the whole profile list instead of a flattened mapping string. "Controls on/off", "Next profile" and "Previous profile" become actions you can pick for any gesture. Index triple, middle triple and middle hold become editable rows. Practice and the text-field mode use the core's practice mode.

**Architecture:**
- **Kotlin keeps:** storage (`ProfileList` in prefs), the foreground app, call state and the UI. It sends a profiles JSON to the bridge whenever any of them change.
- **The core does** the switching. The bridge reports what happened as event lines in the actions channel:
  - `@gesture <label>`
  - `@controls on|off`
  - `@profile <index> <step>`
  - `@held <app>`
  - `@practiced <action>`
- **Kotlin then:** stores the new pick, announces it, updates the notification, and feeds lessons.

These replace the mapping string, the status-counter diffing and the 1.5 s "who paused" heuristic.

**Tech Stack:** Rust (JNI bridge, serde_json), Kotlin (plain Android views, no AndroidX), band-core `controls` (0.5.0, local).

**Spec:** `~/Projects/band-core/docs/superpowers/specs/2026-10-25-shared-controls-design.md`.

**Rules:**
- Work in `~/Projects/air-gestures-android` on a new branch `controls` from `master`.
- No push and no tags. No sudo. Never run `iwctl`.
- Dev builds go on the phone only with the dev key (`local.properties` already has it) and the dev icon. Never uninstall the dev build.
- Commit messages end with:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01MLDqGPYx8bTGKJcVLgoyzo
  ```
- Before every commit, all of these must pass:
  - `(cd rust && cargo test)` and `(cd rust && cargo clippy --all-targets)` (no new warnings vs `master`);
  - `./build-rust.sh`;
  - `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew -q assembleDebug testDebugUnitTest`.

**Stored action ids for the built-ins** (Kotlin and the profiles JSON):
- `controls.toggle` → `Builtin::Controls`
- `profile.next` → `Builtin::NextProfile`
- `profile.previous` → `Builtin::PreviousProfile`

The last two are the names the bridge already emits today, so the old lessons' wording carries over.

---

### Task 1: Use band-core 0.5.0

- [ ] **Step 1:**

```bash
cd ~/Projects/air-gestures-android
git status -s                   # empty
git switch -c controls
git -C rust/band-core fetch -q /home/user888/Projects/band-core controls
git -C rust/band-core checkout -q FETCH_HEAD
(cd rust && cargo test 2>&1 | grep -cE "FAILED|^error")      # 0
git add rust/band-core rust/Cargo.lock
git commit -q -m "band-core 0.5.0 (the shared controls layer; local until pushed)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MLDqGPYx8bTGKJcVLgoyzo"
```

---

### Task 2: The bridge runs `Controls<String>`, fed a profiles JSON

**Files:**
- Create: `rust/bridge/src/profiles.rs` (JSON → `Profiles<String>` + `Options`)
- Modify: `rust/bridge/src/lib.rs` (`Connection`), `rust/bridge/src/jni_api.rs`, `rust/bridge/src/status.rs`
- Delete: `rust/bridge/src/controller.rs`, `rust/bridge/src/config.rs`
- Test: `rust/bridge/tests/connection.rs` (rewrite its setup), new `rust/bridge/tests/profiles.rs`

**The profiles JSON** (Kotlin builds it in Task 3):

```json
{
  "hand": "band",
  "picked": 0,
  "apps": {"org.videolan.vlc": 1},
  "calls": {"index_double": "call.answer", "middle_double": "-"},
  "profiles": [
    {"name": "Profile 1",
     "map": {"swipe_up": "media.previous", "middle_hold": "controls.toggle", "index_triple": "profile.next"},
     "dial": {"up": "volume.up", "down": "volume.down"}},
    {"name": "Games", "map": {}, "dial": null}
  ]
}
```

What the fields mean:
- **`map`:** every gesture key with an action. `""` or a missing key means nothing. `automation` is already expanded by Kotlin to `automation:<key>`.
- **`calls`:** only gestures whose call action isn't "as usual". `"-"` means nothing.
- **`dial`:** `null` means the dial does nothing.
- **`hand`:** `band`, `left` or `right`; anything else counts as `band`.

- [ ] **Step 1: Failing tests for the JSON**

Create `rust/bridge/tests/profiles.rs`:

```rust
use airgestures_bridge::profiles::parse;
use band_core::controls::{Action, Builtin, CallBinding, Gesture, HandSetting};

#[test]
fn the_apps_json_becomes_profiles_and_options() {
    let (options, profiles) = parse(
        r#"{"hand":"left","picked":1,"apps":{"vlc":0},
            "calls":{"index_double":"call.answer","middle_double":"-"},
            "profiles":[
              {"name":"A","map":{"swipe_up":"media.previous","middle_hold":"controls.toggle",
                                 "index_triple":"profile.next","middle_triple":"profile.previous",
                                 "index_tap":""},
               "dial":{"up":"volume.up","down":"volume.down"}},
              {"name":"B","map":{},"dial":null}]}"#,
    )
    .unwrap();
    assert_eq!(options.hand, HandSetting::Left);
    assert_eq!(profiles.picked, 1);
    assert_eq!(profiles.apps.get("vlc"), Some(&0));
    let a = &profiles.list[0];
    assert_eq!(a.name, "A");
    assert_eq!(a.gestures.get(&Gesture::SwipeUp), Some(&Action::Run("media.previous".into())));
    assert_eq!(a.gestures.get(&Gesture::MiddleHold), Some(&Action::Builtin(Builtin::Controls)));
    assert_eq!(a.gestures.get(&Gesture::IndexTriple), Some(&Action::Builtin(Builtin::NextProfile)));
    assert_eq!(a.gestures.get(&Gesture::MiddleTriple), Some(&Action::Builtin(Builtin::PreviousProfile)));
    assert_eq!(a.gestures.get(&Gesture::IndexTap), None);
    assert_eq!(a.dial.as_ref().map(|d| d.up.as_str()), Some("volume.up"));
    assert!(profiles.list[1].dial.is_none());
    assert_eq!(
        profiles.calls.get(&Gesture::IndexDouble),
        Some(&CallBinding::Do(Action::Run("call.answer".into())))
    );
    assert_eq!(profiles.calls.get(&Gesture::MiddleDouble), Some(&CallBinding::Nothing));
}

#[test]
fn unknown_gesture_keys_are_skipped_and_bad_json_is_an_error() {
    let (_, profiles) = parse(r#"{"profiles":[{"name":"A","map":{"index_hold":"x"},"dial":null}]}"#).unwrap();
    assert!(profiles.list[0].gestures.is_empty());
    assert!(parse("{").is_err());
    assert!(parse(r#"{"profiles":[]}"#).unwrap().1.list.is_empty());
}
```

Run: `(cd rust && cargo test -p bridge --test profiles 2>&1 | grep -E "error\[E" | head -2)`

Expected: unresolved `airgestures_bridge::profiles`.

- [ ] **Step 2: `rust/bridge/src/profiles.rs`**

```rust
//! The app's profiles, as JSON from Kotlin, into band-core's `Profiles` and `Options`.

use std::collections::BTreeMap;

use band_core::controls::{Action, Builtin, CallBinding, Dial, Gesture, HandSetting, Options, Profile, Profiles};
use serde::Deserialize;

#[derive(Deserialize)]
struct Json {
    #[serde(default)]
    hand: String,
    #[serde(default)]
    picked: usize,
    #[serde(default)]
    apps: BTreeMap<String, usize>,
    #[serde(default)]
    calls: BTreeMap<String, String>,
    #[serde(default)]
    profiles: Vec<ProfileJson>,
}

#[derive(Deserialize)]
struct ProfileJson {
    name: String,
    #[serde(default)]
    map: BTreeMap<String, String>,
    dial: Option<DialJson>,
}

#[derive(Deserialize)]
struct DialJson {
    up: String,
    down: String,
}

/// An app action id, or one of the three built-ins.
pub fn action(id: &str) -> Option<Action<String>> {
    match id {
        "" => None,
        "controls.toggle" => Some(Action::Builtin(Builtin::Controls)),
        "profile.next" => Some(Action::Builtin(Builtin::NextProfile)),
        "profile.previous" => Some(Action::Builtin(Builtin::PreviousProfile)),
        other => Some(Action::Run(other.to_owned())),
    }
}

pub fn parse(json: &str) -> Result<(Options, Profiles<String>), String> {
    let json: Json = serde_json::from_str(json).map_err(|e| e.to_string())?;
    let options = Options {
        hand: match json.hand.as_str() {
            "left" => HandSetting::Left,
            "right" => HandSetting::Right,
            _ => HandSetting::Band,
        },
        ..Options::default()
    };
    let list = json
        .profiles
        .into_iter()
        .map(|p| {
            let mut profile = Profile::new(&p.name);
            for (key, id) in &p.map {
                if let (Some(gesture), Some(action)) = (Gesture::from_key(key), action(id)) {
                    profile.gestures.insert(gesture, action);
                }
            }
            profile.dial = p.dial.map(|d| Dial { up: d.up, down: d.down });
            profile
        })
        .collect();
    let calls = json
        .calls
        .iter()
        .filter_map(|(key, id)| {
            let gesture = Gesture::from_key(key)?;
            Some((gesture, match id.as_str() {
                "-" => CallBinding::Nothing,
                "" => CallBinding::AsUsual,
                other => action(other).map(CallBinding::Do).unwrap_or(CallBinding::AsUsual),
            }))
        })
        .collect();
    Ok((options, Profiles { list, picked: json.picked, apps: json.apps, calls }))
}
```

In `lib.rs`: `pub mod profiles;`. Remove `pub mod config;` and `pub mod controller;`, and delete both files. Their tests are covered by band-core (`controls_triples.rs`) and by Step 1's tests.

- [ ] **Step 3: `Connection` on `Controls`**

In `rust/bridge/src/lib.rs`:
- **Imports and field:** replace the controller import with `use band_core::controls::{Controls, Output};`. Replace the `controller: Controller` field with `controls: Controls<String>`.
- **`Connection::new(owner_key, scheme_guess, paused, profiles: &str)`:** the `dial` and `mapping` parameters go. Then:
  ```rust
          let (options, profiles) = profiles::parse(profiles)?;
          let mut controls = Controls::new(options, profiles);
          if paused {
              controls.pause();
          }
  ```
- **Replace `set_mapping` and `set_dial`:**
  ```rust
      /// New profiles, hand or call layer from the app (see `profiles::parse`). The app knows
      /// which profile it picked: a switch this causes isn't reported back.
      pub fn set_profiles(&mut self, json: &str) -> Result<(), String> {
          let (options, profiles) = profiles::parse(json)?;
          let mut outputs = self.controls.set_options(options);
          outputs.extend(self.controls.set_profiles(profiles));
          self.take(outputs.into_iter().filter(|o| !matches!(o, Output::Profile { step: 0, .. })).collect(), 0.0);
          Ok(())
      }

      pub fn set_foreground(&mut self, app: &str) {
          let outputs = self.controls.set_foreground((!app.is_empty()).then(|| app.to_owned()));
          // The app follows the foreground itself.
          self.take(outputs.into_iter().filter(|o| !matches!(o, Output::Profile { .. })).collect(), 0.0);
      }

      pub fn set_in_call(&mut self, in_call: bool) {
          self.controls.set_in_call(in_call);
      }

      pub fn set_practice(&mut self, practice: bool) {
          self.controls.set_practice(practice);
      }
  ```
  `set_options` may return a `SetHand` output. `take` needs it written to the band, so `take` returns bytes. Then `set_profiles` returns `Result<Vec<u8>, String>`, and the JNI wrapper and Kotlin write those bytes like `feed`'s output. If that's awkward, keep the hand request for the next `tick`: queue it in a `pending_hand: Option<Hand>` field that `tick` sends. **Pick the queue:** `tick` runs every 50 ms already, so set_profiles stays `Result<(), String>`.
- **`set_paused`:**
  ```rust
      pub fn set_paused(&mut self, paused: bool, now: f64) {
          // The app asked: it knows; nothing to report back.
          let _ = if paused { self.controls.pause() } else { self.controls.resume(now) };
      }
  ```
- **`tick`:** after the session events, also fire due held taps and send a queued hand:
  ```rust
          let due = self.controls.poll(now);
          outgoing.extend(self.take(due, now)?);
          if let Some(hand) = self.pending_hand.take() {
              outgoing.extend(self.session.set_handedness(hand, now).map_err(|e| e.to_string())?);
          }
  ```
- **`on_event`:** `let outputs = self.controls.on_event(event, now); outgoing.extend(self.take(outputs, now)?);`. Remove the gesture-counter diffing: the log line comes from `Output::Gesture` now.
- **Replace `apply` with `take`:**
  ```rust
      /// What the controls asked for: actions and events for the app, hand writes for the band.
      fn take(&mut self, outputs: Vec<Output<String>>, now: f64) -> Result<Vec<u8>, String> {
          let mut outgoing = Vec::new();
          for output in outputs {
              match output {
                  Output::Run(action) => {
                      self.log.push(format!("action {action}"));
                      self.actions.push(action);
                  }
                  Output::Gesture(gesture) => {
                      self.log.push(format!("gesture {}", gesture.label()));
                      self.actions.push(format!("@gesture {}", gesture.label()));
                  }
                  Output::Controls { enabled } => {
                      self.actions.push(format!("@controls {}", if enabled { "on" } else { "off" }));
                  }
                  Output::Profile { index, step } => self.actions.push(format!("@profile {index} {step}")),
                  Output::ProfileHeld { app } => self.actions.push(format!("@held {app}")),
                  Output::Practiced(action) => self.actions.push(format!("@practiced {action}")),
                  Output::SetHand(hand) => {
                      if now > 0.0 {
                          outgoing.extend(self.session.set_handedness(hand, now).map_err(|e| e.to_string())?);
                      } else {
                          self.pending_hand = Some(hand);
                      }
                  }
              }
          }
          Ok(outgoing)
      }
  ```
  Add the field `pending_hand: Option<band_core::events::Hand>`, initialised to `None`.
- **`status_json`:** in `Snapshot`, replace the flattened controller `Status` with the core's values. Change `rust/bridge/src/status.rs`'s `Status` to:
  ```rust
  #[derive(Debug, Clone, Default, Serialize)]
  pub struct Status {
      pub paused: bool,
      pub battery: Option<u8>,
      pub charging: Option<bool>,
      pub hand: Option<String>,
      pub last_gesture: Option<String>,
      pub last_action: Option<String>,
      pub gestures: u64,
  }
  ```
  `dial` goes: Kotlin knows the active profile's dial. Then build it in `status_json`:
  ```rust
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
  ```
  Pass `&status` to the snapshot. The `Snapshot` field `status: &'a Status` keeps `#[serde(flatten)]`.

- [ ] **Step 4: JNI**

In `rust/bridge/src/jni_api.rs`:
- **`Bridge_open`:** takes `(owner_key, scheme_guess, paused, profiles: JString)`. The `dial` and `mapping` parameters go.
- **Replace** `Bridge_setMapping` and `Bridge_setDial` with these. Model them on the existing `setPaused` and `setMapping` wrappers; a `String` error throws.
  - `Bridge_setProfiles(handle, json: JString)`, which calls `connection.set_profiles`;
  - `Bridge_setForeground(handle, app: JString)`;
  - `Bridge_setInCall(handle, jboolean)`;
  - `Bridge_setPractice(handle, jboolean)`.
- **Add a static** `Bridge_profileWarnings(json: JString) -> JString`. It parses with `profiles::parse` and returns one line per `Profiles::warnings()` entry, `no-controls <index>` or `no-way-out <index>`. Invalid JSON gives `""`.

- [ ] **Step 5: Rewrite the bridge's connection tests**

In `rust/bridge/tests/connection.rs`, `connected_with(paused, mapping)` becomes `connected_with(paused, profiles_json)`. Write a small helper `json(map: &[(&str, &str)], extra_profiles: usize) -> String` that builds the JSON above. Then keep every existing test's intent, translated:
- `media.next` on swipe down;
- an index tap after its double window;
- a middle hold toggles: now it asserts the `@controls off` event, with `middle_hold = controls.toggle` in the map;
- an unmapped gesture does nothing: `@gesture` lines are fine, but no plain actions;
- the other wrist → set hand;
- the stream-probe tests stay as they are.

Drop `index double → dial.changed.brightness`: the dial toggle is gone, as the spec says. In its place add:
- `a_triple_tap_with_two_profiles_reports_the_switch`: `@profile 1 1`;
- `practice_reports_what_would_run`: `set_practice(true)`, then an index tap gives `@gesture index tap` and `@practiced media.play_pause`, and no plain action.

Use `take_actions()` and split the plain lines from the `@` lines in the assertions.

- [ ] **Step 6: Run, then commit**

```bash
(cd rust && cargo test 2>&1 | grep -cE "FAILED|^error")             # 0
(cd rust && cargo clippy --all-targets 2>&1 | grep -c "^warning")   # not more than on master
git add -A rust
git commit -q -m "bridge: band-core's shared controls instead of its own controller; profiles as JSON

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MLDqGPYx8bTGKJcVLgoyzo"
```

Kotlin won't build until Task 3; don't run Gradle for this commit. Task 3 follows straight after.

---

### Task 3: Kotlin sends profiles and follows the events

**Files:**
- Modify: `Bridge.kt`, `BandLink.kt`, `BandService.kt`, `Prefs.kt`, `Mappings.kt`, `Mapping.kt`, `Identity.kt` (`Bridge.open` call), `Lessons.kt`, `WriteService.kt` only if it calls changed APIs
- Test: `app/src/test/java/dev/kb896/airgestures/MappingsTest.kt` (the `effective` tests become `profilesJson` tests), `LessonsBackupTest.kt`

All paths are under `app/src/main/java/dev/kb896/airgestures/` unless noted.

- [ ] **Step 1: The gestures and the built-in actions**

`Mapping.kt`:
1. Add to `enum class Action`, after `NONE`, a group of three:
   ```kotlin
       /** Built into the band layer (band-core): any gesture can have them. */
       CONTROLS("controls.toggle", "Controls on/off", R.drawable.ic_power),
       NEXT_PROFILE("profile.next", "Next profile", R.drawable.ic_layers),
       PREVIOUS_PROFILE("profile.previous", "Previous profile", R.drawable.ic_layers),
   ```
   Check that `ic_power` and `ic_layers` exist in `res/drawable`; they do.
2. In `enum class Gesture`, add after `MIDDLE_DOUBLE`:
   ```kotlin
       INDEX_TRIPLE("index_triple", "Index triple tap", Action.NEXT_PROFILE),
       MIDDLE_TRIPLE("middle_triple", "Middle triple tap", Action.PREVIOUS_PROFILE),
       MIDDLE_HOLD("middle_hold", "Middle hold", Action.CONTROLS),
   ```
3. Remove "(the middle hold stays the controls toggle)" from its doc comment.

Existing profiles don't store these keys, so everywhere a profile's map is read with `?: it.default.id` they get the defaults. Current behaviour stays without a migration.

- [ ] **Step 2: The profiles JSON (failing tests first)**

In `Mappings.kt`, replace `effective(...)` with a pure function:

```kotlin
    /**
     * What the bridge gets (see rust/bridge/src/profiles.rs): every profile with every gesture
     * key resolved (defaults for keys a profile never stored), the dial's two actions, the
     * picked profile and the apps' own (as indexes), and the call layer.
     */
    fun profilesJson(
        list: ProfileList,
        calls: Map<String, String>,
        dials: (String) -> Pair<String, String>?,
        hand: String,
    ): String {
        val profiles = org.json.JSONArray()
        for (profile in list.profiles) {
            val map = org.json.JSONObject()
            for (gesture in Gesture.entries) {
                val id = profile.map[gesture.key] ?: gesture.default.id
                map.put(gesture.key, if (id == AUTOMATION) "$AUTOMATION:${gesture.key}" else id)
            }
            profiles.put(org.json.JSONObject().apply {
                put("name", profile.name)
                put("map", map)
                put("dial", dials(profile.dial)?.let { (up, down) ->
                    org.json.JSONObject().put("up", up).put("down", down)
                } ?: org.json.JSONObject.NULL)
            })
        }
        val index = { id: String -> list.profiles.indexOfFirst { it.id == id }.coerceAtLeast(0) }
        return org.json.JSONObject().apply {
            put("hand", hand)
            put("picked", index(list.picked))
            put("apps", org.json.JSONObject(list.apps.mapValues { index(it.value) }))
            put("calls", org.json.JSONObject(calls.filterValues { it != AS_USUAL }.mapValues { (key, id) ->
                when (id) {
                    NOTHING -> "-"
                    AUTOMATION -> "$AUTOMATION:$key"
                    else -> id
                }
            }))
            put("profiles", profiles)
        }.toString()
    }
```

The dial's automation: `DialChoice.AUTOMATION`'s up/down are already `automation:dial_up` / `automation:dial_down`. Keep whatever `DialChoice` gives.

In `MappingsTest.kt`, replace the `effective` tests with `profilesJson` tests. Model them on the old ones, and include at least these four:
1. defaults fill unstored keys, including `middle_hold` → `controls.toggle` and `index_triple` → `profile.next`;
2. `automation` becomes `automation:<key>` in both the map and the calls;
3. calls: as usual is left out, nothing becomes `-`;
4. `picked` and `apps` are indexes.

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew -q testDebugUnitTest --tests '*MappingsTest*'`

Expected: FAIL (no `profilesJson`), then implement, then PASS.

`Prefs.mapping()` becomes:

```kotlin
    /** Everything the bridge needs to know about gestures, as JSON (see Mappings.profilesJson). */
    fun profilesJson(): String = Mappings.profilesJson(
        Profiles.load(context),
        Gesture.entries.associate { it.key to callActionId(it) },
        { key -> DialChoice.of(key).let { if (it.up.isEmpty() && it.down.isEmpty()) null else it.up to it.down } },
        hand,
    )
```

This keeps the "none" dial as `null`. Check `DialChoice.NONE`'s up and down; adapt the null test to however "none" is defined there.

- [ ] **Step 3: Bridge, BandLink**

- **`Bridge.kt`:**
  - `open(ownerKey, schemeGuess, paused, profiles: String)`;
  - replace `setDial`/`setMapping` with `setProfiles(handle, json)`, `setForeground(handle, app)`, `setInCall(handle, on)`, `setPractice(handle, on)`;
  - `@JvmStatic external fun profileWarnings(json: String): String`.
- **`Identity.kt:84`:** `Bridge.close(Bridge.open(owner, 0, false, "{}"))`.
- **`BandLink.kt`:**
  - `setMapping(mapping)` becomes `setProfiles(json)` (same locking), and `setForeground(app)`, `setInCall(on)`, `setPractice(on)` are added. All are no-ops while `handle == 0L`; the next `open` gets the current JSON anyway.
  - The open call is `Bridge.open(key.readBytes(), guess, prefs.paused, prefs.profilesJson())`.
  - Right after opening, also call `Bridge.setForeground(handle, Profiles.foreground ?: "")`, `Bridge.setInCall(handle, CallWatch.inCall)` and `Bridge.setPractice(handle, service.practice != null)` (or a flag BandService passes in).

- [ ] **Step 4: BandService follows the events**

In `BandService.kt`:
- **`remap()`:**
  ```kotlin
      fun remap() {
          link.setProfiles(prefs.profilesJson())
          link.setInCall(CallWatch.inCall)
          link.setForeground(Profiles.foreground ?: "")
          showProfile()
      }
  ```
  `setHand` calls `link.setProfiles(prefs.profilesJson())`.
- **`onActions`:** split the event lines from plain actions:
  ```kotlin
      override fun onActions(actions: List<String>) = main.post {
          for (line in actions) {
              if (!line.startsWith("@")) {
                  if (practice == null) this.actions.run(line)?.let(::onLog)
                  continue
              }
              val (kind, rest) = line.removePrefix("@").split(' ', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
              when (kind) {
                  "gesture" -> practice?.gesture(rest)
                  "practiced" -> practice?.action(rest)
                  "controls" -> bandToggled(rest == "on")
                  "profile" -> rest.split(' ').let { (index, step) -> bandSwitched(index.toInt(), step.toInt()) }
                  "held" -> Toast.makeText(this, "This app has its own profile", Toast.LENGTH_SHORT).show()
              }
          }
      }.let {}

      /** The band turned the controls on or off (a gesture with "Controls on/off"). */
      private fun bandToggled(on: Boolean) {
          prefs.paused = !on
          pausedChanged(!on)
      }

      /** The band moved to another profile: store the pick, then tell the user. */
      private fun bandSwitched(index: Int, step: Int) {
          val list = Profiles.load(this)
          val id = list.profiles.getOrNull(index)?.id ?: return
          val now = Profiles.update(this) { it.picking(id) }.active(Profiles.foreground)
          announce(now, back = step < 0)
      }
  ```
- **Split `switched()`** into `announce(profile, back)` (toast, speech, buzz, log, `showProfile`) and a remap. `profileStep` (now unused) goes. The widget picker's `pick(id)` keeps its local path: store, `remap()`, `announce`.
- **`onStatus`:**
  - remove the `commandedAt` heuristic and the lesson hold handling (the `ours` and `lesson.held()` block). Keep mirroring `paused` into prefs and state as now: it's still right after a reconnect;
  - remove `commandedAt` in `setPaused`;
  - remove the `if (lesson != null && now.gestures > before.gestures) lesson.gesture(…)` line: `@gesture` events do this now, one per gesture with none collapsed.
- **Practice:**
  - `startPractice` calls `link.setPractice(true)`, and no longer forces the controls on: the core's practice reports while off;
  - `endPractice` calls `link.setPractice(false)`;
  - remove the `practiceWasPaused` restore logic and its prefs keys if nothing else uses them;
  - the `Practice` interface keeps `gesture` and `action`; `held()` goes. Update `PracticeActivity` and `WriteService`, whose `held()` is a no-op.
- **Elsewhere:** wherever the foreground (`TouchService`/`ProfileReceiver` paths that call `BandService.front()` or similar) and the call state (`CallWatch`) change, `remap()` already runs. Keep that; it now sends the foreground and the call state too.

- [ ] **Step 5: Lessons**

In `Lessons.kt`:
- `Done.Hold` matches the gesture label `"middle hold"`.
- The triple lessons use `Done.Gesture("index triple tap")` and `Done.Gesture("middle triple tap")`. Show them only when the active profile's triple is bound to something and there are 2+ profiles, because the core only waits for a triple that does something.
- `steps(...)` gets the active profile's resolved map to decide that, plus the hold lesson's wording: "It runs what the middle hold is set to (by default the controls on/off)".
- Update `LessonsBackupTest` accordingly.

- [ ] **Step 6: Build, tests, commit**

```bash
./build-rust.sh >/dev/null && JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew -q assembleDebug testDebugUnitTest
grep -rn "setMapping\|setDial\|commandedAt\|profileStep\|fun mapping(" app/src/main/java | head   # nothing
git add -A app rust
git commit -q -m "App: profiles to the bridge as JSON; built-ins, profile switches and the lock as band events

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MLDqGPYx8bTGKJcVLgoyzo"
```

---

### Task 4: The gesture rows, the new actions, the warnings

**Files:** `ProfileActivity.kt` (and/or `GestureScreen.kt` / `GesturesActivity.kt`, wherever a profile's gesture rows and the action picker are built; read them first), `Lessons.kt` only for wording.

- [ ] **Step 1: Rows**

A profile's screen lists `Gesture.entries`, so the three new rows appear on their own. Check the screen's existing grouping: if gestures are grouped (swipes, index, middle), put each triple after its double and the hold last. Check the During calls screen too: it may list `Gesture.entries`. The three built-ins can be picked there as well; that's allowed by the spec.

- [ ] **Step 2: The picker**

The action picker (`choose(...)` in `Ui.kt`, fed from a list of `Action`s; find where it filters `callOnly`) shows the three built-ins first, under a heading "Band" if the picker has headings. Otherwise it shows them right after "Nothing".

- [ ] **Step 3: Warnings**

On a profile's screen, under the title, show a quiet card when `Bridge.profileWarnings(prefs.profilesJson())` names this profile's index:
- `no-controls`: "No gesture turns the controls on and off in this profile. You can still do it from the app."
- `no-way-out`: "No gesture switches away from this profile. Use the app or the widget to switch."

Use the app's existing card/text helpers, as `CreditsActivity.kt` does: `card(null, …)`, `Cb.text(…, Cb.INK_500)`. Refresh it in `onResume` and after any edit on that screen.

- [ ] **Step 4: Build, install, commit**

```bash
./build-rust.sh >/dev/null && JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew -q assembleDebug testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk      # Success (if INSTALL_FAILED_USER_RESTRICTED: the owner taps the phone's prompt)
git add -A app
git commit -q -m "Gestures: triple taps and the hold are editable; Controls on/off and profile switches as actions; warnings

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MLDqGPYx8bTGKJcVLgoyzo"
```

---

### Task 5: Changelog and the phone check (with the owner)

- [ ] **Step 1: `CHANGELOG.md`**

Add to the top section (`## 0.7.8` exists unreleased; the owner decides the version):
- **Controls:** "Controls on/off", "Next profile" and "Previous profile" are actions you can put on any gesture. The middle hold and the triple taps are editable like any other gesture; by default they keep doing what they did.
- **Warnings:** a profile without a way to turn the controls on and off, or to leave it, shows a warning (never blocks).
- **Fixes:**
  - a slow triple tap no longer runs a single tap first;
  - a tap followed quickly by the other finger's tap no longer loses the first;
  - a busy band (connected to another device) says so.
- **Shared code:** gestures, profiles and the lock now come from band-core, shared with the Linux app.

- [ ] **Step 2: On the phone (owner)**

With the dev build installed and the band on the phone, the owner checks:
- **Defaults:** gestures; the middle hold turns the controls off and on; with 2 profiles, the triple taps switch, with the toast.
- **Built-ins on other gestures:** put "Next profile" on swipe left in profile 1, and "Controls on/off" on a middle double tap. Both work.
- **An app's own profile:** a triple there shows "This app has its own profile".
- **Practice:** the hold lesson finishes on the hold, and the triple lessons on the triples.
- **Handwriting in a text field:** taps and swipes still edit; the hold doesn't lock.
- **Calls:** an index double tap answers.

- [ ] **Step 3: Commit the changelog**

```bash
git add CHANGELOG.md
git commit -q -m "Changelog: built-in actions on any gesture

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MLDqGPYx8bTGKJcVLgoyzo"
```

Merging, pushing (band-core first) and tagging wait for the owner.
