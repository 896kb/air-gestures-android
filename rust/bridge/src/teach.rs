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
