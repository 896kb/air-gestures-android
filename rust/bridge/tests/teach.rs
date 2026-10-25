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
