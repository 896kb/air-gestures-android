# Changelog

Each release's section becomes its GitLab release notes (and what the app's update
notification shows). Add a section for the version before tagging it; the release job
fails without one.

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
