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
    Mode(#[allow(dead_code)] String),
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
