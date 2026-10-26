//! The Activity screen's lines: what each gesture did, with the palm it was made with, as
//! `@activity <json>` (Kotlin words it with the app's labels). One line per gesture (its run,
//! its band control, paused, nothing, …), per drawing, and per pinch for dial and slider steps
//! (gathered until they pause for [`STEP_GAP`] or something else happens).
//!
//! JSON: `{"what", "palm", "calibrated"}` and one of `"run": id`, `"practiced": id`,
//! `"steps": [[id, n], …]`, `"band": "controls", "result": "on"|"off"`, `"band": "next"|"previous",
//! "profile": index`, `"band": …, "held": app`, `"band": "draw", "result": "opened"|"cancelled"`,
//! `"paused": control`, `"off": true` (the controls were off), `"nothing": true`; a drawing is
//! `{"drawn": name}` with its `run` / `practiced`, or `{"not_drawn": true}`.
//!
//! The palm is the one the engine reads when the gesture comes out (`Controls::palm(now)`):
//! for a hold reported at its release, that's the release's, not the press's (band-core
//! doesn't report the press palm with the gesture).

use band_core::controls::{Builtin, Gesture, Output, Palm};
use serde_json::{Map, Value, json};

use crate::report::builtin_key;

/// A pinch's steps that pause this long (seconds) are one line.
pub const STEP_GAP: f64 = 0.8;

/// What the line needs from the connection at the moment its outputs came.
pub struct Context<'a> {
    pub palm: Palm,
    /// The palm calibration in use is the user's (else band-core's defaults).
    pub calibrated: bool,
    /// The controls are on.
    pub enabled: bool,
    /// The band control on a gesture in a position.
    pub band: &'a dyn Fn(Gesture, Palm) -> Option<Builtin>,
    /// Where a step's action comes from: "index dial", "middle left/right slider".
    pub source: &'a dyn Fn(&str) -> Option<String>,
}

/// A pinch's steps so far.
struct Steps {
    source: String,
    palm: Palm,
    calibrated: bool,
    counts: Vec<(String, u32)>,
    last: f64,
}

impl Steps {
    fn line(&self) -> String {
        let steps: Vec<Value> = self.counts.iter().map(|(id, n)| json!([id, n])).collect();
        json!({"what": self.source, "palm": self.palm.key(), "calibrated": self.calibrated, "steps": steps}).to_string()
    }
}

/// A gesture waiting to see what it did (the outputs right after it).
struct Pending {
    line: Map<String, Value>,
    gesture: Option<Gesture>,
    palm: Palm,
}

#[derive(Default)]
pub struct Activity {
    steps: Option<Steps>,
}

impl Activity {
    /// The lines for these outputs (in their order). `now`: also ends a pinch's steps that paused.
    pub fn take(&mut self, outputs: &[Output<String>], ctx: &Context, now: Option<f64>) -> Vec<String> {
        let mut lines = Vec::new();
        if let (Some(now), Some(steps)) = (now, &self.steps)
            && now - steps.last >= STEP_GAP
        {
            lines.push(steps.line());
            self.steps = None;
        }
        let mut pending: Option<Pending> = None;
        let finish = |pending: Option<Pending>, lines: &mut Vec<String>| {
            if let Some(p) = pending {
                lines.push(Self::close(p, ctx));
            }
        };
        for output in outputs {
            match output {
                Output::Gesture(gesture) => {
                    finish(pending.take(), &mut lines);
                    // A gesture ends the pinch's steps before it.
                    if let Some(steps) = self.steps.take() {
                        lines.push(steps.line());
                    }
                    let mut line = Map::new();
                    line.insert("what".into(), json!(gesture.label()));
                    line.insert("palm".into(), json!(ctx.palm.key()));
                    line.insert("calibrated".into(), json!(ctx.calibrated));
                    pending = Some(Pending { line, gesture: Some(*gesture), palm: ctx.palm });
                }
                Output::Drawn { name, .. } => {
                    finish(pending.take(), &mut lines);
                    let mut line = Map::new();
                    line.insert("drawn".into(), json!(name));
                    pending = Some(Pending { line, gesture: None, palm: ctx.palm });
                }
                Output::NotDrawn => {
                    finish(pending.take(), &mut lines);
                    lines.push(json!({"not_drawn": true}).to_string());
                }
                Output::Run(id) | Output::Practiced(id) => {
                    let key = if matches!(output, Output::Run(_)) { "run" } else { "practiced" };
                    match pending.as_mut().filter(|p| Self::open(p)) {
                        Some(p) => {
                            p.line.insert(key.into(), json!(id));
                            finish(pending.take(), &mut lines);
                        }
                        // No gesture before it: a dial's or a slider's step.
                        None => self.step(id, ctx, now, &mut lines),
                    }
                }
                Output::Controls { enabled } => Self::band(&mut pending, &mut lines, ctx, "controls", "result", json!(if *enabled { "on" } else { "off" })),
                Output::Profile { index, step } => {
                    let control = if *step < 0 { "previous" } else { "next" };
                    Self::band(&mut pending, &mut lines, ctx, control, "profile", json!(index))
                }
                Output::ProfileHeld { app } => {
                    let control = pending
                        .as_ref()
                        .and_then(|p| p.gesture.and_then(|g| (ctx.band)(g, p.palm)))
                        .map_or("next", builtin_key);
                    Self::band(&mut pending, &mut lines, ctx, control, "held", json!(app))
                }
                Output::DrawStarted => Self::band(&mut pending, &mut lines, ctx, "draw", "result", json!("opened")),
                Output::DrawCancelled => Self::band(&mut pending, &mut lines, ctx, "draw", "result", json!("cancelled")),
                _ => {}
            }
        }
        finish(pending, &mut lines);
        lines
    }

    /// Nothing written to it yet but what made it.
    fn open(p: &Pending) -> bool {
        !p.line.keys().any(|k| k != "what" && k != "palm" && k != "calibrated" && k != "drawn")
    }

    /// A band control's effect, on the gesture before it (none: the app's own call, not a line).
    fn band(pending: &mut Option<Pending>, lines: &mut Vec<String>, ctx: &Context, control: &str, key: &str, value: Value) {
        let Some(mut p) = pending.take().filter(Self::open) else { return };
        p.line.insert("band".into(), json!(control));
        p.line.insert(key.into(), value);
        lines.push(Self::close(p, ctx));
    }

    /// A gesture's line once its outputs are over: what it did, or why nothing.
    fn close(mut p: Pending, ctx: &Context) -> String {
        if Self::open(&p) {
            match p.gesture.and_then(|g| (ctx.band)(g, p.palm)) {
                // A band control is on it and didn't act (practice, the band not live): its action waits.
                Some(builtin) => p.line.insert("paused".into(), json!(builtin_key(builtin))),
                None if !ctx.enabled => p.line.insert("off".into(), json!(true)),
                None => p.line.insert("nothing".into(), json!(true)),
            };
        }
        Value::Object(p.line).to_string()
    }

    /// One step of a dial or slider: counted on this pinch's line.
    fn step(&mut self, id: &str, ctx: &Context, now: Option<f64>, lines: &mut Vec<String>) {
        let source = (ctx.source)(id).unwrap_or_else(|| "pinch".into());
        if self.steps.as_ref().is_some_and(|s| s.source != source)
            && let Some(old) = self.steps.take()
        {
            lines.push(old.line());
        }
        let now = now.unwrap_or_else(|| self.steps.as_ref().map_or(0.0, |s| s.last));
        let steps = self.steps.get_or_insert_with(|| Steps {
            source,
            palm: ctx.palm,
            calibrated: ctx.calibrated,
            counts: Vec::new(),
            last: now,
        });
        steps.last = now;
        match steps.counts.iter_mut().find(|(known, _)| known == id) {
            Some((_, n)) => *n += 1,
            None => steps.counts.push((id.to_owned(), 1)),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ctx<'a>(band: &'a dyn Fn(Gesture, Palm) -> Option<Builtin>, source: &'a dyn Fn(&str) -> Option<String>) -> Context<'a> {
        Context { palm: Palm::Up, calibrated: true, enabled: true, band, source }
    }

    fn parsed(line: &str) -> Value {
        serde_json::from_str(line).unwrap()
    }

    #[test]
    fn a_gesture_and_what_it_ran_are_one_line() {
        let band = |_, _| None;
        let source = |_: &str| None;
        let mut a = Activity::default();
        let lines = a.take(&[Output::Gesture(Gesture::IndexTap), Output::Run("media.next".into())], &ctx(&band, &source), Some(1.0));
        assert_eq!(parsed(&lines[0]), json!({"what": "index tap", "palm": "up", "calibrated": true, "run": "media.next"}));
        let lines = a.take(&[Output::Gesture(Gesture::IndexDragAway)], &ctx(&band, &source), Some(2.0));
        assert_eq!(parsed(&lines[0])["nothing"], json!(true));
    }

    #[test]
    fn band_controls_say_what_they_did_or_that_they_held_the_action() {
        let band = |g, _| (g == Gesture::MiddleHold).then_some(Builtin::Controls).or((g == Gesture::MiddleDouble).then_some(Builtin::Draw));
        let source = |_: &str| None;
        let mut a = Activity::default();
        let lines = a.take(&[Output::Gesture(Gesture::MiddleHold), Output::Controls { enabled: false }], &ctx(&band, &source), Some(1.0));
        assert_eq!(parsed(&lines[0])["band"], json!("controls"));
        assert_eq!(parsed(&lines[0])["result"], json!("off"));
        let lines = a.take(&[Output::Gesture(Gesture::MiddleDouble)], &ctx(&band, &source), Some(2.0));
        assert_eq!(parsed(&lines[0])["paused"], json!("draw"));
        let lines = a.take(&[Output::Gesture(Gesture::IndexTriple), Output::Profile { index: 1, step: 1 }], &ctx(&band, &source), Some(3.0));
        assert_eq!((parsed(&lines[0])["band"].clone(), parsed(&lines[0])["profile"].clone()), (json!("next"), json!(1)));
        // The app's own pause: no gesture, no line.
        assert!(a.take(&[Output::Controls { enabled: false }], &ctx(&band, &source), Some(4.0)).is_empty());
    }

    #[test]
    fn a_pinchs_steps_are_one_line_once_they_pause() {
        let band = |_, _| None;
        let source = |id: &str| id.starts_with("volume").then(|| "index dial".to_owned());
        let c = ctx(&band, &source);
        let mut a = Activity::default();
        for i in 0..6 {
            assert!(a.take(&[Output::Run("volume.down".into())], &c, Some(1.0 + 0.1 * f64::from(i))).is_empty());
        }
        a.take(&[Output::Run("volume.up".into())], &c, Some(1.7));
        let lines = a.take(&[], &c, Some(1.7 + STEP_GAP));
        assert_eq!(parsed(&lines[0]), json!({"what": "index dial", "palm": "up", "calibrated": true, "steps": [["volume.down", 6], ["volume.up", 1]]}));
        // A gesture ends them at once, before its own line.
        a.take(&[Output::Run("volume.up".into())], &c, Some(5.0));
        let lines = a.take(&[Output::Gesture(Gesture::SwipeUp), Output::Run("media.previous".into())], &c, Some(5.1));
        assert_eq!(lines.len(), 2);
        assert!(lines[0].contains("steps") && lines[1].contains("swipe up"));
    }

    #[test]
    fn drawings_and_the_controls_off() {
        let band = |_, _| None;
        let source = |_: &str| None;
        let mut a = Activity::default();
        let lines = a.take(&[Output::DrawFinished { strokes: vec![] }, Output::Drawn { name: "Lightbulb".into(), distance: 0.5 }, Output::Run("torch.toggle".into())], &ctx(&band, &source), Some(1.0));
        assert_eq!(parsed(&lines[0]), json!({"drawn": "Lightbulb", "run": "torch.toggle"}));
        assert_eq!(parsed(&a.take(&[Output::NotDrawn], &ctx(&band, &source), Some(2.0))[0]), json!({"not_drawn": true}));
        let off = Context { enabled: false, ..ctx(&band, &source) };
        assert_eq!(parsed(&a.take(&[Output::Gesture(Gesture::SwipeDown)], &off, Some(3.0))[0])["off"], json!(true));
    }
}
