# Practice lessons and settings backup

Sub-project 4 of the October 2026 roadmap, on branch `groundwork` (nothing merged, pushed or
tagged until the user says so).

## Behaviour (agreed)

- **Practice** (Home row between Gestures and Setup): guided lessons, one gesture at a time —
  a one-line how-to, "waiting…", then "Got it · would: <action> (<profile>)" and Next; Skip and
  Back always. Steps: swipe up/down/left/right, index tap/double, middle tap/double, pinch and
  turn (only when the profile's dial does something), middle hold, index/middle triple tap
  (only with 2+ profiles), then "All done". Step 0 "Connect the band" (with the power button)
  while it isn't connected. **Nothing runs during the lessons.**
- **Backup** (Setup, beside Export the key): **Back up settings** writes
  `air-gestures-settings.json` — profiles (names, gestures, dials, tap spots, apps), the picked
  profile, During calls, wrist, "say the name", "vibrate"; **not the owner key**. **Restore
  settings** reads one, checks it, confirms ("Replace your settings with this backup's N
  profiles?"), replaces and sends the mapping to the band. Apps that aren't installed keep their
  assignment.

## Architecture

- `Lessons` (pure, tested): the step list for (profile count, dial mapped); each step says what
  counts as done — a gesture label from the bridge (`swipe up`, `index double tap`, …), a dial
  action, the controls flipping (middle hold), or a profile action (`profile.next` / `previous`).
- Practice mode in `BandService` (`practice: Practice?`): actions from the bridge go to the
  lesson instead of running; a rising gesture count reports the last gesture; controls are on for
  the lesson, a flip to off (the middle hold) is reported and put back, and the controls return to
  how they were when the lesson ends.
- `Backup` (pure, tested with org.json): `{"version":1,"profiles":[…],"picked":…,"apps":{…},
  "calls":{…},"hand":…,"speak":…,"vibrate":…}`; reading refuses other versions, no profiles,
  or anything malformed, before anything changes.

## Testing

JUnit for `Lessons` and `Backup`; emulators for the screens; the phone for the lessons with the band.
