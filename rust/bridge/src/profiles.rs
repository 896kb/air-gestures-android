//! The app's profiles, as JSON from Kotlin, into band-core's `Profiles` and `Options`.
//!
//! ```json
//! {"hand": "band", "picked": 0, "apps": {"org.videolan.vlc": 1},
//!  "calls": {"index_double": "call.answer", "middle_double": "-"},
//!  "profiles": [{"name": "Profile 1", "map": {"swipe_up": "media.previous"},
//!                "dial": {"up": "volume.up", "down": "volume.down"}}]}
//! ```
//!
//! `map`: `""` or a missing key is nothing. `calls`: only gestures whose call action isn't
//! "as usual"; `"-"` is nothing. `dial`: `null` is nothing. `hand`: `band`, `left` or `right`.

use std::collections::BTreeMap;

use band_core::controls::{
    Action, Builtin, CallBinding, Dial, Gesture, HandSetting, Options, Profile, Profiles, Warning,
};
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
    #[serde(default)]
    dial: Option<DialJson>,
}

#[derive(Deserialize)]
struct DialJson {
    up: String,
    down: String,
}

/// An app action id, or one of the three built-ins (`""`: nothing).
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
            let binding = match id.as_str() {
                "-" => CallBinding::Nothing,
                other => action(other).map_or(CallBinding::AsUsual, CallBinding::Do),
            };
            Some((gesture, binding))
        })
        .collect();
    Ok((options, Profiles { list, picked: json.picked, apps: json.apps, calls }))
}

/// What the app may want to warn about, one line each: `no-controls <index>` (no gesture
/// locks the band there) or `no-way-out <index>` (2+ profiles and none leaves this one).
/// Invalid JSON: nothing.
pub fn warnings(json: &str) -> String {
    let Ok((_, profiles)) = parse(json) else {
        return String::new();
    };
    profiles
        .warnings()
        .iter()
        .map(|warning| match warning {
            Warning::NoControls { profile } => format!("no-controls {profile}"),
            Warning::NoWayOut { profile } => format!("no-way-out {profile}"),
        })
        .collect::<Vec<_>>()
        .join("\n")
}
