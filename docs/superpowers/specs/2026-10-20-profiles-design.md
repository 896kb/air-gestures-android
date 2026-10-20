# Profiles: several gesture sets, per-app profiles, triple-tap switching

Sub-project 2 of the October 2026 roadmap, on branch `groundwork` (nothing merged, pushed or
tagged until the user says so).

## Behaviour (agreed)

- A **profile** = a name + its own 8 gestures + its own pinch and turn. Shared by all profiles:
  During calls and the wrist. The existing mapping becomes **Profile 1** (renamable).
- **New profile** adds one, starting from the default mapping. Any profile but the last can be
  deleted.
- **Per-app profiles:** a profile lists apps. While one of them is in front, that profile applies
  and the triple taps are ignored; leaving it returns to the profile the user picked.
- **Switching:** index triple tap → next profile, middle triple tap → previous, wrapping round.
  Only while the user has 2 or more profiles; with one, nothing changes.
- **Feedback:** a pop-up "Profile: <name>" on every switch (manual or per-app), the name in the
  band's notification and on Home; a setting "Also say the profile's name" (off by default)
  speaks it.

### The cost of triple taps (only with 2+ profiles)

The bridge has to wait to see whether a third tap follows, so for both index and middle:
- a **double tap** fires ~0.45 s later than now;
- a **single tap** waits ~0.45 s (the whole triple window) even when its double tap is set to
  Nothing (today it waits only when the double has an action), or a triple tap would also run
  it; two slow single taps within that window run the tap's action once.

## Architecture

### Store (`Profiles.kt`, new)

- SharedPreferences `profiles` = JSON list `[{"id":"p1","name":"Profile 1","map":{gesture key:
  action id},"dial":"volume"}]`; `profile.current` = the picked id; `profile.apps` = JSON object
  package → id; `profile.speak` = Boolean.
- **Migration** on first read: no `profiles` yet → Profile 1 from the old `map.<gesture>` and
  `dial.choice` keys (left in place, unused).
- Pure part (`ProfileList`, unit-tested): parse/serialize, `next`/`previous` with wrap, `active(
  picked, foregroundPackage, apps)`, add (id `p<n+1>` not in use, name "Profile <n>"), rename,
  delete (never the last; a deleted picked profile → the first; its apps unassigned).

### Mapping

`Prefs.actionId(gesture)` / `dial` read the **active** profile; `setAction`/`dial` write a given
profile (the screen edits a chosen profile, not necessarily the active one). `Prefs.mapping()`
adds `triples=profiles` when there are 2+ profiles. `Mappings.effective` is unchanged apart from
taking the active profile's map.

### Foreground app (`TouchService`)

`accessibilityEventTypes` gains `typeWindowStateChanged` (still no window content). On each event
with a package name that isn't ours, `com.android.systemui`, or the current input method, and has
a launcher entry (so dialogs/overlays of system packages don't flip profiles), it reports the
package to `Profiles.foreground(pkg)`. If the active profile changes, the mapping is re-sent and
the switch is shown. The service description says it notices which app is open (for per-app
profiles), and still reads nothing on screen. Setup's Screen gestures step also appears when any
app has a profile.

### Bridge (Rust)

- `Config.band.profile_triples: bool`, set by `triples=profiles` in the mapping (absent → false).
- `Controller`: triple-tap tracking per finger (`toggle_taps` + the finger it belongs to). For a
  finger with a triple use (the configured toggle, or profile triples): single taps are held as if
  the double had an action; the double tap is held `TRIPLE_TAP_GAP`; three singles within the gap
  → the toggle, or `Command::Run("profile.next")` (index) / `"profile.previous"` (middle). Not
  while controls are off. `last_gesture` = "triple tap".
- Tests in `controller.rs`: index ×3 → profile.next; middle ×3 → profile.previous; without
  `triples=profiles` a triple tap is three ordinary taps; a single tap with triples on fires after
  the wait, once.

### Running `profile.next` / `profile.previous` (`Actions` → `BandService`)

Ignored while the app in front has its own profile. Otherwise the picked profile moves, the
mapping is re-sent, and the switch is shown.

### Screens

- **Gestures** → a list: each profile (name, "N apps", current marked), **New profile**, the
  **During calls** card, the **Also say the profile's name** switch.
- **Profile screen** (`ProfileActivity`, extra = profile id): gestures + dial as today; **Rename**
  (dialog with a text field), **Delete** (confirm), **Apps** card (each app with remove, **Add
  app** via the app picker; an app belongs to one profile, adding it elsewhere moves it).
- Home's status card: **Profile** (the active one's name).

## Testing

- JUnit: `ProfileList` (migration input → Profile 1, round-trip, wrap both ways, active with and
  without an app profile, add/rename/delete rules).
- Rust: the controller tests above; `cargo test` green.
- Emulators 33–36: the screens, no crashes.
- Xiaomi: triple taps with the band (both ways, wrap), the delay on single/double taps feels OK,
  opening an assigned app switches and locks, leaving returns.
