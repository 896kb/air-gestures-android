# Feedback and status: tiles, widget, battery and idle notifications, profile vibration

Sub-project 3 of the October 2026 roadmap, on branch `groundwork` (nothing merged, pushed or
tagged until the user says so). Gestures behave the same locked or unlocked (the user chose no
lock-screen change). "Release the band for the laptop" is the Band tile / power button.

## Behaviour (agreed)

- **Quick Settings tiles** (two, the user adds either):
  - **Band**: on = keep the band connected, off = release it (as the power button). Subtitle:
    Connected · 72 % / Searching / Off / Bluetooth off. Long-press opens the app.
  - **Controls**: on/off (as the middle hold). Unavailable while the band service is off.
- **Widget** (4×1): "Meta Band · Connected · 72 %", "Profile: <name>" (when 2+ profiles), a
  power button and a controls button; the rest opens the app.
- **Notifications**, channels **Battery** and **Idle**:
  - battery ≤ 20 %, then ≤ 10 %: "Meta Band is at N %" — each once per discharge; both re-arm
    after the band has charged (a reading ≥ 50 % while charging, or 100 %).
  - 100 % while charging: "Meta Band is charged" — once per charge.
  - 21:00 daily (inexact): when the last reading was below 50 %: "Charge your band tonight?"
    with the reading and its time.
  - controls off for 15 minutes while the band is connected: "Controls have been off for
    15 minutes" with **Disconnect** (stops the service). Cancelled the moment controls go on
    or the service stops. The app can't power the band off; disconnecting stops its streaming.
- **Vibration on profile switch** (setting next to "Say the name", off by default): one short
  buzz for next or an app's own profile, two for previous.

## Architecture

- `BatteryAlerts` (pure, tested): input = reading (level, charging) + stored flags (`low20`,
  `low10`, `charged`) → which alert to post (or none) + new flags. Flags and the last reading
  (level, time) live in prefs (`battery.*`).
- `Reminder` (pure part tested: due or not given last reading + now): an `AlarmManager`
  `setInexactRepeating` alarm at the next 21:00, re-armed from `RestartReceiver` (boot / update)
  and when the service starts; a `ReminderReceiver` posts the notification.
- Idle: `BandService` posts a delayed runnable when controls go off while connected; any change
  (controls on, disconnected, stopped) removes it and cancels the notification.
- Tiles: `BandTile`, `ControlsTile` (`TileService`), `TileService.requestListeningState` on
  every state change; long-press → `MainActivity` via `ACTION_QS_TILE_PREFERENCES`.
- Widget: `BandWidget` (`AppWidgetProvider`, RemoteViews layout in the app's tones); buttons
  are `PendingIntent`s to `BandService` (start / stop / toggle controls); refreshed by
  `BandService` on state changes.
- Vibration: `VibratorManager`'s default vibrator, `VibrationEffect.createWaveform`; prefs
  `profile.vibrate`; `VIBRATE` permission (normal).

## Testing

JUnit for `BatteryAlerts` (each threshold once, re-arming, charged once, no alert on unknown
level) and the reminder's due check; emulators (tiles via `cmd statusbar`, widget via the
launcher, notifications by feeding status JSON through a debug path is not added — checked on
the phone); the phone for real readings.
