# C2: the Android app on band controls, the pinch, palm and drawings — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The app runs band-core's new `controls` (0.5.0 as on band-core's local `controls` tip): Controls on/off, Next profile, Previous profile and Draw are **band controls** above the profiles (one gesture each, optionally limited to palm positions), a profile's action on a band-control gesture is kept and shown as **paused**, and the app gets the new gestures and screens: index/middle hold, drags and sliders, the middle dial, palm overrides, palm calibration, teach your drags, the draw pad, drawings and teaching them.

**Architecture:**
- **band-core decides, the app shows.** The bridge parses one profiles JSON into `Profiles<String>` + per-hand `Options`, and answers the screens with static JNI calls that run band-core's own rules (`uses`, `paused`, `paused_sliders`, `invalid`, `warnings`, and "what would moving this band control pause"). Kotlin never re-implements those rules.
- **New `@` lines** in the actions channel carry the draw session and drag teaching (`@draw-started`, `@pen`, `@draw-point`, `@draw-finished`, `@drawn`, `@not-drawn`, `@draw-cancelled`, `@drag-sample`), next to today's `@gesture`, `@controls`, `@profile`, `@held`, `@practiced`.
- **Teaching helpers are static** (palm calibration from rolls, `Prototypes::taught`, `draw::check_tries`, `Library::looks_like`); the connection adds the draw session, teaching modes, the drawings library and a live readout for the calibration screen (the roll from orientation events, the palm from `Controls::palm`).
- **Kotlin keeps storage** (profiles, band controls, drawings, calibrations per band and wrist), the screens (built in code with the `Neu`/`Cb` helpers, as today) and the overlay pad (an accessibility overlay from `TouchService`).

**Tech Stack:** Rust 2024 (JNI bridge, `jni` 0.21, serde_json), Kotlin (plain Android views, no AndroidX, JUnit 4 with `org.json`), band-core `controls` (local, unreleased).

**Specs:** `~/Projects/band-core/docs/superpowers/specs/2026-10-25-shared-controls-design.md` (revised: band controls; "Platform side after the move → Android") and `~/Projects/band-core/docs/superpowers/specs/2026-10-25-pinch-palm-drawings-design.md` (parts 2–4, "Platform side → Android"). API: `~/Projects/band-core/src/controls/{mod,profiles,pinch,palm,draw,session}.rs`, band-core `CHANGELOG.md` 0.5.0.

**What exists on `controls` already** (plan `2026-10-25-shared-controls-c-android.md`, done): the bridge runs `Controls<String>` from a profiles JSON; built-ins are *actions* (`controls.toggle`, `profile.next`, `profile.previous`) stored in profiles; a per-profile warnings card from `Bridge.profileWarnings`. This plan replaces those built-in actions with band controls. The submodule is pinned to band-core `f5fccf6` (the first build's API: `Action<A>`, `Profile::dial`, `Warning::NoControls { profile }`); none of it compiles against the new tip until Task 1 is done.

---

## The approved mockups (https://claude.ai/artifact/H6SNBzoLUnqrEQYFEsjCiJ; described, not fetched)

| # | Screen | What it shows |
|---|---|---|
| 1 | Gestures | A **Band controls · All profiles** card above Profiles: Controls on/off (default Middle hold), Next profile (Index triple tap), Previous profile (Middle triple tap), Draw (default Nothing). A row whose gesture has paused actions shows a coral **number** badge (just the number, digits optically centred, `Cb.Signal.STOP` fill and label colour). Each profile row, and During calls, shows its own coral number of paused actions. A warnings card from `Profiles::warnings`. |
| 2 | Picking a band control's gesture | Nothing, then every gesture with "used in N" / "free" / "Now". Gestures held by the other band controls aren't listed. |
| 3 | Conflict prompt | Picking a used gesture asks: "Middle double tap is used in 3 places", each place with its action and a coral **Will pause** chip; Cancel / Use it anyway. A free gesture is set without asking. |
| 5 | In a profile (and During calls) | Rows show the profile's own action. A paused one has a coral **Paused** chip before the action; a band-control gesture with no action just says "Nothing". A note names the gestures that are band controls now. No "all profiles" line, no lock, nothing greyed. |
| 6 | Editing a band-control gesture in a profile | The normal action picker (any action). A coral **Paused** banner says which band control is on it and that the choice runs once it moves; a link to Band controls. |
| 7 | A band control moved away | Its paused actions show normally again. **No toast.** |
| 9 | Profile | Grouped **Thumb** (swipes) / **Index** (Tap, Double tap, Triple tap, Hold) / **Middle** (same). Palm overrides as a small grey tag under the action ("Palm up · Next track"). The index hold says "when you let go" when that finger's hold waits (it has a dial). A **Dials · pinch and turn** card: Index dial, Middle dial. |
| 10 | Editing a gesture | A 3-tile strip (Palm down / Sideways / Palm up; an override's tile is dark), a **Normal action** card, a **Palm overrides · up to 2** card with Remove buttons and "Add an override", a note that one position always keeps the normal action, a link to calibration. |
| 11 | Middle dial picker | Nothing / Volume / Playback position / Brightness / Navigate / Send to automation; once set, a note: "Middle hold now acts when you let go". |
| 12 | Band control editor | The gesture row, then **Palm position**: "Any position" / "Only with the palm…" with Down / Sideways / Up chips (1–2); a note about the other positions; a coral count "… · palm up is used in 2 places". |
| 13 | Palm calibration | A progress ring; step 1 Palm down **Done**, step 2 Palm up **holding…**; a live "Right now"; saved per band and wrist; switching the hand asks again. |
| 13b | Drags (per finger, per profile) | Segmented **Gestures | Sliders**. Gestures: 6 rows (Drag left / right / toward / away / up / down) with palm tags. Sliders: Left/right (right = forward), Toward/away (away = up), Up/down (up = up), each with a dial target. |
| 14 | Draw pad | A dark card over the app in front, live strokes from `DrawPoint`, a "Pen down" indicator, the result ("Lightbulb → Light on/off", or "Not one of your drawings" + two short buzzes). |
| 15 | Drawings | The Draw band control row; an **All profiles** list (thumbnail + action); a **"<Profile>'s own"** list with a "replaces shared" tag; Teach a drawing buttons. |
| 16 | Teach a drawing | 3 try tiles, an **Alike** chip, the name, the action, Where (All profiles / Only in <profile>), Save after the 3rd; errors from `draw::check_tries` / `Library::looks_like` ("these don't look alike…", "looks like Circle"). |
| — | Teach your drags | The turn and six directions, one pinch each (`set_drag_teaching` / `DragSample` → `Prototypes::taught`; errors `TooAlike`, `LikeTheTurn`, …), stored per band and wrist like the palm calibration. |

## Judgement calls (decided here, one line each)

1. **During calls** keeps one action per gesture (no palm overrides) and lists the swipes, taps, triples and holds, not the drags (drags are "as usual" in a call).
2. **During calls becomes its own screen** (`CallsActivity`), reached from a row on Gestures that carries its coral number (mockup 1 counts it like a profile row).
3. **The drags** are two rows in the Dials card ("Index drags", "Middle drags") that open the Drags screen (13b).
4. **Two band controls may share a gesture in disjoint palm positions**; the picker (2) hides a gesture only when another band control holds it in a position this one also covers, and the editor (12) disables the chips that would overlap.
5. **A third palm chip is refused** with a note ("all three is Any position").
6. **Storage:** drawing templates in `filesDir/drawings.json`; shared drawing actions, band controls and calibrations in the app's prefs; calibrations keyed by the band's Bluetooth address and wrist; `BandLink` remembers the address.
7. **Automation names:** an override sends `gesture=<key>` plus `palm=<down|sideways|up>`; the index dial keeps 0.7.7's `gesture=dial`, the middle dial is `middle_dial`, a slider `<finger>_<pair>` (`index_left_right`…), a drawing `gesture=drawing` + `name`.
8. **Backup version 2** carries the band controls, drawings and their shared actions; calibrations aren't backed up (they belong to a band). Version 1 backups still restore (band controls standard, built-ins dropped).
9. **Without the accessibility service** there is no pad: results show as toasts (and the two buzzes).
10. **"Pen down"** follows band-core's `Output::Pen` (`@pen down|up`).
11. **"When you let go"** comes from band-core's `Profiles::hold_waits` per palm position: the hint shows when the hold waits in any position, naming the positions when it's not all three.
12. **Warnings move to the Gestures screen** (they're for the whole set now); `Bridge.profileWarnings` and the per-profile warnings card go.
13. **Teach your drags' turn** is "the way that turns the volume up" (band-core: a taught turn's + is the user's own).
14. **After `@pen down` the next `@draw-point` starts its stroke**; a dropped stroke's number comes again, and the pad redraws it.
15. **Calibration screens ask which wrist** when the hand is "band" and the band hasn't said yet.
16. **Switching the hand asks to calibrate** whenever the new wrist has no palm calibration on this band (the right wrist's defaults still work if declined).

## band-core API for what were gaps (band-core `controls` `bf121bf`, under review: small changes possible)

The first draft of this plan worked around four gaps; band-core now fills them, and this plan uses them (band-core is still not changed from here):
- `Controls::set_calibration(palm, drags)` swaps the palm calibration and taught drags without touching the hand state (a runtime hand, its request and retry stay; nothing is written to the band). The bridge calls it when the band reports its hand, and when only the calibration changed in a new profiles JSON; `set_options` only when the hand setting changed.
- `Controls::palm(now) -> Palm`: the palm position now, as the engine reads it (down when stale or uncalibrated). The calibration screen's "Right now" shows it, with the roll the bridge reads from orientation events (the samples the calibration needs).
- `Output::Pen { down }`: the pad's "Pen down" light, as `@pen down|up` (down just before a stroke's first `DrawPoint`; up when the pen lets go or its stroke drops).
- `Profiles::hold_waits(profile, finger, palm)`: the "when you let go" hint; the bridge reports it per palm position and doesn't copy the rule.
- `Responder::gyro3` / `Responder::orientation` let the bridge's connection tests send motion and orientation.

Still as documented: `on_disconnect` ends a draw session without an output, so the app closes the pad on disconnect.

---

## House rules

- Work in `~/Projects/air-gestures-android` on the local branch **`controls`**. **Never push, merge or tag.** No sudo. Never run `iwctl`.
- **Never run `cargo fmt`.** Stage files **by name** (`git add <paths>`), never `-A` / `.`.
- band-core is read-only here: the submodule moves to band-core's local `controls` tip in Task 1 (`git -C rust/band-core fetch ~/Projects/band-core controls && git -C rust/band-core checkout FETCH_HEAD`), never pushed, never edited. If band-core blocks something, stop and report it.
- Before every commit, all of these pass:
  - `(cd rust && cargo test 2>&1 | grep -E "^test result|FAILED|^error" )` — every `test result: ok`;
  - `(cd rust && cargo clippy -p bridge --all-targets 2>&1 | grep -c "^warning")` — no more than before the task;
  - `./build-rust.sh >/dev/null`;
  - `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew -q assembleDebug testDebugUnitTest`.
- **Dev builds** are signed with `~/.android/debug.keystore.linux-backup` (`local.properties` has `debug.keystore=`) and carry the "dev" icon tab (`app/src/debug/res/mipmap-*/ic_launcher_foreground.png`). Check both **before** installing (commands in Task 19). **Never uninstall a dev build**, not even for a signature mismatch: stop and tell the owner.
- Phone: adb serial **`f940914b`** (always `adb -s f940914b`). It has two displays: screenshots use `screencap -d` with the **first** id from `dumpsys SurfaceFlinger --display-id`. **The owner does the phone checks**; screenshots are only to look at layout.
- Commit messages end with:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01MLDqGPYx8bTGKJcVLgoyzo
  ```

Paths: Kotlin sources are under `app/src/main/java/dev/kb896/airgestures/`, Kotlin tests under `app/src/test/java/dev/kb896/airgestures/`, Rust under `rust/bridge/`.

---

## File structure

**Rust bridge**
- `rust/band-core` — submodule moved to band-core's `controls` tip.
- `src/profiles.rs` — rewritten: the new JSON → `Profiles<String>` and a per-hand `Setup` (`options`, `palm`, `hand_in_use`).
- `src/report.rs` — new: `report(json)` (warnings, invalid, paused, paused_sliders, uses, hold_waits) and `band_choices(json, control, palm)`.
- `src/teach.rs` — new: `palm_calibration`, `drag_prototypes`, `library`, `check_tries`, `looks_like`.
- `src/palmlive.rs` — new: the band's roll right now (the palm comes from `Controls::palm`).
- `src/lib.rs` — `Connection`: `Setup`, options on handedness, `line()` for every `@` line, draw/teach calls, `palm_now`.
- `src/jni_api.rs` — new natives; `profileWarnings` goes.
- Tests: `tests/profiles.rs` (rewritten), `tests/lines.rs`, `tests/report.rs`, `tests/teach.rs` (new), `tests/connection.rs` (its JSON helper and two tests change, three tests added).

**Kotlin**
- `Mapping.kt` — `Part`; `Gesture` gets the holds and drags, `short`, `part`, `drag`; `Action` loses the built-ins.
- `Controls.kt` — new: `Palm`, `Builtin`, `BandControl`, `BandControls`.
- `ProfileList.kt` — `Profile` gains palm overrides, middle dial, drags, own drawings; `Drags`; `withoutBuiltins()`.
- `Drawings.kt` — new: `Stroke`, `Drawing`, `DrawingLibrary` (pure) and `Drawings` (storage).
- `Calibration.kt` — new: `Calibration` (pure) and `Calibrations` (storage).
- `Teach.kt` — new, pure: `PalmSampler`, `DragTeach`, `DrawTeach`.
- `Report.kt` — new, pure: parses `Bridge.profileReport` / `Bridge.bandChoices`.
- `Profiles.kt` — band controls, shared drawing actions, the one-time migration.
- `Mappings.kt` — `profilesJson(Input)`, dial names, `automation(rest)`.
- `Prefs.kt`, `Lessons.kt` (+ `Backup`), `Bridge.kt`, `BandLink.kt`, `BandService.kt`, `Actions.kt`, `PracticeActivity.kt`, `SetupActivity.kt`, `MainActivity.kt`, `TouchService.kt`.
- UI: `Neu.kt` (`CountBadge`, `Cb.count`), `Ui.kt` (`navRow` count, `Option.detail`, `choose(note)`), `GestureScreen.kt` (rows, labels), `GesturesActivity.kt`, `ProfileActivity.kt`; new `CallsActivity.kt`, `BandControlActivity.kt`, `GestureActivity.kt`, `DragsActivity.kt`, `PalmCalibrationActivity.kt`, `TeachDragsActivity.kt`, `DrawPad.kt` (overlay + `StrokesView`), `DrawingsActivity.kt`, `TeachDrawingActivity.kt`; `AndroidManifest.xml`.
- Tests: `ControlsTest.kt`, `ReportTest.kt`, `DrawingsTest.kt`, `TeachTest.kt` (new); `MappingsTest.kt`, `ProfileListTest.kt`, `LessonsBackupTest.kt` (updated).
- `CHANGELOG.md`.

### The profiles JSON (Kotlin → bridge)

```json
{
  "hand": "band",
  "picked": 0,
  "apps": {"org.videolan.vlc": 1},
  "band": {
    "controls": {"gesture": "middle_hold", "palm": null},
    "next": {"gesture": "index_triple", "palm": ["up"]},
    "previous": {"gesture": "middle_triple", "palm": null},
    "draw": null
  },
  "calls": {"index_double": "call.answer", "middle_double": "-"},
  "drawings": {"Lightbulb": "torch.toggle"},
  "calibration": {
    "left": null,
    "right": {"palm": {"down": -141.0, "up": -12.0},
              "drags": {"roll": -141.0, "turn": [0.05, 0.98, 0.2], "drags": [[…], [… six]]}}
  },
  "profiles": [
    {"name": "Profile 1",
     "map": {"swipe_up": "media.previous", "index_tap": "media.play_pause", "index_hold": ""},
     "palm": {"index_tap": {"up": "media.next", "sideways": ""}},
     "index": {"dial": {"up": "volume.up", "down": "volume.down"}, "drags": "gestures"},
     "middle": {"dial": null, "drags": {"sliders": [{"up": "brightness.up", "down": "brightness.down"}, null, null]}},
     "drawings": {"Lightbulb": "app:org.home"}}
  ]
}
```

- `map`: the normal action per gesture key (all 24 keys, defaults resolved by Kotlin); `""` or missing is nothing.
- `palm`: overrides per gesture by palm key (`down`, `sideways`, `up`); `""` is nothing there. More than two are kept (band-core reports `Invalid::Binding`).
- `band`: per band control, its gesture key and positions (`null` = all); `null` or missing = no gesture.
- `calls`: only gestures whose call action isn't "as usual"; `"-"` is nothing.
- `drags`: `"gestures"`, or `{"sliders": [left/right, toward/away, up/down]}`, each a dial or `null`.
- `calibration`: this band's, per wrist, as the teach helpers return them; checked again on parse.
- Automation is already expanded by Kotlin (`automation:<key>`, `automation:<key>:<palm>`, `automation:dial:<name>:up|down`, `automation:drawing:<name>`).

### The `@` lines (bridge → Kotlin, in `Bridge.actions`)

Existing: `@gesture <label>`, `@controls on|off`, `@profile <index> <step>`, `@held <app>`, `@practiced <action>`. New:

| Line | From |
|---|---|
| `@draw-started` | `Output::DrawStarted` |
| `@pen down` / `@pen up` | `Output::Pen { down }`: down just before a stroke's first point, up when the pen lets go or its stroke drops |
| `@draw-point <stroke> <x> <y>` | `Output::DrawPoint` (degrees, 2 decimals, x right, y up); the first after `@pen down` starts its stroke, and a dropped stroke's number comes again (redraw it) |
| `@draw-finished <json>` | `Output::DrawFinished`: `[[[x,y],…],…]` (strokes) |
| `@drawn <name>` | `Output::Drawn` (the distance goes to the log) |
| `@not-drawn` | `Output::NotDrawn` |
| `@draw-cancelled` | `Output::DrawCancelled` |
| `@drag-sample <index\|middle> <x> <y> <z> <roll>` | `Output::DragSample`, plus `Controls::prototypes().palm_down_roll` (what `Prototypes::taught` needs) |

---

### Task 1: band-core's `controls` tip; the new profiles JSON; every `@` line

**Files:**
- Modify: `rust/band-core` (submodule), `rust/Cargo.lock`, `rust/bridge/src/profiles.rs` (rewrite), `rust/bridge/src/lib.rs`, `rust/bridge/src/jni_api.rs` (only `profileWarnings`' doc)
- Test: `rust/bridge/tests/profiles.rs` (rewrite), `rust/bridge/tests/lines.rs` (new), `rust/bridge/tests/connection.rs` (helper + one test)

- [ ] **Step 1: Move the submodule** (not committed until Step 7; the bridge doesn't build in between)

```bash
cd ~/Projects/air-gestures-android
git status -s                                  # empty
git branch --show-current                      # controls
git -C rust/band-core fetch -q ~/Projects/band-core controls
git -C rust/band-core checkout -q FETCH_HEAD
git -C rust/band-core log --oneline -1         # bf121bf controls: pen state for the pad; hold_waits for the UI (or later: under review;
                                               # if it moved, check set_calibration, palm, Output::Pen, hold_waits still read as used here)
(cd rust && cargo build -p bridge 2>&1 | grep -E "^error" | head)   # errors in profiles.rs / lib.rs: expected
```

- [ ] **Step 2: Failing tests for the JSON** — replace `rust/bridge/tests/profiles.rs` with:

```rust
use airgestures_bridge::profiles::parse;
use band_core::controls::{BandControl, Binding, CallBinding, Dial, DragMode, Gesture, HandSetting, Palm, PalmSet, Palms};
use band_core::events::Hand;

const FULL: &str = r#"{"hand":"left","picked":1,"apps":{"vlc":0},
  "band":{"controls":{"gesture":"middle_hold","palm":null},"next":{"gesture":"index_triple","palm":["up"]},
          "previous":null,"draw":{"gesture":"swipe_down"}},
  "calls":{"index_double":"call.answer","middle_double":"-","swipe_up":""},
  "drawings":{"Lightbulb":"torch.toggle"},
  "calibration":{"left":{"palm":{"down":-140.0,"up":-12.0},"drags":null},"right":null},
  "profiles":[
    {"name":"A","map":{"swipe_up":"media.previous","index_tap":""},
     "palm":{"index_tap":{"up":"media.next","sideways":""},"index_hold":{"down":"x"}},
     "index":{"dial":{"up":"volume.up","down":"volume.down"},"drags":"gestures"},
     "middle":{"dial":null,"drags":{"sliders":[{"up":"brightness.up","down":"brightness.down"},null,null]}},
     "drawings":{"Lightbulb":"app:org.home"}},
    {"name":"B"}]}"#;

fn dial(up: &str, down: &str) -> Dial<String> {
    Dial { up: up.into(), down: down.into() }
}

#[test]
fn the_apps_json_becomes_band_controls_and_profiles() {
    let (setup, profiles) = parse(FULL).unwrap();
    assert_eq!(setup.hand, HandSetting::Left);
    assert_eq!(profiles.picked, 1);
    assert_eq!(profiles.apps.get("vlc"), Some(&0));
    assert_eq!(profiles.band.controls, Some(BandControl::on(Gesture::MiddleHold)));
    assert_eq!(
        profiles.band.next,
        Some(BandControl { gesture: Gesture::IndexTriple, palm: PalmSet::Only(Palms::from([Palm::Up])) })
    );
    assert_eq!(profiles.band.previous, None);
    assert_eq!(profiles.band.draw, Some(BandControl::on(Gesture::SwipeDown)));
    assert_eq!(profiles.drawings.get("Lightbulb").map(String::as_str), Some("torch.toggle"));
    let a = &profiles.list[0];
    assert_eq!(a.gestures.get(&Gesture::SwipeUp), Some(&Binding::from("media.previous".to_owned())));
    // The index tap: nothing normally, the next track palm up, nothing sideways.
    let tap = &a.gestures[&Gesture::IndexTap];
    assert_eq!(tap.normal, None);
    assert_eq!(tap.action(Palm::Up).map(String::as_str), Some("media.next"));
    assert_eq!(tap.overrides.get(&Palm::Side), Some(&None));
    assert_eq!(a.gestures[&Gesture::IndexHold].action(Palm::Down).map(String::as_str), Some("x"));
    assert_eq!(a.index.dial, Some(dial("volume.up", "volume.down")));
    assert_eq!(a.index.drags, DragMode::Gestures);
    assert!(a.middle.dial.is_none());
    assert_eq!(a.middle.drags, DragMode::Sliders([Some(dial("brightness.up", "brightness.down")), None, None]));
    assert_eq!(a.drawings.get("Lightbulb").map(String::as_str), Some("app:org.home"));
    let b = &profiles.list[1];
    assert!(b.gestures.is_empty() && b.index.dial.is_none() && b.index.drags == DragMode::Gestures);
    assert_eq!(
        profiles.calls.get(&Gesture::IndexDouble),
        Some(&CallBinding::Do(Binding::from("call.answer".to_owned())))
    );
    assert_eq!(profiles.calls.get(&Gesture::MiddleDouble), Some(&CallBinding::Nothing));
    assert_eq!(profiles.calls.get(&Gesture::SwipeUp), Some(&CallBinding::AsUsual));
}

#[test]
fn the_options_follow_the_hand_in_use() {
    let (setup, _) = parse(FULL).unwrap();
    // Set to the left wrist: its calibration until the band says otherwise.
    assert_eq!(setup.options(None).palm.map(|c| c.down_roll), Some(-140.0));
    assert_eq!(setup.options(Some(Hand::Left)).palm.map(|c| c.up_roll), Some(-12.0));
    // The band still says right (the write hasn't landed): band-core reads the right hand.
    assert_eq!(setup.options(Some(Hand::Right)).palm, None);
    let (band, _) = parse(r#"{"hand":"band","calibration":{"right":{"palm":{"down":-150.0,"up":-20.0}}}}"#).unwrap();
    assert_eq!(band.options(None).palm, None, "no hand known yet");
    assert_eq!(band.options(Some(Hand::Right)).palm.map(|c| c.down_roll), Some(-150.0));
}

#[test]
fn stored_drags_are_checked_again() {
    let good = r#"{"hand":"right","calibration":{"right":{"drags":{"roll":-142.3,"turn":[0,1,0],
        "drags":[[0,0,1],[0,0,-1],[1,0,0],[-1,0,0],[0.7,-0.7,0],[-0.7,0.7,0.1]]}}}}"#;
    let (setup, _) = parse(good).unwrap();
    assert_eq!(setup.options(None).drags.map(|p| p.palm_down_roll), Some(-142.3));
    let bad = good.replace("[0,0,-1]", "[0,0,1]");
    assert!(parse(&bad).unwrap().0.options(None).drags.is_none(), "two drags alike: the defaults instead");
}

#[test]
fn unknown_keys_are_skipped_and_bad_json_is_an_error() {
    let (_, profiles) = parse(
        r#"{"band":{"controls":{"gesture":"index_wave"}},
            "profiles":[{"name":"A","map":{"index_wave":"x"},"palm":{"index_tap":{"left":"y"}}}]}"#,
    )
    .unwrap();
    assert_eq!(profiles.band.controls, None);
    assert!(profiles.list[0].gestures.is_empty());
    assert!(parse("{").is_err());
    assert!(parse("{}").unwrap().1.list.is_empty());
}
```

Run: `(cd rust && cargo test -p bridge --test profiles 2>&1 | grep -E "^error" | head -3)` — Expected: errors (`parse` returns the old tuple; old imports).

- [ ] **Step 3: `rust/bridge/src/profiles.rs`** — replace the whole file:

```rust
//! The app's profiles, as JSON from Kotlin, into band-core's `Profiles` and the per-hand
//! `Options`. The format is in docs/superpowers/plans/2026-10-25-c2-android-controls.md
//! ("The profiles JSON"):
//!
//! - `map`: the normal action; `""` or a missing key is nothing.
//! - `palm`: overrides per gesture, by palm key (`down`, `sideways`, `up`); `""` is nothing
//!   there. More than two are kept: band-core reports them (`Invalid::Binding`).
//! - `band`: each band control's gesture key and positions (`null`: all); `null` or a missing
//!   key is no gesture. An unknown gesture is no gesture, an unknown position is skipped.
//! - `calls`: only gestures whose call action isn't "as usual"; `"-"` is nothing.
//! - `drags`: `"gestures"`, or `{"sliders": [left/right, toward/away, up/down]}`.
//! - `calibration`: this band's, per wrist (see [`Setup::options`]).
//! - `hand`: `band`, `left` or `right`.

use std::collections::BTreeMap;

use band_core::controls::pinch::Prototypes;
use band_core::controls::{
    BandControl, BandControls, Binding, CallBinding, Dial, DragMode, FingerSetup, Gesture, HandSetting, Options, Palm,
    PalmCalibration, PalmSet, Profile, Profiles,
};
use band_core::events::Hand;
use serde::Deserialize;

#[derive(Deserialize, Default)]
#[serde(default)]
struct Json {
    hand: String,
    picked: usize,
    apps: BTreeMap<String, usize>,
    band: BandJson,
    calls: BTreeMap<String, String>,
    drawings: BTreeMap<String, String>,
    calibration: HandsJson,
    profiles: Vec<ProfileJson>,
}

#[derive(Deserialize, Default)]
#[serde(default)]
struct BandJson {
    controls: Option<ControlJson>,
    next: Option<ControlJson>,
    previous: Option<ControlJson>,
    draw: Option<ControlJson>,
}

#[derive(Deserialize)]
struct ControlJson {
    gesture: String,
    #[serde(default)]
    palm: Option<Vec<String>>,
}

#[derive(Deserialize)]
struct ProfileJson {
    name: String,
    #[serde(default)]
    map: BTreeMap<String, String>,
    #[serde(default)]
    palm: BTreeMap<String, BTreeMap<String, String>>,
    #[serde(default)]
    index: FingerJson,
    #[serde(default)]
    middle: FingerJson,
    #[serde(default)]
    drawings: BTreeMap<String, String>,
}

#[derive(Deserialize, Default)]
#[serde(default)]
struct FingerJson {
    dial: Option<DialJson>,
    drags: DragsJson,
}

#[derive(Deserialize, Clone)]
struct DialJson {
    up: String,
    down: String,
}

#[derive(Deserialize)]
#[serde(untagged)]
enum DragsJson {
    Sliders { sliders: [Option<DialJson>; 3] },
    /// `"gestures"` (any other word reads as gestures too).
    Mode(String),
}

impl Default for DragsJson {
    fn default() -> Self {
        DragsJson::Mode("gestures".into())
    }
}

#[derive(Deserialize, Default)]
#[serde(default)]
struct HandsJson {
    left: Option<CalibrationJson>,
    right: Option<CalibrationJson>,
}

#[derive(Deserialize, Default)]
#[serde(default)]
struct CalibrationJson {
    palm: Option<PalmJson>,
    drags: Option<PrototypesJson>,
}

#[derive(Deserialize)]
struct PalmJson {
    down: f64,
    up: f64,
}

/// Taught drags as `teach::drag_prototypes` returns them (and the app stores them).
#[derive(Deserialize)]
pub(crate) struct PrototypesJson {
    pub roll: f64,
    pub turn: [f64; 3],
    pub drags: [[f64; 3]; 6],
}

/// What one wrist has on this band.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Calibration {
    pub palm: Option<PalmCalibration>,
    pub drags: Option<Prototypes>,
}

/// The hand setting and both wrists' calibrations: the options follow the hand in use.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Setup {
    pub hand: HandSetting,
    pub left: Calibration,
    pub right: Calibration,
}

impl Setup {
    /// The hand band-core reads gestures for: the band's, else the one it's set to (as
    /// `Controls` does).
    pub fn hand_in_use(&self, band: Option<Hand>) -> Option<Hand> {
        band.or(match self.hand {
            HandSetting::Left => Some(Hand::Left),
            HandSetting::Right => Some(Hand::Right),
            HandSetting::Band => None,
        })
    }

    /// The options for the hand in use (`band`: the hand the band reported, if it has): that
    /// wrist's palm calibration and taught drags; none for an unknown hand (band-core's defaults).
    pub fn options(&self, band: Option<Hand>) -> Options {
        let calibration = match self.hand_in_use(band) {
            Some(Hand::Left) => self.left.clone(),
            Some(Hand::Right) => self.right.clone(),
            None => Calibration::default(),
        };
        Options { hand: self.hand, palm: calibration.palm, drags: calibration.drags, ..Options::default() }
    }
}

pub fn parse(json: &str) -> Result<(Setup, Profiles<String>), String> {
    let json: Json = serde_json::from_str(json).map_err(|e| e.to_string())?;
    let setup = Setup {
        hand: match json.hand.as_str() {
            "left" => HandSetting::Left,
            "right" => HandSetting::Right,
            _ => HandSetting::Band,
        },
        left: calibration(json.calibration.left),
        right: calibration(json.calibration.right),
    };
    let band = BandControls {
        controls: control(json.band.controls),
        next: control(json.band.next),
        previous: control(json.band.previous),
        draw: control(json.band.draw),
    };
    let list = json.profiles.into_iter().map(profile).collect();
    let calls = json
        .calls
        .iter()
        .filter_map(|(key, id)| {
            let gesture = Gesture::from_key(key)?;
            let binding = match id.as_str() {
                "-" => CallBinding::Nothing,
                "" => CallBinding::AsUsual,
                other => CallBinding::Do(Binding::from(other.to_owned())),
            };
            Some((gesture, binding))
        })
        .collect();
    Ok((setup, Profiles { band, list, picked: json.picked, apps: json.apps, calls, drawings: json.drawings }))
}

fn calibration(json: Option<CalibrationJson>) -> Calibration {
    let json = json.unwrap_or_default();
    Calibration {
        palm: json.palm.map(|p| PalmCalibration { down_roll: p.down, up_roll: p.up }),
        // Checked again: a damaged set gives band-core's defaults rather than nonsense.
        drags: json.drags.and_then(|d| Prototypes::taught(d.turn, d.drags, d.roll).ok()),
    }
}

fn control(json: Option<ControlJson>) -> Option<BandControl> {
    let json = json?;
    let gesture = Gesture::from_key(&json.gesture)?;
    let palm = match json.palm {
        None => PalmSet::All,
        Some(keys) => PalmSet::Only(keys.iter().filter_map(|key| Palm::from_key(key)).collect()),
    };
    Some(BandControl { gesture, palm })
}

fn dial(json: Option<DialJson>) -> Option<Dial<String>> {
    json.map(|d| Dial { up: d.up, down: d.down })
}

fn finger(json: FingerJson) -> FingerSetup<String> {
    let drags = match json.drags {
        DragsJson::Sliders { sliders } => DragMode::Sliders(sliders.map(dial)),
        DragsJson::Mode(_) => DragMode::Gestures,
    };
    FingerSetup { dial: dial(json.dial), drags }
}

fn nothing() -> Binding<String> {
    Binding { normal: None, overrides: BTreeMap::new() }
}

fn profile(json: ProfileJson) -> Profile<String> {
    let mut profile = Profile::new(&json.name);
    for (key, id) in json.map {
        if let Some(gesture) = Gesture::from_key(&key)
            && !id.is_empty()
        {
            profile.gestures.entry(gesture).or_insert_with(nothing).normal = Some(id);
        }
    }
    for (key, positions) in json.palm {
        let Some(gesture) = Gesture::from_key(&key) else { continue };
        for (palm, id) in positions {
            if let Some(palm) = Palm::from_key(&palm) {
                profile.gestures.entry(gesture).or_insert_with(nothing).overrides.insert(palm, (!id.is_empty()).then_some(id));
            }
        }
    }
    profile.index = finger(json.index);
    profile.middle = finger(json.middle);
    profile.drawings = json.drawings.into_iter().filter(|(_, id)| !id.is_empty()).collect();
    profile
}

/// The warnings, one word each line (`no-controls`, `no-way-out`, `draw-on-tap`); invalid JSON:
/// nothing. Only until Task 2 replaces it with `report::report`.
pub fn warnings(json: &str) -> String {
    use band_core::controls::Warning;
    let Ok((_, profiles)) = parse(json) else { return String::new() };
    profiles
        .warnings()
        .iter()
        .map(|w| match w {
            Warning::NoControls => "no-controls",
            Warning::NoWayOut => "no-way-out",
            Warning::DrawOnTap => "draw-on-tap",
        })
        .collect::<Vec<_>>()
        .join("\n")
}
```

- [ ] **Step 4: Failing test for the `@` lines** — create `rust/bridge/tests/lines.rs`:

```rust
use airgestures_bridge::line;
use band_core::controls::{Finger, Gesture, Output};

#[test]
fn the_controls_outputs_become_at_lines() {
    let l = |output: Output<String>| line(&output, -142.3);
    assert_eq!(l(Output::Gesture(Gesture::IndexHold)).as_deref(), Some("@gesture index hold"));
    assert_eq!(l(Output::Controls { enabled: false }).as_deref(), Some("@controls off"));
    assert_eq!(l(Output::Profile { index: 1, step: -1 }).as_deref(), Some("@profile 1 -1"));
    assert_eq!(l(Output::ProfileHeld { app: "vlc".into() }).as_deref(), Some("@held vlc"));
    assert_eq!(l(Output::Practiced("media.next".into())).as_deref(), Some("@practiced media.next"));
    assert_eq!(l(Output::DrawStarted).as_deref(), Some("@draw-started"));
    assert_eq!(l(Output::DrawPoint { stroke: 0, x: 1.0, y: -2.5 }).as_deref(), Some("@draw-point 0 1.00 -2.50"));
    assert_eq!(l(Output::Pen { down: true }).as_deref(), Some("@pen down"));
    assert_eq!(l(Output::Pen { down: false }).as_deref(), Some("@pen up"));
    assert_eq!(
        l(Output::DrawFinished { strokes: vec![vec![[0.0, 0.0], [1.5, 2.0]]] }).as_deref(),
        Some("@draw-finished [[[0.0,0.0],[1.5,2.0]]]")
    );
    assert_eq!(l(Output::Drawn { name: "Light bulb".into(), distance: 0.8 }).as_deref(), Some("@drawn Light bulb"));
    assert_eq!(l(Output::NotDrawn).as_deref(), Some("@not-drawn"));
    assert_eq!(l(Output::DrawCancelled).as_deref(), Some("@draw-cancelled"));
    assert_eq!(
        l(Output::DragSample { finger: Finger::Middle, direction: [0.0, 0.6, 0.8] }).as_deref(),
        Some("@drag-sample middle 0.0000 0.6000 0.8000 -142.30")
    );
    assert_eq!(l(Output::Run("media.next".into())), None, "actions go as they are");
}
```

- [ ] **Step 5: `lib.rs`**
1. `Connection` gains `setup: profiles::Setup` (keep `controls`). In `new`: `let (setup, profiles) = profiles::parse(profiles)?; let mut controls = Controls::new(setup.options(None), profiles);` and store `setup`.
2. `set_profiles`:
   ```rust
       pub fn set_profiles(&mut self, json: &str) -> Result<(), String> {
           let (setup, profiles) = profiles::parse(json)?;
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
   ```
3. `on_event`, after `let outputs = self.controls.on_event(event, now);` (no `mut`: `set_calibration` returns nothing):
   ```rust
           // The band said which wrist: that wrist's calibration and taught drags, the hand
           // state untouched (set_options would ask for the hand again).
           if let Event::Handedness(hand) = event {
               let options = self.setup.options(Some(*hand));
               let current = self.controls.options();
               if options.palm != current.palm || options.drags != current.drags {
                   self.controls.set_calibration(options.palm, options.drags);
               }
           }
           self.take(outputs, Some(now))
   ```
4. In `take`, every output other than `Run` and `SetHand` goes through `line()`:
   ```rust
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
   ```
5. Add, after `impl Connection`:
   ```rust
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
               let finger = match finger {
                   Finger::Index => "index",
                   Finger::Middle => "middle",
               };
               format!("@drag-sample {finger} {x:.4} {y:.4} {z:.4} {roll:.2}")
           }
           Output::Run(_) | Output::SetHand(_) => return None,
       })
   }
   ```
   Imports: `use band_core::controls::{Controls, Finger, Output};`. Update `take_actions`' doc comment with the new lines (copy the table above).
6. `jni_api.rs`: `profileWarnings`' doc now reads "one word per line: `no-controls`, `no-way-out`, `draw-on-tap`".

- [ ] **Step 6: `tests/connection.rs`** — the JSON helper takes band controls; the standard ones by default:

```rust
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
```

Replace `json_hand(h, m, e)` calls with `json_full(h, STANDARD, m, e)`. In `remapped_gestures_run_their_new_actions_the_hold_too`, drop `("middle_hold", "media.next")` and the hold lines (rename it `remapped_gestures_run_their_new_actions`), and add:

```rust
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
```

Run: `(cd rust && cargo test -p bridge 2>&1 | grep -E "^test result|FAILED|panicked" )`. Expected: all ok. If a connection test fails for a reason other than the JSON (band-core's gesture timing), stop and report it: band-core's own tests define that behaviour.

- [ ] **Step 7: Commit** (the Kotlin app still sends the old JSON; it isn't installed until Task 7)

```bash
(cd rust && cargo clippy -p bridge --all-targets 2>&1 | grep -c "^warning")
./build-rust.sh >/dev/null && JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew -q assembleDebug testDebugUnitTest
git add rust/band-core rust/Cargo.lock rust/bridge/src/profiles.rs rust/bridge/src/lib.rs rust/bridge/src/jni_api.rs \
  rust/bridge/tests/profiles.rs rust/bridge/tests/lines.rs rust/bridge/tests/connection.rs
git commit -q -m "bridge: band-core's band controls, pinch, palm and drawings; the new profiles JSON and @ lines

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MLDqGPYx8bTGKJcVLgoyzo"
```

---

### Task 2: The screens' report and the band-control choices (static)

**Files:**
- Create: `rust/bridge/src/report.rs`, `rust/bridge/tests/report.rs`
- Modify: `rust/bridge/src/lib.rs` (`pub mod report;`), `rust/bridge/src/profiles.rs` (remove `warnings`), `rust/bridge/src/jni_api.rs` (`profileReport`, `bandChoices`; remove `profileWarnings`)

- [ ] **Step 1: Failing tests** — `rust/bridge/tests/report.rs`:

```rust
use airgestures_bridge::report::{band_choices, report};
use serde_json::{Value, json};

/// A has an action on the middle hold (Controls on/off is there) and an override on the index
/// triple's palm up; Next profile is on the index triple with the palm up only; Previous is on
/// the middle drag left, where B's middle finger has a left/right slider; calls answer on the
/// index double.
const JSON: &str = r#"{"band":{"controls":{"gesture":"middle_hold"},"next":{"gesture":"index_triple","palm":["up"]},
                               "previous":{"gesture":"middle_drag_left"}},
  "calls":{"index_double":"call.answer"},
  "profiles":[
    {"name":"A","map":{"middle_hold":"media.next","index_triple":"x","index_double":"volume.mute"},
     "palm":{"index_triple":{"up":"y"}}},
    {"name":"B","index":{"dial":{"up":"volume.up","down":"volume.down"}},
     "middle":{"dial":null,"drags":{"sliders":[{"up":"volume.up","down":"volume.down"},null,null]}}}]}"#;

fn parsed(text: &str) -> Value {
    serde_json::from_str(text).unwrap()
}

#[test]
fn the_report_lists_what_band_controls_pause_by_place_and_position() {
    let r = parsed(&report(JSON));
    assert_eq!(r["warnings"], json!([]));
    assert_eq!(r["invalid"], json!([]));
    assert_eq!(
        r["paused"],
        json!([
            {"control":"controls","gesture":"middle_hold","place":0,"palm":null,"positions":["down","sideways","up"]},
            // The triple's normal action still runs palm down and sideways; its palm-up override is paused.
            {"control":"next","gesture":"index_triple","place":0,"palm":"up","positions":["up"]}
        ])
    );
    assert_eq!(
        r["paused_sliders"],
        json!([{"control":"previous","gesture":"middle_drag_left","profile":1,"finger":"middle","pair":0,
                "positions":["down","sideways","up"]}])
    );
    assert_eq!(r["uses"]["index_double"], json!([{"place":0,"palm":null},{"place":"calls","palm":null}]));
    // Per profile and finger, where the hold waits: a dial, or a drag that does something there
    // (Previous on a middle drag, with two profiles, in every position). band-core's rule: if it
    // answers otherwise, it's right and this expectation is wrong.
    let all = json!(["down", "sideways", "up"]);
    assert_eq!(r["hold_waits"], json!([{"index": [], "middle": all}, {"index": all, "middle": all}]));
}

#[test]
fn the_report_flags_invalid_settings_and_warns() {
    let json = r#"{"band":{"next":{"gesture":"index_tap","palm":[]},"draw":{"gesture":"middle_tap"}},
      "profiles":[{"name":"A","palm":{"swipe_up":{"down":"a","sideways":"b","up":"c"}}},{"name":"B"}]}"#;
    let r = parsed(&report(json));
    assert_eq!(r["warnings"], json!(["no-controls", "no-way-out", "draw-on-tap"]));
    assert_eq!(
        r["invalid"],
        json!([{"kind":"binding","place":0,"gesture":"swipe_up"},{"kind":"band","control":"next"}])
    );
    let r = parsed(&report(r#"{"band":{"controls":{"gesture":"middle_hold"},"draw":{"gesture":"index_hold"}}}"#));
    assert_eq!(r["invalid"], json!([{"kind":"draw-cant-cancel"}]));
    assert_eq!(report("{"), "{}");
}

#[test]
fn moving_a_band_control_lists_what_it_would_pause_and_skips_gestures_the_others_hold() {
    let choices: Vec<Value> = serde_json::from_str(&band_choices(JSON, "previous", "")).unwrap();
    let keys: Vec<&str> = choices.iter().map(|c| c["gesture"].as_str().unwrap()).collect();
    assert!(!keys.contains(&"middle_hold"), "Controls on/off holds it in every position");
    assert!(!keys.contains(&"index_triple"), "Next profile holds it with the palm up");
    assert_eq!(keys.len(), 22);
    let double = choices.iter().find(|c| c["gesture"] == "index_double").unwrap();
    assert_eq!(double["pauses"].as_array().unwrap().len(), 2, "A's and the call layer's");
    let here = choices.iter().find(|c| c["gesture"] == "middle_drag_left").unwrap();
    assert_eq!(here["pauses"], json!([]));
    assert_eq!(here["sliders"].as_array().unwrap().len(), 1, "where it is now: B's slider");
    // Only with the palm down: the index triple is free of Next there; its normal action pauses palm down.
    let down: Vec<Value> = serde_json::from_str(&band_choices(JSON, "previous", "down")).unwrap();
    let triple = down.iter().find(|c| c["gesture"] == "index_triple").unwrap();
    assert_eq!(
        triple["pauses"],
        json!([{"control":"previous","gesture":"index_triple","place":0,"palm":null,"positions":["down"]}])
    );
    assert_eq!(band_choices("{", "next", ""), "[]");
    assert_eq!(band_choices(JSON, "lock", ""), "[]");
}
```

Run: `(cd rust && cargo test -p bridge --test report 2>&1 | grep -E "^error" | head -2)` — Expected: unresolved `airgestures_bridge::report`.

- [ ] **Step 2: `rust/bridge/src/report.rs`**

```rust
//! What the app's screens show about a profiles JSON, from band-core's own rules, without a
//! connection (like the old `profileWarnings`): the warnings, what's invalid, what the band
//! controls pause, each gesture's uses, which holds wait for the release, and what moving a
//! band control would pause. Places are profile indexes or `"calls"`; palms and band controls
//! use the JSON's keys.

use band_core::controls::{
    BandControl, BandControls, Builtin, Finger, Gesture, Invalid, Palm, PalmSet, Palms, Paused, PausedSlider, Place,
    Profiles, Warning,
};
use serde_json::{Map, Value, json};

use crate::profiles::parse;

/// The band control's name in the profiles JSON.
pub fn builtin_key(builtin: Builtin) -> &'static str {
    match builtin {
        Builtin::Controls => "controls",
        Builtin::NextProfile => "next",
        Builtin::PreviousProfile => "previous",
        Builtin::Draw => "draw",
    }
}

fn builtin_of(key: &str) -> Option<Builtin> {
    Builtin::ALL.into_iter().find(|&builtin| builtin_key(builtin) == key)
}

fn finger_key(finger: Finger) -> &'static str {
    match finger {
        Finger::Index => "index",
        Finger::Middle => "middle",
    }
}

fn place(place: Place) -> Value {
    match place {
        Place::Profile(index) => json!(index),
        Place::Calls => json!("calls"),
    }
}

fn palms(palms: Palms) -> Value {
    json!(palms.iter().map(Palm::key).collect::<Vec<_>>())
}

fn paused_json(p: &Paused<'_, String>) -> Value {
    json!({
        "control": builtin_key(p.builtin), "gesture": p.gesture.key(), "place": place(p.used.place),
        "palm": p.used.palm.map(Palm::key), "positions": palms(p.positions),
    })
}

fn slider_json(s: &PausedSlider) -> Value {
    json!({
        "control": builtin_key(s.builtin), "gesture": s.gesture.key(), "profile": s.profile,
        "finger": finger_key(s.finger), "pair": s.pair, "positions": palms(s.positions),
    })
}

fn report_of(profiles: &Profiles<String>) -> Value {
    let warnings: Vec<&str> = profiles
        .warnings()
        .iter()
        .map(|w| match w {
            Warning::NoControls => "no-controls",
            Warning::NoWayOut => "no-way-out",
            Warning::DrawOnTap => "draw-on-tap",
        })
        .collect();
    let invalid: Vec<Value> = profiles
        .invalid()
        .iter()
        .map(|i| match i {
            Invalid::Binding { place: at, gesture } => json!({"kind": "binding", "place": place(*at), "gesture": gesture.key()}),
            Invalid::BandControl(builtin) => json!({"kind": "band", "control": builtin_key(*builtin)}),
            Invalid::DrawCantCancel => json!({"kind": "draw-cant-cancel"}),
        })
        .collect();
    let mut uses = Map::new();
    for gesture in Gesture::ALL {
        let list: Vec<Value> =
            profiles.uses(gesture).iter().map(|u| json!({"place": place(u.place), "palm": u.palm.map(Palm::key)})).collect();
        if !list.is_empty() {
            uses.insert(gesture.key().into(), Value::Array(list));
        }
    }
    // Where each finger's hold waits for the release (band-core's `Profiles::hold_waits`).
    let holds: Vec<Value> = (0..profiles.list.len())
        .map(|i| {
            let waits = |finger: Finger| palms(Palm::ALL.into_iter().filter(|&palm| profiles.hold_waits(i, finger, palm)).collect());
            json!({"index": waits(Finger::Index), "middle": waits(Finger::Middle)})
        })
        .collect();
    json!({
        "warnings": warnings,
        "invalid": invalid,
        "paused": profiles.paused().iter().map(paused_json).collect::<Vec<_>>(),
        "paused_sliders": profiles.paused_sliders().iter().map(slider_json).collect::<Vec<_>>(),
        "uses": uses,
        "hold_waits": holds,
    })
}

/// `{"warnings", "invalid", "paused", "paused_sliders", "uses", "hold_waits"}` for this profiles
/// JSON (see the tests); invalid JSON: `{}`.
pub fn report(json: &str) -> String {
    match parse(json) {
        Ok((_, profiles)) => report_of(&profiles).to_string(),
        Err(_) => "{}".into(),
    }
}

fn palm_set(keys: &str) -> PalmSet {
    if keys.is_empty() {
        PalmSet::All
    } else {
        PalmSet::Only(keys.split(',').filter_map(|key| Palm::from_key(key.trim())).collect())
    }
}

fn slot(band: &mut BandControls, builtin: Builtin) -> &mut Option<BandControl> {
    match builtin {
        Builtin::Controls => &mut band.controls,
        Builtin::NextProfile => &mut band.next,
        Builtin::PreviousProfile => &mut band.previous,
        Builtin::Draw => &mut band.draw,
    }
}

/// Another band control holds this gesture in a position `set` also covers.
fn held_by_other(profiles: &Profiles<String>, builtin: Builtin, gesture: Gesture, set: PalmSet) -> bool {
    Builtin::ALL
        .into_iter()
        .filter(|&other| other != builtin)
        .filter_map(|other| profiles.band.control(other))
        .any(|c| c.gesture == gesture && Palm::ALL.into_iter().any(|palm| c.palm.contains(palm) && set.contains(palm)))
}

/// Where band control `control` (`controls`, `next`, `previous`, `draw`) could go with palm set
/// `palm` (`""`: any position, else keys joined by `,`): for each gesture no other band control
/// holds in those positions, in `Gesture::ALL` order, `{"gesture", "pauses", "sliders"}` — what
/// it would pause there, as `paused` / `paused_sliders` in the report. Its current gesture is
/// listed too. Invalid JSON or an unknown control: `[]`.
pub fn band_choices(json: &str, control: &str, palm: &str) -> String {
    let (Ok((_, profiles)), Some(builtin)) = (parse(json), builtin_of(control)) else { return "[]".into() };
    let set = palm_set(palm);
    let choices: Vec<Value> = Gesture::ALL
        .into_iter()
        .filter(|&gesture| !held_by_other(&profiles, builtin, gesture, set))
        .map(|gesture| {
            let mut moved = profiles.clone();
            *slot(&mut moved.band, builtin) = Some(BandControl { gesture, palm: set });
            let pauses: Vec<Value> = moved.paused().iter().filter(|p| p.builtin == builtin).map(paused_json).collect();
            let sliders: Vec<Value> = moved.paused_sliders().iter().filter(|s| s.builtin == builtin).map(slider_json).collect();
            json!({"gesture": gesture.key(), "pauses": pauses, "sliders": sliders})
        })
        .collect();
    Value::Array(choices).to_string()
}
```

`hold_waits` in the test: B has an index dial (all positions) and Previous on its middle drag left (all); A has no index dial or drags (none) and the same middle (all).

- [ ] **Step 3: JNI** — in `jni_api.rs`, replace `profileWarnings` with:

```rust
/// What the screens show about a profiles JSON (see `report::report`); invalid JSON: `{}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_profileReport<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    json: JString<'local>,
) -> jstring {
    match get_string(&mut env, &json) {
        Ok(json) => string_out(&mut env, crate::report::report(&json)),
        Err(message) => {
            throw(&mut env, &message);
            std::ptr::null_mut()
        }
    }
}

/// Where a band control could go and what it would pause (see `report::band_choices`).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_bandChoices<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    json: JString<'local>,
    control: JString<'local>,
    palm: JString<'local>,
) -> jstring {
    let strings = get_string(&mut env, &json)
        .and_then(|json| Ok((json, get_string(&mut env, &control)?, get_string(&mut env, &palm)?)));
    match strings {
        Ok((json, control, palm)) => string_out(&mut env, crate::report::band_choices(&json, &control, &palm)),
        Err(message) => {
            throw(&mut env, &message);
            std::ptr::null_mut()
        }
    }
}
```

Remove `profiles::warnings`. Add `pub mod report;` to `lib.rs`.

- [ ] **Step 4: Run and commit**

```bash
(cd rust && cargo test -p bridge 2>&1 | grep -E "^test result|FAILED|panicked")
(cd rust && cargo clippy -p bridge --all-targets 2>&1 | grep -c "^warning")
./build-rust.sh >/dev/null && JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew -q assembleDebug testDebugUnitTest
git add rust/bridge/src/report.rs rust/bridge/src/lib.rs rust/bridge/src/profiles.rs rust/bridge/src/jni_api.rs rust/bridge/tests/report.rs
git commit -q -m "bridge: the screens' report (paused, uses, warnings, invalid) and where a band control could go

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MLDqGPYx8bTGKJcVLgoyzo"
```

(`Bridge.kt` still declares `profileWarnings` and `ProfileActivity` still calls it inside `runCatching`, which now catches the missing native and shows no warnings; Task 7 removes both.)

---

### Task 3: Teaching helpers (static): palm calibration, taught drags, drawings

**Files:**
- Create: `rust/bridge/src/teach.rs`, `rust/bridge/tests/teach.rs`
- Modify: `rust/bridge/src/lib.rs` (`pub mod teach;`), `rust/bridge/src/jni_api.rs`

- [ ] **Step 1: Failing tests** — `rust/bridge/tests/teach.rs`:

```rust
use airgestures_bridge::teach::{check_tries, drag_prototypes, library, looks_like, palm_calibration};
use serde_json::Value;

/// One try: a circle of radius `r` degrees, one stroke.
fn circle(r: f64) -> String {
    let points: Vec<String> = (0..=40)
        .map(|i| {
            let a = f64::from(i) / 40.0 * std::f64::consts::TAU;
            format!("[{:.3},{:.3}]", r * a.cos(), r * a.sin())
        })
        .collect();
    format!("[[{}]]", points.join(","))
}

const ZIGZAG: &str = "[[[0,20],[20,20],[0,0],[20,0]]]";

#[test]
fn palm_calibration_from_rolls_or_why_not() {
    let ok: Value = serde_json::from_str(&palm_calibration(&[-141.0, -143.0], &[-11.0, -9.0])).unwrap();
    assert!((ok["down"].as_f64().unwrap() + 142.0).abs() < 1e-6);
    assert!((ok["up"].as_f64().unwrap() + 10.0).abs() < 1e-6);
    assert_eq!(palm_calibration(&[], &[-10.0]), r#"{"error":"empty down"}"#);
    assert_eq!(palm_calibration(&[-140.0, -110.0], &[-10.0]), r#"{"error":"not-still down"}"#);
    assert_eq!(palm_calibration(&[-40.0], &[-10.0]), r#"{"error":"too-close"}"#);
}

#[test]
fn taught_drags_become_prototypes_or_say_which_to_redo() {
    let good = r#"{"roll":-142.3,"turn":[0,2,0],"drags":[[0,0,1],[0,0,-1],[1,0,0],[-1,0,0],[0.7,-0.7,0],[-0.7,0.7,0.1]]}"#;
    let p: Value = serde_json::from_str(&drag_prototypes(good)).unwrap();
    assert_eq!(p["roll"], -142.3);
    assert_eq!(p["turn"], serde_json::json!([0.0, 1.0, 0.0]), "made unit length");
    assert_eq!(p["drags"].as_array().unwrap().len(), 6);
    let alike = good.replace("[0,0,-1]", "[0,0,1]");
    assert_eq!(drag_prototypes(&alike), r#"{"error":"too-alike left right"}"#);
    let turn = good.replace("[1,0,0]", "[0,1,0]");
    assert_eq!(drag_prototypes(&turn), r#"{"error":"like-the-turn toward"}"#);
    assert_eq!(drag_prototypes(&good.replace("[0,2,0]", "[0,0,0]")), r#"{"error":"no-turn"}"#);
    assert_eq!(drag_prototypes("{"), r#"{"error":"bad-json"}"#);
}

#[test]
fn tries_are_checked_and_lookalikes_named() {
    let three = format!("[{},{},{}]", circle(10.0), circle(12.0), circle(9.0));
    assert_eq!(check_tries(&three), "");
    assert_eq!(check_tries(&format!("[{},{ZIGZAG}]", circle(10.0))), "not-alike 0 1");
    assert_eq!(check_tries(&format!("[{}]", circle(1.0))), "too-small 0");
    assert_eq!(check_tries("[]"), "no-tries");
    assert_eq!(check_tries("{"), "bad-json");
    let lib = format!(r#"[{{"name":"Circle","tries":[{}]}}]"#, circle(10.0));
    assert!(library(&lib).is_ok() && library("[").is_err());
    assert_eq!(looks_like(&lib, &format!("[{}]", circle(20.0))), "Circle");
    assert_eq!(looks_like(&lib, &format!("[{ZIGZAG}]")), "");
}
```

Run: `(cd rust && cargo test -p bridge --test teach 2>&1 | grep -E "^error" | head -2)` — Expected: unresolved `teach`.

- [ ] **Step 2: `rust/bridge/src/teach.rs`**

```rust
//! The app's teach screens, without a connection: palm calibration from rolls, taught drags,
//! and taught drawings (the library a connection matches, the tries' check, look-alikes).
//! JSON in and out; tries are `[[[x, y], …], …]` per try (strokes of points, degrees).

use band_core::controls::draw::{self, Drawing, Library, Stroke, TeachError};
use band_core::controls::palm::CalibrationError;
use band_core::controls::pinch::{Prototypes, TaughtError};
use band_core::controls::{Drag, PalmCalibration};
use serde::Deserialize;
use serde_json::json;

use crate::profiles::PrototypesJson;

/// `{"down": roll, "up": roll}` from rolls held still in each position, or
/// `{"error": "empty <palm>" | "not-still <palm>" | "too-close"}`.
pub fn palm_calibration(down: &[f64], up: &[f64]) -> String {
    let result = match PalmCalibration::from_rolls(down, up) {
        Ok(c) => json!({"down": c.down_roll, "up": c.up_roll}),
        Err(CalibrationError::Empty(palm)) => json!({"error": format!("empty {}", palm.key())}),
        Err(CalibrationError::NotStill(palm)) => json!({"error": format!("not-still {}", palm.key())}),
        Err(CalibrationError::TooClose) => json!({"error": "too-close"}),
    };
    result.to_string()
}

fn drag_key(drag: Drag) -> &'static str {
    match drag {
        Drag::Left => "left",
        Drag::Right => "right",
        Drag::Toward => "toward",
        Drag::Away => "away",
        Drag::Up => "up",
        Drag::Down => "down",
    }
}

/// From `{"roll", "turn": [x,y,z], "drags": [six, in left, right, toward, away, up, down order]}`
/// (the `@drag-sample` directions and roll): the prototypes as the profiles JSON's
/// `calibration.<wrist>.drags` keeps them, or `{"error": "no-turn" | "no-drag <drag>" |
/// "too-alike <drag> <drag>" | "like-the-turn <drag>" | "no-roll" | "bad-json"}`.
pub fn drag_prototypes(samples: &str) -> String {
    let Ok(s) = serde_json::from_str::<PrototypesJson>(samples) else { return json!({"error": "bad-json"}).to_string() };
    let result = match Prototypes::taught(s.turn, s.drags, s.roll) {
        Ok(p) => json!({"roll": p.palm_down_roll, "turn": p.turn, "drags": p.drags}),
        Err(TaughtError::NoTurn) => json!({"error": "no-turn"}),
        Err(TaughtError::NoDrag(d)) => json!({"error": format!("no-drag {}", drag_key(d))}),
        Err(TaughtError::TooAlike(a, b)) => json!({"error": format!("too-alike {} {}", drag_key(a), drag_key(b))}),
        Err(TaughtError::LikeTheTurn(d)) => json!({"error": format!("like-the-turn {}", drag_key(d))}),
        Err(TaughtError::NoRoll) => json!({"error": "no-roll"}),
    };
    result.to_string()
}

#[derive(Deserialize)]
struct DrawingJson {
    name: String,
    tries: Vec<Vec<Stroke>>,
}

/// The drawings `[{"name", "tries"}]` as a library to match against.
pub fn library(json: &str) -> Result<Library, String> {
    let drawings: Vec<DrawingJson> = serde_json::from_str(json).map_err(|e| e.to_string())?;
    let drawings: Vec<Drawing> = drawings.into_iter().map(|d| Drawing { name: d.name, tries: d.tries }).collect();
    Ok(Library::new(&drawings))
}

fn tries(json: &str) -> Option<Vec<Vec<Stroke>>> {
    serde_json::from_str(json).ok()
}

/// `""` when these tries can teach a drawing, else `no-tries`, `too-small <i>`,
/// `not-alike <i> <j>` (from 0) or `bad-json`.
pub fn check_tries(json: &str) -> String {
    let Some(tries) = tries(json) else { return "bad-json".into() };
    match draw::check_tries(&tries) {
        Ok(()) => String::new(),
        Err(TeachError::NoTries) => "no-tries".into(),
        Err(TeachError::TooSmall(i)) => format!("too-small {i}"),
        Err(TeachError::NotAlike { first, second, .. }) => format!("not-alike {first} {second}"),
    }
}

/// The drawing in the library these tries look like ("looks like Circle"), or `""`.
pub fn looks_like(library_json: &str, tries_json: &str) -> String {
    match (library(library_json), tries(tries_json)) {
        (Ok(library), Some(tries)) => library.looks_like(&tries).unwrap_or_default(),
        _ => String::new(),
    }
}
```

- [ ] **Step 3: JNI** (all static; strings in, strings out, as `profileReport`):
  - `Bridge_palmCalibration(down: JDoubleArray, up: JDoubleArray) -> jstring` → `teach::palm_calibration`. Read arrays with a helper:
    ```rust
    fn doubles(env: &mut JNIEnv, array: &JDoubleArray) -> Result<Vec<f64>, String> {
        let length = env.get_array_length(array).map_err(|e| e.to_string())?;
        let mut values = vec![0.0; length.max(0) as usize];
        env.get_double_array_region(array, 0, &mut values).map_err(|e| e.to_string())?;
        Ok(values)
    }
    ```
    (`use jni::objects::JDoubleArray;` — check the two calls' exact signatures against `jni` 0.21 if it doesn't compile.)
  - `Bridge_dragPrototypes(samples: JString) -> jstring` → `teach::drag_prototypes`.
  - `Bridge_checkTries(tries: JString) -> jstring` → `teach::check_tries`.
  - `Bridge_looksLike(library: JString, tries: JString) -> jstring` → `teach::looks_like`.

- [ ] **Step 4: Run and commit**

```bash
(cd rust && cargo test -p bridge 2>&1 | grep -E "^test result|FAILED|panicked")
(cd rust && cargo clippy -p bridge --all-targets 2>&1 | grep -c "^warning")
./build-rust.sh >/dev/null && JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew -q assembleDebug testDebugUnitTest
git add rust/bridge/src/teach.rs rust/bridge/src/lib.rs rust/bridge/src/jni_api.rs rust/bridge/tests/teach.rs
git commit -q -m "bridge: palm calibration, taught drags and drawings' checks for the teach screens

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MLDqGPYx8bTGKJcVLgoyzo"
```

---

### Task 4: The connection: draw sessions, teaching, the library, the live palm

**Files:**
- Create: `rust/bridge/src/palmlive.rs`
- Modify: `rust/bridge/src/lib.rs`, `rust/bridge/src/jni_api.rs`
- Test: `rust/bridge/tests/connection.rs` (4 tests), `palmlive.rs` unit tests

- [ ] **Step 1: Failing tests** — append to `tests/connection.rs`:

```rust
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
    // Roll −11.3° (turned +11.3° about the band's y): palm up on the right hand's measured calibration.
    let half = 11.3_f64.to_radians() / 2.0;
    let bytes = band.orientation([half.cos() as f32, 0.0, half.sin() as f32, 0.0]).unwrap();
    let reply = connection.feed(&bytes, 3.0).unwrap();
    pump(&mut connection, &mut band, reply, 3.0);
    assert_eq!(connection.palm_now(3.1), r#"{"palm":"up","roll":-11.3}"#);
    assert_eq!(connection.palm_now(4.0), r#"{"palm":null,"roll":null}"#, "stale");
}
```

And `palmlive.rs` gets (Step 2) its own tests.

- [ ] **Step 2: `rust/bridge/src/palmlive.rs`**

```rust
//! The band's roll right now, for the calibration screen (its samples are what
//! `PalmCalibration::from_rolls` takes): from orientation events, read as band-core reads
//! them (`pinch::roll_or`, `up_in_band`). The palm position itself is `Controls::palm`.

use band_core::controls::pinch::{roll_or, up_in_band};
use band_core::events::Event;

/// A sample older than this isn't "right now" (band-core's `ORIENTATION_STALE`).
const FRESH: f64 = 0.5;

#[derive(Debug, Default)]
pub struct LiveRoll {
    /// The last readable roll (degrees) and when it came.
    last: Option<(f64, f64)>,
}

impl LiveRoll {
    pub fn on_event(&mut self, event: &Event, now: f64) {
        let Event::Orientation(q) = event else { return };
        let roll = roll_or(up_in_band(*q), f64::NAN);
        if roll.is_finite() {
            self.last = Some((roll, now));
        }
    }

    /// The roll (to 0.1°) when the last readable sample is fresh.
    pub fn fresh(&self, now: f64) -> Option<f64> {
        self.last.filter(|(_, at)| now - at < FRESH).map(|(roll, _)| (roll * 10.0).round() / 10.0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// An orientation whose roll (see `pinch::roll`) is `roll` degrees: turned about the band's y.
    fn rolled(roll: f64) -> Event {
        let half = (-roll).to_radians() / 2.0;
        Event::Orientation([half.cos() as f32, 0.0, half.sin() as f32, 0.0])
    }

    #[test]
    fn it_reads_the_roll_and_goes_stale() {
        let mut live = LiveRoll::default();
        assert_eq!(live.fresh(0.0), None);
        live.on_event(&rolled(-142.3), 1.0);
        assert_eq!(live.fresh(1.1), Some(-142.3));
        // Forearm straight down: no roll to read, the last one stays.
        live.on_event(&Event::Orientation([0.7071, 0.7071, 0.0, 0.0]), 1.2);
        assert_eq!(live.fresh(1.3), Some(-142.3));
        assert_eq!(live.fresh(1.6), None, "stale");
    }
}
```

- [ ] **Step 3: `Connection`** (in `lib.rs`; `mod palmlive;`):
1. Field `roll: palmlive::LiveRoll` (`Default` in `new`).
2. In `on_event`, before `take`: `self.roll.on_event(event, now);`
3. New methods:
   ```rust
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
   ```
4. `status_json`: add `"drawing": self.controls.drawing()` next to the update fields of `Snapshot` (a `drawing: bool` field), so a reconnecting app can close a pad left open.

- [ ] **Step 4: JNI** — natives taking the handle (as `setPractice`): `setLibrary(handle, json)` (throws on bad JSON), `startDrawing(handle, now)`, `cancelDrawing(handle)`, `setTeaching(handle, on)`, `setDragTeaching(handle, on)` (errors throw), `palmNow(handle, now) -> String`.

- [ ] **Step 5: Run and commit**

```bash
(cd rust && cargo test -p bridge 2>&1 | grep -E "^test result|FAILED|panicked")
(cd rust && cargo clippy -p bridge --all-targets 2>&1 | grep -c "^warning")
./build-rust.sh >/dev/null && JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew -q assembleDebug testDebugUnitTest
git add rust/bridge/src/palmlive.rs rust/bridge/src/lib.rs rust/bridge/src/jni_api.rs rust/bridge/src/status.rs rust/bridge/tests/connection.rs
git commit -q -m "bridge: draw sessions, teaching drawings and drags, the drawings library and the live palm

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MLDqGPYx8bTGKJcVLgoyzo"
```

(Stage `status.rs` only if it changed.)

---

### Task 5: Kotlin data: gestures, band controls, profiles

**Files:**
- Modify: `Mapping.kt`, `ProfileList.kt`, `Lessons.kt` (`steps`), `Actions.kt` (built-in line), `PracticeActivity.kt` (`would`, `steps` call), `GestureScreen.kt` (`pickAction` filter)
- Create: `Controls.kt`
- Test: `ControlsTest.kt` (new), `ProfileListTest.kt`, `LessonsBackupTest.kt`

- [ ] **Step 1: Failing tests** — `app/src/test/java/dev/kb896/airgestures/ControlsTest.kt`:

```kotlin
package dev.kb896.airgestures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ControlsTest {
    @Test fun `the standard band controls are today's gestures, Draw on none`() {
        val band = BandControls.standard()
        assertEquals(BandControl(Gesture.MIDDLE_HOLD), band[Builtin.CONTROLS])
        assertEquals(Gesture.INDEX_TRIPLE, band[Builtin.NEXT]?.gesture)
        assertEquals(Gesture.MIDDLE_TRIPLE, band[Builtin.PREVIOUS]?.gesture)
        assertNull(band[Builtin.DRAW])
    }

    @Test fun `the one on a gesture follows the palm, Controls first`() {
        val band = BandControls.standard()
            .with(Builtin.NEXT, BandControl(Gesture.SWIPE_LEFT, setOf(Palm.UP)))
            .with(Builtin.DRAW, BandControl(Gesture.MIDDLE_HOLD))
        assertEquals(Builtin.NEXT, band.on(Gesture.SWIPE_LEFT, Palm.UP))
        assertNull(band.on(Gesture.SWIPE_LEFT, Palm.DOWN))
        assertEquals(Builtin.CONTROLS, band.on(Gesture.MIDDLE_HOLD, Palm.SIDE))
        assertEquals(listOf(Builtin.CONTROLS, Builtin.DRAW), band.onGesture(Gesture.MIDDLE_HOLD))
    }

    @Test fun `band controls round-trip as the bridge reads them`() {
        val band = BandControls.standard().with(Builtin.NEXT, BandControl(Gesture.INDEX_DRAG_LEFT, setOf(Palm.UP, Palm.DOWN)))
        val json = band.toJson()
        // org.json's JVM JSONObject keeps no key order: compare fields, not strings.
        assertEquals("index_drag_left", json.getJSONObject("next").getString("gesture"))
        assertEquals("""["down","up"]""", json.getJSONObject("next").getJSONArray("palm").toString())
        assertEquals(true, json.isNull("draw"))
        assertEquals(true, json.getJSONObject("controls").isNull("palm"))
        assertEquals(band, BandControls.fromJson(json.toString()))
        assertEquals(BandControls(), BandControls.fromJson("""{"controls":{"gesture":"wave"}}"""))
    }

    @Test fun `gesture keys and labels are band-core's`() {
        assertEquals(24, Gesture.entries.size)
        assertEquals("index hold", Gesture.INDEX_HOLD.label.lowercase())
        assertEquals("middle drag toward", Gesture.MIDDLE_DRAG_TOWARD.label.lowercase())
        assertEquals(listOf("Tap", "Double tap", "Triple tap", "Hold"), Gesture.entries.filter { it.part == Part.INDEX && !it.drag }.map { it.short })
        assertEquals(6, Gesture.entries.count { it.part == Part.MIDDLE && it.drag })
    }
}
```

Add to `ProfileListTest.kt`:

```kotlin
    @Test fun `palm overrides: at most two, removing the last drops the gesture`() {
        var list = three()
        list = list.withOverride("p1", "index_tap", Palm.UP, "media.next").withOverride("p1", "index_tap", Palm.SIDE, "")
        assertEquals(mapOf("up" to "media.next", "sideways" to ""), list.profile("p1")!!.palm["index_tap"])
        assertEquals(list, list.withOverride("p1", "index_tap", Palm.DOWN, "x"))
        list = list.withoutOverride("p1", "index_tap", Palm.UP).withoutOverride("p1", "index_tap", Palm.SIDE)
        assertFalse("index_tap" in list.profile("p1")!!.palm)
    }

    @Test fun `the dev build's built-in actions leave every profile`() {
        val list = ProfileList(
            listOf(Profile("p1", "A", mapOf("middle_hold" to "controls.toggle", "swipe_left" to "profile.next", "index_tap" to "media.next"), "volume",
                palm = mapOf("index_tap" to mapOf("up" to "profile.previous")))),
            "p1", emptyMap(),
        ).withoutBuiltins()
        assertEquals(mapOf("index_tap" to "media.next"), list.profile("p1")!!.map)
        assertTrue(list.profile("p1")!!.palm.isEmpty())
        assertEquals(setOf("index_double"), ProfileList.builtinCalls(mapOf("index_double" to "controls.toggle", "middle_double" to "-")))
    }

    @Test fun `the new profile fields round-trip and 0_7_7's profiles read with their defaults`() {
        val made = Profile("p1", "A", mapOf("swipe_up" to "media.next"), "seek",
            palm = mapOf("index_tap" to mapOf("up" to "media.next")), middleDial = "brightness",
            middleDrags = Drags(sliders = true, targets = listOf("volume", "none", "none")), drawings = mapOf("Bulb" to "torch.toggle"))
        val list = ProfileList(listOf(made), "p1", emptyMap())
        val (profiles, apps) = list.toJson()
        assertEquals(list, ProfileList.fromJson(profiles, apps, "p1"))
        val old = ProfileList.fromJson("""[{"id":"p1","name":"A","map":{},"dial":"volume"}]""", "{}", "p1").profile("p1")!!
        assertEquals("none", old.middleDial)
        assertEquals(Drags(), old.indexDrags)
        assertTrue(old.palm.isEmpty() && old.drawings.isEmpty())
    }
```

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew -q testDebugUnitTest --tests '*ControlsTest*' --tests '*ProfileListTest*'` — Expected: compile errors.

- [ ] **Step 2: `Mapping.kt`**
1. `Action`: remove `CONTROLS`, `NEXT_PROFILE`, `PREVIOUS_PROFILE` and `val builtin`. Fix the callers: `Actions.kt:96` (the "works on a gesture only" line goes), `GestureScreen.pickAction` (drop `!action.builtin`), `PracticeActivity.would` (Step 5).
2. Add before `Gesture`:
   ```kotlin
   /** Which part of the hand makes a gesture: the profile screen's groups. */
   enum class Part(val label: String) { THUMB("Thumb"), INDEX("Index"), MIDDLE("Middle") }
   ```
3. `Gesture` becomes (band-core's 24, in its order except the swipes, which keep the app's):
   ```kotlin
   /**
    * The gestures band-core reads (its keys; its labels, which the lessons match lowercased),
    * with the defaults mirroring the desktop layout and what each does during a call by
    * default. [short]: the name within its [part]'s group. The triples and holds default to
    * nothing: the band controls sit on them (see [BandControls.standard]).
    */
   enum class Gesture(
       val key: String,
       val label: String,
       val short: String,
       val part: Part,
       val default: Action = Action.NONE,
       val callDefault: String = Mappings.AS_USUAL,
   ) {
       SWIPE_UP("swipe_up", "Swipe up", "Swipe up", Part.THUMB, Action.PREVIOUS),
       SWIPE_DOWN("swipe_down", "Swipe down", "Swipe down", Part.THUMB, Action.NEXT),
       SWIPE_LEFT("swipe_left", "Swipe left", "Swipe left", Part.THUMB),
       SWIPE_RIGHT("swipe_right", "Swipe right", "Swipe right", Part.THUMB),
       INDEX_TAP("index_tap", "Index tap", "Tap", Part.INDEX, Action.PLAY_PAUSE),
       INDEX_DOUBLE("index_double", "Index double tap", "Double tap", Part.INDEX, Action.NONE, Action.ANSWER.id),
       INDEX_TRIPLE("index_triple", "Index triple tap", "Triple tap", Part.INDEX),
       INDEX_HOLD("index_hold", "Index hold", "Hold", Part.INDEX),
       MIDDLE_TAP("middle_tap", "Middle tap", "Tap", Part.MIDDLE),
       MIDDLE_DOUBLE("middle_double", "Middle double tap", "Double tap", Part.MIDDLE, Action.MUTE, Action.END_CALL.id),
       MIDDLE_TRIPLE("middle_triple", "Middle triple tap", "Triple tap", Part.MIDDLE),
       MIDDLE_HOLD("middle_hold", "Middle hold", "Hold", Part.MIDDLE),
       INDEX_DRAG_LEFT("index_drag_left", "Index drag left", "Drag left", Part.INDEX),
       INDEX_DRAG_RIGHT("index_drag_right", "Index drag right", "Drag right", Part.INDEX),
       INDEX_DRAG_TOWARD("index_drag_toward", "Index drag toward", "Drag toward", Part.INDEX),
       INDEX_DRAG_AWAY("index_drag_away", "Index drag away", "Drag away", Part.INDEX),
       INDEX_DRAG_UP("index_drag_up", "Index drag up", "Drag up", Part.INDEX),
       INDEX_DRAG_DOWN("index_drag_down", "Index drag down", "Drag down", Part.INDEX),
       MIDDLE_DRAG_LEFT("middle_drag_left", "Middle drag left", "Drag left", Part.MIDDLE),
       MIDDLE_DRAG_RIGHT("middle_drag_right", "Middle drag right", "Drag right", Part.MIDDLE),
       MIDDLE_DRAG_TOWARD("middle_drag_toward", "Middle drag toward", "Drag toward", Part.MIDDLE),
       MIDDLE_DRAG_AWAY("middle_drag_away", "Middle drag away", "Drag away", Part.MIDDLE),
       MIDDLE_DRAG_UP("middle_drag_up", "Middle drag up", "Drag up", Part.MIDDLE),
       MIDDLE_DRAG_DOWN("middle_drag_down", "Middle drag down", "Drag down", Part.MIDDLE);

       /** A pinch-and-move drag: on the Drags screen, not in the profile's groups. */
       val drag get() = "_drag_" in key

       companion object {
           fun of(key: String?) = entries.firstOrNull { it.key == key }

           /** A finger's six drags, in band-core's order (left, right, toward, away, up, down). */
           fun drags(part: Part) = entries.filter { it.drag && it.part == part }
       }
   }
   ```
   Callers that used `Gesture.entries` for the call layer (`callRows`, `Prefs.usesCalls`, `SetupActivity` backup) keep working; the call screen filters `!it.drag` (Task 10).

- [ ] **Step 3: `Controls.kt`** (new)

```kotlin
package dev.kb896.airgestures

import org.json.JSONArray
import org.json.JSONObject

/** Palm down, sideways or up (band-core's `Palm`, with its keys). */
enum class Palm(val key: String, val label: String) {
    DOWN("down", "Palm down"),
    SIDE("sideways", "Sideways"),
    UP("up", "Palm up");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

/** The band controls (band-core's `Builtin`): above every profile, each on one gesture or none. */
enum class Builtin(val key: String, val label: String, val icon: Int) {
    CONTROLS("controls", "Controls on/off", R.drawable.ic_power),
    NEXT("next", "Next profile", R.drawable.ic_layers),
    PREVIOUS("previous", "Previous profile", R.drawable.ic_layers),
    DRAW("draw", "Draw", R.drawable.ic_pencil);

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

/** Where a band control is: a gesture, in every palm position ([palms] null) or only in 1–2. */
data class BandControl(val gesture: Gesture, val palms: Set<Palm>? = null) {
    fun covers(palm: Palm) = palms == null || palm in palms

    /** "Index triple tap", "Index triple tap · palm up or sideways". */
    val label: String
        get() = gesture.label + (palms?.let { p -> " · " + Palm.entries.filter { it in p }.joinToString(" or ") { it.label.lowercase() } } ?: "")
}

/** The four band controls; stored once, above the profiles. Free of Android, so it's unit-tested. */
data class BandControls(val at: Map<Builtin, BandControl> = emptyMap()) {
    operator fun get(builtin: Builtin) = at[builtin]

    /** The band control on [gesture] with the palm this way, as band-core picks it (Controls, Next, Previous, Draw). */
    fun on(gesture: Gesture, palm: Palm) = Builtin.entries.firstOrNull { at[it]?.let { c -> c.gesture == gesture && c.covers(palm) } == true }

    /** The band controls on [gesture], in any position. */
    fun onGesture(gesture: Gesture) = Builtin.entries.filter { at[it]?.gesture == gesture }

    fun with(builtin: Builtin, control: BandControl?) = BandControls(if (control == null) at - builtin else at + (builtin to control))

    /** As the profiles JSON's `band` (see rust/bridge/src/profiles.rs). */
    fun toJson(): JSONObject = JSONObject().apply {
        for (builtin in Builtin.entries) {
            val c = at[builtin]
            put(builtin.key, if (c == null) JSONObject.NULL else JSONObject().apply {
                put("gesture", c.gesture.key)
                put("palm", c.palms?.let { p -> JSONArray(Palm.entries.filter { it in p }.map { it.key }) } ?: JSONObject.NULL)
            })
        }
    }

    companion object {
        /** Today's gestures: middle hold, index triple tap, middle triple tap; no Draw. */
        fun standard() = BandControls(mapOf(
            Builtin.CONTROLS to BandControl(Gesture.MIDDLE_HOLD),
            Builtin.NEXT to BandControl(Gesture.INDEX_TRIPLE),
            Builtin.PREVIOUS to BandControl(Gesture.MIDDLE_TRIPLE),
        ))

        /** Unknown gestures are no gesture, unknown positions are skipped; throws on bad JSON. */
        fun fromJson(text: String): BandControls {
            val json = JSONObject(text)
            val at = Builtin.entries.mapNotNull { builtin ->
                val c = json.optJSONObject(builtin.key) ?: return@mapNotNull null
                val gesture = Gesture.of(c.optString("gesture")) ?: return@mapNotNull null
                val palms = c.optJSONArray("palm")?.let { a -> (0 until a.length()).mapNotNull { Palm.of(a.optString(it)) }.toSet() }
                builtin to BandControl(gesture, palms)
            }.toMap()
            return BandControls(at)
        }
    }
}
```

- [ ] **Step 4: `ProfileList.kt`**

```kotlin
/** A finger's six drags: gestures (their actions in [Profile.map]), or three sliders. */
data class Drags(
    val sliders: Boolean = false,
    /** Left/right, toward/away, up/down: a [DialChoice] key each (kept in gestures mode). */
    val targets: List<String> = List(3) { DialChoice.NONE.key },
)

/**
 * One gesture set: a name, an action id per gesture key, palm overrides, both fingers' dials
 * and drags, and its own drawings' actions.
 */
data class Profile(
    val id: String,
    val name: String,
    val map: Map<String, String>,
    /** The index finger's pinch and turn (a [DialChoice] key; the name 0.7.7 stored it under). */
    val dial: String,
    /** Gesture key → palm key → action id ("" = nothing there); at most [ProfileList.MAX_OVERRIDES] each. */
    val palm: Map<String, Map<String, String>> = emptyMap(),
    val middleDial: String = DialChoice.NONE.key,
    val indexDrags: Drags = Drags(),
    val middleDrags: Drags = Drags(),
    /** Drawing name → this profile's own action (wins over the shared one). */
    val drawings: Map<String, String> = emptyMap(),
) {
    fun dialOf(part: Part) = if (part == Part.MIDDLE) middleDial else dial
    fun drags(part: Part) = if (part == Part.MIDDLE) middleDrags else indexDrags
}
```

`ProfileList` gains (and `added` keeps its signature):

```kotlin
    /** [palm] runs [action] on [gesture] instead of the normal one ("" = nothing); a third is refused. */
    fun withOverride(id: String, gesture: String, palm: Palm, action: String) = change(id) {
        val own = it.palm[gesture].orEmpty()
        if (palm.key !in own && own.size >= MAX_OVERRIDES) it else it.copy(palm = it.palm + (gesture to own + (palm.key to action)))
    }

    fun withoutOverride(id: String, gesture: String, palm: Palm) = change(id) {
        val own = it.palm[gesture].orEmpty() - palm.key
        it.copy(palm = if (own.isEmpty()) it.palm - gesture else it.palm + (gesture to own))
    }

    fun withMiddleDial(id: String, dial: String) = change(id) { it.copy(middleDial = dial) }

    fun withDrags(id: String, part: Part, drags: Drags) =
        change(id) { if (part == Part.MIDDLE) it.copy(middleDrags = drags) else it.copy(indexDrags = drags) }

    /** This profile's own action for a drawing (null: none of its own, the shared one applies). */
    fun withDrawing(id: String, name: String, action: String?) =
        change(id) { it.copy(drawings = if (action == null) it.drawings - name else it.drawings + (name to action)) }

    /** A drawing renamed, or gone (null), in every profile. */
    fun renamedDrawing(old: String, new: String?) = copy(profiles = profiles.map { p ->
        val action = p.drawings[old] ?: return@map p
        p.copy(drawings = (p.drawings - old) + (if (new == null) emptyMap() else mapOf(new to action)))
    })

    /** The first controls dev build's built-in action ids out of every profile (their gestures go back to their defaults). */
    fun withoutBuiltins() = copy(profiles = profiles.map { p ->
        p.copy(
            map = p.map.filterValues { it !in BUILTIN_IDS },
            palm = p.palm.mapValues { (_, own) -> own.filterValues { it !in BUILTIN_IDS } }.filterValues { it.isNotEmpty() },
        )
    })
```

`companion object` gains `const val MAX_OVERRIDES = 2`, `val BUILTIN_IDS = setOf("controls.toggle", "profile.next", "profile.previous")` and `fun builtinCalls(calls: Map<String, String>) = calls.filterValues { it in BUILTIN_IDS }.keys`.

`toJson` adds per profile: `put("palm", JSONObject(profile.palm.mapValues { JSONObject(it.value) }))`, `put("middle_dial", profile.middleDial)`, `put("drags", JSONObject().put("index", drags(profile.indexDrags)).put("middle", drags(profile.middleDrags)))` with `drags(d) = JSONObject().put("sliders", d.sliders).put("targets", JSONArray(d.targets))`, and `put("drawings", JSONObject(profile.drawings))`. `fromJson` reads them with `opt…` and the defaults when absent (0.7.7 has none of them): a helper `fun strings(o: JSONObject?) = o?.keys()?.asSequence()?.associateWith { o.getString(it) }.orEmpty()`.

- [ ] **Step 5: `Lessons.kt` and `PracticeActivity.kt`**
- `Lessons.steps(profiles: Int, dial: Boolean, map: Map<String, String>, band: BandControls)`: a triple is practised when it does something there, as band-core decides whether its taps wait (lessons are palm down):
  ```kotlin
          fun does(gesture: Gesture): Boolean = when (band.on(gesture, Palm.DOWN)) {
              Builtin.CONTROLS, Builtin.DRAW -> true
              Builtin.NEXT, Builtin.PREVIOUS -> profiles >= 2
              // An action of the profile's own; one paused by a band control doesn't run.
              null -> (map[gesture.key] ?: gesture.default.id).isNotEmpty()
          }
  ```
  `if (does(Gesture.INDEX_TRIPLE)) …` / `MIDDLE_TRIPLE` as before; a new step after the middle hold when `does(Gesture.INDEX_HOLD)`: `Step("Hold your index finger", "Keep your thumb on your index finger for a second, without moving your wrist.", Done.Gesture("index hold"), Gesture.INDEX_HOLD)`. The middle hold step's text: "Keep your thumb on your middle finger for a second. It runs what the middle hold does (by default the controls on/off, a band control)."
- `PracticeActivity`: `Lessons.steps(…, band = Profiles.band(this))` (Task 6 adds `Profiles.band`; until then `BandControls.standard()`), and `would()` asks the band controls first:
  ```kotlin
              is Lessons.Done.Gesture, Lessons.Done.Hold -> {
                  val gesture = step.gesture ?: return ""
                  when (Profiles.band(this).on(gesture, Palm.DOWN)) {
                      Builtin.CONTROLS -> "turn the controls off (band control)"
                      Builtin.DRAW -> "open the drawing pad (band control)"
                      Builtin.NEXT, Builtin.PREVIOUS -> if (list.locked(Profiles.foreground) || list.profiles.size < 2) {
                          "nothing here"
                      } else {
                          val by = if (Profiles.band(this).on(gesture, Palm.DOWN) == Builtin.NEXT) 1 else -1
                          "switch to ${list.step(by).active(null).name}"
                      }
                      null -> { …the existing Action.of(id) branch, without the built-ins… }
                  }
              }
  ```
- `LessonsBackupTest`: pass `band = BandControls.standard()` in the first, second and fourth tests (sizes stay 10 and 11). The third becomes:
  ```kotlin
      @Test fun `a triple tap is practised only where it does something`() {
          val none = BandControls()
          val one = Lessons.steps(1, true, defaults + ("index_triple" to "media.next"), none).map { it.gesture }
          assertTrue(Gesture.INDEX_TRIPLE in one)
          assertFalse(Gesture.MIDDLE_TRIPLE in one)
          // Next profile on it with one profile can't act, and the profile's action is paused.
          val paused = Lessons.steps(1, true, defaults + ("index_triple" to "media.next"), BandControls.standard()).map { it.gesture }
          assertFalse(Gesture.INDEX_TRIPLE in paused)
          val two = Lessons.steps(2, true, defaults, BandControls.standard()).map { it.gesture }
          assertTrue(Gesture.INDEX_TRIPLE in two && Gesture.MIDDLE_TRIPLE in two)
          assertTrue(Gesture.INDEX_HOLD in Lessons.steps(1, true, defaults + ("index_hold" to "media.next"), none).map { it.gesture })
      }
  ```

- [ ] **Step 6: `MappingsTest`** — `profilesJson` keeps its signature until Task 6, but its first test asserted the built-in defaults: `middle_hold`, `index_triple` and `middle_triple` now resolve to `""` (change those three lines; the fixture's `"middle_hold" to "profile.next"` becomes `"middle_hold" to "media.next"`).

- [ ] **Step 7: Run and commit**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew -q assembleDebug testDebugUnitTest
grep -rn "NEXT_PROFILE\|PREVIOUS_PROFILE\|Action.CONTROLS\|\.builtin\b" app/src/main/java app/src/test/java   # nothing
git add app/src/main/java/dev/kb896/airgestures/{Mapping,Controls,ProfileList,Lessons,Actions,PracticeActivity,GestureScreen}.kt \
  app/src/test/java/dev/kb896/airgestures/{ControlsTest,ProfileListTest,LessonsBackupTest,MappingsTest}.kt
git commit -q -m "App data: 24 gestures, band controls, palm overrides, dials and drags per finger; built-ins leave the actions

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MLDqGPYx8bTGKJcVLgoyzo"
```

---

### Task 6: Storage, migration, drawings, calibrations, the bridge's JSON

**Files:**
- Modify: `Profiles.kt`, `Prefs.kt`, `Mappings.kt`, `Lessons.kt` (`Backup`), `SetupActivity.kt` (backup settings), `BandLink.kt` (band address only)
- Create: `Drawings.kt`, `Calibration.kt`
- Test: `MappingsTest.kt`, `DrawingsTest.kt` (new), `LessonsBackupTest.kt`

- [ ] **Step 1: Failing tests**

`MappingsTest.kt` — the fixture and helpers become:

```kotlin
    private val list = ProfileList(
        listOf(
            Profile("p1", "Music", mapOf("swipe_up" to "media.previous", "index_double" to "", "middle_tap" to "automation"), "volume",
                palm = mapOf("index_tap" to mapOf("up" to "automation", "sideways" to "")),
                middleDrags = Drags(sliders = true, targets = listOf("automation", "none", "brightness")),
                drawings = mapOf("Bulb" to "automation")),
            Profile("p2", "Reading", mapOf("swipe_up" to "nav.next"), "none", middleDial = "seek"),
            Profile("p3", "Lights", emptyMap(), "automation"),
        ),
        picked = "p2",
        apps = mapOf("org.videolan.vlc" to "p1", "gone.app" to "p9"),
    )
    private val calls = mapOf("index_double" to "call.answer", "middle_double" to "-", "swipe_up" to "", "middle_tap" to "automation")
    private val band = BandControls.standard().with(Builtin.NEXT, BandControl(Gesture.INDEX_TRIPLE, setOf(Palm.UP)))
    private val input = Mappings.Input(list, band, calls, mapOf("Bulb" to "torch.toggle"),
        mapOf("left" to Calibration(palm = -140.0 to -12.0), "right" to Calibration()), "left")

    private fun json() = org.json.JSONObject(Mappings.profilesJson(input))
    private fun profile(i: Int) = json().getJSONArray("profiles").getJSONObject(i)
    private fun map(i: Int) = profile(i).getJSONObject("map")
```

Tests (replace the built-in and dial ones, keep the others):

```kotlin
    @Test fun `every profile gets every gesture, defaults for keys it never stored`() {
        val first = map(0)
        assertEquals(Gesture.entries.map { it.key }.toSet(), first.keys().asSequence().toSet())
        assertEquals("media.previous", first.getString("swipe_up"))
        assertEquals("media.next", first.getString("swipe_down"))
        assertEquals("", first.getString("middle_hold"))
        assertEquals("", first.getString("index_triple"))
        assertEquals("", first.getString("index_drag_left"))
    }

    @Test fun `the band controls and the calibrations go along`() {
        val json = json()
        val next = json.getJSONObject("band").getJSONObject("next")
        assertEquals("index_triple", next.getString("gesture"))
        assertEquals("up", next.getJSONArray("palm").getString(0))
        assertEquals(-140.0, json.getJSONObject("calibration").getJSONObject("left").getJSONObject("palm").getDouble("down"), 0.0)
        assertTrue(json.getJSONObject("calibration").getJSONObject("right").isNull("palm"))
        assertEquals("torch.toggle", json.getJSONObject("drawings").getString("Bulb"))
    }

    @Test fun `automation is named after what made it`() {
        assertEquals("automation:middle_tap", map(0).getString("middle_tap"))
        assertEquals("automation:middle_tap", json().getJSONObject("calls").getString("middle_tap"))
        assertEquals("automation:index_tap:up", profile(0).getJSONObject("palm").getJSONObject("index_tap").getString("up"))
        assertEquals("", profile(0).getJSONObject("palm").getJSONObject("index_tap").getString("sideways"))
        assertEquals("automation:drawing:Bulb", profile(0).getJSONObject("drawings").getString("Bulb"))
        val sliders = profile(0).getJSONObject("middle").getJSONObject("drags").getJSONArray("sliders")
        assertEquals("automation:dial:middle_left_right:up", sliders.getJSONObject(0).getString("up"))
        assertTrue(sliders.isNull(1))
        assertEquals("brightness.down", sliders.getJSONObject(2).getString("down"))
        assertEquals("automation:dial:dial:down", profile(2).getJSONObject("index").getJSONObject("dial").getString("down"))
    }

    @Test fun `dials are their two actions, nothing is null, drags in gestures mode say so`() {
        assertEquals("volume.up", profile(0).getJSONObject("index").getJSONObject("dial").getString("up"))
        assertTrue(profile(1).getJSONObject("index").isNull("dial"))
        assertEquals("dial.seek_forward", profile(1).getJSONObject("middle").getJSONObject("dial").getString("up"))
        assertTrue(profile(0).getJSONObject("middle").isNull("dial"))
        assertEquals("gestures", profile(0).getJSONObject("index").getString("drags"))
    }

    @Test fun `automation names read back`() {
        assertEquals(Mappings.Automation.Gesture("index_tap", null), Mappings.automation("index_tap"))
        assertEquals(Mappings.Automation.Gesture("index_tap", "up"), Mappings.automation("index_tap:up"))
        assertEquals(Mappings.Automation.Step("dial", +1), Mappings.automation("dial:dial:up"))
        assertEquals(Mappings.Automation.Step("middle_left_right", -1), Mappings.automation("dial:middle_left_right:down"))
        assertEquals(Mappings.Automation.Drawing("Light: on"), Mappings.automation("drawing:Light: on"))
    }
```

`DrawingsTest.kt`:

```kotlin
package dev.kb896.airgestures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawingsTest {
    private val stroke = listOf(0.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0)
    private val bulb = Drawing("Bulb", List(3) { listOf(stroke) })

    @Test fun `the library round-trips as the bridge reads it`() {
        val library = DrawingLibrary(listOf(bulb))
        val json = org.json.JSONArray(library.toJson()).getJSONObject(0)
        assertEquals("Bulb", json.getString("name"))
        // tries → strokes → points → [x, y]
        assertEquals(10.0, json.getJSONArray("tries").getJSONArray(2).getJSONArray(0).getJSONArray(1).getDouble(0), 0.0)
        assertEquals(library, DrawingLibrary.fromJson(library.toJson()))
    }

    @Test fun `a session's strokes read as a try`() {
        assertEquals(listOf(listOf(0.0 to 0.0, 1.5 to 2.0)), DrawingLibrary.strokes("[[[0.0,0.0],[1.5,2.0]]]"))
    }

    @Test fun `teaching a drawing again replaces it, rename and delete follow the name`() {
        var library = DrawingLibrary(listOf(bulb, Drawing("Z", bulb.tries)))
        library = library.with(Drawing("Bulb", listOf(listOf(stroke))))
        assertEquals(1, library.get("Bulb")!!.tries.size)
        assertEquals(listOf("Bulb", "Z"), library.names)
        library = library.renamed("Z", "Zed").without("Bulb")
        assertEquals(listOf("Zed"), library.names)
        assertTrue(library.without("Zed").drawings.isEmpty())
    }
}
```

(The JVM's `org.json:json` keeps no key order in a `JSONObject`: the Kotlin tests compare fields, never whole JSON strings.)

`LessonsBackupTest.kt`: the fixture `Settings(...)` gains `band = BandControls.standard().with(Builtin.DRAW, BandControl(Gesture.SWIPE_RIGHT))`, `sharedDrawings = mapOf("Bulb" to "torch.toggle")`, `drawings = DrawingLibrary(listOf(Drawing("Bulb", listOf(listOf(listOf(0.0 to 0.0, 9.0 to 9.0))))))`; the "newer backup is refused" test uses version 3; add:

```kotlin
    @Test fun `a version 1 backup restores with today's band controls and no built-in actions`() {
        val v1 = """{"version":1,"profiles":[{"id":"p1","name":"A","map":{"middle_hold":"controls.toggle","swipe_up":"media.next"},"dial":"volume"}],
            "picked":"p1","apps":{},"calls":{"index_double":"profile.next"},"hand":"band"}"""
        val restored = Backup.read(v1)!!
        assertEquals(BandControls.standard(), restored.band)
        assertEquals(mapOf("swipe_up" to "media.next"), restored.profiles.profile("p1")!!.map)
        assertFalse("index_double" in restored.calls)
        assertNull(restored.drawings)
    }
```

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew -q testDebugUnitTest --tests '*MappingsTest*' --tests '*DrawingsTest*' --tests '*LessonsBackupTest*'` — Expected: compile errors.

- [ ] **Step 2: `Drawings.kt`** (new)

```kotlin
package dev.kb896.airgestures

import android.content.Context
import org.json.JSONArray
import java.io.File

/** One stroke: points in degrees (x right, y up), as band-core draws them. */
typealias Stroke = List<Pair<Double, Double>>

/** A taught drawing: its name and the tries it was taught with (each a list of strokes). */
data class Drawing(val name: String, val tries: List<List<Stroke>>)

/** The taught drawings, as the bridge reads them (`teach::library`). Free of Android, so it's unit-tested. */
data class DrawingLibrary(val drawings: List<Drawing> = emptyList()) {
    val names get() = drawings.map { it.name }
    fun get(name: String) = drawings.firstOrNull { it.name == name }

    /** Taught (again): replaces the one with its name, else goes at the end. */
    fun with(drawing: Drawing) = if (get(drawing.name) == null) copy(drawings = drawings + drawing)
        else copy(drawings = drawings.map { if (it.name == drawing.name) drawing else it })

    fun renamed(old: String, new: String) = copy(drawings = drawings.map { if (it.name == old) it.copy(name = new) else it })
    fun without(name: String) = copy(drawings = drawings.filter { it.name != name })

    /** Without [name] (null: all): what a drawing being taught again mustn't "look like". */
    fun except(name: String?) = if (name == null) this else without(name)

    fun toJson(): String = JSONArray(drawings.map { d ->
        org.json.JSONObject().put("name", d.name).put("tries", JSONArray(d.tries.map(::tryJson)))
    }).toString()

    companion object {
        fun tryJson(strokes: List<Stroke>) = JSONArray(strokes.map { s -> JSONArray(s.map { (x, y) -> JSONArray().put(x).put(y) }) })

        /** One try's strokes from JSON (`@draw-finished`, a stored try). */
        fun strokes(json: String): List<Stroke> = strokes(JSONArray(json))

        private fun strokes(a: JSONArray): List<Stroke> = (0 until a.length()).map { i ->
            val s = a.getJSONArray(i)
            (0 until s.length()).map { j -> s.getJSONArray(j).let { it.getDouble(0) to it.getDouble(1) } }
        }

        fun fromJson(json: String): DrawingLibrary {
            val a = JSONArray(json)
            return DrawingLibrary((0 until a.length()).map { i ->
                val d = a.getJSONObject(i)
                val tries = d.getJSONArray("tries")
                Drawing(d.getString("name"), (0 until tries.length()).map { strokes(tries.getJSONArray(it)) })
            })
        }
    }
}

/** Where the drawings live: the templates in a file (they can be large), the shared actions in [Profiles]. */
object Drawings {
    @Volatile
    private var cached: DrawingLibrary? = null

    private fun file(context: Context) = File(context.filesDir, "drawings.json")

    fun load(context: Context): DrawingLibrary = cached ?: synchronized(this) {
        cached ?: (runCatching { DrawingLibrary.fromJson(file(context).readText()) }.getOrNull() ?: DrawingLibrary()).also { cached = it }
    }

    fun update(context: Context, change: (DrawingLibrary) -> DrawingLibrary): DrawingLibrary = synchronized(this) {
        change(load(context)).also {
            cached = it
            file(context).writeText(it.toJson())
        }
    }

    /** The action [name] runs in [profile]: its own, else the shared one ("" = none). */
    fun action(context: Context, profile: Profile, name: String) = profile.drawings[name] ?: Profiles.sharedDrawings(context)[name].orEmpty()
}
```

- [ ] **Step 3: `Calibration.kt`** (new)

```kotlin
package dev.kb896.airgestures

import android.content.Context
import org.json.JSONObject

/**
 * One wrist on one band: palm down and palm up as rolls (degrees), and taught drags (the
 * bridge's JSON from Bridge.dragPrototypes, kept as it came). Free of Android, so it's unit-tested.
 */
data class Calibration(val palm: Pair<Double, Double>? = null, val drags: String? = null) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("palm", palm?.let { (down, up) -> JSONObject().put("down", down).put("up", up) } ?: JSONObject.NULL)
        put("drags", drags?.let(::JSONObject) ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(text: String): Calibration {
            val json = JSONObject(text)
            val palm = json.optJSONObject("palm")?.let { it.getDouble("down") to it.getDouble("up") }
            return Calibration(palm, json.optJSONObject("drags")?.toString())
        }
    }
}

/** Calibrations by band (its Bluetooth address) and wrist ("left" / "right"), in the app's settings. */
object Calibrations {
    private fun prefs(context: Context) = context.getSharedPreferences("air-gestures", Context.MODE_PRIVATE)
    private fun key(band: String, hand: String) = "calibration.$band.$hand"

    fun get(context: Context, band: String?, hand: String): Calibration {
        band ?: return Calibration()
        return prefs(context).getString(key(band, hand), null)?.let { runCatching { Calibration.fromJson(it) }.getOrNull() } ?: Calibration()
    }

    fun update(context: Context, band: String, hand: String, change: (Calibration) -> Calibration) =
        prefs(context).edit().putString(key(band, hand), change(get(context, band, hand)).toJson().toString()).apply()
}
```

- [ ] **Step 4: `Profiles.kt`** — band controls, shared drawing actions, the migration (add `import org.json.JSONObject`):

```kotlin
    private const val BAND = "band.controls"
    private const val SHARED_DRAWINGS = "drawings.shared"
    private const val VERSION_KEY = "controls.version"
    /** 2: band controls (from 0.7.7, and from the first controls dev build with built-in actions). */
    private const val VERSION = 2

    @Volatile
    private var band: BandControls? = null

    /** The band controls; today's gestures until changed (or when unreadable). */
    fun band(context: Context): BandControls = band ?: synchronized(this) {
        band ?: (prefs(context).getString(BAND, null)?.let { runCatching { BandControls.fromJson(it) }.getOrNull() }
            ?: BandControls.standard()).also { band = it }
    }

    fun updateBand(context: Context, change: (BandControls) -> BandControls): BandControls = synchronized(this) {
        change(band(context)).also {
            band = it
            prefs(context).edit().putString(BAND, it.toJson().toString()).apply()
        }
    }

    /** Drawing name → its action in every profile without its own. */
    fun sharedDrawings(context: Context): Map<String, String> =
        prefs(context).getString(SHARED_DRAWINGS, null)?.let { runCatching { JSONObject(it).let { o -> o.keys().asSequence().associateWith(o::getString) } }.getOrNull() }.orEmpty()

    fun updateSharedDrawings(context: Context, change: (Map<String, String>) -> Map<String, String>) =
        prefs(context).edit().putString(SHARED_DRAWINGS, JSONObject(change(sharedDrawings(context))).toString()).apply()
```

In `read(context)`, every path that returns a list goes through `migrated(context, list)`:

```kotlin
    /**
     * Once: built-in action ids (the first controls dev build stored them in profiles and During
     * calls) go, their gestures back to their defaults; the band controls start on today's
     * gestures (0.7.7 had them fixed there, so its profiles keep doing what they did).
     */
    private fun migrated(context: Context, list: ProfileList): ProfileList {
        val prefs = prefs(context)
        if (prefs.getInt(VERSION_KEY, 0) >= VERSION) return list
        val clean = list.withoutBuiltins()
        val calls = Gesture.entries.associate { it.key to (prefs.getString("call.${it.key}", null) ?: "") }
        prefs.edit().apply {
            for (key in ProfileList.builtinCalls(calls)) remove("call.$key")
            if (!prefs.contains(BAND)) putString(BAND, BandControls.standard().toJson().toString())
            putInt(VERSION_KEY, VERSION)
        }.apply()
        if (clean != list) write(context, clean)
        return clean
    }
```

(`read`'s two `runCatching { return ProfileList.fromJson(…) }` lines become `runCatching { return migrated(context, ProfileList.fromJson(…)) }`, and the old-mapping path `migrated(context, ProfileList.migrated(…))`.)

- [ ] **Step 5: `Mappings.kt`** — `profilesJson(Input)`, the dial names, `automation(rest)` (code as in the tests' expectations):

```kotlin
    const val DIAL = "dial:"
    const val DRAWING = "drawing:"
    private val PAIRS = listOf("left_right", "toward_away", "up_down")

    /** Everything the bridge needs (rust/bridge/src/profiles.rs). */
    class Input(
        val list: ProfileList,
        val band: BandControls,
        /** Gesture key → action id, [AS_USUAL] or [NOTHING]. */
        val calls: Map<String, String>,
        /** Drawing name → its action in every profile without its own. */
        val drawings: Map<String, String>,
        /** "left" / "right" → this band's calibration of that wrist. */
        val calibrations: Map<String, Calibration>,
        val hand: String,
    )

    /** A stored [AUTOMATION] becomes `automation:<name>`: the bridge reports only action names. */
    private fun named(id: String, name: String) = if (id == AUTOMATION) "$AUTOMATION:$name" else id

    /** A dial's two actions (null: nothing); automation steps carry the dial's name (`dial` for the index dial, as 0.7.7). */
    fun dial(key: String, name: String): Pair<String, String>? = when (val choice = DialChoice.of(key)) {
        DialChoice.NONE -> null
        DialChoice.AUTOMATION -> "$AUTOMATION:$DIAL$name:up" to "$AUTOMATION:$DIAL$name:down"
        else -> choice.up to choice.down
    }

    private fun dialJson(key: String, name: String): Any =
        dial(key, name)?.let { (up, down) -> JSONObject().put("up", up).put("down", down) } ?: JSONObject.NULL

    private fun finger(profile: Profile, part: Part): JSONObject {
        val f = part.name.lowercase()
        val drags = profile.drags(part)
        return JSONObject().apply {
            put("dial", dialJson(profile.dialOf(part), if (part == Part.INDEX) "dial" else "${f}_dial"))
            put("drags", if (!drags.sliders) "gestures" else JSONObject().put("sliders",
                JSONArray(drags.targets.mapIndexed { i, key -> dialJson(key, "${f}_${PAIRS[i]}") })))
        }
    }

    private fun drawingsJson(map: Map<String, String>) =
        JSONObject(map.filterValues { it.isNotEmpty() }.mapValues { (name, id) -> named(id, "$DRAWING$name") })

    fun profilesJson(input: Input): String {
        val list = input.list
        val profiles = JSONArray()
        for (profile in list.profiles) {
            val map = JSONObject()
            for (gesture in Gesture.entries) map.put(gesture.key, named(profile.map[gesture.key] ?: gesture.default.id, gesture.key))
            val palm = JSONObject()
            for ((key, own) in profile.palm) palm.put(key, JSONObject(own.mapValues { (at, id) -> named(id, "$key:$at") }))
            profiles.put(JSONObject().apply {
                put("name", profile.name)
                put("map", map)
                put("palm", palm)
                put("index", finger(profile, Part.INDEX))
                put("middle", finger(profile, Part.MIDDLE))
                put("drawings", drawingsJson(profile.drawings))
            })
        }
        val index = { id: String -> list.profiles.indexOfFirst { it.id == id } }
        return JSONObject().apply {
            put("hand", input.hand)
            put("picked", index(list.picked).coerceAtLeast(0))
            put("apps", JSONObject(list.apps.mapValues { index(it.value) }.filterValues { it >= 0 }))
            put("band", input.band.toJson())
            put("calls", JSONObject(input.calls.filterValues { it != AS_USUAL }.mapValues { (key, id) -> if (id == NOTHING) "-" else named(id, key) }))
            put("drawings", drawingsJson(input.drawings))
            put("calibration", JSONObject().apply {
                for (hand in listOf("left", "right")) put(hand, input.calibrations[hand]?.toJson() ?: JSONObject.NULL)
            })
            put("profiles", profiles)
        }.toString()
    }

    /** What `automation:<rest>` sends (Actions.automation). */
    sealed class Automation {
        /** A gesture; [palm]: the position its override is for. */
        data class Gesture(val name: String, val palm: String?) : Automation()
        /** A dial or slider step, gathered per [name]. */
        data class Step(val name: String, val step: Int) : Automation()
        data class Drawing(val name: String) : Automation()
    }

    fun automation(rest: String): Automation = when {
        rest.startsWith(DIAL) -> rest.removePrefix(DIAL).let { Automation.Step(it.substringBeforeLast(':'), if (it.endsWith(":down")) -1 else +1) }
        rest.startsWith(DRAWING) -> Automation.Drawing(rest.removePrefix(DRAWING))
        ':' in rest -> Automation.Gesture(rest.substringBefore(':'), rest.substringAfter(':'))
        else -> Automation.Gesture(rest, null)
    }
```

`DialChoice.AUTOMATION`'s `up`/`down` strings stay as placeholders (they're never sent now; note it in its doc comment).

- [ ] **Step 6: `Prefs.kt`**
- `var bandAddress: String?` (prefs `"band.address"`); `BandLink.connect()` sets it from `bonded.address` when it differs.
- `fun handInUse(): String?` = `BandService.state.value.hand ?: hand.takeIf { it != "band" }` (as `Setup::hand_in_use`).
- `profilesJson()`:
  ```kotlin
      fun profilesJson(): String = Mappings.profilesJson(Mappings.Input(
          Profiles.load(context),
          Profiles.band(context),
          Gesture.entries.associate { it.key to callActionId(it) },
          Profiles.sharedDrawings(context),
          listOf("left", "right").associateWith { Calibrations.get(context, bandAddress, it) },
          hand,
      ))
  ```
- `val dial` / `fun dial(profile)` / `setDial` stay (index dial). `allIds()` adds every profile's palm override ids and drawing ids and the shared drawing ids; `dials()` becomes every profile's index dial, middle dial and slider targets (`DialChoice.of` each), so `usesBrightness`, `usesSeek`, `usesNotifications`, `usesNavigation` see them.

- [ ] **Step 7: `Backup`** (in `Lessons.kt`) — `VERSION = 2`; `Settings` gains `band: BandControls = BandControls.standard()`, `sharedDrawings: Map<String, String> = emptyMap()`, `drawings: DrawingLibrary? = null`. `write` adds `"band"`, `"shared_drawings"`, `"drawings"` (the library's JSON as an array). `read` accepts versions 1 and 2: version 1 gets `band = standard()`, `profiles.withoutBuiltins()`, and calls without `ProfileList.builtinCalls(calls)`. `SetupActivity.settings()` fills the new fields from `Profiles.band`, `Profiles.sharedDrawings`, `Drawings.load`; `apply()` writes them back (`Profiles.updateBand`, `Profiles.updateSharedDrawings`, `Drawings.update` only when `drawings != null`).

- [ ] **Step 8: Run and commit**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew -q assembleDebug testDebugUnitTest
git add app/src/main/java/dev/kb896/airgestures/{Profiles,Prefs,Mappings,Lessons,SetupActivity,BandLink,Drawings,Calibration}.kt \
  app/src/test/java/dev/kb896/airgestures/{MappingsTest,DrawingsTest,LessonsBackupTest}.kt
git commit -q -m "App storage: band controls once above the profiles, drawings, calibrations per band and wrist; migration from 0.7.7 and the dev build

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MLDqGPYx8bTGKJcVLgoyzo"
```

---

### Task 7: Plumbing: Bridge, BandLink, BandService, Actions, the report

**Files:**
- Modify: `Bridge.kt`, `BandLink.kt`, `BandService.kt`, `Actions.kt`, `GestureScreen.kt` (the automation guide's text)
- Create: `Report.kt`, `Teach.kt` (pure logic for the teach screens)
- Test: `ReportTest.kt`, `TeachTest.kt` (new)

- [ ] **Step 1: Failing tests**

`ReportTest.kt` (the JSON is what `report::report` returns for the bridge test's `JSON`):

```kotlin
package dev.kb896.airgestures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportTest {
    private val report = Report("""{"warnings":["no-way-out"],"invalid":[{"kind":"band","control":"next"}],
        "paused":[{"control":"controls","gesture":"middle_hold","place":0,"palm":null,"positions":["down","sideways","up"]},
                  {"control":"next","gesture":"index_triple","place":0,"palm":"up","positions":["up"]},
                  {"control":"next","gesture":"index_double","place":"calls","palm":null,"positions":["up"]}],
        "paused_sliders":[{"control":"previous","gesture":"middle_drag_left","profile":1,"finger":"middle","pair":0,"positions":["down"]}],
        "uses":{},"hold_waits":[{"index":[],"middle":["down","sideways","up"]},{"index":["down","sideways","up"],"middle":["up"]}]}""")

    @Test fun `counts by band control and by place`() {
        assertEquals(1, report.count(Builtin.CONTROLS))
        assertEquals(2, report.count(Builtin.NEXT))
        assertEquals(1, report.count(Builtin.PREVIOUS))
        assertEquals(2, report.count(0))
        assertEquals(1, report.count(1))
        assertEquals(1, report.count(null as Int?))
    }

    @Test fun `what's paused where, and the holds`() {
        assertEquals(setOf(Palm.UP), report.paused(0, Gesture.INDEX_TRIPLE, Palm.UP)!!.positions)
        assertNull(report.paused(0, Gesture.INDEX_TRIPLE, null))
        assertEquals(Builtin.PREVIOUS, report.pausedSlider(1, Part.MIDDLE, 0)!!.control)
        assertTrue(report.holdWaits(0, Part.INDEX).isEmpty())
        assertEquals(Palm.entries.toSet(), report.holdWaits(1, Part.INDEX))
        assertEquals(setOf(Palm.UP), report.holdWaits(1, Part.MIDDLE))
        assertEquals(listOf("no-way-out"), report.warnings)
        assertEquals(listOf("band next"), report.invalid)
        assertEquals(0, Report("not json").count(Builtin.CONTROLS))
    }

    @Test fun `band choices read with what they'd pause`() {
        val choices = Report.choices("""[{"gesture":"index_double","pauses":[{"control":"previous","gesture":"index_double","place":"calls","palm":null,"positions":["down"]}],"sliders":[]},
            {"gesture":"swipe_left","pauses":[],"sliders":[]}]""")
        assertEquals(listOf(Gesture.INDEX_DOUBLE, Gesture.SWIPE_LEFT), choices.map { it.gesture })
        assertEquals(1, choices[0].count)
        assertNull(choices[0].pauses[0].place)
        assertEquals(0, choices[1].count)
    }
}
```

`TeachTest.kt`:

```kotlin
package dev.kb896.airgestures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeachTest {
    @Test fun `a still palm is kept for two seconds once it settled`() {
        val s = PalmSampler()
        for (t in 0..3000L step 50) s.add(-142.0 + (t % 100) / 200.0, t)
        assertTrue(s.done)
        assertTrue(s.rolls.size >= 40)
    }

    @Test fun `a move or a missing reading starts again`() {
        val s = PalmSampler()
        for (t in 0..1500L step 50) s.add(-142.0, t)
        assertTrue(s.progress > 0f)
        s.add(-100.0, 1550)
        assertEquals(0f, s.progress)
        for (t in 1600..2500L step 50) s.add(-100.0, t)
        s.add(null, 2550)
        assertEquals(0f, s.progress)
        assertEquals(2.0, PalmSampler.angle(179.0 - -179.0), 1e-9)
    }

    @Test fun `teach your drags: one sample per step, the bridge's JSON, and what to redo`() {
        val t = DragTeach()
        assertEquals(DragTeach.Step.TURN, t.current)
        repeat(7) { t.sample(doubleArrayOf(0.0, 1.0, it.toDouble()), -142.3) }
        assertNull(t.current)
        val json = org.json.JSONObject(t.json()!!)
        assertEquals(-142.3, json.getDouble("roll"), 0.0)
        assertEquals(6, json.getJSONArray("drags").length())
        val (redo, message) = t.refused("too-alike left right")
        assertEquals(listOf(DragTeach.Step.LEFT, DragTeach.Step.RIGHT), redo)
        assertTrue("Drag left" in message)
        assertEquals(DragTeach.Step.LEFT, t.current)
        assertNull(t.json())
    }

    @Test fun `teach a drawing: three tries, the bridge's refusals`() {
        val t = DrawTeach()
        val stroke = listOf(listOf(0.0 to 0.0, 9.0 to 9.0))
        repeat(3) { t.add(stroke) }
        assertTrue(t.full)
        assertNull(t.refused(""))
        assertEquals("Try 2 was too small: draw it bigger.", t.refused("too-small 1"))
        assertEquals(2, t.tries.size)
        assertTrue(t.refused("not-alike 0 1")!!.startsWith("These don't look alike"))
        assertTrue(t.tries.isEmpty())
        assertTrue(DrawTeach.nameFree("Bulb", listOf("Zed"), own = null))
        assertFalse(DrawTeach.nameFree("zed", listOf("Zed"), own = null))
        assertTrue(DrawTeach.nameFree("Zed", listOf("Zed"), own = "Zed"))
        assertFalse(DrawTeach.nameFree("  ", emptyList(), own = null))
    }
}
```

- [ ] **Step 2: `Report.kt`**

```kotlin
package dev.kb896.airgestures

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * What band-core says about the settings (Bridge.profileReport), for the screens: the warnings,
 * what's invalid, the actions and sliders the band controls pause (places: profile indexes,
 * null for During calls), and whose hold waits for the release. Free of Android, so it's unit-tested.
 */
class Report(json: String) {
    data class Paused(val control: Builtin, val gesture: Gesture, val place: Int?, val palm: Palm?, val positions: Set<Palm>)
    data class PausedSlider(val control: Builtin, val gesture: Gesture, val profile: Int, val part: Part, val pair: Int, val positions: Set<Palm>)
    class Choice(val gesture: Gesture, val pauses: List<Paused>, val sliders: List<PausedSlider>) {
        val count get() = pauses.size + sliders.size
    }

    private val root = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
    val warnings: List<String> = strings(root.optJSONArray("warnings"))
    /** `binding <place> <gesture>`, `band <control>`, `draw-cant-cancel`. */
    val invalid: List<String> = objects(root.optJSONArray("invalid")).map { o ->
        when (o.optString("kind")) {
            "binding" -> "binding ${o.opt("place")} ${o.optString("gesture")}"
            "band" -> "band ${o.optString("control")}"
            else -> o.optString("kind")
        }
    }
    val paused: List<Paused> = paused(root.optJSONArray("paused"))
    val pausedSliders: List<PausedSlider> = sliders(root.optJSONArray("paused_sliders"))
    private val holds = root.optJSONArray("hold_waits")

    fun count(control: Builtin) = paused.count { it.control == control } + pausedSliders.count { it.control == control }
    /** In profile [place] (an index), or During calls (null). */
    fun count(place: Int?) = paused.count { it.place == place } + if (place == null) 0 else pausedSliders.count { it.profile == place }
    fun paused(place: Int?, gesture: Gesture, palm: Palm?) = paused.firstOrNull { it.place == place && it.gesture == gesture && it.palm == palm }
    fun pausedSlider(profile: Int, part: Part, pair: Int) = pausedSliders.firstOrNull { it.profile == profile && it.part == part && it.pair == pair }
    /** Where [part]'s hold acts when you let go in profile [profile] (band-core's `Profiles::hold_waits`): the palm positions. */
    fun holdWaits(profile: Int, part: Part): Set<Palm> =
        palms(holds?.optJSONObject(profile)?.optJSONArray(if (part == Part.MIDDLE) "middle" else "index"))

    companion object {
        /** For the stored settings, from band-core. */
        fun of(context: Context) = Report(runCatching { Bridge.profileReport(Prefs(context).profilesJson()) }.getOrDefault("{}"))

        /** Bridge.bandChoices' answer. */
        fun choices(json: String): List<Choice> = objects(runCatching { JSONArray(json) }.getOrNull()).mapNotNull { o ->
            Choice(Gesture.of(o.optString("gesture")) ?: return@mapNotNull null, paused(o.optJSONArray("pauses")), sliders(o.optJSONArray("sliders")))
        }

        private fun objects(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        private fun strings(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
        private fun palms(a: JSONArray?) = strings(a).mapNotNull { Palm.of(it) }.toSet()
        private fun place(o: JSONObject) = (o.opt("place") as? Number)?.toInt()
        private fun palm(o: JSONObject) = if (o.isNull("palm")) null else Palm.of(o.optString("palm"))

        private fun paused(a: JSONArray?) = objects(a).mapNotNull { o ->
            Paused(Builtin.of(o.optString("control")) ?: return@mapNotNull null, Gesture.of(o.optString("gesture")) ?: return@mapNotNull null,
                place(o), palm(o), palms(o.optJSONArray("positions")))
        }

        private fun sliders(a: JSONArray?) = objects(a).mapNotNull { o ->
            PausedSlider(Builtin.of(o.optString("control")) ?: return@mapNotNull null, Gesture.of(o.optString("gesture")) ?: return@mapNotNull null,
                o.optInt("profile"), if (o.optString("finger") == "middle") Part.MIDDLE else Part.INDEX, o.optInt("pair"), palms(o.optJSONArray("positions")))
        }
    }
}
```

- [ ] **Step 3: `Teach.kt`** (pure)

```kotlin
package dev.kb896.airgestures

import org.json.JSONArray
import org.json.JSONObject

/**
 * One palm position held still, for the calibration: rolls (degrees) come in at any rate;
 * once they've stayed within [STEADY] of each other for [SETTLE] ms, [HOLD] ms of them are
 * kept. A move past [STEADY], or no reading, starts again. band-core checks the result
 * (PalmCalibration::from_rolls).
 */
class PalmSampler {
    private val recent = ArrayDeque<Pair<Long, Double>>()
    private val kept = mutableListOf<Double>()
    private var keptFrom: Long? = null

    /** 0…1: how much of [HOLD] is kept. */
    var progress = 0f
        private set
    val done get() = progress >= 1f
    val rolls: DoubleArray get() = kept.toDoubleArray()

    fun add(roll: Double?, now: Long) {
        if (done) return
        if (roll == null) return reset()
        recent.addLast(now to roll)
        while (now - recent.first().first > SETTLE) recent.removeFirst()
        if (recent.any { angle(it.second - roll) > STEADY }) {
            reset()
            recent.addLast(now to roll)
            return
        }
        val from = keptFrom ?: if (now - recent.first().first >= SETTLE * 4 / 5) now.also { keptFrom = it } else return
        kept += roll
        progress = ((now - from).toFloat() / HOLD).coerceIn(0f, 1f)
    }

    fun reset() {
        recent.clear()
        kept.clear()
        keptFrom = null
        progress = 0f
    }

    companion object {
        const val SETTLE = 500L
        const val HOLD = 2000L
        const val STEADY = 6.0

        /** How far apart two angles are (degrees), across ±180. */
        fun angle(difference: Double) = Math.abs(((difference % 360) + 540) % 360 - 180)
    }
}

/** Teach your drags: the turn and the six drags, one pinch each, in this order. */
class DragTeach {
    enum class Step(val key: String, val title: String, val how: String) {
        TURN("turn", "Turn", "Pinch and turn your wrist the way that should turn the volume up."),
        LEFT("left", "Drag left", "Pinch and move your hand to the left."),
        RIGHT("right", "Drag right", "Pinch and move your hand to the right."),
        TOWARD("toward", "Drag toward", "Pinch and pull your hand toward you."),
        AWAY("away", "Drag away", "Pinch and push your hand away from you."),
        UP("up", "Drag up", "Pinch and lift your hand."),
        DOWN("down", "Drag down", "Pinch and lower your hand."),
    }

    private val samples = arrayOfNulls<DoubleArray>(Step.entries.size)
    private var roll: Double? = null

    /** The step the next pinch is for (null: all done). */
    val current get() = Step.entries.firstOrNull { samples[it.ordinal] == null }
    fun done(step: Step) = samples[step.ordinal] != null

    /** A `@drag-sample`: its direction and the roll it was read in. */
    fun sample(direction: DoubleArray, roll: Double) {
        val step = current ?: return
        samples[step.ordinal] = direction
        this.roll = roll
    }

    fun redo(step: Step) {
        samples[step.ordinal] = null
    }

    /** For Bridge.dragPrototypes, once every step has its sample. */
    fun json(): String? {
        if (current != null) return null
        val r = roll ?: return null
        return JSONObject().put("roll", r).put("turn", JSONArray(samples[0]!!.toList()))
            .put("drags", JSONArray((1..6).map { JSONArray(samples[it]!!.toList()) })).toString()
    }

    /** The bridge's refusal: the steps to do again (already cleared) and what to say. */
    fun refused(error: String): Pair<List<Step>, String> {
        val words = error.split(' ')
        fun step(key: String?) = Step.entries.firstOrNull { it.key == key }
        val (redo, message) = when (words[0]) {
            "too-alike" -> listOfNotNull(step(words.getOrNull(1)), step(words.getOrNull(2))).let {
                it to "${it.getOrNull(0)?.title} and ${it.getOrNull(1)?.title?.lowercase()} went the same way. Do them again, moving further."
            }
            "like-the-turn" -> listOfNotNull(step(words.getOrNull(1))).let { it to "${it.firstOrNull()?.title} turned your wrist like the turn. Move your whole hand, without turning." }
            "no-turn" -> listOf(Step.TURN) to "The turn didn't move. Turn further."
            "no-drag" -> listOfNotNull(step(words.getOrNull(1))).let { it to "${it.firstOrNull()?.title} didn't move. Move further." }
            else -> Step.entries to "That didn't work. Start again."
        }
        redo.forEach(::redo)
        return redo to message
    }
}

/** Teach a drawing: [TRIES] tries, then the bridge's checks (Bridge.checkTries, Bridge.looksLike). */
class DrawTeach {
    val tries = mutableListOf<List<Stroke>>()
    val full get() = tries.size >= TRIES

    fun add(strokes: List<Stroke>) {
        if (!full) tries += strokes
    }

    fun json() = JSONArray(tries.map(DrawingLibrary::tryJson)).toString()

    /** Bridge.checkTries' answer: null when they're alike, else what to say (the tries it names go). */
    fun refused(error: String): String? = when {
        error.isEmpty() -> null
        error.startsWith("too-small") -> {
            val i = error.substringAfter(' ').toIntOrNull() ?: 0
            if (i in tries.indices) tries.removeAt(i)
            "Try ${i + 1} was too small: draw it bigger."
        }
        error.startsWith("not-alike") -> {
            tries.clear()
            "These don't look alike: try again, or draw bigger."
        }
        else -> {
            tries.clear()
            "Draw it $TRIES times."
        }
    }

    companion object {
        const val TRIES = 3

        /** A name no other drawing has ([own]: the one being taught again keeps its own). */
        fun nameFree(name: String, names: Collection<String>, own: String?): Boolean {
            val clean = name.trim()
            return clean.isNotEmpty() && (clean == own || names.none { it.equals(clean, ignoreCase = true) })
        }
    }
}
```

- [ ] **Step 4: `Bridge.kt`** — remove `profileWarnings`, and with it `ProfileActivity`'s `warnings`, `warningsCard` and `showWarnings` (the warnings come back for the whole set on Gestures, Task 10); update `actions`' doc with the new lines; add:

```kotlin
    /** What the screens show about a profiles JSON (Report): warnings, invalid, paused, paused_sliders, uses, hold_waits. */
    @JvmStatic external fun profileReport(json: String): String
    /** Where band control [control] (controls, next, previous, draw) could go with [palm] ("" any, else "up,down"), and what it would pause. */
    @JvmStatic external fun bandChoices(json: String, control: String, palm: String): String
    /** `{"down", "up"}` rolls, or `{"error"}` (empty / not-still / too-close). */
    @JvmStatic external fun palmCalibration(down: DoubleArray, up: DoubleArray): String
    /** Taught drags (`{"roll", "turn", "drags"}`) as stored prototypes, or `{"error"}`. */
    @JvmStatic external fun dragPrototypes(samples: String): String
    /** "" when the tries can teach a drawing, else `too-small <i>`, `not-alike <i> <j>`, `no-tries`. */
    @JvmStatic external fun checkTries(tries: String): String
    /** The drawing in [library] these tries look like, or "". */
    @JvmStatic external fun looksLike(library: String, tries: String): String
    /** The drawings draw sessions match against (DrawingLibrary JSON); throws on bad JSON. */
    @JvmStatic external fun setLibrary(handle: Long, library: String)
    @JvmStatic external fun startDrawing(handle: Long, now: Double)
    @JvmStatic external fun cancelDrawing(handle: Long)
    /** Teaching drawings: sessions report `@draw-finished` only. */
    @JvmStatic external fun setTeaching(handle: Long, on: Boolean)
    /** Teaching drags: pinches report `@drag-sample`, nothing acts. */
    @JvmStatic external fun setDragTeaching(handle: Long, on: Boolean)
    /** `{"palm", "roll"}` right now (nulls: no fresh reading). */
    @JvmStatic external fun palmNow(handle: Long, now: Double): String
```

- [ ] **Step 5: `BandLink.kt`** — mirror `setPractice` for the two teaching flags (kept for the next connection), and add `setLibrary(json)`, `startDrawing()`, `cancelDrawing()`, `palmNow(): JSONObject?` (all `synchronized(lock)`, no-ops while `handle == 0L`, using its private `now()`). In `openSession`, after `Bridge.setPractice`: `Bridge.setLibrary(handle, Drawings.load(context).toJson())`, `Bridge.setTeaching(handle, teaching)`, `Bridge.setDragTeaching(handle, dragTeaching)`.

- [ ] **Step 6: `BandService.kt`**

```kotlin
    /** A draw session's listener: a teach screen while one is open, else the pad over the app in front (or toasts). */
    interface Drawing {
        fun started() {}
        /** The pen went down (the next point starts its stroke; a dropped stroke's number comes again: draw it afresh) or up. */
        fun pen(down: Boolean) {}
        fun point(stroke: Int, x: Float, y: Float) {}
        /** The session's strokes (DrawingLibrary.strokes reads them). */
        fun finished(strokes: String) {}
        fun drawn(name: String) {}
        fun notDrawn() {}
        fun cancelled() {}
    }

    /** Teach your drags' listener. */
    fun interface DragTeaching {
        fun sample(finger: String, direction: DoubleArray, roll: Double)
    }

    var drawing: Drawing? = null
        private set
    private var dragTeaching: DragTeaching? = null

    /** Results without a pad: toasts (Task 16 words them with `DrawPad.result`). */
    private val toasts = object : Drawing {
        override fun drawn(name: String) = toast(name)
        override fun notDrawn() = toast("Not one of your drawings")
    }
    /** Task 16 puts the overlay pad between the two: `drawing ?: TouchService.instance?.pad ?: toasts`. */
    private val pad: Drawing get() = drawing ?: toasts
```

`onActions`' `when (kind)` gains:

```kotlin
                "draw-started" -> pad.started()
                "pen" -> pad.pen(rest == "down")
                "draw-point" -> rest.split(' ').takeIf { it.size == 3 }?.let { (s, x, y) ->
                    pad.point(s.toInt(), x.toFloat(), y.toFloat())
                }
                "draw-finished" -> pad.finished(rest)
                "drawn" -> pad.drawn(rest)
                "not-drawn" -> {
                    pad.notDrawn()
                    buzz(times = 2)
                }
                "draw-cancelled" -> pad.cancelled()
                "drag-sample" -> rest.split(' ').takeIf { it.size == 5 }?.let { w ->
                    dragTeaching?.sample(w[0], doubleArrayOf(w[1].toDouble(), w[2].toDouble(), w[3].toDouble()), w[4].toDouble())
                }
```

Also: `bandToggled(false)` and `onPhase(phase != CONNECTED)` call `pad.cancelled()` (band-core doesn't report a session ended by a disconnect). New methods:

```kotlin
    /** Teach a drawing: practice (nothing runs) and teaching (sessions report their strokes). */
    fun startTeachingDrawing(listener: Drawing) {
        drawing = listener
        startPractice(object : Practice { override fun gesture(label: String) = Unit; override fun action(id: String) = Unit })
        link.setTeaching(true)
    }
    /** One try: a draw session the screen opened. */
    fun drawTry() = link.startDrawing()
    fun endTeachingDrawing() {
        link.cancelDrawing()
        link.setTeaching(false)
        endPractice()
        drawing = null
    }
    fun startDragTeaching(listener: DragTeaching) {
        dragTeaching = listener
        link.setDragTeaching(true)
    }
    fun endDragTeaching() {
        link.setDragTeaching(false)
        dragTeaching = null
    }
    fun cancelDrawing() = link.cancelDrawing()
    fun palmNow() = link.palmNow()
    /** The drawings changed: the band matches against the new ones. */
    fun drawingsChanged() = link.setLibrary(Drawings.load(this).toJson())
```

(`toast(text)` is `Toast.makeText(this, text, Toast.LENGTH_SHORT).show()`.)

- [ ] **Step 7: `Actions.kt`** — `automation(rest)` reads `Mappings.automation(rest)`: `Step` → `dialStep(name, step)` with one `StepBatch` per name (`mutableMapOf<String, StepBatch>()`, a flush per name) and the broadcast `gesture=<name>`, `steps`; `Gesture` → `gesture=<name>` plus `palm` when set; `Drawing` → `gesture=drawing`, `name`. The automation guide (`GestureScreen.automationGuide`) adds one sentence: "An override also carries palm (down, sideways, up); the middle dial is middle_dial, a slider index_left_right and so on, and a drawing is drawing with its name."

- [ ] **Step 8: Run, install, commit**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew -q testDebugUnitTest --tests '*ReportTest*' --tests '*TeachTest*'
./build-rust.sh >/dev/null && JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew -q assembleDebug testDebugUnitTest
git add app/src/main/java/dev/kb896/airgestures/{Bridge,BandLink,BandService,Actions,GestureScreen,ProfileActivity,Report,Teach}.kt \
  app/src/test/java/dev/kb896/airgestures/{ReportTest,TeachTest}.kt
git commit -q -m "App: the band's draw and teach events, the drawings library per connection, the report for the screens

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MLDqGPYx8bTGKJcVLgoyzo"
```

From here every task builds an installable dev build (install per Task 19's checks when the owner wants to look).

---

### Task 8: UI pieces: the coral number, paused chips, row tags, picker details

**Files:** Modify `Neu.kt`, `Ui.kt`, `GestureScreen.kt`.

- [ ] **Step 1: `CountBadge`** (in `Neu.kt`) — the coral number (mockup 1):

```kotlin
/**
 * The coral number of paused actions: just the number on a STOP plate, its digits centred on
 * their own ink (Geist's digits sit high in the line box, so the font's metrics would put them
 * off centre).
 */
class CountBadge(context: Context, count: Int) : android.view.View(context) {
    private val text = count.toString()
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Cb.font(context, R.font.geist_semibold)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, context.resources.displayMetrics)
        color = Cb.Signal.STOP.label
        fontFeatureSettings = "tnum"
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Cb.Signal.STOP.fill }
    private val bounds = android.graphics.Rect().also { ink.getTextBounds(text, 0, text.length, it) }

    init {
        contentDescription = if (count == 1) "1 paused" else "$count paused"
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val size = Cb.dp(context, 20f).toInt()
        setMeasuredDimension(maxOf(size, bounds.width() + Cb.dp(context, 12f).toInt()), size)
    }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, fill)
        canvas.drawText(text, width / 2f - bounds.exactCenterX(), height / 2f - bounds.exactCenterY(), ink)
    }
}
```

`Cb.count(context, n) = CountBadge(context, n)`.

- [ ] **Step 2: `Ui.kt`**
- `navRow(…, count: Int = 0, …)`: when `count > 0`, `addView(Cb.count(context, count))` before the chevron (after `detail`).
- `Option(…, val detail: String? = null)`: `choose` shows it right-aligned before the check (`Cb.text(context, 13f, color = Cb.INK_500)`).
- `choose(…, note: String? = null)`: a `Cb.text(13f, INK_500)` under the list (mockup 11's note).
- `fun pausedChip() = Cb.chip(this, Cb.Signal.STOP, "Paused")` and `fun willPauseChip() = Cb.chip(this, Cb.Signal.STOP, "Will pause")`.
- `fun banner(chip: View, text: String, link: String?, onLink: () -> Unit)`: a hairline card (`Cb.hairlineCard`), row of the chip and a 14sp `INK_700` text (weighted), then the link pill under it (mockup 6).

- [ ] **Step 3: `GestureScreen.kt`**
- Move `stepLabel` into a top-level `object Labels { fun action(context: Context, id: String): String }` (same body; `appName` from `context.packageManager`), so the pad (a service) can name actions; `stepLabel` calls it.
- `gestureRow(iconRes, gesture, action, unset, paused: Boolean = false, tags: List<Pair<String, Boolean>> = emptyList(), hint: String? = null, info, onClick)`: the right side becomes a column, end-aligned: a row of (`pausedChip()` when `paused`, the action text), then each tag (12sp `INK_500`; a paused tag gets a small `pausedChip()` before it), then the `hint` (12sp `INK_400`). The chevron is always shown; the lock icon goes (mockup 5: no lock). `onClick` becomes non-null.

- [ ] **Step 4: Build, look, commit**

```bash
./build-rust.sh >/dev/null && JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew -q assembleDebug testDebugUnitTest
git add app/src/main/java/dev/kb896/airgestures/{Neu,Ui,GestureScreen}.kt
git commit -q -m "UI: the coral number, Paused and Will pause chips, row tags and picker details

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MLDqGPYx8bTGKJcVLgoyzo"
```

---

### Task 9: The band control editor, its gesture picker and the conflict prompt (mockups 12, 2, 3)

**Files:** Create `BandControlActivity.kt`; modify `AndroidManifest.xml`.

- [ ] **Step 1: Layout** (`BandControlActivity : GestureScreen`, extra `"control"` = `Builtin.key`, title = `builtin.label`; `companion fun open(context, builtin)` as `ProfileActivity.open`; `render()` re-reads `Profiles.band` and `Report.of(this)`):
1. Card (no title): a row "Gesture" → the current `at?.gesture?.label ?: "Nothing"` → chevron; tap opens the picker (Step 2).
2. Card "Palm position" (shown when a gesture is set): `segmented(segment("Any position"), segment("Only with the palm…"))`; in "Only", a row of three toggle pills Down / Sideways / Up (latched = selected). Rules:
   - 1–2 positions; a third tap shows "Up to two: with all three, pick Any position." under the chips and changes nothing (judgement 5);
   - a chip whose position another band control holds on this gesture is disabled, with "Palm up is Next profile's here." (judgement 4);
   - switching to "Only" starts with Palm up selected when free, else the first free one.
3. A note: "In the other positions, <gesture> does what each profile says." (only when "Only").
4. The coral count line when the current placement pauses something: `row(Cb.count(n), text "<at.label> is used in <n> place(s): they're paused")`, from `report.count(builtin)`.
5. Draw only: when `report.invalid` has `draw-cant-cancel`, the note "Draw on a hold, a triple tap or a drag can't cancel its drawing: it ends by itself, or with the pad's close button."; `report.warnings` `draw-on-tap` shows its warning text here too.

- [ ] **Step 2: The picker (mockup 2)**

```kotlin
    private fun pickGesture() {
        val palm = at?.palms?.let { p -> Palm.entries.filter { it in p }.joinToString(",") { it.key } }.orEmpty()
        val choices = Report.choices(Bridge.bandChoices(Prefs(this).profilesJson(), builtin.key, palm))
        val options = listOf(Option("Nothing", at == null, R.drawable.ic_minus, detail = if (at == null) "Now" else null)) +
            choices.map { c ->
                val now = c.gesture == at?.gesture
                Option(c.gesture.label, now, R.drawable.ic_hand, detail = when {
                    now -> "Now"
                    c.count == 0 -> "free"
                    else -> "used in ${c.count}"
                })
            }
        choose(builtin.label, options, heading = "Band control") { index ->
            if (index == 0) return@choose save(null)
            val choice = choices[index - 1]
            val control = BandControl(choice.gesture, at?.palms)
            if (choice.count == 0 || choice.gesture == at?.gesture) save(control) else prompt(choice, control)
        }
    }
```

(Gestures the other band controls hold in these positions aren't in `choices`: the bridge leaves them out.)

- [ ] **Step 3: The prompt (mockup 3)** — also used when a palm-set change pauses more than before:

```kotlin
    private fun prompt(choice: Report.Choice, control: BandControl) {
        val list = Profiles.load(this)
        val body = column(gap = 10f).apply {
            for (p in choice.pauses) {
                val profile = p.place?.let { list.profiles.getOrNull(it) }
                val place = profile?.name ?: "During calls"
                val id = when {
                    profile == null -> Prefs(context).callActionId(p.gesture)
                    p.palm == null -> profile.map[p.gesture.key] ?: p.gesture.default.id
                    else -> profile.palm[p.gesture.key]?.get(p.palm.key).orEmpty()
                }
                val where = p.palm?.let { " · ${it.label.lowercase()}" }.orEmpty()
                addView(row(gap = 10f).apply {
                    addView(column(gap = 2f).apply {
                        addView(Cb.text(context, 14f, R.font.geist_medium, Cb.INK_900).apply { text = place + where })
                        addView(Cb.text(context, 13f, color = Cb.INK_500).apply { text = label(id, calls = profile == null) })
                    }, weighted())
                    addView(willPauseChip())
                })
            }
            for (s in choice.sliders) { /* "<profile> · <Index|Middle> <pair> slider", its dial label, Will pause */ }
            addView(Cb.text(context, 13f, color = Cb.INK_500).apply {
                text = "They stay saved and come back if you move ${builtin.label} to another gesture."
            })
        }
        val n = choice.count
        sheet("${control.label} is used in $n ${if (n == 1) "place" else "places"}", body, "Use it anyway") {
            save(control)
            true
        }
    }

    private fun save(control: BandControl?) {
        Profiles.updateBand(applicationContext) { it.with(builtin, control) }
        BandService.instance?.remap()
        render()
    }
```

The palm-set change (Step 1.2): compute `Report.choices(Bridge.bandChoices(json, builtin.key, newPalm))` for the current gesture; when its `count` is larger than `report.count(builtin)`, `prompt(...)` before saving; else save.

- [ ] **Step 4: Build, commit**

```bash
./build-rust.sh >/dev/null && JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew -q assembleDebug testDebugUnitTest
git add app/src/main/java/dev/kb896/airgestures/BandControlActivity.kt app/src/main/AndroidManifest.xml
git commit -q -m "Band controls: pick a gesture (used in N / free / Now), limit it to palm positions, ask before pausing

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MLDqGPYx8bTGKJcVLgoyzo"
```

---

### Task 10: The Gestures screen (mockup 1) and During calls (mockup 5)

**Files:** Modify `GesturesActivity.kt`, `GestureScreen.kt` (`callRows`), `ProfileActivity.kt` (the warnings card goes), `AndroidManifest.xml`; create `CallsActivity.kt`.

- [ ] **Step 1: `GesturesActivity`** — the page becomes, top to bottom:
1. **Warnings card** (hidden when empty), from `report.warnings`, one row each (the icon row `ProfileActivity.showWarnings` builds today, moved here):
   - `no-controls`: "No gesture turns the controls on and off. You can still do it from the app."
   - `no-way-out`: "No gesture switches profiles. Use the app or the widget to switch."
   - `draw-on-tap`: "Draw is on a single tap: while drawing, a short pinch is that tap, and it cancels the drawing."
2. **Band controls card**: a header row (title "Band controls", 17sp semibold, then "All profiles" 13sp `INK_500` on the right), then one `navRow` per `Builtin.entries`:
   ```kotlin
           for (builtin in Builtin.entries) {
               val at = band[builtin]
               addView(navRow(builtin.icon, builtin.label, detail = at?.label ?: "Nothing", count = report.count(builtin)) {
                   BandControlActivity.open(context, builtin)
               })
           }
   ```
3. **Profiles card** — each profile's `navRow` gets `count = report.count(index)`. The text under it: "Next profile and Previous profile are band controls (above): by default an index and a middle triple tap. A tap waits a moment when its triple tap does something, to tell them apart." (one profile: "Add a profile for another set of gestures; switch with Next profile and Previous profile, or give apps their own.").
4. **Drawings card** — added in Task 17.
5. **Sequences card** — unchanged.
6. **During calls card** — the explanation text, then `navRow(R.drawable.ic_phone, "During calls", detail = "<n> set" or "As usual", count = report.count(null as Int?))` → `CallsActivity`.

`render()` computes `val report = Report.of(this)` and `val band = Profiles.band(this)` once. Moving a band control and coming back simply re-renders (mockup 7: no toast).

- [ ] **Step 2: `CallsActivity`** (extends `GestureScreen`, title "During calls"): the explanation text, then `callRows(report)` over `Gesture.entries.filter { !it.drag }` (Task 11 groups them like a profile); each row `paused = report.paused(null, gesture, null) != null`; the band-control note under the card (`bandNote`, Step 3); a tap opens `pick(gesture, null)` as today (no palm overrides in calls). Register it in the manifest (`android:exported="false"`).

- [ ] **Step 3: The band-control note** (in `GestureScreen`; Task 11 and `CallsActivity` show it under their rows):

```kotlin
    /** "Middle hold is Controls on/off; …": the gestures that are band controls now. */
    fun bandNote(band: BandControls): String? {
        val on = Builtin.entries.mapNotNull { b -> band[b]?.let { "${it.label} is ${b.label}" } }
        if (on.isEmpty()) return null
        return on.joinToString("; ", postfix = ".") + " Band controls work in every profile; what you set on those gestures here waits until they move."
    }
```

- [ ] **Step 4: Build, screenshot, commit**

```bash
./build-rust.sh >/dev/null && JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew -q assembleDebug testDebugUnitTest
git add app/src/main/java/dev/kb896/airgestures/{GesturesActivity,GestureScreen,CallsActivity}.kt app/src/main/AndroidManifest.xml
git commit -q -m "Gestures: Band controls above the profiles, coral counts of paused actions, warnings for the whole set; During calls on its own screen

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MLDqGPYx8bTGKJcVLgoyzo"
```

---

### Task 11: The profile screen (mockups 5, 7, 9)

**Files:** Modify `ProfileActivity.kt`, `GestureScreen.kt` (`profileRows`, `bandNote`, `callRows` grouping).

- [ ] **Step 1: Grouped rows** — `profileRows(profileId, report)` replaces the flat list:

```kotlin
    fun profileRows(profileId: String, report: Report) = column(gap = 14f).apply {
        val list = Profiles.load(context)
        val index = list.profiles.indexOfFirst { it.id == profileId }
        val profile = list.profiles.getOrNull(index) ?: return@apply
        for (part in Part.entries) {
            addView(Cb.label(context, part.label))
            addView(column(gap = 4f).apply {
                for (gesture in Gesture.entries.filter { it.part == part && !it.drag }) addView(profileRow(profile, index, gesture, report))
            })
        }
    }

    private fun profileRow(profile: Profile, index: Int, gesture: Gesture, report: Report): View {
        val id = profile.map[gesture.key] ?: gesture.default.id
        val action = Action.of(id)
        val tags = Palm.entries.mapNotNull { palm ->
            profile.palm[gesture.key]?.get(palm.key)?.let { "${palm.label} · ${label(it, calls = false)}" to (report.paused(index, gesture, palm) != null) }
        }
        val hold = gesture == Gesture.INDEX_HOLD || gesture == Gesture.MIDDLE_HOLD
        val waits = if (hold) report.holdWaits(index, gesture.part) else emptySet()
        val hint = when {
            waits.isEmpty() -> null
            waits.size == Palm.entries.size -> "when you let go"
            else -> "when you let go · " + Palm.entries.filter { it in waits }.joinToString(" or ") { it.label.lowercase() }
        }
        val guide = if (action == Action.AUTOMATION) ({ automationGuide() }) else null
        return gestureRow(action.icon, gesture.short, label(id, calls = false), action == Action.NONE,
            paused = report.paused(index, gesture, null) != null, tags = tags, hint = hint, info = guide) {
            // Task 12 opens the gesture editor here instead.
            pick(gesture, profile.id)
        }
    }
```

A band-control gesture with no action shows "Nothing" with no chip (nothing is paused). Nothing is greyed or locked.

- [ ] **Step 2: The band-control note** under the gestures card: `bandNote(Profiles.band(this))` (Task 10). `CallsActivity` groups its rows the same way (`Part` labels, `!it.drag`).

- [ ] **Step 3: The Dials card** (mockup 9: "Dials · pinch and turn"): rows Index dial (`DialChoice.of(profile.dial)`) and Middle dial (`DialChoice.of(profile.middleDial)`); both open `pickDial(profile, part)` (the existing picker with a `part` parameter; Task 13 adds the middle dial's note and the drag rows).

- [ ] **Step 4: `ProfileActivity.render()`** — `val report = Report.of(this)`; cards: gestures (`profileRows` + `bandNote` text), Dials, Apps, Delete (Task 17 adds a Drawings row). `onResume` re-renders, so a band control moved elsewhere shows its paused actions as normal again (mockup 7).

- [ ] **Step 5: Build, commit**

```bash
./build-rust.sh >/dev/null && JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew -q assembleDebug testDebugUnitTest
git add app/src/main/java/dev/kb896/airgestures/{ProfileActivity,GestureScreen,CallsActivity}.kt
git commit -q -m "Profile: thumb, index and middle groups with holds, Paused chips, palm tags, the band-control note, the Dials card

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MLDqGPYx8bTGKJcVLgoyzo"
```

---

### Task 12: Editing a gesture: palm overrides and the Paused banner (mockups 10, 6)

**Files:** Create `GestureActivity.kt`; modify `AndroidManifest.xml`.

- [ ] **Step 1: Layout** (`GestureActivity : GestureScreen`, extras `"profile"`, `"gesture"`; title = `gesture.label`; subtitle label = the profile's name):
1. **Paused banner** for each band control on this gesture (`band.onGesture(gesture)`), mockup 6:
   ```kotlin
           for (b in band.onGesture(gesture)) {
               val c = band[b]!!
               val where = c.palms?.let { p -> " with the ${Palm.entries.filter { it in p }.joinToString(" or ") { it.label.lowercase() }}" } ?: ""
               body.addView(banner(pausedChip(),
                   "${b.label} is on ${gesture.label.lowercase()}$where, in every profile. What you pick here is kept and runs once it moves.",
                   "Band controls") { BandControlActivity.open(this, b) })
           }
   ```
2. **The strip** (mockup 10): a row of three equal tiles (weighted), one per `Palm.entries`. A tile shows `Cb.label(palm.label)`, the action in that position (`override ?: normal`, via `label(id, false)`), and a `pausedChip()` when `report.paused(index, gesture, palm-or-null)` covers that position. An override's tile is dark: `GradientDrawable` `INK_900` fill, 12 dp corners, `PAGE` text, `INK_300` label; a normal tile is `Cb.hairlineCard(context, 12f)`. Tapping a dark tile edits that override; tapping a normal one adds an override there (when fewer than two exist), else edits the normal action.
3. **Normal action card**: one `gestureRow` (the action, its Paused chip) → `pickAction(gesture.label, normalId) { id -> BandService.setAction(applicationContext, profileId, gesture, id); render() }`. Any action can be picked, paused or not (mockup 6).
4. **"Palm overrides · up to 2" card**: a row per override (position, action label, Paused chip if paused, a "Remove" pill → `withoutOverride`), then "Add an override" (pill; hidden at two) → `choose("Palm position", <positions without an override>)` → `pickAction` → `withOverride`. Under it: "One position always keeps the normal action." Each change: `Profiles.update` + `BandService.instance?.remap()` + `render()`.
5. **Calibration line**: added in Task 14 (it opens the calibration screen).
6. **Hold hint**: for a hold whose `report.holdWaits(index, part)` isn't empty (naming the positions when not all three): "Acts when you let go: this finger's dial or drags do something too."

- [ ] **Step 2: Wire it** — `profileRow` (Task 11) opens `GestureActivity.open(context, profileId, gesture)` instead of the picker (the Drags screen's rows do the same in Task 13). Register the activity.

- [ ] **Step 3: Build, commit**

```bash
./build-rust.sh >/dev/null && JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew -q assembleDebug testDebugUnitTest
git add app/src/main/java/dev/kb896/airgestures/{GestureActivity,GestureScreen}.kt app/src/main/AndroidManifest.xml
git commit -q -m "Gesture editor: the palm strip, the normal action, up to two palm overrides, the Paused banner

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MLDqGPYx8bTGKJcVLgoyzo"
```

---

### Task 13: Dials and drags (mockups 11, 13b)

**Files:** Create `DragsActivity.kt`; modify `GestureScreen.kt` (`pickDial`), `ProfileActivity.kt` (the drag rows), `BandService.kt` (companion `setMiddleDial`, `setDrags` like `setDial`), `AndroidManifest.xml`.

- [ ] **Step 1: The dial pickers** — `pickDial(profile, part)`:
  ```kotlin
      private fun pickDial(profile: String, part: Part) {
          val current = DialChoice.of(Profiles.load(this).profile(profile)?.dialOf(part))
          val note = if (part == Part.MIDDLE) {
              if (current != DialChoice.NONE) "Middle hold now acts when you let go." else "With a dial, the middle hold acts when you let go."
          } else null
          val title = if (part == Part.MIDDLE) "Middle dial" else "Index dial"
          choose(title, DialChoice.entries.map { Option(it.label, it == current, it.icon) }, note = note) { index ->
              val choice = DialChoice.entries[index]
              if (part == Part.MIDDLE) BandService.setMiddleDial(applicationContext, profile, choice) else BandService.setDial(applicationContext, profile, choice)
              render()
              if (choice == DialChoice.AUTOMATION) automationGuide(firstTime = true)
          }
      }
  ```
  The order is `DialChoice.entries`' (Volume, Playback position, Brightness, Navigate, Send to automation, Nothing); mockup 11 puts Nothing first: build the options as `listOf(DialChoice.NONE) + DialChoice.entries.filter { it != DialChoice.NONE }` and index that list.

- [ ] **Step 2: `DragsActivity`** (extras `"profile"`, `"part"`; title "Index drags" / "Middle drags"):
1. `segmented(segment("Gestures"), segment("Sliders"))`, the active one ink-filled as `MainActivity`'s wrist control does; switching stores `Drags(sliders = …, targets kept)` via `Profiles.update { it.withDrags(id, part, drags) }` + remap.
2. **Gestures**: six `gestureRow`s (`Gesture.drags(part)`, `short` labels) with palm tags and Paused chips from the report (as `profileRow`) → `GestureActivity`.
3. **Sliders**: three rows:
   | Row | Detail | |
   |---|---|---|
   | Left/right | right = forward | `targets[0]` |
   | Toward/away | away = up | `targets[1]` |
   | Up/down | up = up | `targets[2]` |
   Each shows `DialChoice.of(target).label` and, when `report.pausedSlider(index, part, pair)` exists, a Paused chip and the tag "<band control label> is on a drag of this pair"; tap → `choose("Left/right", DialChoice options)` → store the target.
4. A note: "Drags read the same with the palm down, up or sideways." (Task 15 adds "Mixed up? Teach your drags".)
5. **The Dials card** (Task 11) gains "Index drags" and "Middle drags" rows (detail "Gestures" / "Sliders", `count` = that finger's paused sliders in `report.pausedSliders`) → `DragsActivity.open(this, profile.id, part)`.

- [ ] **Step 3: Build, commit**

```bash
./build-rust.sh >/dev/null && JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew -q assembleDebug testDebugUnitTest
git add app/src/main/java/dev/kb896/airgestures/{DragsActivity,GestureScreen,ProfileActivity,BandService}.kt app/src/main/AndroidManifest.xml
git commit -q -m "Dials and drags: the middle dial, each finger's drags as gestures or sliders

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MLDqGPYx8bTGKJcVLgoyzo"
```

---

### Task 14: Palm calibration (mockup 13); switching the hand asks again

**Files:** Create `PalmCalibrationActivity.kt`; modify `MainActivity.kt` (`setHand`), `GestureActivity.kt` (its calibration line), `AndroidManifest.xml`.

- [ ] **Step 1: Layout** (title "Palm positions"):
1. **Wrist**: `Prefs.handInUse()`; when null (hand "band", the band hasn't said), a segmented Left / Right asks first (judgement 15). No `prefs.bandAddress`: "Connect the band first: positions are measured per band." and nothing else.
2. **The ring**: a custom view, 120 dp, a 6 dp `HAIRLINE` track and an `INK_900` arc (round caps) for `sampler.progress`, the step number in the middle ("1/2"):
   ```kotlin
   private class Ring(context: Context) : android.view.View(context) {
       var progress = 0f
           set(value) { field = value; invalidate() }
       var label = ""
       private val stroke = Cb.dp(context, 6f)
       private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = Cb.HAIRLINE }
       private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND; color = Cb.INK_900 }
       private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Cb.font(context, R.font.geist_semibold); textSize = Cb.dp(context, 20f); color = Cb.INK_900; textAlign = Paint.Align.CENTER }
       override fun onDraw(canvas: Canvas) {
           val box = android.graphics.RectF(stroke, stroke, width - stroke, height - stroke)
           canvas.drawOval(box, track)
           canvas.drawArc(box, -90f, 360f * progress, false, arc)
           canvas.drawText(label, width / 2f, height / 2f - (text.ascent() + text.descent()) / 2f, text)
       }
   }
   ```
3. **Steps**: "1 Palm down" and "2 Palm up", each with its state on the right: "Done" + check, "holding…" (the current one, while `progress > 0`), "Hold still" (current, not settled), "Next".
4. **Right now**: "Right now: Palm down · −141°" from `BandService.instance?.palmNow()` (`palm` → `Palm.of(...).label`, `roll` rounded; no reading: "Right now: no reading (is the band connected?)").
5. Pills: "Start again"; when saved: "Done" (finish). Under: "Saved for this band and your <left|right> wrist."

- [ ] **Step 2: Logic** — a `Handler` posts every 50 ms while resumed:
  ```kotlin
      private fun tick() {
          val now = BandService.instance?.palmNow()
          val roll = now?.takeUnless { it.isNull("roll") }?.optDouble("roll")
          sampler.add(roll, SystemClock.uptimeMillis())
          ring.progress = sampler.progress
          if (sampler.done) next()
          render()
      }

      private fun next() {
          if (step == Palm.DOWN) {
              down = sampler.rolls
              step = Palm.UP
              sampler.reset()
              return
          }
          val result = JSONObject(Bridge.palmCalibration(down, sampler.rolls))
          when (val error = result.optString("error")) {
              "" -> {
                  Calibrations.update(this, address, hand) { it.copy(palm = result.getDouble("down") to result.getDouble("up")) }
                  BandService.instance?.remap()
                  saved = true
              }
              else -> {
                  message = when {
                      error.startsWith("not-still") -> "Your hand moved. Rest your elbow and try again."
                      error == "too-close" -> "Palm down and palm up came out too close. Turn your hand all the way over."
                      else -> "The band sent no position. Is it connected?"
                  }
                  step = Palm.DOWN
                  sampler.reset()
              }
          }
      }
  ```
- [ ] **Step 3: Switching the hand asks again** — in `MainActivity.setHand(hand)`, after setting it: if `Calibrations.get(this, prefs.bandAddress, hand).palm == null`, `confirm("Calibrate your $hand wrist?", "Palm positions are measured per band and wrist. Until you do, ${if (hand == "left") "every gesture on the left wrist counts as palm down" else "the right wrist uses positions measured on another hand"}.", "Calibrate") { open<PalmCalibrationActivity>() }` (judgement 16).

- [ ] **Step 4: The gesture editor's calibration line** (`GestureActivity`, item 5): when `Prefs.handInUse() == "left"` and `Calibrations.get(this, prefs.bandAddress, "left").palm == null`: "Your left wrist isn't calibrated: every gesture counts as palm down until you calibrate." + pill "Calibrate"; otherwise a quiet row "Palm positions feel off? Calibrate". Both open `PalmCalibrationActivity`.

- [ ] **Step 5: Build, commit**

```bash
./build-rust.sh >/dev/null && JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew -q assembleDebug testDebugUnitTest
git add app/src/main/java/dev/kb896/airgestures/{PalmCalibrationActivity,MainActivity,GestureActivity}.kt app/src/main/AndroidManifest.xml
git commit -q -m "Palm calibration: palm down then palm up held still, saved per band and wrist; a new wrist asks for it

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MLDqGPYx8bTGKJcVLgoyzo"
```

---

### Task 15: Teach your drags

**Files:** Create `TeachDragsActivity.kt`; modify `AndroidManifest.xml`; entries from `DragsActivity` and `PalmCalibrationActivity` (a quiet row "Teach your drags").

- [ ] **Step 1: Layout** (title "Teach your drags"): the wrist line as in Task 14; a card listing `DragTeach.Step.entries`, each row with its title, `how` under it for the current step, and on the right "Done" + a "Redo" pill (done), "Now" (current) or nothing; a message line for refusals; when all are saved, "Saved for this band and your <wrist> wrist." and pills "Done" and "Use the measured ones" (clears `drags`: `Calibrations.update { it.copy(drags = null) }` + remap).
- [ ] **Step 2: Logic**

```kotlin
    override fun onResume() {
        super.onResume()
        BandService.instance?.startDragTeaching { _, direction, roll ->
            teach.sample(direction, roll)
            val json = teach.json()
            if (json != null) check(json)
            render()
        }
    }

    override fun onPause() {
        super.onPause()
        BandService.instance?.endDragTeaching()
    }

    private fun check(samples: String) {
        val result = JSONObject(Bridge.dragPrototypes(samples))
        val error = result.optString("error")
        if (error.isEmpty()) {
            Calibrations.update(this, address, hand) { it.copy(drags = result.toString()) }
            BandService.instance?.remap()
            saved = true
        } else {
            message = teach.refused(error).second
        }
    }
```

(The listener runs on the main thread: `BandService.onActions` posts there.)

- [ ] **Step 3: Build, commit**

```bash
./build-rust.sh >/dev/null && JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew -q assembleDebug testDebugUnitTest
git add app/src/main/java/dev/kb896/airgestures/{TeachDragsActivity,DragsActivity,PalmCalibrationActivity}.kt app/src/main/AndroidManifest.xml
git commit -q -m "Teach your drags: the turn and six drags, one pinch each, saved per band and wrist

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MLDqGPYx8bTGKJcVLgoyzo"
```

---

### Task 16: The draw pad over the app in front (mockup 14)

**Files:** Create `DrawPad.kt`; modify `TouchService.kt` (`val pad by lazy { DrawPad(this) }`, `pad.hide()` in `onDestroy`/`onUnbind`), `BandService.kt` (`pad` becomes `drawing ?: TouchService.instance?.pad ?: toasts`; `toasts` uses `DrawPad.result`).

- [ ] **Step 1: `StrokesView`** (in `DrawPad.kt`; also the thumbnails of Task 17): draws strokes scaled to fit, one scale for both axes, y up, centred, `margin` around:

```kotlin
/** Strokes in degrees (x right, y up), fitted into the view. */
class StrokesView(context: Context, private val ink: Int, private val widthDp: Float = 3f) : android.view.View(context) {
    var strokes: List<List<Pair<Float, Float>>> = emptyList()
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = Cb.dp(context, widthDp); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = ink
    }

    override fun onDraw(canvas: Canvas) {
        val points = strokes.flatten()
        if (points.isEmpty()) return
        val minX = points.minOf { it.first }; val maxX = points.maxOf { it.first }
        val minY = points.minOf { it.second }; val maxY = points.maxOf { it.second }
        val margin = Cb.dp(context, 12f)
        // A tiny drawing isn't blown up: at least 10° across.
        val span = maxOf(maxX - minX, maxY - minY, 10f)
        val scale = minOf(width - 2 * margin, height - 2 * margin) / span
        val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2
        for (stroke in strokes) {
            val path = android.graphics.Path()
            stroke.forEachIndexed { i, (x, y) ->
                val px = width / 2f + (x - cx) * scale
                val py = height / 2f - (y - cy) * scale
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            canvas.drawPath(path, paint)
        }
    }
}
```

- [ ] **Step 2: `DrawPad`** — `class DrawPad(private val service: AccessibilityService) : BandService.Drawing`, built like `ChoicePopup` (a `TYPE_ACCESSIBILITY_OVERLAY` window, `FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL`, centred, 300 × 340 dp):
  - **View**: a dark card (`INK_900`, 20 dp corners, elevation 24 dp): a header row (`Cb.label` "DRAW" in `INK_300`, a "Pen down" indicator — an 8 dp dot `Signal.LIVE.fill` + 12sp text, dimmed to `INK_500` when the pen is up — and a close button `ic_x` tinted `INK_300` → `BandService.instance?.cancelDrawing()`), the `StrokesView(ink = Cb.PAGE)` filling the middle (hint "Pinch and draw" in `INK_500` while empty), and a result line (15sp `geist_medium`, `PAGE`).
  - `started()`: clear, show.
  - `pen(down)`: the indicator (lit / dimmed); `down` also marks that the next point starts a stroke (judgements 10, 14).
  - `point(stroke, x, y)`: the first point after `pen(true)` replaces stroke `stroke` with `[(x, y)]` when it exists (a dropped stroke drawn afresh), or starts it; any other point is appended to stroke `stroke`.
  - `finished(_)`: keep the strokes; wait for the result.
  - `drawn(name)`: result `DrawPad.result(service, name)`, hide after 1500 ms.
  - `notDrawn()`: "Not one of your drawings", hide after 1500 ms (BandService buzzes twice).
  - `cancelled()`: hide now.
  - `companion fun result(context: Context, name: String)`: `"$name → ${Labels.action(context, Drawings.action(context, Profiles.active(context), name))}"` (an empty action is "Nothing").

- [ ] **Step 3: Build, commit**

```bash
./build-rust.sh >/dev/null && JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew -q assembleDebug testDebugUnitTest
git add app/src/main/java/dev/kb896/airgestures/{DrawPad,TouchService,BandService}.kt
git commit -q -m "Draw pad: live strokes over the app in front, pen down, the drawing and what it ran, or two buzzes

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MLDqGPYx8bTGKJcVLgoyzo"
```

---

### Task 17: Drawings and teaching one (mockups 15, 16)

**Files:** Create `DrawingsActivity.kt`, `TeachDrawingActivity.kt`; modify `GesturesActivity.kt` (a Drawings card: `navRow(R.drawable.ic_pencil, "Drawings", detail = "<n> drawings" or "None yet")`, between Profiles and Sequences) and `ProfileActivity.kt` (a Drawings row, "<n> of its own" → `DrawingsActivity.open(this, profileId)`), `AndroidManifest.xml`.

- [ ] **Step 1: `DrawingsActivity`** (extra `"profile"` optional; title "Drawings"):
1. **Draw card**: `navRow(Builtin.DRAW.icon, "Draw", detail = band[Builtin.DRAW]?.label ?: "Nothing", count = report.count(Builtin.DRAW))` → `BandControlActivity(DRAW)`; when Draw has no gesture: "Put Draw on a gesture, then pinch and draw in the air or on a table."
2. **All profiles card**: each drawing in `Drawings.load(this)`, as a row: a 40 dp `StrokesView(ink = INK_900, widthDp = 2f)` of its first try, the name, the shared action label (`Profiles.sharedDrawings[name]` → `Labels.action`, "" = "Nothing"). Tap → `choose(name, ["Action: <label>", "Teach it again", "Rename", "Delete"])`: Action → `pickAction` → `Profiles.updateSharedDrawings`; Teach again → `TeachDrawingActivity.open(this, name, null)`; Rename → `ask` (refused when `!DrawTeach.nameFree`) → `Drawings.update { it.renamed }`, shared map key, `Profiles.update { it.renamedDrawing(old, new) }`; Delete → `confirm` → all three with `null`. After any change: `BandService.instance?.drawingsChanged()` and `remap()`.
3. **"<Profile>'s own" card** per profile (only the extra's profile when given; else each profile with own actions): rows as above with that profile's action, and a grey "replaces shared" tag when the drawing also has a shared action. Tap → Action (`withDrawing(id, name, action)`; "Use the shared one" removes it) / Teach again / Remove from <Profile>.
4. Pills: "Teach a drawing" (→ `TeachDrawingActivity.open(this, null, null)`) and, in a profile's card, "Teach one for <Profile>" (→ `open(this, null, profileId)`).

- [ ] **Step 2: `TeachDrawingActivity`** (extends `GestureScreen` for `pickAction`; extras `"name"` = teaching an existing drawing again, `"profile"` = Where defaults to that profile):
1. **Tries**: three square tiles in a row (hairline, 96 dp). Done tries show their `StrokesView`; the current one draws live from `pen(...)` / `point(...)` (the pad's rule); the next ones show "Try 2" / "Try 3" (12sp `INK_400`).
2. Under them: the message line (refusals) or, once three are alike and don't look like another, an **Alike** chip (`Cb.chip(context, Cb.Signal.LIVE, "Alike")`).
3. **Name** row → `ask("Name", name, "Save")`; **Action** row → `pickAction`; **Where**: `segmented("All profiles", "Only in <profile>")` (the extra's profile, else the picked one).
4. **Save** pill, enabled after the third alike try with a free name (`DrawTeach.nameFree(name, library.names, own = extra name)`) → `Drawings.update { it.with(Drawing(name, teach.tries)) }`, then either `Profiles.updateSharedDrawings { it + (name to action) }` or `Profiles.update { it.withDrawing(profileId, name, action) }`, then `drawingsChanged()` + `remap()` + finish.
5. **Flow** (`BandService.Drawing`):
   ```kotlin
       override fun onResume() {
           super.onResume()
           BandService.instance?.startTeachingDrawing(this)
           BandService.instance?.drawTry()
       }

       override fun onPause() {
           super.onPause()
           BandService.instance?.endTeachingDrawing()
       }

       override fun finished(strokes: String) {
           teach.add(DrawingLibrary.strokes(strokes))
           live = emptyList()
           if (!teach.full) return main.postDelayed({ BandService.instance?.drawTry() }, 600).let { render() }
           message = teach.refused(Bridge.checkTries(teach.json()))
               ?: Bridge.looksLike(Drawings.load(this).except(editing).toJson(), teach.json()).takeIf { it.isNotEmpty() }?.let {
                   teach.tries.clear()
                   "Looks like $it: draw something else, or teach $it again."
               }
           alike = message == null
           if (!teach.full) main.postDelayed({ BandService.instance?.drawTry() }, 600)
           render()
       }

       override fun cancelled() {
           // 10 s without a stroke: wait for the user.
           message = "Draw when you're ready."
           render()
       }
   ```
   plus a "Start again" pill (clears `teach`, `drawTry()`) and, after `cancelled()`, "Draw" (→ `drawTry()`).

- [ ] **Step 3: Build, commit**

```bash
./build-rust.sh >/dev/null && JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew -q assembleDebug testDebugUnitTest
git add app/src/main/java/dev/kb896/airgestures/{DrawingsActivity,TeachDrawingActivity,GesturesActivity,ProfileActivity}.kt app/src/main/AndroidManifest.xml
git commit -q -m "Drawings: shared and per-profile actions, teach one in three tries, rename, teach again, delete

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MLDqGPYx8bTGKJcVLgoyzo"
```

---

### Task 18: Lessons for the new gestures (practice)

**Files:** Modify `Lessons.kt`, `PracticeActivity.kt`; test `LessonsBackupTest.kt`.

- [ ] **Step 1: Failing test** — a profile with a middle dial gets a "Pinch and turn with your middle finger" step; a drag bound in gestures mode gets "Pinch and drag": `Lessons.steps(1, dial = true, map = defaults + ("index_drag_left" to "media.next"), band = BandControls.standard(), middleDial = true)` contains `Done.Gesture("index drag left")` and a step whose `done` is `Done.MiddleDial`.
- [ ] **Step 2: Implement** — `steps(..., middleDial: Boolean = false)`; `Done.MiddleDial` matches any of the middle dial's actions (as `Done.Dial` does with the index dial's; `PracticeActivity.heard` passes both sets); drag steps for each drag gesture with an action in the active profile ("Pinch and move your hand left", from `DragTeach.Step`'s wording). `PracticeActivity` passes `middleDial = DialChoice.of(active.middleDial) != DialChoice.NONE`.
- [ ] **Step 3: Run, commit**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew -q assembleDebug testDebugUnitTest
git add app/src/main/java/dev/kb896/airgestures/{Lessons,PracticeActivity}.kt app/src/test/java/dev/kb896/airgestures/LessonsBackupTest.kt
git commit -q -m "Practice: the index hold, the middle dial and bound drags

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MLDqGPYx8bTGKJcVLgoyzo"
```

---

### Task 19: Changelog, install, and the phone check list (the owner)

- [ ] **Step 1: `CHANGELOG.md`** — add to the unreleased `## 0.7.8` section (the owner picks the version):
- **Band controls:** Controls on/off, Next profile, Previous profile and the new Draw sit above the profiles, one gesture each (by default the middle hold, the index and middle triple taps; Draw on nothing), the same in every profile, in apps' own profiles and during calls. Each can be limited to palm positions. A profile's action on a band-control gesture is kept and shown as paused, and runs again once the band control moves; picking a used gesture says what will pause.
- **The pinch:** index and middle each have a hold, a dial (pinch and turn) and six drags (left, right, toward, away, up, down) as gestures or sliders. With a dial or drags, a finger's hold acts when you let go.
- **Palm position:** every gesture can do something else with the palm up or sideways (up to two positions); calibrate per band and wrist.
- **Drawings:** pinch and draw in the air or on a table, matched against the drawings you teach (three tries each); shared or per-profile actions; a pad shows what you draw.
- **Teach your drags** for your own wrist.
- **Automation:** overrides carry `palm`; the middle dial, sliders and drawings have their own names.
- The settings backup is version 2 (band controls and drawings); version 1 backups still restore.

```bash
git add CHANGELOG.md
git commit -q -m "Changelog: band controls, the pinch, palm positions and drawings

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MLDqGPYx8bTGKJcVLgoyzo"
```

- [ ] **Step 2: Build and check before installing**

```bash
cd ~/Projects/air-gestures-android
./build-rust.sh >/dev/null && JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew -q assembleDebug testDebugUnitTest
APK=app/build/outputs/apk/debug/app-debug.apk
BT=$(ls -d ~/Android/Sdk/build-tools/* | sort -V | tail -1)
# 1. The dev key: the APK's signer is the dev keystore's (AB:99…). Different: stop.
"$BT/apksigner" verify --print-certs "$APK" | grep "SHA-256 digest"
keytool -list -v -keystore ~/.android/debug.keystore.linux-backup -storepass android -alias androiddebugkey | grep "SHA256:"
# 2. The dev icon tab: the debug foreground is in the APK.
unzip -l "$APK" | grep "mipmap-.*ic_launcher_foreground"
cmp <(unzip -p "$APK" res/mipmap-xxxhdpi-v4/ic_launcher_foreground.png) app/src/debug/res/mipmap-xxxhdpi/ic_launcher_foreground.png && echo "dev icon"
# (If the path differs or the PNG was recompressed, compare its size with the debug one; unsure: ask the owner to look at the launcher icon first.)
# 3. Install over the dev build. INSTALL_FAILED_UPDATE_INCOMPATIBLE or a signature error: STOP and tell the owner. Never uninstall.
adb -s f940914b install -r "$APK"
```

Screenshots (layout only):

```bash
D=$(adb -s f940914b shell dumpsys SurfaceFlinger --display-id | head -1 | awk '{print $2}')
adb -s f940914b exec-out screencap -d "$D" -p > /tmp/claude-1000/c2-shot.png   # or the session's scratchpad
```

- [ ] **Step 3: The owner checks on the phone** (the band on the right wrist, then the left):
1. **Migration:** after installing over the dev build, Gestures shows Band controls with Middle hold / Index triple tap / Middle triple tap / Draw: Nothing; no profile has "Controls on/off" or "Next profile" as an action any more; the middle hold locks and unlocks; with two profiles the triples switch (toast, speech, buzz as set).
2. **Paused (1, 3, 5, 6, 7):** put Next profile on middle double tap: the prompt lists each profile's Mute (and During calls' Hang up) with Will pause; after "Use it anyway", Gestures shows coral numbers on Next profile, the profiles and During calls; in a profile the row says Paused · Mute; editing it shows the Paused banner and any action can be picked; a middle double tap switches profiles. Move Next profile back to the index triple: the rows show Mute again, no toast, and a middle double tap mutes.
3. **Picker (2):** the gestures held by the other band controls aren't listed; the current one says Now; a free one is set without asking.
4. **Palm (10, 12, 13):** calibrate palm down and up; give index tap a palm-up override (Next track): palm down plays/pauses, palm up skips; limit Next profile to palm up on the index triple: palm down the triple does the profile's action. The focused sideways check: index taps with the palm sideways (the recordings once saw middle taps there).
5. **Holds and dials (9, 11):** index hold says "when you let go" (index dial = volume) and acts on release; set a middle dial: the note shows and the middle hold (the lock) now acts on release; set it back to Nothing: the lock is fast again.
6. **Drags (13b):** index drags as gestures: drag right with an action runs it, with the palm down and up; middle drags as sliders: left/right moves the volume; a band control on a middle drag shows that slider as paused.
7. **Teach your drags:** all seven steps save; a repeated direction is refused naming the two drags.
8. **Draw (14, 15, 16):** put Draw on swipe down; teach "Lightbulb" (three tries, Alike, Save for All profiles with Flashlight on or off); a swipe down opens the pad over another app, strokes show live, Pen down lights while pinching and goes off on letting go; the lightbulb shows "Lightbulb → Flashlight on or off" and toggles the light; a scribble shows "Not one of your drawings" with two buzzes; the close button cancels. Pen grip on a table works too. A profile's own action for Lightbulb replaces the shared one there ("replaces shared" tag).
9. **Calls:** during a call the band controls still work; Paused chips show on the During calls screen.
10. **Left wrist:** switch to the left wrist: the app asks to calibrate; until then palm overrides don't apply (every gesture counts as palm down).
11. **Practice and text fields:** lessons include the index hold when it's bound; handwriting in a text field still edits, and no band control acts there.

Merging, pushing (band-core first) and tagging wait for the owner.
