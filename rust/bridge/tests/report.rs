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
    let r = parsed(&report(r#"{"band":{"controls":{"gesture":"middle_hold"},"draw":{"gesture":"index_triple"}}}"#));
    assert_eq!(r["invalid"], json!([{"kind":"draw-cant-cancel"}]));
    // On a hold (the standard Draw) it's fine.
    let r = parsed(&report(r#"{"band":{"controls":{"gesture":"middle_hold"},"draw":{"gesture":"index_hold"}}}"#));
    assert_eq!(r["invalid"], json!([]));
    assert_eq!(report("{"), r#"{"error":"bad-json"}"#);
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
    assert_eq!(band_choices("{", "next", ""), r#"{"error":"bad-json"}"#);
    assert_eq!(band_choices(JSON, "lock", ""), r#"{"error":"unknown-control"}"#);
    // No position it knows: any position, as "".
    assert_eq!(band_choices(JSON, "previous", "left"), band_choices(JSON, "previous", ""));
}
