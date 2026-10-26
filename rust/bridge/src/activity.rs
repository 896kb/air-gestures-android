//! The Activity screen's lines: what each gesture did, with the palm it was resolved in, as
//! `@activity <json>` (Kotlin words it with the app's labels). One line per gesture, per
//! drawing, and per stepping pinch, all from band-core's own report: `Output::Gesture`'s palm,
//! `Output::Unused`'s reason, `Output::PinchEnded`'s steps (each step's own `Run` isn't a line).
//!
//! JSON: `{"what", "palm", "calibrated"}` and one of `"run": id`, `"practiced": id`,
//! `"steps": [[id, n], …]`, `"band": "controls", "result": "on"|"off"`, `"band": "next"|"previous",
//! "profile": index`, `"band": …, "held": app`, `"band": "draw", "result": "opened"|"cancelled"`,
//! `"paused": control`, `"off": true` (the controls were off), `"too_soon": true`,
//! `"teaching": true` (teaching drags), `"drawing": true` (a draw session open), `"nothing": true`;
//! a pinch's `steps` carry `"practiced": true` in practice (what they would have done); a drawing is `{"drawn": name}` with its `run` / `practiced`, or
//! `{"not_drawn": true}`.

use band_core::controls::{Builtin, Class, Finger, Gesture, Output, Palm, Unused};
use serde_json::{Map, Value, json};

use crate::report::builtin_key;

/// What a pinch stepped, by its finger and class: its name ("index dial", "middle left/right
/// slider") and the up and down actions.
pub type Steps<'a> = &'a dyn Fn(Finger, Class) -> Option<(String, String, String)>;

/// What the lines need from the connection.
pub struct Context<'a> {
    /// The palm calibration in use is the user's (else band-core's defaults).
    pub calibrated: bool,
    /// The band control on a gesture in a position (to name the one an app's own profile held back).
    pub band: &'a dyn Fn(Gesture, Palm) -> Option<Builtin>,
    pub steps: Steps<'a>,
}

/// A gesture or a drawing waiting for what it did (the outputs right after it).
struct Pending {
    line: Map<String, Value>,
    gesture: Option<(Gesture, Palm)>,
}

impl Pending {
    /// Nothing written to it yet but what made it.
    fn open(&self) -> bool {
        !self.line.keys().any(|k| !matches!(k.as_str(), "what" | "palm" | "calibrated" | "drawn"))
    }

    fn put(mut self, key: &str, value: Value) -> String {
        self.line.insert(key.into(), value);
        Value::Object(self.line).to_string()
    }
}

/// What the gesture just reported did (none open: the app's own call, or a pinch's step).
fn did(pending: &mut Option<Pending>, lines: &mut Vec<String>, key: &str, value: Value, extra: Option<(&str, Value)>) {
    if let Some(mut p) = pending.take().filter(Pending::open) {
        if let Some((k, v)) = extra {
            p.line.insert(k.into(), v);
        }
        lines.push(p.put(key, value));
    }
}

/// The lines for these outputs, in their order.
pub fn lines(outputs: &[Output<String>], ctx: &Context) -> Vec<String> {
    let mut lines = Vec::new();
    let mut pending: Option<Pending> = None;
    // A gesture with nothing after it (teaching drags, a drawing open): nothing. A drawing with
    // nothing after it: its action (a match always has one) was refused by the action gate.
    let close = |pending: Option<Pending>, lines: &mut Vec<String>| {
        if let Some(p) = pending {
            lines.push(match (p.open(), p.gesture.is_none()) {
                (true, true) => p.put("too_soon", json!(true)),
                (true, false) => p.put("nothing", json!(true)),
                _ => Value::Object(p.line).to_string(),
            });
        }
    };
    for output in outputs {
        match output {
            Output::Gesture { gesture, palm } => {
                close(pending.take(), &mut lines);
                let mut line = Map::new();
                line.insert("what".into(), json!(gesture.label()));
                line.insert("palm".into(), json!(palm.key()));
                line.insert("calibrated".into(), json!(ctx.calibrated));
                pending = Some(Pending { line, gesture: Some((*gesture, *palm)) });
            }
            Output::Drawn { name, .. } => {
                close(pending.take(), &mut lines);
                let mut line = Map::new();
                line.insert("drawn".into(), json!(name));
                pending = Some(Pending { line, gesture: None });
            }
            Output::NotDrawn => {
                close(pending.take(), &mut lines);
                lines.push(json!({"not_drawn": true}).to_string());
            }
            Output::Run(id) => did(&mut pending, &mut lines, "run", json!(id), None),
            Output::Practiced(id) => did(&mut pending, &mut lines, "practiced", json!(id), None),
            Output::Unused { why, .. } => match why {
                Unused::Unbound => did(&mut pending, &mut lines, "nothing", json!(true), None),
                Unused::ControlsOff => did(&mut pending, &mut lines, "off", json!(true), None),
                Unused::TooSoon => did(&mut pending, &mut lines, "too_soon", json!(true), None),
                Unused::Paused(builtin) => did(&mut pending, &mut lines, "paused", json!(builtin_key(*builtin)), None),
                Unused::Teaching => did(&mut pending, &mut lines, "teaching", json!(true), None),
                Unused::Drawing => did(&mut pending, &mut lines, "drawing", json!(true), None),
            },
            Output::Controls { enabled } => did(&mut pending, &mut lines, "band", json!("controls"), Some(("result", json!(if *enabled { "on" } else { "off" })))),
            Output::Profile { index, step } => {
                did(&mut pending, &mut lines, "band", json!(if *step < 0 { "previous" } else { "next" }), Some(("profile", json!(index))))
            }
            Output::ProfileHeld { app } => {
                let control = pending
                    .as_ref()
                    .and_then(|p| p.gesture.and_then(|(g, palm)| (ctx.band)(g, palm)))
                    .map_or("next", builtin_key);
                did(&mut pending, &mut lines, "band", json!(control), Some(("held", json!(app))))
            }
            Output::DrawStarted => did(&mut pending, &mut lines, "band", json!("draw"), Some(("result", json!("opened")))),
            Output::DrawCancelled => did(&mut pending, &mut lines, "band", json!("draw"), Some(("result", json!("cancelled")))),
            // Also when band-core drops a stepping pinch (pause, a drawing or drag teaching starting).
            Output::PinchEnded { finger, class, palm, up, down, practiced } => {
                close(pending.take(), &mut lines);
                if let Some((what, up_id, down_id)) = (ctx.steps)(*finger, *class) {
                    let steps: Vec<Value> =
                        [(up_id, *up), (down_id, *down)].into_iter().filter(|(_, n)| *n > 0).map(|(id, n)| json!([id, n])).collect();
                    let mut line = json!({"what": what, "palm": palm.key(), "calibrated": ctx.calibrated, "steps": steps});
                    if *practiced {
                        line["practiced"] = json!(true);
                    }
                    lines.push(line.to_string());
                }
            }
            _ => {}
        }
    }
    close(pending, &mut lines);
    lines
}

#[cfg(test)]
mod tests {
    use super::*;
    use band_core::controls::Drag;

    fn ctx<'a>(band: &'a dyn Fn(Gesture, Palm) -> Option<Builtin>, steps: Steps<'a>) -> Context<'a> {
        Context { calibrated: true, band, steps }
    }

    fn parsed(line: &str) -> Value {
        serde_json::from_str(line).unwrap()
    }

    const NO_BAND: &dyn Fn(Gesture, Palm) -> Option<Builtin> = &|_, _| None;
    const NO_STEPS: Steps = &|_, _| None;

    #[test]
    fn a_gesture_its_palm_and_what_it_ran_or_why_not() {
        let c = ctx(NO_BAND, NO_STEPS);
        let tap = Output::Gesture { gesture: Gesture::IndexTap, palm: Palm::Up };
        let lines = lines(&[tap.clone(), Output::Run("media.next".into())], &c);
        assert_eq!(parsed(&lines[0]), json!({"what": "index tap", "palm": "up", "calibrated": true, "run": "media.next"}));
        let why = |why| parsed(&super::lines(&[tap.clone(), Output::Unused { gesture: Gesture::IndexTap, why }], &c)[0]);
        assert_eq!(why(Unused::Unbound)["nothing"], json!(true));
        assert_eq!(why(Unused::ControlsOff)["off"], json!(true));
        assert_eq!(why(Unused::TooSoon)["too_soon"], json!(true));
        assert_eq!(why(Unused::Paused(Builtin::Draw))["paused"], json!("draw"));
        assert_eq!(why(Unused::Teaching)["teaching"], json!(true));
        assert_eq!(why(Unused::Drawing)["drawing"], json!(true));
    }

    #[test]
    fn band_controls_say_what_they_did() {
        let c = ctx(NO_BAND, NO_STEPS);
        let hold = Output::Gesture { gesture: Gesture::MiddleHold, palm: Palm::Down };
        let line = parsed(&lines(&[hold, Output::Controls { enabled: false }], &c)[0]);
        assert_eq!((line["band"].clone(), line["result"].clone()), (json!("controls"), json!("off")));
        // The app's own pause: no gesture, no line.
        assert!(lines(&[Output::Controls { enabled: false }], &c).is_empty());
    }

    #[test]
    fn a_stepping_pinch_is_one_line_its_steps_are_none() {
        let steps: Steps = &|finger, class| match (finger, class) {
            (Finger::Index, Class::Turn) => Some(("index dial".into(), "volume.up".into(), "volume.down".into())),
            (Finger::Middle, Class::Drag(d)) if d.pair() == 0 => {
                Some(("middle left/right slider".into(), "brightness.up".into(), "brightness.down".into()))
            }
            _ => None,
        };
        let c = ctx(NO_BAND, steps);
        let mut out: Vec<Output<String>> = (0..6).map(|_| Output::Run("volume.down".into())).collect();
        out.push(Output::PinchEnded { finger: Finger::Index, class: Class::Turn, palm: Palm::Down, up: 0, down: 6, practiced: false });
        let lines = lines(&out, &c);
        assert_eq!(lines.len(), 1);
        assert_eq!(parsed(&lines[0]), json!({"what": "index dial", "palm": "down", "calibrated": true, "steps": [["volume.down", 6]]}));
        let slider = Output::PinchEnded { finger: Finger::Middle, class: Class::Drag(Drag::Right), palm: Palm::Up, up: 2, down: 1, practiced: false };
        assert_eq!(parsed(&super::lines(&[slider], &c)[0])["steps"], json!([["brightness.up", 2], ["brightness.down", 1]]));
        // In practice the steps came as Practiced: "would".
        let practiced = Output::PinchEnded { finger: Finger::Index, class: Class::Turn, palm: Palm::Down, up: 4, down: 0, practiced: true };
        let line = parsed(&super::lines(&[practiced], &c)[0]);
        assert_eq!((line["steps"].clone(), line["practiced"].clone()), (json!([["volume.up", 4]]), json!(true)));
        // A pinch dropped by opening a drawing: its line, then the drawing's own outputs (no gesture: no line).
        let dropped = Output::PinchEnded { finger: Finger::Index, class: Class::Turn, palm: Palm::Up, up: 1, down: 0, practiced: false };
        assert_eq!(super::lines(&[dropped, Output::DrawStarted], &c).len(), 1);
    }

    #[test]
    fn drawings() {
        let c = ctx(NO_BAND, NO_STEPS);
        let lines = lines(&[Output::DrawFinished { strokes: vec![] }, Output::Drawn { name: "Lightbulb".into(), distance: 0.5 }, Output::Run("torch.toggle".into())], &c);
        assert_eq!(parsed(&lines[0]), json!({"drawn": "Lightbulb", "run": "torch.toggle"}));
        assert_eq!(parsed(&super::lines(&[Output::NotDrawn], &c)[0]), json!({"not_drawn": true}));
        // Matched, but its action came too soon after the last (the gate): no Run after it.
        let gated = super::lines(&[Output::Drawn { name: "Lightbulb".into(), distance: 0.5 }], &c);
        assert_eq!(parsed(&gated[0]), json!({"drawn": "Lightbulb", "too_soon": true}));
    }
}
