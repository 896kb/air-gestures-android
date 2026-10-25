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
