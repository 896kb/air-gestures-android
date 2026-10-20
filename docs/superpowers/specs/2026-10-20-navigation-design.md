# Accessibility navigation

Sub-project 5 of the October 2026 roadmap, on branch `groundwork` (nothing merged, pushed or
tagged until the user says so).

## Behaviour (agreed)

- Actions for any profile: **Focus next / previous** (a glowing coral outline moves between the
  screen's buttons, links, switches and text fields in reading order), **Select** (taps the
  outlined item; a text field opens the keyboard), **Scroll forward / back** (the outlined
  item's list, else the main scrollable area). Back and Home exist already. Dial choice
  **Navigate**: each step is focus next / previous.
- **New profile** offers **Empty** (the defaults) or **Navigate**: swipe right/left focus
  next/previous, swipe up/down scroll back/forward, index tap select, middle double back,
  middle tap home, dial navigate.
- A separate accessibility service, **Air Gestures navigation**, reads the screen's layout to
  find what can be focused; nothing is stored or leaves the phone. Setup lists it only when a
  navigation action is mapped, and says what it reads. The screen-gestures service still reads
  nothing.
- The outline follows the item across screen changes when it's still there, else starts at the
  first; it hides after 10 s without navigating.

## Architecture

- `Navigation` (pure, tested): `NavNode` (bounds, flags, children); `focusables(root)` — depth-first
  pre-order, visible + enabled + (clickable | editable | checkable), non-empty bounds; `step(list,
  at, by)` wraps; `find(list, previous)` keeps focus by view id + text + bounds.
- `NavigateService` (AccessibilityService, window content on): wraps `AccessibilityNodeInfo`,
  keeps the focused item, draws the outline in a `TYPE_ACCESSIBILITY_OVERLAY` window that isn't
  touchable, performs `ACTION_CLICK` (or `ACTION_FOCUS` + click for text fields) and
  `ACTION_SCROLL_FORWARD/BACKWARD` on the nearest scrollable ancestor or the largest scrollable.
- Actions `nav.next`, `nav.previous`, `nav.select`, `nav.scroll_forward`, `nav.scroll_back`;
  `DialChoice.NAVIGATE`.
- **Debug-only test hook** (`src/debug`): an `adb shell am broadcast` receiver, guarded by the
  `DUMP` permission (adb has it, apps can't), that runs any action — for emulator tests without
  the band. Not in release builds.

## Testing

JUnit for `Navigation`; emulator with the hook (the user watches the outline move); phone later.
