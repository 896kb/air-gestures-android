use airgestures_bridge::profiles::parse;
use band_core::controls::{Action, Builtin, CallBinding, Gesture, HandSetting};

#[test]
fn the_apps_json_becomes_profiles_and_options() {
    let (options, profiles) = parse(
        r#"{"hand":"left","picked":1,"apps":{"vlc":0},
            "calls":{"index_double":"call.answer","middle_double":"-"},
            "profiles":[
              {"name":"A","map":{"swipe_up":"media.previous","middle_hold":"controls.toggle",
                                 "index_triple":"profile.next","middle_triple":"profile.previous",
                                 "index_tap":""},
               "dial":{"up":"volume.up","down":"volume.down"}},
              {"name":"B","map":{},"dial":null}]}"#,
    )
    .unwrap();
    assert_eq!(options.hand, HandSetting::Left);
    assert_eq!(profiles.picked, 1);
    assert_eq!(profiles.apps.get("vlc"), Some(&0));
    let a = &profiles.list[0];
    assert_eq!(a.name, "A");
    assert_eq!(a.gestures.get(&Gesture::SwipeUp), Some(&Action::Run("media.previous".into())));
    assert_eq!(a.gestures.get(&Gesture::MiddleHold), Some(&Action::Builtin(Builtin::Controls)));
    assert_eq!(a.gestures.get(&Gesture::IndexTriple), Some(&Action::Builtin(Builtin::NextProfile)));
    assert_eq!(a.gestures.get(&Gesture::MiddleTriple), Some(&Action::Builtin(Builtin::PreviousProfile)));
    assert_eq!(a.gestures.get(&Gesture::IndexTap), None);
    assert_eq!(a.dial.as_ref().map(|d| d.up.as_str()), Some("volume.up"));
    assert!(profiles.list[1].dial.is_none());
    assert_eq!(
        profiles.calls.get(&Gesture::IndexDouble),
        Some(&CallBinding::Do(Action::Run("call.answer".into())))
    );
    assert_eq!(profiles.calls.get(&Gesture::MiddleDouble), Some(&CallBinding::Nothing));
}

#[test]
fn unknown_gesture_keys_are_skipped_and_bad_json_is_an_error() {
    let (_, profiles) = parse(r#"{"profiles":[{"name":"A","map":{"index_hold":"x"},"dial":null}]}"#).unwrap();
    assert!(profiles.list[0].gestures.is_empty());
    assert!(parse("{").is_err());
    assert!(parse(r#"{"profiles":[]}"#).unwrap().1.list.is_empty());
}

#[test]
fn warnings_name_the_profiles_without_a_lock_or_a_way_out() {
    use airgestures_bridge::profiles::warnings;
    let json = r#"{"profiles":[
        {"name":"A","map":{"middle_hold":"controls.toggle","index_triple":"profile.next"},"dial":null},
        {"name":"B","map":{"swipe_up":"media.next"},"dial":null}]}"#;
    assert_eq!(warnings(json), "no-controls 1\nno-way-out 1");
    assert_eq!(warnings("{"), "");
}
