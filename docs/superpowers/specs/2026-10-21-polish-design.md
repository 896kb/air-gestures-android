# 0.7.7 polish — design

Date: 2026-10-21 · Branch: `groundwork` (ships in v0.7.7; no tag until the owner says)

From the owner's review of the dev build and the news screenshots.

## 1. Shadows that are never cut

**Problem.** A raised view's shadow is drawn outside its bounds. Wherever a raised pill, card, sheet or bar sits in a container exactly its own size (a WRAP_CONTENT row, a dialog window, a WRAP_CONTENT overlay window), the shadow is clipped to a hard edge. Seen in:

- the automation guide sheet: "Send a test" / "Full guide" cut underneath and on the left, and the sheet's own bottom corners squared off
- the picker's bottom bar (Quick / Cancel / Set), the first "tap here" bubble, and the recorder's Stop / Cancel window

**Fix, in the shared code so new screens inherit it:**

- **One helper for rows of buttons** (`Ui.kt` / `Cb`): it reserves room for the shadow, about 8 dp below and 4 dp on the sides and top, and turns off `clipToPadding`/`clipChildren` on the row. Every row of pills in sheets and screens uses it.
- **Sheets and dialogs** (`sheet`, `choose`, `confirm`, `ask`): the window or decor gets enough transparent inset for the panel's own shadow, so the rounded corners and the bottom edge render in full.
- **Floating overlay windows** (the SpotPicker bubble, the controls window, the layer's hint and bar): the content gets a transparent margin of about 24 dp inside its WRAP_CONTENT window.
- **Buttons inside a raised bar are flat.** Only the bar is raised, the way pills sit on the page on the site.

**Audit.**

- Find every `elevation =` and raised background in the app, and check each one's container.
- Screenshot every changed screen on the emulator (ag36, visible floating pinned window) before and after, at full resolution:
  - the guide sheet
  - the choice lists
  - the confirm and rename dialogs
  - the action list
  - Setup
  - the home cards
  - the picker bubble, bar and recorder
  - the widget
- Send the owner the before/after pairs.

## 2. The floating controls while placing or recording

- **The first bubble:**
  - A short coral pill per mode: *Place the tap* / *Place the swipe* / *Draw the swipe* / *Start recording*.
  - A **round ✕ button with a fixed size** (44 dp, `ic_x`) instead of the "Cancel" text pill, so it can never be squeezed.
  - Above them, a small caption pill: *Open the app first*.
  - The coral pill ellipsizes if it must. The ✕ never shrinks.
- **The hints while placing:**
  - Tap: *Drag the orb onto the spot*. This one is unchanged.
  - Swipe: *Drag coral to start, green to end*.
- **The bottom bar and the recorder's bar** use the same flat-buttons-on-a-raised-bar style, with room for the shadow (see §1).

## 3. The swipe orbs

- The end orb uses the brand green `Cb.Signal.LIVE`, replacing the blue `Cb.Signal.INFO`.
- The path and arrow fade from coral at the start to green at the end.
- The start orb stays coral. When several orbs are shown, they keep distinct colours.

## 4. The widget

**Layout (4×1):**

- **Behind everything:** a still frame of the band from `assets/meta-band.mp4`, saved as `res/drawable-nodpi/widget_band.webp`. It's cropped to the band, centred or right of centre, and shown at about 28 % opacity on the page-tone background.
- **Left:** the app mark.
- **Middle:**
  - **A state chip** in capitals:
    - **ON** (green: `Cb.Signal.LIVE` fill, its dark label) when the band is connected
    - **OFF** (coral: `Cb.Signal.STOP`) when the connection is off (no service)
    - **DISCONNECTED** (grey: the ink-500 chip) when the connection is on but the band isn't connected (searching, Bluetooth off)
  - **The battery after the state:** a centred dot, then the band's charge, e.g. **ON · 84 %**. It's shown only while there's a live reading from the band (ON). OFF and DISCONNECTED have no reading, and the widget never shows a stale one.
  - **Below it:** the current profile's name and a ›.
  - No band name, no sentence.
- **Right:** two round buttons.
  - **Power:** green while the connection is on, coral while it's off.
  - **Gestures (hand):** green while gestures are on, coral while they're off or the connection is off.
  - Taps work as today: power starts or stops; the hand toggles the gestures, or opens the app while the connection is off.

**Behaviour:**

- **Tapping the profile name** opens the **profile picker**. Tapping anywhere else opens the app, as today.
- **The profile name** comes from the profile store, so it shows even while the connection is off.

**The profile picker:**

- A new small activity, `ProfilePickerActivity`, translucent, shown as a bottom sheet over the home screen in the app's sheet style. It is excluded from recents and has no launcher entry.
- It lists the user's profiles; the one in use is marked.
- Tapping one switches to it the same way the triple taps do (pop-up and spoken name as set), then closes the picker. If the service isn't running, it only sets the active profile in the store.
- If an app profile currently holds the screen, there's a one-line note: it takes over again in that app.

**Refresh:** `Surfaces.refresh` already redraws on every state change; the profile switch also triggers it.

## 5. The guide sheet's Home Assistant text

Make it match the site:

- needs Home Assistant app 2026.8.4 or newer
- turn on Last update trigger and "Add new intent"
- **check the saved intent reads the line above**, because the switch saves the next broadcast
- force-stop the app once

## Out of scope

Anything else on the widget: last gesture, a 2×2 size. The tiles stay as they are.

## Testing

- **Unit (JVM):** the widget state mapping, as a pure function `WidgetState.of(running, connected, paused, battery)` → (chip text, chip colour, power colour, hand colour), the chip text including " · 84 %" only when connected with a reading, with tests for every combination.
- **Emulator:**
  - the before/after screenshots from §1
  - the widget placed on the home screen in each state: OFF, then DISCONNECTED by starting the connection with no band, ON through the debug hook if possible, otherwise its screenshot in the remaining states
  - the picker switching profiles, with the widget updating
- **Lint:** 0 errors. **Kotlin unit tests and `cargo test`:** all pass.
- **Phone:** install the dev build over the current one (dev key AB:99…, the "dev" icon tab), keeping the data.
