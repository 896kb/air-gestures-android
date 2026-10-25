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
