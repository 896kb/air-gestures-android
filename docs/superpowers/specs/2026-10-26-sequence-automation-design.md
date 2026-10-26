# Send to automation inside a sequence (0.9.18)

Owner's ask (2026-10-26): one gesture, several actions, e.g. a pinch opens Instagram and turns
on the flashlight, or turns the lights off in Home Assistant while closing the curtains.

## What exists

Sequences (Gestures screen → Sequences card, `SequenceActivity`) already run several actions
from one gesture: any action is a step, plus waits; steps follow each other with
`STEP_PAUSE_MS` (150 ms) unless a wait says otherwise. "Instagram + flashlight" works today.

The gap: the step picker leaves out **Send to automation**
(`pickAction(inSequence = true)` filters `Action.AUTOMATION`), because a gesture's event is
named after the gesture (`index_tap`) and a step has no gesture of its own. So phone and home
actions can't be mixed in one sequence.

## Design

**Picking.** In a sequence, Add step / change step offers Send to automation. It asks "For
which device?" with the same `pickDevice` as gestures (No device, the Home devices, add one).
The step is stored like a gesture's: `automation` or `automation@<device id>`. The step row
reads "Send to automation" or "Send to automation · Curtains" (the existing
`GestureScreen` label). The automation guide is not shown from a sequence (it is about gesture
names); the step row's subtitle says what HA receives instead (see below).

**Running.** When `Actions.sequence` reaches an automation step it sends one broadcast
(`dev.kb896.airgestures.GESTURE`) with:

| extra | value |
|---|---|
| `gesture` | `sequence` |
| `name` | the sequence's name as it is now (`Goodnight`) |
| `device` | the device's name as it is now, when the step has one |
| `profile` | the profile whose gesture started the sequence (During calls: its layer name, as gestures send) |

No `steps`, no `palm`. A new `Mappings.Automation.Sequence(name, profile, device)` builds these
extras through `Mappings.extras`, so the field names match gestures and drawings exactly.

The profile has to reach the sequence run: the sequence action the bridge reports carries it the
way Send to automation already does (`<stored>/<URL-encoded profile>`), and `Actions` hands it to
the steps. A sequence started without a profile (Try it in `SequenceActivity`) sends no `profile`.

**Matching in HA / Tasker.** Match `gesture == sequence` and `name`, plus `device` when a
sequence has several HA steps. Two HA steps for the same device in one sequence send the same
event twice; that's allowed (e.g. a toggle twice with a wait between).

**Renames.** Renaming a sequence or a device changes what is sent from then on (as with drawings
and devices). `SequenceActivity`'s rename dialog says so when the sequence has an automation step:
"Home Assistant matches this name; update your automations too."

**Unchanged.**
- Dials and sliders still can't be steps (they turn continuously; a sequence fires once).
- Another sequence, Nothing and call-only actions still can't be steps.
- Recording a sequence never records automation steps (nothing to record).
- A running sequence still blocks another start ("… is still running").

**Backups.** No format change: steps are action ids already. An older app that restores a
sequence with an automation step logs "unknown action" for that step and runs the rest.

**Linux.** No code change; a command can already run several things (`cmd1; cmd2`, or a script).
The README gets a short "several actions" example next to the Home Assistant one.

**Docs.** The in-app automation guide and the site's automation guide get a "From a sequence"
paragraph with the extras above and an HA trigger example (`event_type: android.intent_received`,
`gesture: sequence`, `name: Goodnight`).

## Tests

- `Mappings`: an automation step in sequence `Goodnight` for device `Curtains` from profile
  `Music` → extras `gesture=sequence`, `name=Goodnight`, `device=Curtains`, `profile=Music`; no
  device and no profile → only `gesture` and `name`.
- The sequence action id carries the profile and parses back; an id without a profile still runs.
- `pickAction(inSequence = true)` offers Send to automation and still leaves out Sequence,
  Nothing and the call actions.
- The step label: "Send to automation · Curtains"; a deleted device → "Send to automation".
- A sequence mixing a phone step, a wait and two automation steps sends two broadcasts in order,
  after the phone step (JVM test with a recording sender).
- Emulator: build a sequence with flashlight + two HA steps, run Try it, check both broadcasts
  with `adb shell dumpsys activity broadcasts` or a test receiver.
