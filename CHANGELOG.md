# Changelog

Each release's section becomes its GitLab release notes (and what the app's update
notification shows). Add a section for the version before tagging it; the release job
fails without one.

## Unreleased

- Needs Android 13 or newer. Below that the app didn't work properly before either.
- On Android 13 to 15, linking the band no longer closes the app, and the band coming
  into range wakes the app again (that wake-up worked on Android 16 only).
- New gesture actions: tap a spot on the screen you pick (drag a target over the app
  it's for), open the voice assistant, read the newest notification aloud (only the app
  and title while the phone is locked) or dismiss it, and send the gesture to Tasker,
  MacroDroid or Home Assistant (broadcast `dev.kb896.airgestures.GESTURE`, extra
  `gesture`).
- Pinch and turn can go to automation too, for dimming a smart light: `gesture` is
  `dial` and `steps` the steps turned (+ up, − down), sent at most every 250 ms so a
  quick turn doesn't flood the light.
- During calls: while the phone rings or a call is on, gestures can do something else.
  By default an index double tap answers and a middle double tap declines or hangs up;
  every gesture is yours to set. Phone calls only: WhatsApp and other app calls can't
  be answered by another app.
- Home is shorter: Gestures, Setup and Activity have their own screens. Home shows how
  many Setup steps are left.
- A debug build installs beside the release (as "Air Gestures (dev)") instead of over it.
- The update card shows the whole first line of the release notes.

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
