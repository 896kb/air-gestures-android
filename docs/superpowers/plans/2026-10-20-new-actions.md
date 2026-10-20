# New Actions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Tap here, Voice assistant, Dismiss/Read notification, Send to automation, and a "During calls" mapping (answer / decline-or-hang-up) to the Android app.

**Architecture:** Action strings stay opaque to the Rust bridge. A pure `Mappings.effective()` builds the string the bridge gets from the normal map plus, while `CallWatch.inCall`, the call layer; `Prefs.mapping()` calls it, so every existing `link.setMapping(prefs.mapping())` and the connect-time mapping pick it up. Pure helpers (`Tap`, `Speech`, `Notes`) carry the logic that can be unit-tested; `Actions` runs them against Android APIs.

**Tech Stack:** Kotlin (AGP 9.4.1), plain Android (no AndroidX), JUnit 4.13.2 (new, tests only), JDK 21.

**Spec:** `docs/superpowers/specs/2026-10-20-new-actions-design.md`

**Branch:** `groundwork` (all roadmap work; never tag, merge or push unasked).

**Check command** (build + lint + unit tests):

```bash
cd ~/Projects/air-gestures-android && JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew --console=plain assembleDebug lintDebug testDebugUnitTest 2>&1 | grep -E 'BUILD|^e: |FAILED|tests completed|Lint found'; grep -cE 'Error: ' app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt
```

## File map

| File | |
|---|---|
| `app/build.gradle.kts` | `testImplementation("junit:junit:4.13.2")` |
| `app/src/main/java/dev/kb896/airgestures/Mappings.kt` (new) | `Mappings.effective`, `Tap`, `Speech`, `Notes` — pure |
| `app/src/test/java/dev/kb896/airgestures/MappingsTest.kt` (new) | their tests |
| `.../Mapping.kt` | new `Action` entries, `Action.of` for `tap:` |
| `.../Prefs.kt` | call layer storage, `mapping()` via `Mappings`, `uses*()` |
| `.../CallWatch.kt` (new) | call state → `inCall` |
| `.../BandService.kt` | owns `CallWatch`; static `setAction`/`setCallAction` |
| `.../MediaAccess.kt` | `instance`, honest doc |
| `.../TouchService.kt` | `tap()`, `startFromBackground()`, `pickSpot()` overlay |
| `.../Actions.kt` | the new actions, TTS |
| `.../GesturesActivity.kt` | new picker entries, During calls card, tap picking |
| `.../Setup.kt`, `.../SetupActivity.kt` | Phone calls step, notification access wording |
| `app/src/main/AndroidManifest.xml` | `READ_PHONE_STATE`, `ANSWER_PHONE_CALLS`, listener label |
| `app/src/main/res/values/strings.xml` | accessibility description |
| `app/src/main/res/drawable/ic_{crosshair,mic,bell_off,message_square_text,zap,phone,phone_off}.xml` (new) | Lucide icons |
| `CHANGELOG.md` | Unreleased |

---

### Task 1: pure helpers, test-first

**Files:** `app/build.gradle.kts`, create `Mappings.kt`, `app/src/test/java/dev/kb896/airgestures/MappingsTest.kt`

- [ ] **Step 1: JUnit.** Append to `app/build.gradle.kts`:

```kotlin
dependencies {
    // JVM tests of the pure parts (Mappings.kt); not AndroidX.
    testImplementation("junit:junit:4.13.2")
}
```

- [ ] **Step 2: failing tests** — `MappingsTest.kt`:

```kotlin
package dev.kb896.airgestures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MappingsTest {
    private val normal = linkedMapOf(
        "swipe_up" to "media.previous",
        "index_double" to "",
        "middle_tap" to "automation",
        "middle_double" to "volume.mute",
    )
    private val calls = mapOf("index_double" to "call.answer", "middle_double" to "call.end", "swipe_up" to "", "middle_tap" to "-")

    private fun effective(inCall: Boolean) =
        Mappings.effective(normal, calls, "volume.up", "volume.down", "band", inCall)

    @Test fun `out of a call the normal mapping goes through, automation named after its gesture`() = assertEquals(
        "swipe_up=media.previous;index_double=;middle_tap=automation:middle_tap;middle_double=volume.mute;" +
            "dial_up=volume.up;dial_down=volume.down;hand=band",
        effective(inCall = false),
    )

    @Test fun `in a call the call layer is on top, as usual passes through, a dash is nothing`() = assertEquals(
        "swipe_up=media.previous;index_double=call.answer;middle_tap=;middle_double=call.end;" +
            "dial_up=volume.up;dial_down=volume.down;hand=band",
        effective(inCall = true),
    )

    @Test fun `automation inside the call layer is named after its gesture too`() = assertEquals(
        "swipe_up=automation:swipe_up",
        Mappings.effective(linkedMapOf("swipe_up" to "media.next"), mapOf("swipe_up" to "automation"), "", "", "band", true)
            .substringBefore(";dial_up"),
    )

    @Test fun `a tap spot round-trips with four decimals`() {
        assertEquals("tap:0.5000,0.8125", Tap.format(.5f, .8125f))
        assertEquals(.5f to .8125f, Tap.parse("tap:0.5000,0.8125"))
    }

    @Test fun `bad or off-screen tap spots are refused`() {
        assertNull(Tap.parse("tap:1.2,0.5"))
        assertNull(Tap.parse("tap:abc"))
        assertNull(Tap.parse("media.next"))
    }

    @Test fun `unlocked the whole notification is read, locked only app and title`() {
        assertEquals("WhatsApp. Ana: are you coming tonight?", Speech.sentence("WhatsApp", "Ana", "are you coming tonight?", locked = false))
        assertEquals("WhatsApp. Ana.", Speech.sentence("WhatsApp", "Ana", "are you coming tonight?", locked = true))
        assertEquals("Gmail. 3 new emails", Speech.sentence("Gmail", null, "3 new emails", locked = false))
        assertEquals("Gmail.", Speech.sentence("Gmail", " ", null, locked = true))
    }

    @Test fun `the newest notification skips ongoing, ours and empty summaries`() {
        val notes = listOf(
            Notes.Note("old", 1, clearable = true, ongoing = false, ours = false, emptySummary = false),
            Notes.Note("nav", 9, clearable = false, ongoing = true, ours = false, emptySummary = false),
            Notes.Note("ours", 8, clearable = true, ongoing = false, ours = true, emptySummary = false),
            Notes.Note("summary", 7, clearable = true, ongoing = false, ours = false, emptySummary = true),
            Notes.Note("chat", 5, clearable = true, ongoing = false, ours = false, emptySummary = false),
        )
        assertEquals("chat", Notes.newest(notes)?.key)
        assertNull(Notes.newest(notes.take(0)))
    }
}
```

- [ ] **Step 3: run, expect compile failure** (`Mappings` etc. undefined): `./gradlew testDebugUnitTest`.

- [ ] **Step 4: `Mappings.kt`:**

```kotlin
package dev.kb896.airgestures

import java.util.Locale

/** The plain logic behind the mapping and the new actions, kept free of Android so it's unit-tested. */
object Mappings {
    /** In the call layer: the gesture keeps its normal action. */
    const val AS_USUAL = ""
    /** In the call layer: the gesture does nothing during a call. */
    const val NOTHING = "-"
    const val AUTOMATION = "automation"

    /**
     * The `gesture=action;…;dial_up=…;dial_down=…;hand=…` string the bridge gets: [normal]
     * (gesture key → action id, in order), with [calls] on top while [inCall]. A stored
     * [AUTOMATION] becomes `automation:<gesture>`, since the bridge reports only action names.
     */
    fun effective(
        normal: Map<String, String>,
        calls: Map<String, String>,
        dialUp: String,
        dialDown: String,
        hand: String,
        inCall: Boolean,
    ): String {
        val gestures = normal.entries.joinToString(";") { (key, action) ->
            val during = if (inCall) calls[key] else null
            val id = when (during) {
                null, AS_USUAL -> action
                NOTHING -> ""
                else -> during
            }
            "$key=${if (id == AUTOMATION) "$AUTOMATION:$key" else id}"
        }
        return "$gestures;dial_up=$dialUp;dial_down=$dialDown;hand=$hand"
    }
}

/** A "tap here" spot: `tap:<x>,<y>`, as fractions of the screen's width and height. */
object Tap {
    const val PREFIX = "tap:"

    fun format(x: Float, y: Float) = String.format(Locale.ROOT, "%s%.4f,%.4f", PREFIX, x, y)

    /** The spot, or null for anything that isn't a tap on the screen. */
    fun parse(id: String): Pair<Float, Float>? {
        if (!id.startsWith(PREFIX)) return null
        val (x, y) = id.removePrefix(PREFIX).split(',').takeIf { it.size == 2 }
            ?.map { it.toFloatOrNull() ?: return null } ?: return null
        return if (x in 0f..1f && y in 0f..1f) x to y else null
    }
}

/** What "read the newest notification" says. */
object Speech {
    /** App, title and text; only app and title while [locked] (as the lock screen hides content). */
    fun sentence(app: String, title: String?, text: String?, locked: Boolean): String {
        val heading = title?.takeIf { it.isNotBlank() }?.trim()
        val body = text?.takeIf { it.isNotBlank() && !locked }?.trim()
        return when {
            heading != null && body != null -> "$app. $heading: $body"
            body != null -> "$app. $body"
            heading != null -> "$app. $heading."
            else -> "$app."
        }
    }
}

/** Which notification the dismiss and read gestures act on. */
object Notes {
    class Note(
        val key: String,
        val postTime: Long,
        val clearable: Boolean,
        val ongoing: Boolean,
        val ours: Boolean,
        /** A group's summary with no text of its own. */
        val emptySummary: Boolean,
    )

    /** The latest one the user could swipe away themselves: not ongoing, not ours, not an empty summary. */
    fun newest(notes: List<Note>) =
        notes.filter { it.clearable && !it.ongoing && !it.ours && !it.emptySummary }.maxByOrNull { it.postTime }
}
```

- [ ] **Step 5: run the tests, expect 7 passing.** **Step 6: commit** `Mappings: the call layer, tap spots, read-aloud text, the newest notification (tested)`.

### Task 2: actions, prefs, call layer storage

**Files:** `Mapping.kt`, `Prefs.kt`, Lucide icons

- [ ] **Step 1: icons.** Fetch `https://unpkg.com/lucide-static@1.48.0/icons/<name>.svg` for crosshair, mic, bell-off, message-square-text, zap, phone, phone-off into a scratch dir; convert each element (`path`, `circle` → arc path, `line` → `M x1 y1 L x2 y2`, `rect`, `polyline`) to `<path>` with the same attributes as `ic_hand.xml` (stroke `#FF111214`, 1.75, round caps/joins, no fill, comment `Lucide "<name>" (ISC, lucide-static 1.48.0)`), saved as `ic_<name with _>.xml`.

- [ ] **Step 2: `Action` entries** (in `Mapping.kt`, before `OPEN_APP`):

```kotlin
    /** Stored as `tap:<x>,<y>`; the picker asks where. */
    TAP(Tap.PREFIX, "Tap here", R.drawable.ic_crosshair),
    ASSISTANT("assistant.voice", "Voice assistant", R.drawable.ic_mic),
    DISMISS("notification.dismiss", "Dismiss notification", R.drawable.ic_bell_off),
    READ("notification.read", "Read notification", R.drawable.ic_message_square_text),
    AUTOMATION(Mappings.AUTOMATION, "Send to automation", R.drawable.ic_zap),
    /** During calls only. */
    ANSWER("call.answer", "Answer call", R.drawable.ic_phone),
    /** During calls only: declines a ringing call, ends an active one. */
    END_CALL("call.end", "Decline or hang up", R.drawable.ic_phone_off),
```
and
```kotlin
    /** Whether it belongs to the During calls picker only. */
    val callOnly get() = this == ANSWER || this == END_CALL

    companion object {
        fun of(id: String) = when {
            id.startsWith(APP_PREFIX) -> OPEN_APP
            id.startsWith(Tap.PREFIX) -> TAP
            else -> entries.firstOrNull { it.id == id } ?: NONE
        }
    }
```
`Gesture` gets the call defaults: add a parameter `callDefault: String = Mappings.AS_USUAL`; `INDEX_DOUBLE(..., callDefault = Action.ANSWER.id)`, `MIDDLE_DOUBLE(..., callDefault = Action.END_CALL.id)`.

- [ ] **Step 3: `Prefs`:**

```kotlin
    /** The During calls layer: an action id, [Mappings.AS_USUAL] or [Mappings.NOTHING]. */
    fun callActionId(gesture: Gesture): String =
        prefs.getString("call.${gesture.key}", gesture.callDefault) ?: gesture.callDefault

    fun setCallAction(gesture: Gesture, id: String) =
        prefs.edit().putString("call.${gesture.key}", id).apply()

    /** `gesture=action;…` plus `dial_up`/`dial_down` for the bridge, with the call layer while in a call. */
    fun mapping(inCall: Boolean = CallWatch.inCall): String = Mappings.effective(
        Gesture.entries.associateTo(LinkedHashMap()) { it.key to actionId(it) },
        Gesture.entries.associate { it.key to callActionId(it) },
        dial.up,
        dial.down,
        hand,
        inCall,
    )

    /** Every action id a gesture can run, in or out of a call. */
    private fun allIds() = Gesture.entries.flatMap { listOf(actionId(it), callActionId(it)) }

    fun usesScreen() = allIds().any { id ->
        id.startsWith("screen.") || id.startsWith(APP_PREFIX) || id.startsWith(Tap.PREFIX) || id == Action.ASSISTANT.id
    }
    fun usesApps() = allIds().any { it.startsWith(APP_PREFIX) }
    fun usesBrightness() = dial == DialChoice.BRIGHTNESS || allIds().any { it.startsWith("brightness.") }
    /** Notification access: the seek dial, or reading/dismissing notifications. */
    fun usesNotifications() = dial == DialChoice.SEEK || allIds().any { it.startsWith("notification.") }
    fun usesNotificationGestures() = allIds().any { it.startsWith("notification.") }
    fun usesCalls() = Gesture.entries.any { callActionId(it).startsWith("call.") }
```
(replacing the old `mapping()`, `usesScreen`, `usesApps`, `usesBrightness`, `usesMedia`). Rename the one caller of `usesMedia()` (Setup.kt).

- [ ] **Step 4: build** (CallWatch missing → do Task 3 before building, or stub). **Commit with Task 3.**

### Task 3: CallWatch + BandService

- [ ] **Step 1: `CallWatch.kt`:**

```kotlin
package dev.kb896.airgestures

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager

/**
 * Whether the phone is ringing or in a call, so the During calls layer goes on and off.
 * Needs the phone-state permission (Setup's Phone calls step); without it nothing is watched.
 */
class CallWatch(private val context: Context, private val changed: () -> Unit) :
    TelephonyCallback(), TelephonyCallback.CallStateListener {
    private var watching = false

    /** Start watching if allowed; again is harmless (e.g. right after the permission is granted). */
    fun start() {
        if (watching || context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
        context.getSystemService(TelephonyManager::class.java).registerTelephonyCallback(context.mainExecutor, this)
        watching = true
    }

    fun stop() {
        if (watching) context.getSystemService(TelephonyManager::class.java).unregisterTelephonyCallback(this)
        watching = false
        inCall = false
    }

    override fun onCallStateChanged(state: Int) {
        val now = state != TelephonyManager.CALL_STATE_IDLE
        if (now == inCall) return
        inCall = now
        changed()
    }

    companion object {
        /** Ringing or off-hook; read by [Prefs.mapping]. */
        @Volatile
        var inCall = false
            private set
    }
}
```

- [ ] **Step 2: `BandService`:** field `private lateinit var calls: CallWatch`; in `onCreate` after `link = …`: `calls = CallWatch(this) { link.setMapping(prefs.mapping()) }.also { it.start() }`; in `onDestroy` before `link.stop()`: `calls.stop()`; add `fun watchCalls() = calls.start()` and

```kotlin
    fun setCallAction(gesture: Gesture, id: String) {
        prefs.setCallAction(gesture, id)
        link.setMapping(prefs.mapping())
    }
```
In the companion object:
```kotlin
        /** Store a gesture's action, live on the band when the service runs. */
        fun setAction(context: Context, gesture: Gesture, id: String) =
            instance?.setAction(gesture, id) ?: Prefs(context).setAction(gesture, id)

        fun setCallAction(context: Context, gesture: Gesture, id: String) =
            instance?.setCallAction(gesture, id) ?: Prefs(context).setCallAction(gesture, id)
```

- [ ] **Step 3: build + lint + tests (0 errors).** **Commit** `During calls: a call layer on the mapping while the phone rings or a call is on`.

### Task 4: TouchService — tap, background starts, the spot picker

- [ ] **Step 1:** add to `TouchService`:

```kotlin
    /** A short press at a spot given as fractions of the screen. */
    fun tap(x: Float, y: Float) {
        val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val path = Path().apply { moveTo(bounds.width() * x, bounds.height() * y) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build(), null, null)
    }

    /** Android lets an enabled accessibility service start activities from the background. */
    fun startFromBackground(intent: Intent): Boolean =
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
```

- [ ] **Step 2: the picker** — new file `SpotPicker.kt`, a class driven by `TouchService.pickSpot(start: Pair<Float, Float>?, onPicked: (Pair<Float, Float>?) -> Unit)`:
  - Phase 1: a `TYPE_ACCESSIBILITY_OVERLAY` window (`WRAP_CONTENT`, top centre, `FLAG_NOT_FOCUSABLE`) holding a coral pill "Open the app, then tap here" + a small "Cancel". Tap the pill → phase 2; Cancel → `onPicked(null)`.
  - Phase 2: a full-screen overlay (`MATCH_PARENT`, `FLAG_LAYOUT_IN_SCREEN or FLAG_LAYOUT_NO_LIMITS`, `layoutInDisplayCutoutMode = ALWAYS`) with a 15 % ink scrim, a 56 dp ring + centre dot at the start spot (or the centre), following `rawX/rawY` on touch, and a bottom row "Cancel" / "Set" pills. Set → fractions of `currentWindowMetrics.bounds` → `onPicked(x to y)`.
  - Every exit removes its windows; a second `pickSpot` while one is showing replaces it.
  - The views are built with the `Cb` tokens (Geist, ink, coral) so they read as the app.

- [ ] **Step 3: build + lint.** **Commit** `Tap here: a tap at a chosen spot, and the overlay that picks it`.

### Task 5: Actions + MediaAccess

- [ ] **Step 1: `MediaAccess`:** class doc → "Notification access: lets the app steer the playing app (the playback-position dial) and, only when a read or dismiss gesture is made, read or clear the newest notification." Add

```kotlin
    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { instance = null }
    // companion: @Volatile var instance: MediaAccess? = null; private set
```

- [ ] **Step 2: `Actions.run`** gains:

```kotlin
            "assistant.voice" -> return assistant()
            "notification.dismiss" -> return dismiss()
            "notification.read" -> return read()
            "call.answer" -> return call(answer = true)
            "call.end" -> return call(answer = false)
            else -> return when {
                action.startsWith(APP_PREFIX) -> open(action.removePrefix(APP_PREFIX))
                action.startsWith(Tap.PREFIX) -> tap(action)
                action.startsWith("${Mappings.AUTOMATION}:") -> automation(action.substringAfter(':'))
                else -> "unknown action $action"
            }
```
with
```kotlin
    private fun tap(action: String): String? {
        val touch = TouchService.instance ?: return "tap here needs screen gestures (Setup)"
        val (x, y) = Tap.parse(action) ?: return "the tap spot is broken; pick it again"
        touch.tap(x, y)
        return null
    }

    /** The default assistant: the voice command screen, else the assist app. */
    private fun assistant(): String? {
        val touch = TouchService.instance ?: return "the voice assistant needs screen gestures (Setup)"
        if (touch.startFromBackground(Intent(Intent.ACTION_VOICE_COMMAND))) return null
        return if (touch.startFromBackground(Intent(Intent.ACTION_ASSIST))) null else "no voice assistant answered"
    }

    private fun newest(listener: MediaAccess): android.service.notification.StatusBarNotification? {
        val active = runCatching { listener.activeNotifications }.getOrNull() ?: return null
        val pick = Notes.newest(active.map {
            Notes.Note(
                it.key, it.postTime, it.isClearable, it.isOngoing, it.packageName == context.packageName,
                emptySummary = it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0 &&
                    it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).isNullOrBlank(),
            )
        }) ?: return null
        return active.first { it.key == pick.key }
    }

    private fun dismiss(): String? {
        val listener = MediaAccess.instance ?: return "notification gestures need notification access (Setup)"
        val note = newest(listener) ?: return "no notifications"
        listener.cancelNotification(note.key)
        return null
    }

    private fun read(): String? {
        val listener = MediaAccess.instance ?: return "notification gestures need notification access (Setup)"
        val note = newest(listener) ?: return "no notifications"
        val extras = note.notification.extras
        val app = runCatching {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(note.packageName, 0)).toString()
        }.getOrDefault(note.packageName)
        val locked = context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        speak(Speech.sentence(
            app,
            extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString(),
            (extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(android.app.Notification.EXTRA_TEXT))?.toString(),
            locked,
        ))
        return null
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null

    /** One engine for the service's life; the first sentence waits for it to start. */
    private fun speak(text: String) {
        if (ttsReady) return say(text)
        pendingSpeech = text
        if (tts == null) tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) pendingSpeech?.let(::say)
            pendingSpeech = null
        }.apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        }
    }

    private fun say(text: String) { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "notification") }

    private fun call(answer: Boolean): String? {
        if (context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            return "call gestures need the Phone calls permission (Setup)"
        }
        val telecom = context.getSystemService(TelecomManager::class.java)
        @Suppress("DEPRECATION")
        if (answer) telecom.acceptRingingCall() else if (!telecom.endCall()) return "no call to end"
        return null
    }

    /** For Tasker, MacroDroid, Home Assistant…: the gesture's name, nothing else. */
    private fun automation(gesture: String): String? {
        context.sendBroadcast(Intent(AUTOMATION_ACTION).putExtra("gesture", gesture))
        return null
    }
    // companion: const val AUTOMATION_ACTION = "dev.kb896.airgestures.GESTURE"
```
`close()` also `tts?.shutdown()`.

- [ ] **Step 3: build + lint + tests.** **Commit** `Voice assistant, notification read/dismiss, call answer/end, automation broadcast`.

### Task 6: Gestures screen

- [ ] **Step 1:** normal picker lists `Action.entries.filterNot { it.callOnly }`; choosing `TAP` → `pickTap(gesture, calls = false)`.
- [ ] **Step 2:** second card "During calls" (heading label "During calls"): one row per `Gesture`, value "As usual" (unset style) / "Nothing" / the action's label. Its picker: `Option("As usual")`, `Option("Nothing")`, then `Action.entries.filter { it != Action.NONE }`; `TAP` → `pickTap(gesture, calls = true)`, `OPEN_APP` → `pickApp` for the call layer. Store with `BandService.setCallAction(this, …)`.
- [ ] **Step 3:** `pickTap`: if `TouchService.instance == null` → `confirm("Turn on screen gestures", "Tap here uses the accessibility service that screen gestures use. Turn it on in Setup first.", "Open Setup") { open<SetupActivity>() }`; else `moveTaskToBack(true)` and `TouchService.instance!!.pickSpot(current) { spot -> spot?.let { store(Tap.format(it.first, it.second)) }; startActivity(Intent(applicationContext, GesturesActivity::class.java).addFlags(NEW_TASK or CLEAR_TOP or SINGLE_TOP)) }`, where `store` is `BandService.setAction`/`setCallAction` on `applicationContext`.
- [ ] **Step 4:** labels: Tap here rows read "Tap here"; app rows in the call card read "Open <app>".
- [ ] **Step 5: build + lint.** **Commit** `Gestures: the new actions, a During calls card, picking the tap spot`.

### Task 7: Setup, manifest, strings, changelog

- [ ] Manifest: `<uses-permission android:name="android.permission.READ_PHONE_STATE" />`, `<uses-permission android:name="android.permission.ANSWER_PHONE_CALLS" />`; MediaAccess label `${appLabel} media and notifications`.
- [ ] `strings.xml` `touch_service_description`: "Lets band gestures swipe and tap the screen, press Back, Home or Recent apps, and open apps or the voice assistant. Air Gestures reads nothing on screen."
- [ ] `Setup.kt`: `val callPermissions = arrayOf(READ_PHONE_STATE, ANSWER_PHONE_CALLS)`; step when `prefs.usesCalls()`: "Phone calls", "Allowed" / "Needed to answer and end calls with gestures", Allow → `requestPermissions(callPermissions, PERMISSIONS)`. Notification access step on `usesNotifications()`, detail "Needed for " + `listOfNotNull("playback position".takeIf { prefs.dial == DialChoice.SEEK }, "notification gestures".takeIf { prefs.usesNotificationGestures() }).joinToString(" and ")` + the greyed-out hint.
- [ ] `SetupActivity.onRequestPermissionsResult`: `BandService.instance?.watchCalls()` before `render()`.
- [ ] `CHANGELOG.md` Unreleased: new actions + During calls lines.
- [ ] build + lint + tests; **commit** `Setup asks for what the new actions need; changelog`.

### Task 8: verify

- [ ] `cargo test` (unchanged Rust, sanity), clean build, lint 0, unit tests pass.
- [ ] Emulators 33–36 (x86 test APK as before): Gestures screen with both cards, both pickers, Setup's Phone calls row; no crashes. Tap picker on one emulator with the accessibility service enabled via `settings put secure enabled_accessibility_services`.
- [ ] Xiaomi (adb over Tailscale): install dev build; with the band: tap here (pick a spot in a real app), voice assistant (which intent Gemini takes), dismiss/read, automation (`adb logcat` or an automation app), a real incoming call — needs the user.
