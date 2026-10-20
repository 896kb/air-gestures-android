# New actions: tap here, calls, voice assistant, notifications, automation

Sub-project 1 of the October 2026 roadmap, on branch `groundwork` (all roadmap work stays on
that branch; nothing is merged, pushed or tagged until the user says so).

## Behaviour (agreed)

| Action | Behaviour |
|---|---|
| **Calls** | A separate "During calls" mapping, its own card in Gestures. While the phone rings or a call is on, it overrides the normal mapping for the gestures set in it; every other gesture keeps its normal action. Defaults: index double tap → answer, middle double tap → decline (ringing) / hang up (in a call). Carrier calls only: Android lets only the system control self-managed (WhatsApp, Telegram, …) calls. |
| **Tap here** | Pick "Tap here", open the target app, drag a floating target onto the spot, Set. Drawn by the accessibility service that screen gestures already use (no new permission). One spot per gesture. |
| **Voice assistant** | Opens the default assistant (Gemini / Google). |
| **Dismiss notification** | Clears the newest notification the user could swipe away. |
| **Read notification** | Reads the newest notification aloud: app, title (sender) and text while unlocked; app and title only while locked. |
| **Send to automation** | A mappable action: the gesture sends the broadcast `dev.kb896.airgestures.GESTURE` with extra `gesture` (e.g. `swipe_left`) for Tasker / MacroDroid / Home Assistant, instead of doing anything itself. |

## Architecture

### Mapping layers (`Mappings.kt`, new, pure)

`Prefs.mapping()` (the string the bridge gets) becomes `Mappings.effective(…)`, built in layers:

1. the normal mapping (`map.<gesture>`, dial, hand), as today;
2. while `inCall` (ringing or off-hook), the "During calls" entries (`call.<gesture>`, unset = as usual) on top.

On the way out, a stored `automation` becomes `automation:<gesture key>`, because the bridge reports
only action names and the broadcast needs the gesture. The function takes plain values (maps,
the dial, the hand, `inCall`) so it is unit-testable; `Prefs` gathers them.

Later sub-project 2 adds a profile layer under the call layer (user-switched profiles, some
shipped ready-made, and per-app profiles); nothing here should assume a single normal mapping
beyond `Mappings.effective`'s inputs.

### Action names

| Name | Stored as | Notes |
|---|---|---|
| Tap here | `tap:<x>,<y>` | x, y = fractions of the screen's width and height, 4 decimals (`tap:0.5000,0.8125`) |
| Voice assistant | `assistant.voice` | |
| Dismiss notification | `notification.dismiss` | |
| Read notification | `notification.read` | |
| Answer call | `call.answer` | "During calls" only |
| Decline or hang up | `call.end` | "During calls" only; `endCall()` declines a ringing call, ends an active one |
| Send to automation | `automation` | effective mapping: `automation:<gesture>` |

The bridge passes action strings through untouched (only `;` and `=` are reserved), so there are
no Rust changes.

### Call state (`CallWatch`, in `BandService`)

A `TelephonyCallback.CallStateListener` (needs `READ_PHONE_STATE`), registered while the service
runs and the "During calls" mapping has any entry. On RINGING / OFFHOOK / IDLE it sets `inCall`
and re-sends `Mappings.effective(…)` through `link.setMapping`. Without the permission it isn't
registered (Setup shows the step).

### Running the actions (`Actions.kt`)

- **Tap:** `TouchService.tap(x, y)`: a 50 ms `dispatchGesture` stroke at the fractions times
  `currentWindowMetrics.bounds`. Needs the accessibility service (else the usual "screen gestures
  are off (Setup)" log line).
- **Tap picker:** `TouchService.pickSpot(onPicked)` shows `TYPE_ACCESSIBILITY_OVERLAY` windows:
  first a small floating bubble "Open the app, then tap here"; tapping it shows a full-screen
  translucent layer with a draggable crosshair and Set / Cancel. Set returns the fractions; the
  Gestures screen stores `tap:x,y`. The app moves itself to the back when picking starts and
  comes back to Gestures after Set / Cancel.
- **Voice assistant:** start `Intent.ACTION_VOICE_COMMAND` through `TouchService` (background
  starts are allowed for an enabled accessibility service), falling back to `Intent.ACTION_ASSIST`.
  Which one Gemini handles on HyperOS is checked on the phone.
- **Notifications:** through the existing listener (`MediaAccess`). "Newest" = latest `postTime`
  among active notifications that are clearable, not ongoing, not ours and not group summaries
  without content. Dismiss: `cancelNotification(key)`. Read: app label + `EXTRA_TITLE` +
  (`EXTRA_BIG_TEXT` or `EXTRA_TEXT`); app + title only when `KeyguardManager.isKeyguardLocked`.
  Spoken with one `TextToSpeech` owned by `Actions` (created lazily, shut down in `close()`),
  `QUEUE_FLUSH`, assistant audio attributes. "No notifications" is logged when there are none.
- **Calls:** `TelecomManager.acceptRingingCall()` / `endCall()` (both need `ANSWER_PHONE_CALLS`;
  deprecated, still supported). `endCall()` returning false → log "no call to end".
- **Automation:** `sendBroadcast(Intent("dev.kb896.airgestures.GESTURE").putExtra("gesture", key))`.
  Same action for release and dev builds.

The listener's class doc and the Setup text change: it now reads notifications, but only when a
read or dismiss gesture is made.

### Screens and Setup

- Gestures screen: the action picker gains Tap here, Voice assistant, Dismiss notification,
  Read notification, Send to automation. A second card, **During calls**, lists every gesture
  with "As usual" or an action; its picker adds Answer call and Decline or hang up. The dial and
  the middle hold aren't in it.
- `Prefs.usesScreen()` also covers `tap:` and `assistant.voice`; a new `usesNotifications()`
  (seek dial, read, dismiss) replaces `usesMedia()`; a new `usesCalls()` (any call action in
  "During calls") adds the Setup step **Phone calls** (`READ_PHONE_STATE` + `ANSWER_PHONE_CALLS`,
  one system prompt group).
- Manifest: `READ_PHONE_STATE`, `ANSWER_PHONE_CALLS`.

## Testing

- JUnit 4 unit tests (new `testImplementation("junit:junit:4.13.2")`, not AndroidX) for the pure
  parts: `Mappings.effective` (call layer on top only when in a call, "as usual" passes through,
  automation expansion incl. inside the call layer), tap spot format/parse (bounds, bad input),
  the read-aloud sentence (unlocked/locked, missing title or text).
- Emulators API 33–36: Gestures screen, both pickers, the During calls card, the new Setup rows,
  no crashes.
- Xiaomi (Android 17): every action with the band; a real incoming call from another phone
  (answer, decline, hang up; the normal mapping returns after); Gemini; a broadcast seen by an
  automation app if one is installed (else by `adb logcat` / a dumpsys of the broadcast).
- `CHANGELOG.md` `## Unreleased` gets the new actions.
