# Groundwork: Android 13+ compatibility, split UI, lint in CI

Sub-project 0 of the October 2026 roadmap (groundwork → new actions → per-app profiles →
feedback and status → practice and backup → accessibility mode → laptop handoff). The whole
roadmap ships as one release; nothing is tagged until the version is chosen at the end.

## Why

- Lint reports 33 NewApi errors. In practice the companion link and the band's wake-up only
  work on Android 16: Android 10 crashes at launch (`insetsController`), Android 11–15 crash
  on Setup → Link (`startObservingDevicePresence(ObservingDevicePresenceRequest)` is API 36,
  called outside `runCatching`), and on 13–15 `BandPresenceService` never wakes the app
  (it only overrides the API 36 `onDevicePresenceEvent`).
- `MainActivity.kt` is 1,024 lines on one scrolling page; every later sub-project adds UI.
- CI never runs lint, so none of this showed.

## 1. Android 13+ (minSdk 33)

- `minSdk` 29 → 33. Removes the API 30/31/33 errors (`insetsController`, `myAssociations`,
  `AssociationInfo`, `onAssociationPending`, `disassociate(id)`). Delete the
  `SDK_INT >= 33` checks this makes always-true (`BandClaim.kt`, `MetaSignIn.kt`); keep the
  `>= 34` check in `Companion.link()`.
- `Companion.watch(manager, association)`: on API 36+ calls
  `startObservingDevicePresence(ObservingDevicePresenceRequest)` (today's code); on 33–35
  calls `startObservingDevicePresence(association.deviceMacAddress.toString())`. Used by
  both `link()` (`onAssociationCreated`) and `observe()`, always inside `runCatching`.
  `observe()` finds the association in `myAssociations` by the stored id, as now.
- `BandPresenceService` overrides `onDevicePresenceEvent` (36+, `EVENT_BLE_APPEARED`) and
  `onDeviceAppeared(AssociationInfo)` (33–35). Both call one `appeared()` that starts
  `BandService` only when `Prefs.running` and `BandService.instance == null`, so a
  double call on 36 is harmless.
- `MetaSignIn` back: it already registers an `onBackInvokedDispatcher` callback on 33+,
  which Android 16 uses (targetSdk 36 opts in by default). On 13–15 the app hasn't opted
  in (`android:enableOnBackInvokedCallback` is unset), so there it relies on the
  deprecated `onBackPressed` override (lint GestureBackNavigation). Set
  `android:enableOnBackInvokedCallback="true"` on `<application>`, register the callback
  unconditionally, delete `onBackPressed`. Other screens keep the default back (finish).
- "Turn on Bluetooth" (`MainActivity.kt:172`, MissingPermission): if `BLUETOOTH_CONNECT`
  isn't granted, open Bluetooth settings instead of `ACTION_REQUEST_ENABLE`.
- Lint warnings: fix the InlinedApi/ObsoleteSdkInt ones that minSdk 33 makes moot; look
  at the rest individually. Warnings don't fail the build.

## 2. Split UI

### `Ui.kt`: `abstract class Screen : Activity()`

- Window setup every screen needs: `Cb.PAGE` background, light status/navigation bars,
  edge-to-edge inset padding (moved from `MainActivity.onCreate`).
- `page(title: String?)`: a scrolling column; with a title, a header row with a back
  chevron that calls `finish()` (the system back gesture works on its own).
- The UI kit, moved unchanged as methods so call sites read the same: `dp`, `dpi`,
  `column`, `row`, `weighted`, `card`, `icon`, `pill`, `segment`, `segmented`,
  `settingRow`, `choose` (with `Option`), `confirm`.
- New: `navRow(icon, label, badge: String?, onClick)` for Home's list (a badge is drawn as
  a WARN chip); the title row's back button uses the existing `ic_arrow_left`, the rows
  the existing `ic_chevron_right`, so there's no new art.

### Screens

| Activity | Contents |
|---|---|
| `MainActivity` (Home) | Header, update card, band video, power block, status card (battery, last gesture), Controls + Wrist toggles, nav list, version line, Ko-fi pill. Still requests permissions on first launch. |
| `GesturesActivity` | Gesture rows; action, app and dial pickers (`pick`, `pickApp`, `pickDial`). |
| `SetupActivity` | Setup rows, claim (`claim`/`runClaim`), permission requests, Bluetooth state receiver, the companion picker (its result arrives through the CDM callback), `onActivityResult` (Meta sign-in, key import/export). |
| `ActivityLogActivity` | The live log. |

Home's nav list: **Gestures ›**, **Setup ›** (a WARN chip "N to do" while any required
step isn't done), **Activity ›**. Profiles,
Practice and Settings rows arrive with their own sub-projects, with no empty screens now.
All new activities `exported="false"`.

### `Setup.steps(context): List<Step>`

`Step(title, detail, done, button, always, icon, required, action: (SetupActivity) -> Unit)`.
`required = false` for rows that are actions rather than to-dos (Claim, Export, Forget,
Sign out); "N to do" counts required steps that aren't done.
Pulls the step list and "is it done" logic out of `renderSetup()` (today ~165 lines
mixing both). `SetupActivity` renders the steps; Home counts `!done` for the badge, so
the two can't disagree. Steps that need an activity result take the calling activity.

### State

Each screen subscribes to `BandService.state` in `onResume` and removes the listener in
`onPause`, as `MainActivity` does today. The cards look the same as now; this changes
where things are, not how they look. The choose() picker no longer re-renders the
gesture and setup cards itself; callers re-render in their `onChoice`.

## 3. CI, verification, git

- `.gitlab-ci.yml` `test` job: add `./gradlew --no-daemon :app:lintDebug` after the unit
  tests. Lint errors fail the pipeline.
- Emulators: install `emulator` + Google APIs x86_64 system images for API 33, 34, 35, 36
  (~6–8 GB). These images translate ARM, so the arm64-only bridge should load; if not,
  build an x86_64 bridge locally only (not shipped). On each:
  - launch; Home → each sub-screen → back (gesture and chevron);
  - Setup → Link: picker finds nothing / is cancelled, **no crash**;
  - Meta sign-in: back gesture moves back within the WebView, then closes it (13–15 is
    where this changes; 16 should behave as before).
- Not provable on emulators: the band waking the app on 13–15 (no real Bluetooth). That
  path is checked by reading the code only, and the notes say so.
- Xiaomi 17 Pro (Android 16, adb over Wi-Fi; the user gives the port): connect, gestures,
  editing mappings, Setup states, Meta sign-in back, presence wake after stopping the
  service.
- `./gradlew assembleDebug lintDebug` clean of errors and `cargo test` green before merge.
- Branch `groundwork`, merged into `master` once verified. No tag. `CHANGELOG.md` gets an
  `## Unreleased` section (the two commits after v0.4.0 plus this work, including "needs
  Android 13 or newer"); it's renamed to the chosen version at the end. The `versionName`
  fallback stays as it is until then.
