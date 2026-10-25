use airgestures_bridge::profiles::parse;
use band_core::controls::{BandControl, Binding, CallBinding, Dial, DragMode, Gesture, HandSetting, Palm, PalmSet, Palms};
use band_core::events::Hand;

const FULL: &str = r#"{"hand":"left","picked":1,"apps":{"vlc":0},
  "band":{"controls":{"gesture":"middle_hold","palm":null},"next":{"gesture":"index_triple","palm":["up"]},
          "previous":null,"draw":{"gesture":"swipe_down"}},
  "calls":{"index_double":"call.answer","middle_double":"-","swipe_up":""},
  "drawings":{"Lightbulb":"torch.toggle"},
  "calibration":{"left":{"palm":{"down":-140.0,"up":-12.0},"drags":null},"right":null},
  "profiles":[
    {"name":"A","map":{"swipe_up":"media.previous","index_tap":""},
     "palm":{"index_tap":{"up":"media.next","sideways":""},"index_hold":{"down":"x"}},
     "index":{"dial":{"up":"volume.up","down":"volume.down"},"drags":"gestures"},
     "middle":{"dial":null,"drags":{"sliders":[{"up":"brightness.up","down":"brightness.down"},null,null]}},
     "drawings":{"Lightbulb":"app:org.home"}},
    {"name":"B"}]}"#;

fn dial(up: &str, down: &str) -> Dial<String> {
    Dial { up: up.into(), down: down.into() }
}

#[test]
fn the_apps_json_becomes_band_controls_and_profiles() {
    let (setup, profiles) = parse(FULL).unwrap();
    assert_eq!(setup.hand, HandSetting::Left);
    assert_eq!(profiles.picked, 1);
    assert_eq!(profiles.apps.get("vlc"), Some(&0));
    assert_eq!(profiles.band.controls, Some(BandControl::on(Gesture::MiddleHold)));
    assert_eq!(
        profiles.band.next,
        Some(BandControl { gesture: Gesture::IndexTriple, palm: PalmSet::Only(Palms::from([Palm::Up])) })
    );
    assert_eq!(profiles.band.previous, None);
    assert_eq!(profiles.band.draw, Some(BandControl::on(Gesture::SwipeDown)));
    assert_eq!(profiles.drawings.get("Lightbulb").map(String::as_str), Some("torch.toggle"));
    let a = &profiles.list[0];
    assert_eq!(a.gestures.get(&Gesture::SwipeUp), Some(&Binding::from("media.previous".to_owned())));
    // The index tap: nothing normally, the next track palm up, nothing sideways.
    let tap = &a.gestures[&Gesture::IndexTap];
    assert_eq!(tap.normal, None);
    assert_eq!(tap.action(Palm::Up).map(String::as_str), Some("media.next"));
    assert_eq!(tap.overrides.get(&Palm::Side), Some(&None));
    assert_eq!(a.gestures[&Gesture::IndexHold].action(Palm::Down).map(String::as_str), Some("x"));
    assert_eq!(a.index.dial, Some(dial("volume.up", "volume.down")));
    assert_eq!(a.index.drags, DragMode::Gestures);
    assert!(a.middle.dial.is_none());
    assert_eq!(a.middle.drags, DragMode::Sliders([Some(dial("brightness.up", "brightness.down")), None, None]));
    assert_eq!(a.drawings.get("Lightbulb").map(String::as_str), Some("app:org.home"));
    let b = &profiles.list[1];
    assert!(b.gestures.is_empty() && b.index.dial.is_none() && b.index.drags == DragMode::Gestures);
    assert_eq!(
        profiles.calls.get(&Gesture::IndexDouble),
        Some(&CallBinding::Do(Binding::from("call.answer".to_owned())))
    );
    assert_eq!(profiles.calls.get(&Gesture::MiddleDouble), Some(&CallBinding::Nothing));
    assert_eq!(profiles.calls.get(&Gesture::SwipeUp), Some(&CallBinding::AsUsual));
}

#[test]
fn the_options_follow_the_hand_in_use() {
    let (setup, _) = parse(FULL).unwrap();
    // Set to the left wrist: its calibration until the band says otherwise.
    assert_eq!(setup.options(None).palm.map(|c| c.down_roll), Some(-140.0));
    assert_eq!(setup.options(Some(Hand::Left)).palm.map(|c| c.up_roll), Some(-12.0));
    // The band still says right (the write hasn't landed): band-core reads the right hand.
    assert_eq!(setup.options(Some(Hand::Right)).palm, None);
    let (band, _) = parse(r#"{"hand":"band","calibration":{"right":{"palm":{"down":-150.0,"up":-20.0}}}}"#).unwrap();
    assert_eq!(band.options(None).palm, None, "no hand known yet");
    assert_eq!(band.options(Some(Hand::Right)).palm.map(|c| c.down_roll), Some(-150.0));
}

#[test]
fn stored_drags_are_checked_again() {
    let good = r#"{"hand":"right","calibration":{"right":{"drags":{"roll":-142.3,"turn":[0,1,0],
        "drags":[[0,0,1],[0,0,-1],[1,0,0],[-1,0,0],[0.7,-0.7,0],[-0.7,0.7,0.1]]}}}}"#;
    let (setup, _) = parse(good).unwrap();
    assert_eq!(setup.options(None).drags.map(|p| p.palm_down_roll), Some(-142.3));
    let bad = good.replace("[0,0,-1]", "[0,0,1]");
    assert!(parse(&bad).unwrap().0.options(None).drags.is_none(), "two drags alike: the defaults instead");
}

#[test]
fn unknown_keys_are_skipped_and_bad_json_is_an_error() {
    let (_, profiles) = parse(
        r#"{"band":{"controls":{"gesture":"index_wave"}},
            "profiles":[{"name":"A","map":{"index_wave":"x"},"palm":{"index_tap":{"left":"y"}}}]}"#,
    )
    .unwrap();
    assert_eq!(profiles.band.controls, None);
    assert!(profiles.list[0].gestures.is_empty());
    assert!(parse("{").is_err());
    assert!(parse("{}").unwrap().1.list.is_empty());
}

#[test]
fn a_stored_palm_calibration_is_checked_again() {
    let (setup, _) = parse(r#"{"hand":"right","calibration":{"right":{"palm":{"down":-40.0,"up":-10.0}}}}"#).unwrap();
    assert_eq!(setup.options(None).palm, None, "too close: band-core's defaults instead");
    assert!(setup.notes.iter().any(|n| n.contains("right") && n.contains("palm")), "{:?}", setup.notes);
}

#[test]
fn a_null_or_wrong_shape_field_is_its_default_not_an_error() {
    let (setup, profiles) = parse(
        r#"{"hand":null,"picked":"x","apps":null,"band":{"controls":{"gesture":null},"next":{"gesture":"index_triple","palm":"up"}},
            "calls":{"index_double":null,"middle_double":"-"},"drawings":{"Bulb":null,"Z":""},"calibration":{"left":7},
            "profiles":[
              {"name":"A","map":{"swipe_up":null,"index_tap":"media.next"},"palm":{"index_tap":null,"middle_tap":{"up":null,"down":"x"}},
               "index":null,"middle":{"dial":{"up":"volume.up"},"drags":null}},
              3,
              {"name":"B","index":{"drags":{"sliders":[{"up":"brightness.up"},{"up":"volume.up","down":"volume.down"}]}}}]}"#,
    )
    .unwrap();
    assert_eq!(setup.hand, HandSetting::Band);
    assert_eq!(profiles.picked, 0);
    assert_eq!(profiles.band.controls, None);
    assert_eq!(profiles.band.next, Some(BandControl::on(Gesture::IndexTriple)), "a palm that isn't a list: every position");
    assert_eq!(profiles.calls.get(&Gesture::MiddleDouble), Some(&CallBinding::Nothing));
    assert!(!profiles.calls.contains_key(&Gesture::IndexDouble));
    assert!(profiles.drawings.is_empty(), "nothing, and \"\" is nothing too");
    assert_eq!(profiles.list.len(), 3, "the profile that isn't one is an empty one in its place: indexes stay");
    assert!(profiles.list[1].gestures.is_empty() && profiles.list[1].index.dial.is_none());
    let a = &profiles.list[0];
    assert_eq!(a.gestures.get(&Gesture::SwipeUp), None);
    assert_eq!(a.gestures[&Gesture::IndexTap].normal.as_deref(), Some("media.next"));
    assert_eq!(a.gestures[&Gesture::MiddleTap].action(Palm::Down).map(String::as_str), Some("x"));
    assert!(a.index.dial.is_none() && a.middle.dial.is_none(), "a dial without its down is none");
    assert_eq!(a.middle.drags, DragMode::Gestures);
    // Sliders: the one without its down is none, a missing third is none.
    assert_eq!(profiles.list[2].index.drags, DragMode::Sliders([None, Some(dial("volume.up", "volume.down")), None]));
    // No name: the profile still counts, with no name.
    let (_, named) = parse(r#"{"profiles":[{"map":{"swipe_up":"x"}},{"name":null}]}"#).unwrap();
    assert_eq!(named.list.len(), 2);
    assert_eq!(named.list[0].gestures[&Gesture::SwipeUp].normal.as_deref(), Some("x"));
}
