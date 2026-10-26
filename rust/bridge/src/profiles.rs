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
//! - `calibration`: this band's, per wrist (see [`Setup::options`]); checked again here, and
//!   one band-core refuses is dropped (a note in [`Setup::notes`]).
//! - `hand`: `band`, `left` or `right`.
//!
//! Only JSON that isn't JSON is an error: a field that is `null` or the wrong shape reads as
//! its default (a map entry that isn't a string is skipped, a dial or slider without both
//! actions is none, `{"gesture": null}` is no gesture). A profile that isn't an object, or
//! has no name, is an empty profile in its place, so the profile indexes (`picked`, `apps`)
//! still point where the app means.

use std::collections::BTreeMap;

use band_core::controls::palm::{RIGHT_PALM_DOWN_ROLL, SWAPPED_RIGHT_PALM_DOWN_ROLL};
use band_core::controls::pinch::Prototypes;
use band_core::controls::{
    BandControl, BandControls, Binding, CallBinding, Dial, DragMode, FingerSetup, Gesture, HandSetting, Options, Palm,
    PalmCalibration, PalmSet, Profile, Profiles,
};
use band_core::events::Hand;
use serde::de::{DeserializeOwned, Deserializer, IgnoredAny};
use serde::Deserialize;
use serde_json::Value;

/// Any field: what it holds, or its default when that's `null` or the wrong shape.
fn lenient<'de, D: Deserializer<'de>, T: DeserializeOwned + Default>(d: D) -> Result<T, D::Error> {
    let value = Value::deserialize(d)?;
    Ok(T::deserialize(value).unwrap_or_default())
}

/// A map of strings: its entries that are strings (`null`, numbers… are skipped).
fn strings<'de, D: Deserializer<'de>>(d: D) -> Result<BTreeMap<String, String>, D::Error> {
    Ok(string_map(Value::deserialize(d)?))
}

fn string_map(value: Value) -> BTreeMap<String, String> {
    match value {
        Value::Object(map) => map.into_iter().filter_map(|(key, v)| Some((key, v.as_str()?.to_owned()))).collect(),
        _ => BTreeMap::new(),
    }
}

/// Palm overrides: per gesture, its map of strings (a gesture whose value isn't a map: none).
fn overrides<'de, D: Deserializer<'de>>(d: D) -> Result<BTreeMap<String, BTreeMap<String, String>>, D::Error> {
    Ok(match Value::deserialize(d)? {
        Value::Object(map) => map.into_iter().filter(|(_, v)| v.is_object()).map(|(key, v)| (key, string_map(v))).collect(),
        _ => BTreeMap::new(),
    })
}

/// The profiles, one per entry: one that doesn't read as a profile is an empty one in its place.
fn profile_list<'de, D: Deserializer<'de>>(d: D) -> Result<Vec<ProfileJson>, D::Error> {
    Ok(match Value::deserialize(d)? {
        Value::Array(list) => list.into_iter().map(|v| ProfileJson::deserialize(v).unwrap_or_default()).collect(),
        _ => Vec::new(),
    })
}

/// Three sliders: each a dial or none (one without both actions, or past the end: none).
fn sliders<'de, D: Deserializer<'de>>(d: D) -> Result<[Option<DialJson>; 3], D::Error> {
    let list = match Value::deserialize(d)? {
        Value::Array(list) => list,
        _ => Vec::new(),
    };
    let mut sliders = list.into_iter().map(|v| DialJson::deserialize(v).ok());
    Ok([(); 3].map(|_| sliders.next().flatten()))
}

#[derive(Deserialize, Default)]
#[serde(default)]
struct Json {
    #[serde(deserialize_with = "lenient")]
    hand: String,
    #[serde(deserialize_with = "lenient")]
    picked: usize,
    #[serde(deserialize_with = "lenient")]
    apps: BTreeMap<String, usize>,
    #[serde(deserialize_with = "lenient")]
    band: BandJson,
    #[serde(deserialize_with = "strings")]
    calls: BTreeMap<String, String>,
    #[serde(deserialize_with = "strings")]
    drawings: BTreeMap<String, String>,
    #[serde(deserialize_with = "lenient")]
    calibration: HandsJson,
    #[serde(deserialize_with = "profile_list")]
    profiles: Vec<ProfileJson>,
}

#[derive(Deserialize, Default)]
#[serde(default)]
struct BandJson {
    #[serde(deserialize_with = "lenient")]
    controls: Option<ControlJson>,
    #[serde(deserialize_with = "lenient")]
    next: Option<ControlJson>,
    #[serde(deserialize_with = "lenient")]
    previous: Option<ControlJson>,
    #[serde(deserialize_with = "lenient")]
    draw: Option<ControlJson>,
}

#[derive(Deserialize)]
struct ControlJson {
    gesture: String,
    /// Not a list of strings: every position.
    #[serde(default, deserialize_with = "lenient")]
    palm: Option<Vec<String>>,
}

#[derive(Deserialize, Default)]
struct ProfileJson {
    #[serde(default, deserialize_with = "lenient")]
    name: String,
    #[serde(default, deserialize_with = "strings")]
    map: BTreeMap<String, String>,
    #[serde(default, deserialize_with = "overrides")]
    palm: BTreeMap<String, BTreeMap<String, String>>,
    #[serde(default, deserialize_with = "lenient")]
    index: FingerJson,
    #[serde(default, deserialize_with = "lenient")]
    middle: FingerJson,
    #[serde(default, deserialize_with = "strings")]
    drawings: BTreeMap<String, String>,
}

#[derive(Deserialize, Default)]
#[serde(default)]
struct FingerJson {
    #[serde(deserialize_with = "lenient")]
    dial: Option<DialJson>,
    #[serde(deserialize_with = "lenient")]
    drags: DragsJson,
}

#[derive(Deserialize, Clone)]
struct DialJson {
    up: String,
    down: String,
}

/// `{"sliders": [...]}`, or anything else (`"gestures"`): gestures. Never an error: whatever
/// isn't sliders reads as gestures.
#[derive(Deserialize)]
#[serde(untagged)]
enum DragsJson {
    Sliders {
        #[serde(deserialize_with = "sliders")]
        sliders: [Option<DialJson>; 3],
    },
    Mode(IgnoredAny),
}

impl Default for DragsJson {
    fn default() -> Self {
        DragsJson::Mode(IgnoredAny)
    }
}

#[derive(Deserialize, Default)]
#[serde(default)]
struct HandsJson {
    #[serde(deserialize_with = "lenient")]
    left: Option<CalibrationJson>,
    #[serde(deserialize_with = "lenient")]
    right: Option<CalibrationJson>,
}

#[derive(Deserialize, Default)]
#[serde(default)]
struct CalibrationJson {
    #[serde(deserialize_with = "lenient")]
    palm: Option<PalmJson>,
    #[serde(deserialize_with = "lenient")]
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
    /// What parsing dropped and why (a stored calibration band-core refuses), for the log.
    pub notes: Vec<String>,
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
    let mut notes = Vec::new();
    let setup = Setup {
        hand: match json.hand.as_str() {
            "left" => HandSetting::Left,
            "right" => HandSetting::Right,
            _ => HandSetting::Band,
        },
        left: calibration("left", json.calibration.left, &mut notes),
        right: calibration("right", json.calibration.right, &mut notes),
        notes,
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
    // As in a profile's own: "" is nothing.
    let drawings = json.drawings.into_iter().filter(|(_, id)| !id.is_empty()).collect();
    Ok((setup, Profiles { band, list, picked: json.picked, apps: json.apps, calls, drawings }))
}

/// A wrist's stored calibration, checked again: a damaged one gives band-core's defaults
/// rather than nonsense (and a note saying so).
fn calibration(wrist: &str, json: Option<CalibrationJson>, notes: &mut Vec<String>) -> Calibration {
    let json = json.unwrap_or_default();
    let palm = json.palm.and_then(|p| match PalmCalibration::from_rolls(&[p.down], &[p.up]) {
        Ok(c) => Some(c),
        Err(e) => {
            notes.push(format!("calibration: the {wrist} wrist's palm calibration is dropped ({e:?})"));
            None
        }
    });
    // Drags taught at band-core's old right-hand palm down (really palm up; before 2026-10-26,
    // without a calibration) are read as taught at palm down, as the Linux app's store does.
    let drags = json.drags.and_then(|d| {
        let roll = if d.roll == SWAPPED_RIGHT_PALM_DOWN_ROLL { RIGHT_PALM_DOWN_ROLL } else { d.roll };
        match Prototypes::taught(d.turn, d.drags, roll) {
            Ok(p) => Some(p),
            Err(e) => {
                notes.push(format!("calibration: the {wrist} wrist's taught drags are dropped ({e:?})"));
                None
            }
        }
    });
    Calibration { palm, drags }
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
