# Groundwork Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the app correct on Android 13–16 (minSdk 33), split the 1,024-line one-page `MainActivity` into Home + Gestures/Setup/Activity screens on a shared `Screen` base, and run lint in CI.

**Architecture:** Plain Android Activities (no AndroidX). `Screen : Activity` (in `Ui.kt`) owns window setup and the CargoBolt UI kit moved out of `MainActivity`. `Setup.steps(context)` is the single source of setup state for both the Setup screen and Home's "N to do" badge. Companion presence uses API 36 calls on 16 and the address-based / `onDeviceAppeared` calls on 13–15.

**Tech Stack:** Kotlin (AGP 9.4.1 built-in), Gradle 9.8 wrapper, JDK 21 (`JAVA_HOME=/usr/lib/jvm/java-21-openjdk`), Android SDK in `~/Android/Sdk`, Rust bridge via `./build-rust.sh`.

**Spec:** `docs/superpowers/specs/2026-10-20-groundwork-design.md`

**Testing note:** the app has no JVM unit tests and everything touched is Android framework UI or system API glue; the checks are `assembleDebug` + `lintDebug` (errors must be 0) after each task, `cargo test` once, then emulator (API 33–36) and on-device runs (Tasks 9–10). Build command used throughout:

```bash
cd ~/Projects/air-gestures-android && JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew --console=plain assembleDebug lintDebug 2>&1 | grep -E 'BUILD|^e: |Lint found|errors' ; grep -cE 'Error: ' app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt
```

Never tag. Commit on branch `groundwork` only.

## File map

| File | Change |
|---|---|
| `app/build.gradle.kts` | minSdk 33 |
| `app/src/main/AndroidManifest.xml` | `enableOnBackInvokedCallback`, three new activities |
| `app/src/main/java/dev/kb896/airgestures/MetaSignIn.kt` | callback unconditional, delete `onBackPressed` |
| `.../BandClaim.kt` | drop the always-true `SDK_INT >= 33` branch |
| `.../Companion.kt` | `watch()`, presence fallbacks |
| `.../Ui.kt` (new) | `Screen` base + UI kit |
| `.../Setup.kt` (new) | `Step`, `Setup.steps/pending/granted/bluetoothOn/permissions` |
| `.../SetupActivity.kt` (new) | Setup screen, claim, key import/export, results |
| `.../GesturesActivity.kt` (new) | Gesture map and pickers |
| `.../ActivityLogActivity.kt` (new) | Live log |
| `.../MainActivity.kt` | Home only |
| `.gitlab-ci.yml` | lint in `test` |
| `CHANGELOG.md` | `## Unreleased` |

---

### Task 1: minSdk 33 and the back callback

**Files:** `app/build.gradle.kts:17-18`, `app/src/main/AndroidManifest.xml` (`<application>`), `MetaSignIn.kt:90-92,113-114`, `BandClaim.kt:120-121`

- [ ] **Step 1: minSdk.** In `app/build.gradle.kts` replace

```kotlin
        // L2CAP connection-oriented channels need API 29.
        minSdk = 29
```
with
```kotlin
        // Android 13: L2CAP channels (29) and the companion link's AssociationInfo API (33).
        minSdk = 33
```

- [ ] **Step 2: opt in to the back callback on 13–15.** In the manifest's `<application …>` add `android:enableOnBackInvokedCallback="true"` after `android:allowBackup="false"`.

- [ ] **Step 3: MetaSignIn.** Replace

```kotlin
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        }
```
with
```kotlin
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
```
and delete
```kotlin
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = back()

```
Remove `import android.os.Build` if nothing else uses it (`grep -n 'Build\.' MetaSignIn.kt`).

- [ ] **Step 4: BandClaim.** Replace

```kotlin
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
```
with
```kotlin
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
```
and drop `import android.os.Build` if now unused.

- [ ] **Step 5: build + lint.** Run the build command. Expected: BUILD SUCCESSFUL for assembleDebug; lint errors drop from 35 to the API 36 ones in `Companion.kt` plus `MainActivity.kt` MissingPermission (≈ 12–14).

- [ ] **Step 6: commit** — `git commit -am "Android 13 or newer: minSdk 33, the back callback on every version"`

### Task 2: Companion presence on 13–15

**Files:** `app/src/main/java/dev/kb896/airgestures/Companion.kt`

- [ ] **Step 1: `watch` + `observe`.** Replace the body of `observe` from `val manager = …` to the end of the function with:

```kotlin
        val manager = context.getSystemService(CompanionDeviceManager::class.java)
        val association = manager.myAssociations.firstOrNull { it.id == id } ?: return
        watch(manager, association)
    }

    /**
     * Asks the system to wake the app when [association]'s band appears: by the
     * association on Android 16, by the band's address before. Fails quietly (the
     * link may have gone); the service asks again each time it starts.
     */
    private fun watch(manager: CompanionDeviceManager, association: AssociationInfo) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 36) {
                manager.startObservingDevicePresence(
                    ObservingDevicePresenceRequest.Builder().setAssociationId(association.id).build(),
                )
            } else {
                val address = association.deviceMacAddress ?: return
                @Suppress("DEPRECATION")
                manager.startObservingDevicePresence(address.toString())
            }
        }
    }
```
(Keep the closing `}` count right: `observe` now ends after `watch(manager, association)`.)

- [ ] **Step 2: `link()`.** In `onAssociationCreated` replace

```kotlin
                manager.startObservingDevicePresence(
                    ObservingDevicePresenceRequest.Builder().setAssociationId(association.id).build(),
                )
```
with `watch(manager, association)`, and `android.os.Build.VERSION.SDK_INT >= 34` with `Build.VERSION.SDK_INT >= 34`. Add `import android.os.Build` and `import android.annotation.TargetApi`.

- [ ] **Step 3: `BandPresenceService`.** Replace the class with:

```kotlin
/** Woken by the system when the linked band appears: bring the service back if it should run. */
class BandPresenceService : CompanionDeviceService() {
    /** Android 16 and newer. */
    @TargetApi(36)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (event.event == DevicePresenceEvent.EVENT_BLE_APPEARED) appeared()
    }

    /** Android 13 to 15 (16 calls [onDevicePresenceEvent] instead). */
    @Deprecated("Deprecated in Java")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) = appeared()

    /** Only when the band should run and isn't already, so a second call changes nothing. */
    private fun appeared() {
        if (Prefs(this).running && BandService.instance == null) BandService.start(this)
    }
}
```

- [ ] **Step 4: build + lint.** Expected: no `Companion.kt` errors left; remaining lint error = MainActivity MissingPermission only.

- [ ] **Step 5: commit** — `git commit -am "Companion link and wake-up on Android 13 to 15 too"`

### Task 3: `Screen` base and UI kit (`Ui.kt`)

**Files:** create `app/src/main/java/dev/kb896/airgestures/Ui.kt`; modify `MainActivity.kt`

- [ ] **Step 1: create `Ui.kt`** with the window setup, the new helpers, and these functions **moved verbatim** from `MainActivity.kt` (change `private` → public): `column` (240–246), `row` (248–255), `weighted` (257), `icon` (266–270), `pill` (369–399), `segment` (401–407), `segmented` (409–415), `settingRow` (422–429), `Option` (512–513, make it `class Option`), `confirm` (994–1022). `card` and `choose` move with the changes shown below.

```kotlin
package dev.kb896.airgestures

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** The runtime permissions' request code. */
const val PERMISSIONS = 1

private const val LIGHT_BARS =
    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS

/**
 * Every screen in the CargoBolt soft-UI language: the page tone, light system bars, a
 * page that scrolls inside them, and the shared pieces (cards, pills, the picker,
 * confirmations).
 */
abstract class Screen : Activity() {
    fun dp(value: Float) = Cb.dp(this, value)
    fun dpi(value: Float) = dp(value).toInt()

    /** A page's column: 20 dp at the sides, [bottom] dp of room under the last card. */
    fun pageColumn(bottom: Float = 24f) = column(gap = 20f).apply {
        setPadding(dpi(20f), dpi(16f), dpi(20f), dpi(bottom))
    }

    /**
     * Shows [page] scrolling inside the system bars (edge-to-edge is enforced from API 35),
     * with a back row on top when it has a [title], and [floating] pinned bottom-right.
     */
    fun show(page: LinearLayout, title: String? = null, floating: View? = null) {
        window.decorView.setBackgroundColor(Cb.PAGE)
        window.insetsController?.setSystemBarsAppearance(LIGHT_BARS, LIGHT_BARS)
        if (title != null) page.addView(titleRow(title), 0)
        val scroll = ScrollView(this).apply {
            addView(page)
            clipToPadding = true
        }
        setContentView(FrameLayout(this).apply {
            addView(scroll)
            if (floating != null) addView(floating, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END,
            ))
            setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                scroll.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                if (floating != null) {
                    (floating.layoutParams as FrameLayout.LayoutParams).setMargins(0, 0, bars.right + dpi(16f), bars.bottom + dpi(16f))
                    floating.requestLayout()
                }
                insets
            }
        })
    }

    /** A sub-screen's top: back, then its title in the wordmark's type. */
    private fun titleRow(title: String) = row(gap = 8f).apply {
        addView(FrameLayout(context).apply {
            addView(icon(R.drawable.ic_arrow_left, 20f), FrameLayout.LayoutParams(dpi(20f), dpi(20f), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(dpi(40f), dpi(40f))
            contentDescription = "Back"
            isClickable = true
            background = ripple(999f)
            setOnClickListener { finish() }
        })
        addView(Cb.text(context, 24f, R.font.geist_bold, Cb.INK_900, -.03f).apply { text = title }, weighted())
    }

    inline fun <reified T : Activity> open() = startActivity(Intent(this, T::class.java))

    /** The pressed state of a tappable row: a quiet ink ripple inside rounded corners. */
    fun ripple(radius: Float = 10f) = RippleDrawable(
        ColorStateList.valueOf(0x14111214), null, GradientDrawable().apply { setColor(-1); cornerRadius = dp(radius) },
    )

    /** A row that opens another screen: icon, label, an optional WARN [badge], chevron. */
    fun navRow(iconRes: Int, label: String, badge: String? = null, onClick: () -> Unit) = row(gap = 12f).apply {
        setPadding(dpi(4f), dpi(12f), dpi(4f), dpi(12f))
        addView(icon(iconRes, 18f))
        addView(Cb.text(context, 15f, R.font.geist_medium, Cb.INK_900).apply { text = label }, weighted())
        if (badge != null) addView(Cb.chip(context, Cb.Signal.WARN, badge))
        addView(icon(R.drawable.ic_chevron_right, 14f, Cb.INK_400))
        isClickable = true
        background = ripple()
        setOnClickListener { onClick() }
    }

    /** A card; [title] null for a sub-screen's single card under its own title. */
    fun card(title: String?, content: View) = column(gap = 12f).apply {
        background = Cb.hairlineCard(context)
        setPadding(dpi(16f), dpi(14f), dpi(16f), dpi(16f))
        if (title != null) addView(Cb.text(context, 17f, R.font.geist_semibold, Cb.INK_900, -.015f).apply { text = title })
        addView(content)
    }

    fun log(line: String) = BandService.state.update { copy(log = (log + line).takeLast(60)) }

    /** The band needs Bluetooth: say so and offer Android's own prompt to switch it on. */
    fun askForBluetooth() = confirm(
        "Bluetooth is off",
        "The band connects over Bluetooth. Turn it on to use the band.",
        "Turn on",
    ) {
        val settings = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        // The system's prompt needs the Bluetooth permission; without it, the settings page.
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            startActivity(settings)
        } else {
            runCatching { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }.onFailure { startActivity(settings) }
        }
    }

    /** Asks before stopping a claim in progress. */
    fun stopClaim() = confirm("Stop claiming?", "The claim stops here. If the band already accepted this phone, its key is kept.", "Stop") {
        BandClaim.cancel()
    }

    // column, row, weighted, icon, pill, segment, segmented, settingRow, Option, choose, confirm: moved from MainActivity
}
```

`choose` moves with two changes: a `heading` parameter (the label above the title, was always "Gesture") and **no** `renderGestures()`/`renderSetup()` in the row's click (callers re-render in `onChoice`):

```kotlin
    /** The picker: a modal on the scrim (CargoBolt: 55% ink scrim, overlay shadow). */
    fun choose(title: String, options: List<Option>, heading: String = "Gesture", onChoice: (Int) -> Unit) {
        // … body of MainActivity.choose (524–587) unchanged, except:
        //   addView(Cb.label(context, "Gesture"))  →  addView(Cb.label(context, heading))
        //   setOnClickListener { onChoice(index); renderGestures(); renderSetup(); dialog.dismiss() }
        //     →  setOnClickListener { dialog.dismiss(); onChoice(index) }
    }
```
(`dismiss` first so a choice that opens a second picker — `pickApp` — isn't stacked on the first.)

- [ ] **Step 2: MainActivity extends Screen.** `class MainActivity : Screen()`; delete from it every function now in `Screen` (`dp`, `dpi`, `column`, `row`, `weighted`, `card`, `icon`, `pill`, `segment`, `segmented`, `settingRow`, `Option`, `choose`, `confirm`, `log`, `askForBluetooth`). In `choose`'s callers in MainActivity (`pick`, `pickApp`, `pickDial`) append `renderGestures(); renderSetup()` inside their `onChoice` lambda so behaviour stays the same until Tasks 4–5 move them. `toggleService`'s claiming branch becomes `stopClaim(); return`.

- [ ] **Step 3: build + lint.** Expected: BUILD SUCCESSFUL; lint errors 0 (the Bluetooth prompt now checks the permission). If lint still flags `ACTION_REQUEST_ENABLE` as MissingPermission, add `@SuppressLint("MissingPermission")` on `askForBluetooth` with the comment `// checked just above`.

- [ ] **Step 4: commit** — `git add -A app/src && git commit -m "Screen: the shared page setup and UI pieces, out of MainActivity"`

### Task 4: `Setup` steps and `SetupActivity`

**Files:** create `Setup.kt`, `SetupActivity.kt`; modify `MainActivity.kt`, `AndroidManifest.xml`

- [ ] **Step 1: create `Setup.kt`.** The detail strings are copied verbatim from `MainActivity.renderSetup()` (755–920).

```kotlin
package dev.kb896.airgestures

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** One line in Setup: what it is, how it stands, and the button that sorts it out. */
class Step(
    val title: String,
    val detail: String,
    /** Drawn with a check; otherwise with the alert icon and its button. */
    val done: Boolean,
    val button: String?,
    /** The button shows even when done (Replace, Export, Forget, Sign out). */
    val always: Boolean = false,
    val icon: Int? = null,
    /** Counted in Home's "N to do" while not done; actions (Claim, Forget, …) aren't. */
    val required: Boolean = true,
    val action: ((SetupActivity) -> Unit)? = null,
)

/** Setup worked out in one place, so Home's badge and the Setup screen agree. */
object Setup {
    val permissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    fun granted(context: Context) = permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun bluetoothOn(context: Context) =
        context.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true

    /** How many required steps aren't done. */
    fun pending(context: Context) = steps(context).count { it.required && !it.done }

    fun steps(context: Context): List<Step> = buildList {
        val prefs = Prefs(context)
        val ownerKey = Identity.present(context)
        val claiming = BandClaim.current != null
        // Claiming is offered until a confirmed key is here (an unconfirmed one may be retried).
        if (!Identity.keyFile(context).exists()) add(Step(
            "Claim a band",
            if (claiming) "Claiming; progress is under Activity" else "Sign in to Meta, then claim a band in pairing mode",
            false,
            if (claiming) "Stop" else "Claim",
            always = true,
            icon = R.drawable.ic_link,
            required = false,
        ) { if (claiming) it.stopClaim() else it.claim() })
        val band = Identity.bandName(context)?.let { " for $it" } ?: ""
        add(Step(
            "Owner key",
            when {
                Identity.unconfirmed(context) -> "Claimed$band, not confirmed yet; connecting confirms it"
                ownerKey -> "Loaded$band"
                else -> "Or import air-gestures-band.json from a computer"
            },
            ownerKey,
            if (ownerKey) "Replace" else "Import",
            always = true,
        ) { it.importKey(replacing = ownerKey) })
        // Only a confirmed key is exported: the band may never have committed a pending one.
        if (Identity.keyFile(context).exists()) add(Step(
            "Export the key", "Save it for a computer (air-gestures import) or another phone", true, "Export",
            always = true, icon = R.drawable.ic_arrow_down, required = false,
        ) { it.exportKey() })
        if (ownerKey) add(Step(
            "Forget the band", "Removes the key, the pairing and the link from this phone", false, "Forget",
            always = true, icon = R.drawable.ic_link, required = false,
        ) { it.forgetBand() })
        if (SessionStore.present(context)) add(Step(
            "Meta sign-in", "Signed in; used only to claim a band", true, "Sign out", always = true, required = false,
        ) { it.signOut() })
        val bluetooth = bluetoothOn(context)
        add(Step("Bluetooth", if (bluetooth) "On" else "Off: the band needs it", bluetooth, "Turn on") { it.askForBluetooth() })
        val granted = granted(context)
        add(Step("Bluetooth and notifications", if (granted) "Allowed" else "Needed to connect", granted, "Allow") {
            it.requestPermissions(permissions, PERMISSIONS)
        })
        val linked = Companion.linked(context)
        add(Step("Companion link", if (linked) "Linked; the system wakes the app" else "Hold the band's button 3 s, then link", linked, "Link") {
            it.link()
        })
        // Only asked for when a screen gesture is mapped somewhere.
        if (prefs.usesScreen()) {
            val on = TouchService.instance != null
            add(Step(
                "Screen gestures",
                if (on) "On" else "Settings, Accessibility, Air Gestures. If it's greyed out: App info, then Allow restricted settings",
                on,
                "Turn on",
            ) { it.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        }
        // HyperOS drops app launches from the background without its own permission.
        if (Xiaomi.isXiaomi && prefs.usesApps()) {
            val allowed = Xiaomi.canOpenFromBackground(context)
            add(Step(
                "Open apps from gestures",
                when (allowed) {
                    true -> "Allowed"
                    false -> "Other permissions: allow Open new windows while running in the background"
                    null -> "Check Other permissions: Open new windows while running in the background"
                },
                allowed == true,
                "Open",
            ) { Xiaomi.openPermissions(it) })
        }
        // Only asked for when brightness is mapped somewhere.
        if (prefs.usesBrightness()) {
            val brightness = Settings.System.canWrite(context)
            add(Step("Brightness access", if (brightness) "Allowed" else "Needed for brightness", brightness, "Allow") {
                it.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${it.packageName}")))
            })
        }
        // Only asked for when the dial seeks.
        if (prefs.usesMedia()) {
            val media = MediaAccess.granted(context)
            add(Step(
                "Notification access",
                if (media) "Allowed" else "Needed for playback position. If it's greyed out: App info, then Allow restricted settings",
                media,
                "Allow",
            ) {
                val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, MediaAccess.component(it).flattenToString())
                runCatching { it.startActivity(detail) }.onFailure { _ -> it.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            })
        }
        val unrestricted = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        add(Step("Battery", if (unrestricted) "Unrestricted" else "Set to no restrictions", unrestricted, "Open") {
            @SuppressLint("BatteryLife")
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${it.packageName}"))
            it.startActivity(intent)
        })
    }
}
```

- [ ] **Step 2: create `SetupActivity.kt`.** `claim`, `runClaim`, the import/export pickers, Forget and Sign out are the bodies from MainActivity (`claim` 626–647, `runClaim` 649–672, the Owner key / Export / Forget / Sign out lambdas in `renderSetup`, `onActivityResult` 954–990) with `renderSetup()` → `render()`, `granted()` → `Setup.granted(this)`, `bluetoothOn()` → `Setup.bluetoothOn(this)`, `permissions()` → `Setup.permissions`, `1` → `PERMISSIONS`.

```kotlin
package dev.kb896.airgestures

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.bluetooth.BluetoothAdapter
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout

/** The companion picker's request code. */
private const val COMPANION = 2
/** The file picker's request code for importing the owner key. */
private const val IMPORT = 3
/** The Meta sign-in's request code (then the claim). */
private const val SIGN_IN = 4
/** The file picker's request code for exporting the owner key. */
private const val EXPORT = 5

/** Setup: the owner key, the permissions and the system settings the band needs. */
class SetupActivity : Screen() {
    private lateinit var steps: LinearLayout
    /** The claim state the steps were last drawn for. */
    private var drawnClaiming = false
    private val watch: (BandService.State) -> Unit = { state -> if (state.claiming != drawnClaiming) render() }

    /** Bluetooth switched on or off: its step follows. */
    private val bluetoothChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        steps = column(gap = 14f)
        show(pageColumn().apply { addView(card(null, steps)) }, title = "Setup")
    }

    override fun onResume() {
        super.onResume()
        BandService.state.observe(watch)
        registerReceiver(bluetoothChanged, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        render()
    }

    override fun onPause() {
        BandService.state.remove(watch)
        runCatching { unregisterReceiver(bluetoothChanged) }
        super.onPause()
    }

    private fun render() {
        drawnClaiming = BandService.state.value.claiming
        steps.removeAllViews()
        Setup.steps(this).forEach { steps.addView(stepRow(it)) }
    }

    private fun stepRow(step: Step) = row(gap = 12f).apply {
        addView(icon(step.icon ?: if (step.done) R.drawable.ic_shield_check else R.drawable.ic_circle_alert, 16f, if (step.done) Cb.INK_900 else Cb.INK_500))
        addView(column(gap = 3f).apply {
            addView(Cb.text(context, 14f, R.font.geist_medium, Cb.INK_900).apply { text = step.title })
            addView(Cb.text(context, 12f, color = Cb.INK_500).apply { text = step.detail })
        }, weighted())
        val action = step.action
        if ((step.always || !step.done) && step.button != null && action != null) {
            addView(pill(step.button) { action(this@SetupActivity) }.first)
        }
    }

    fun link() = Companion.link(this, COMPANION, log = { line -> log(line); render() })

    /** Import an owner key; replacing one asks first (it may be the only key the band trusts). */
    fun importKey(replacing: Boolean) {
        val pick = {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
                IMPORT,
            )
        }
        if (!replacing) {
            pick()
        } else {
            confirm(
                "Replace the owner key?",
                "The key on this phone is overwritten by the one you pick. If this phone claimed the band, " +
                    "its key is the only one the band trusts: export it first, or the band has to be claimed again.",
                "Choose file",
                pick,
            )
        }
    }

    fun exportKey() = confirm(
        "Export the owner key?",
        "The file lets anyone who has it control the band. Keep it private, and delete it once " +
            "it's imported on the other device. The band talks to one device at a time.",
        "Choose where",
    ) {
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "air-gestures-band.json"),
            EXPORT,
        )
    }

    fun forgetBand() = confirm(
        "Forget the band?",
        "The key, the Bluetooth pairing and the companion link are removed from this phone. " +
            "The band keeps its owner: to use it with Meta's apps again, hold its button for about 16 seconds to factory reset it.",
        "Forget",
    ) {
        val openSettings = Identity.forget(this)
        log("forgot the band on this phone")
        if (openSettings) {
            log("tap Forget on the band in Bluetooth settings to remove the pairing")
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        render()
    }

    fun signOut() = confirm(
        "Sign out of Meta?",
        "The Meta sign-in is deleted from this phone. The band keeps working; you'd sign in again " +
            "only to claim a band.",
        "Sign out",
    ) {
        SessionStore.delete(this)
        log("signed out of Meta on this phone")
        render()
    }

    // claim() (public), runClaim() (private), onRequestPermissionsResult (→ render()),
    // onActivityResult: moved from MainActivity with the renames above.
}
```

- [ ] **Step 3: Home.** In `MainActivity`: delete `setup`, `setupClaiming`, `setupCard`, `renderSetup`, `setupRow`, `claim`, `runClaim`, `onActivityResult`, the `IMPORT`/`SIGN_IN`/`EXPORT` constants, `permissions`, `granted`, `bluetoothOn` (use `Setup.*`). Add the nav card (Task 5/6 fill the other rows):

```kotlin
    private lateinit var setupNav: FrameLayout
    /** The claim state the Setup row was last counted for. */
    private var navClaiming = false

    /** Where the rest of the app lives. */
    private fun navCard() = card(null, column().apply {
        setupNav = FrameLayout(context)
        addView(setupNav)
    })

    /** Setup's row with its "N to do". */
    private fun renderNav() {
        val pending = Setup.pending(this)
        setupNav.removeAllViews()
        setupNav.addView(navRow(R.drawable.ic_shield_check, "Setup", if (pending > 0) "$pending to do" else null) { open<SetupActivity>() })
    }
```
In `onCreate` replace `addView(setupCard())` with `addView(navCard())`; `onResume`, `bluetoothChanged`, `onRequestPermissionsResult` call `renderNav()` instead of `renderSetup()`; in `render()` replace the `setupClaiming` block with

```kotlin
        if (claiming != navClaiming) {
            navClaiming = claiming
            renderNav()
        }
```

- [ ] **Step 4: manifest.** After the `.MainActivity` element:

```xml
        <activity android:name=".SetupActivity" android:exported="false" />
        <activity android:name=".GesturesActivity" android:exported="false" />
        <activity android:name=".ActivityLogActivity" android:exported="false" />
```
(Tasks 5–6 create the other two; add all three now only if those classes exist — otherwise add each in its task.)

- [ ] **Step 5: build + lint** (errors 0). **Step 6: commit** — `git add -A app/src && git commit -m "Setup gets its own screen; Home shows what's left to do"`

### Task 5: `GesturesActivity`

**Files:** create `GesturesActivity.kt`; modify `MainActivity.kt`, manifest

- [ ] **Step 1: create it.** `gestureRow`, `pickApp`, `appName` move verbatim; `renderGestures` becomes `render`; every `choose` callback ends with `render()`.

```kotlin
package dev.kb896.airgestures

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout

/** The gesture map: what each gesture does, the pinch and turn, the fixed middle hold. */
class GesturesActivity : Screen() {
    private lateinit var rows: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rows = column(gap = 4f)
        show(pageColumn().apply { addView(card(null, rows)) }, title = "Gestures")
    }

    override fun onResume() {
        super.onResume()
        // An app picked for a gesture may have been uninstalled meanwhile.
        render()
    }

    /** One row per gesture: tap to choose its action. The pinch and the middle hold are fixed. */
    private fun render() {
        rows.removeAllViews()
        val prefs = Prefs(this)
        for (gesture in Gesture.entries) {
            val action = prefs.action(gesture)
            val label = if (action == Action.OPEN_APP) "Open ${appName(prefs.actionId(gesture).removePrefix(APP_PREFIX))}" else action.label
            rows.addView(gestureRow(action.icon, gesture.label, label, action == Action.NONE) { pick(gesture) })
        }
        val dial = prefs.dial
        rows.addView(gestureRow(dial.icon, "Pinch and turn", dial.label, dial == DialChoice.NONE) { pickDial() })
        rows.addView(gestureRow(R.drawable.ic_power, "Middle hold", "Controls on or off", false, null))
    }

    private fun pick(gesture: Gesture) {
        val prefs = Prefs(this)
        choose(gesture.label, Action.entries.map { Option(it.label, it == prefs.action(gesture), it.icon) }) { index ->
            val action = Action.entries[index]
            if (action == Action.OPEN_APP) pickApp(gesture) else setAction(gesture, action.id)
            render()
        }
    }

    private fun setAction(gesture: Gesture, id: String) =
        BandService.instance?.setAction(gesture, id) ?: Prefs(this).setAction(gesture, id)

    private fun pickDial() {
        val prefs = Prefs(this)
        choose("Pinch and turn", DialChoice.entries.map { Option(it.label, it == prefs.dial, it.icon) }) { index ->
            val dial = DialChoice.entries[index]
            BandService.instance?.setDial(dial) ?: run { prefs.dial = dial }
            render()
        }
    }

    // gestureRow (MainActivity 466–480, its RippleDrawable → ripple()), pickApp (494–505,
    // its onChoice gets `; render()`), appName (508–510): moved.
}
```

- [ ] **Step 2: Home.** Delete `gestureRows`, `gestureCard`, `renderGestures`, `gestureRow`, `pick`, `setAction`, `pickApp`, `appName`, `pickDial` from MainActivity and `addView(gestureCard())` from `onCreate`. In `navCard()` add before `setupNav`: `addView(navRow(R.drawable.ic_hand, "Gestures") { open<GesturesActivity>() })`. Manifest: `.GesturesActivity` (if not added in Task 4).

- [ ] **Step 3: build + lint** (errors 0). **Step 4: commit** — `git add -A app/src && git commit -m "Gestures get their own screen"`

### Task 6: `ActivityLogActivity`

- [ ] **Step 1: create it.**

```kotlin
package dev.kb896.airgestures

import android.os.Bundle
import android.widget.TextView

/** The live log: connection, claim and gesture lines, newest last. */
class ActivityLogActivity : Screen() {
    private val watch: (BandService.State) -> Unit = { state ->
        lines.text = state.log.joinToString("\n").ifEmpty { "Nothing yet." }
    }
    private lateinit var lines: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lines = Cb.text(this, 12f, R.font.geist_mono_regular, Cb.INK_500).apply {
            setLineSpacing(0f, 1.35f)
            setTextIsSelectable(true)
        }
        show(pageColumn().apply { addView(card(null, lines)) }, title = "Activity")
    }

    override fun onResume() {
        super.onResume()
        BandService.state.observe(watch)
    }

    override fun onPause() {
        BandService.state.remove(watch)
        super.onPause()
    }
}
```

- [ ] **Step 2: Home.** Delete `activity`, `activityCard`, `addView(activityCard())`, the `activity.text = …` line in `render()`; in `navCard()` after `setupNav`: `addView(navRow(R.drawable.ic_activity, "Activity") { open<ActivityLogActivity>() })`. Manifest: `.ActivityLogActivity`. Update MainActivity's class doc to "Home: the band, its state, the controls, and the way to the other screens."

- [ ] **Step 3: build + lint** (errors 0); `wc -l app/src/main/java/dev/kb896/airgestures/MainActivity.kt` (expect ≈ 450). **Step 4: commit** — `git add -A app/src && git commit -m "Activity gets its own screen; Home is Home"`

### Task 7: lint in CI, changelog

- [ ] **Step 1:** in `.gitlab-ci.yml`'s `test` job `script:` add after the unit tests line:

```yaml
    # Lint errors (calls above minSdk, missing permissions) fail the pipeline; warnings don't.
    - ./gradlew --no-daemon :app:lintDebug
```

- [ ] **Step 2:** in `CHANGELOG.md`, above `## 0.4.0`:

```markdown
## Unreleased

- Needs Android 13 or newer. Below that the app didn't work properly before either.
- On Android 13 to 15, linking the band no longer closes the app, and the band coming
  into range wakes the app again (that wake-up was Android 16 only).
- Home is shorter: Gestures, Setup and Activity have their own screens. Setup shows on
  Home how many steps are left.
- A debug build installs beside the release (as "Air Gestures (dev)") instead of over it.
- The update card shows the whole first line of the release notes.
```

- [ ] **Step 3: commit** — `git commit -am "Lint in CI; changelog for the work since 0.4.0"`

### Task 8: full local check

- [ ] `cd rust && cargo test --workspace 2>&1 | grep -E 'test result|FAILED'` — all ok.
- [ ] `./build-rust.sh && JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew --console=plain clean assembleDebug lintDebug` — BUILD SUCCESSFUL, "Lint found 0 errors".

### Task 9: emulators API 33–36

- [ ] Install: `~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager --install emulator "system-images;android-33;google_apis;x86_64" "system-images;android-34;google_apis;x86_64" "system-images;android-35;google_apis;x86_64" "system-images;android-36;google_apis;x86_64"` (check exact names with `sdkmanager --list | grep google_apis | grep x86_64` first).
- [ ] For each API: `avdmanager create avd -n ag<API> -k "system-images;android-<API>;google_apis;x86_64" -d pixel_7`; `emulator -avd ag<API> -no-snapshot -no-audio -no-window &`; `adb wait-for-device`; wait for `sys.boot_completed`; `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- [ ] Launch `adb shell am start -n dev.kb896.airgestures.dev/dev.kb896.airgestures.MainActivity`; screenshot; open Gestures / Setup / Activity via `uiautomator dump` + `input tap`; back with `input keyevent BACK` and the on-screen arrow; Setup → Link; `adb logcat -d | grep -E 'FATAL|AndroidRuntime'` must be empty after each. If the bridge fails to load (`UnsatisfiedLinkError`), build an x86_64 bridge locally (`cargo ndk -t x86_64 …` into `app/src/main/jniLibs/x86_64`, `abiFilters += "x86_64"` locally only, never committed).
- [ ] Meta sign-in back on API 34: Setup → Claim → Sign in → follow a link → back gesture (`adb shell input keyevent BACK`) goes back one page, again closes.

### Task 10: on the Xiaomi (Android 16)

- [ ] Ask the user for the wireless-debugging port; `adb connect <ip>:<port>`; install the debug build (it installs beside the release as "(dev)"; the release must be stopped or its band connection released first — ask the user).
- [ ] Connect, gestures arrive, change a mapping in Gestures, Setup badge matches the Setup screen, stop the service, band wakes it.

### Task 11: merge

- [ ] Report results to the user; with their OK, `git switch master && git merge --ff-only groundwork`. Pushing `master` (no tag) also only with their OK.
