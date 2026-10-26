# Changelog

Each release's section becomes its GitLab release notes (and what the app's update
notification shows). Add a section for the version before tagging it; the release job
fails without one.

## 0.9.18

- An app can say it's in front itself, for when the screen can't tell: broadcast
  `dev.kb896.airgestures.SET_PROFILE` with extra `profile` = its package name as it comes
  to the front, and an empty one as it leaves. Its own profile then applies until it
  leaves, even while it shows on another screen with the phone folded.
- An app that does that gets the band's state back, `dev.kb896.airgestures.STATUS` with
  `battery` (−1 when unknown), `charging`, `connected` and `controls` (on or off), sent
  to it alone, whenever one of them changes. A remote app can then tell a flat band from
  a lost connection.
- **Band controls:** Controls on/off, Next profile, Previous profile and the new Draw sit
  above the profiles, one gesture each (by default the middle hold, the index and middle
  triple taps, and Draw on the index hold), the same in every profile, in apps' own profiles
  and during calls. Each can be limited to palm positions. A profile's action on a
  band-control gesture is kept and shown as paused, and runs again once the band control
  moves; picking a used gesture says what will pause.
- **The pinch:** index and middle each have a hold, a dial (pinch and turn) and six drags
  (left, right, toward, away, up, down) as gestures or sliders. With a dial or drags, a
  finger's hold acts when you let go.
- **Palm position:** every gesture can do something else with the palm up or sideways (up
  to two positions); calibrate per band and wrist.
- **Drawings:** pinch and draw in the air or on a table, matched against the drawings you
  teach (three tries each); shared or per-profile actions; a pad shows what you draw.
- **Draw is on the index hold** by default. Coming from 0.7.7, it stays off if the index
  hold already does something (in any profile, or during calls): that hold keeps doing it.
- **The middle dial** (pinch and turn with the middle finger) is brightness in new
  profiles; your existing profiles keep theirs.
- **Teach your drags** for your own wrist. Turning drags or sliders on before you have
  says so, with a link: untaught, away and right can mix up.
- **Automation:** overrides carry `palm`; the middle dial, sliders and drawings have their
  own names. Every broadcast also carries `profile`, the name of the profile it came from
  (`During calls` for the During calls actions).
- **Home devices:** name your lamps, speakers and the like (under Gestures, or right after
  picking Send to automation) and tie any Send to automation to one, gestures, overrides,
  dials, sliders and drawings alike. Its broadcast then carries `device` with the device's
  name. Renaming a device renames it everywhere (update your rules too); deleting one
  leaves its gestures sending without a device. The settings backup includes them.
- Sequences can send to automation: mix phone actions and Home Assistant in one gesture
  (`gesture=sequence`, `name`, `device`, `profile`).
- The settings backup is version 3 (band controls, drawings and Home devices); version 1
  and 2 backups still restore.

## 0.7.7

- Needs Android 13 or newer. Below that the app didn't work properly before either.
- On Android 13 to 15, linking the band no longer closes the app, and the band coming
  into range wakes the app again (that wake-up worked on Android 16 only).
- New gesture actions: tap a spot on the screen you pick (drag a glowing orb over the
  app it's for), open the voice assistant, read the newest notification aloud (only the app
  and title while the phone is locked) or dismiss it, and send the gesture to Tasker,
  MacroDroid or Home Assistant (broadcast `dev.kb896.airgestures.GESTURE`, extra
  `gesture`). Picking it shows how to set each of them up, with a test to try it, and
  the guide is on band.896.ro too.
- Pinch and turn can go to automation too, for dimming a smart light: `gesture` is
  `dial` and `steps` the steps turned (+ up, − down), sent at most every 250 ms so a
  quick turn doesn't flood the light.
- Profiles: several sets of gestures (each with its own pinch and turn), renamable,
  under Gestures. An index triple tap moves to the next profile, a middle triple tap to
  the previous one; apps can have their own profile, which applies while the app is open
  (the triple taps don't switch there). Every switch shows the profile's name, and can
  say it. With more than one profile, index and middle taps wait a moment so a triple
  tap can be told apart.
- Swipe here: place a swipe with a coral start orb and a green end orb (quick or slow),
  or draw it with your finger, curves and timing included.
- Sequences: a gesture can run a list of actions with waits between them, built step by
  step or recorded (each tap and swipe goes on to the app as you make it; don't type while
  recording). Try one from its screen.
- Navigation for when the screen is hard to reach: actions that move a glowing outline
  between buttons and fields, select, and scroll, and a ready-made Navigate profile (New
  profile, Navigate). It's a separate accessibility service, Air Gestures navigation, that
  reads the screen's layout to find what to focus; nothing is kept or leaves the phone.
- Practice: guided lessons for every gesture, one at a time, waiting until the band sees
  it and saying what it would do. Nothing runs while you practise.
- Back up and restore the settings (profiles, During calls, wrist, switches) to a file,
  under Setup. The owner key isn't in it; it has its own export.
- Two Quick Settings tiles: Air Gestures (connect or release the band, with its status
  and battery) and Gesture controls (on or off). Long-press either to open the app.
- A home-screen widget: ON with the battery, OFF or DISCONNECTED at a glance, the
  profile in use (tap it to pick another), and a connection and a gestures button, green
  when on and coral when off.
- Battery notifications: at 20 % and 10 %, when it's charged, and at 21:00 when the last
  reading was below half. When the controls have been off for 15 minutes, a notification
  offers to disconnect the band (it goes away once the controls are back on).
- Optional vibration when the profile switches: once for the next, twice for the
  previous (the band already buzzes for its own gestures).
- During calls: while the phone rings or a call is on, gestures can do something else.
  By default an index double tap answers and a middle double tap declines or hangs up;
  every gesture is yours to set. Phone calls only: WhatsApp and other app calls can't
  be answered by another app.
- Home is shorter: Gestures, Setup and Activity have their own screens. Home shows how
  many Setup steps are left.
- A debug build installs beside the release (as "Air Gestures (dev)") instead of over it.
- The update card shows the whole first line of the release notes.
- Sheets, dialogs and the buttons shown over other apps keep their whole shadow; long
  lists fade at their edge instead of being cut.

## 0.4.0

- A new package name, dev.kb896.airgestures, the same family as my other apps and the
  one registered with Google. Android sees it as a new app: this version installs next
  to the old one instead of updating it, and starts without your band or settings.
- Moving over: in the old app, Export the key; open this one, Import that file and run
  Setup again (Accessibility, notification access and the HyperOS permissions); then
  uninstall the old app. Until you do, both try to hold the band.
- The band coming into range wakes the app again even when its companion link was
  restored or moved over rather than made in the app.

## 0.3.9

- A new app icon, also shown next to the name in the app.
- New gesture actions: swipe the screen left or right, turn the flashlight on or off,
  and open an app you pick. On Xiaomi phones, Setup also asks for HyperOS's "Open new
  windows while running in the background", which opening apps needs.
- Pinch and turn can move the playback position of what's playing (5 s per step). It
  replaces next/previous track as a dial choice; needs notification access (Setup).
- Shows when Bluetooth is off, and won't connect or claim the band until it's on.
- Tells you when a newer version is out, with a notification and a card at the top of
  the app. The installed version is shown at the bottom.
- A Support on Ko-fi button.
