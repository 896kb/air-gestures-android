package dev.kb896.airgestures

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject

/**
 * Keeps the band connected in the background (a connected-device foreground
 * service) and runs its gestures. The activity watches it through [state].
 */
class BandService : Service(), BandLink.Listener {
    private lateinit var link: BandLink
    private lateinit var actions: Actions
    private lateinit var prefs: Prefs
    private lateinit var calls: CallWatch
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        actions = Actions(this, ::onLog)
        link = BandLink(this, this)
        // Unlocking the phone: the band is likely near and about to be used.
        registerReceiver(unlocked, android.content.IntentFilter(Intent.ACTION_USER_PRESENT))
        showProfile()
        // A call starting or ending swaps the During calls layer in or out.
        calls = CallWatch(this) { link.setMapping(prefs.mapping()) }.also { it.start() }
        // The app's Companion (the CDM link), not this class's companion object.
        dev.kb896.airgestures.Companion.observe(this)
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Band connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows while the band is kept connected."
                setShowBadge(false)
            },
        )
        // The status notification says when controls are off; no separate alerts.
        notifications.deleteNotificationChannel(OLD_ALERTS)
        instance = this
        main.post(updateCheck)
        Alerts.scheduleReminder(this)
        // A lesson the app never closed (it was killed): the controls go back as they were.
        if (prefs.practising) {
            prefs.practising = false
            prefs.paused = prefs.practiceWasPaused
        }
    }

    /** Look for a newer release now and then while the band is kept connected. */
    private val updateCheck: Runnable = object : Runnable {
        override fun run() {
            UpdateCheck.maybe(this@BandService)
            main.postDelayed(this, UpdateCheck.EVERY_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        when (intent?.action) {
            // From the widget: as the power button.
            ACTION_START -> prefs.running = true
            ACTION_TOGGLE_CONTROLS -> setPaused(!prefs.paused)
            ACTION_STOP -> {
                stop(this)
                return START_NOT_STICKY
            }
        }
        link.start()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(unlocked) }
        main.removeCallbacks(updateCheck)
        main.removeCallbacks(idleCheck)
        Alerts.notIdle(this)
        calls.stop()
        link.stop()
        actions.close()
        instance = null
        state.update { copy(phase = Phase.STOPPED) }
        Surfaces.refresh(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun setPaused(paused: Boolean) {
        // Asked for (button, tile, widget, notification, a lesson): not a middle hold.
        commandedAt = android.os.SystemClock.uptimeMillis()
        prefs.paused = paused
        link.setPaused(paused)
        pausedChanged(paused)
    }

    /** When the controls were last switched by the app rather than by the band. */
    private var commandedAt = 0L

    /** Record the controls' state; the status notification shows it. */
    private fun pausedChanged(paused: Boolean) {
        state.update { copy(paused = paused) }
        refreshNotification()
        watchIdle()
        broadcastStatus()
    }

    private val idleCheck = Runnable { if (idleNow()) Alerts.idle(this) }
    private var idleWaiting = false

    private fun idleNow() = state.value.paused && state.value.phase == Phase.CONNECTED

    /** Controls off with the band connected: after [Alerts.IDLE_MS], suggest disconnecting; else take that back. */
    private fun watchIdle() {
        if (idleNow()) {
            if (!idleWaiting) main.postDelayed(idleCheck, Alerts.IDLE_MS)
            idleWaiting = true
        } else {
            main.removeCallbacks(idleCheck)
            idleWaiting = false
            Alerts.notIdle(this)
        }
    }

    /** Send the mapping in force to the band again (a profile, call or setting changed). */
    fun remap() {
        link.setMapping(prefs.mapping())
        showProfile()
    }

    /** The profile's name in the state and the notification, while there's more than one. */
    private fun showProfile() {
        val list = Profiles.load(this)
        val name = list.active(Profiles.foreground).name.takeIf { list.profiles.size > 1 }
        if (name != state.value.profile) {
            state.update { copy(profile = name) }
            refreshNotification()
        }
    }

    /** A triple tap: the next (+1) or previous (−1) profile, unless the app in front has its own. */
    fun profileStep(by: Int): String? {
        if (Profiles.load(this).locked(Profiles.foreground)) return "this app has its own profile"
        switched(Profiles.update(this) { it.step(by) }.active(Profiles.foreground), back = by < 0)
        return null
    }

    /** The widget's picker chose a profile: like a triple tap, but straight to [id]. */
    fun pick(id: String): String? {
        if (Profiles.load(this).locked(Profiles.foreground)) return "this app has its own profile"
        switched(Profiles.update(this) { it.picking(id) }.active(Profiles.foreground))
        return null
    }

    /** Another profile is in force: the band gets it, and the user is told which. */
    private fun switched(profile: Profile, back: Boolean = false) {
        remap()
        Toast.makeText(this, "Profile: ${profile.name}", Toast.LENGTH_SHORT).show()
        if (Profiles.speak(this)) actions.announce(profile.name)
        if (Profiles.vibrate(this)) buzz(times = if (back) 2 else 1)
        onLog("profile: ${profile.name}")
    }

    /** One short buzz, or two (the previous profile). */
    private fun buzz(times: Int) {
        val vibrator = getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator ?: return
        val effect = if (times == 1) {
            android.os.VibrationEffect.createOneShot(40, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
        } else {
            android.os.VibrationEffect.createWaveform(longArrayOf(0, 40, 90, 40), -1)
        }
        runCatching { vibrator.vibrate(effect) }
    }

    /** Start watching calls, e.g. right after the permission is granted (again is harmless). */
    fun watchCalls() = calls.start()

    /** Debug: write a raw ConfigReq (hex) to the band; the answer goes to the log. */
    fun probeConfig(hex: String) = link.probeConfig(hex)

    /** Debug: ask the band's update status (read only); the answer goes to the log. */
    fun otaStatus() = link.otaStatus()
    fun otaStart(mode: String, dir: String) {
        if (mode == "install" || mode == "update") {
            // Checked when the band reconnects after its reboot (see checkInstall).
            val manifest = runCatching { JSONObject(java.io.File(dir, "manifest.json").readText()) }.getOrNull()
            manifest?.optJSONArray("items")?.optJSONObject(0)?.optLong("version")?.let { prefs.otaExpect = it }
        }
        link.otaStart(mode, dir)
    }

    /** Debug: the band's raw sEMG stream on or off; its rate goes to the log. */
    fun rawEmg(on: Boolean) = link.rawEmg(on)

    /** What the band said about itself (see BandLink.deviceInfo). */
    fun deviceInfo() = link.deviceInfo()

    /** Debug: a handwriting recording (see BandLink.recordStart). */
    val recording get() = link

    private val unlocked = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = nudge()
    }

    /** The band is probably near: connect directly if still waiting for it. */
    fun nudge() = link.nudge()

    /** Set the band to this wrist now and on every later connection. */
    fun setHand(hand: String) {
        prefs.hand = hand
        link.setMapping(prefs.mapping())
    }

    // BandLink.Listener: called from Bluetooth and link threads.

    override fun onPhase(phase: Phase, band: String?) = main.post {
        state.update {
            val back = phase == Phase.CONNECTED && install?.stage == "restarting"
            copy(phase = phase, band = band ?: this.band, install = if (back) install?.copy(stage = "checking") else install)
        }
        refreshNotification()
        watchIdle()
        broadcastStatus()
    }.let {}

    override fun onStatus(status: JSONObject) = main.post {
        var paused = status.optBoolean("paused")
        val lesson = practice
        // A flip to off within a moment of the app switching the controls is that switch
        // (or a late report of it), not the middle hold.
        val ours = android.os.SystemClock.uptimeMillis() - commandedAt < 1_500
        if (lesson != null && paused && !ours) {
            // The middle hold in a lesson: it counts, but the controls stay on.
            lesson.held()
            link.setPaused(false)
            paused = false
        }
        if (paused != prefs.paused) prefs.paused = paused
        if (paused != state.value.paused) pausedChanged(paused)
        val before = state.value
        state.update {
            copy(
                paused = paused,
                battery = if (status.isNull("battery")) null else status.getInt("battery"),
                charging = status.optBoolean("charging"),
                hand = status.optString("hand").takeUnless { status.isNull("hand") },
                dial = status.optString("dial", dial),
                lastGesture = status.optString("last_gesture").takeUnless { status.isNull("last_gesture") },
                gestures = status.optLong("gestures"),
                firmware = status.optString("firmware").takeUnless { status.isNull("firmware") },
                otaStatus = status.optString("ota_status").takeUnless { status.isNull("ota_status") },
                otaKek = status.optLong("ota_kek").takeUnless { status.isNull("ota_kek") },
                otaProgress = status.optString("ota_progress").takeUnless { status.isNull("ota_progress") },
                otaBusy = status.optBoolean("ota_busy"),
                otaItems = status.optJSONArray("ota_items")?.let { items ->
                    (0 until items.length()).map { i ->
                        val item = items.getJSONObject(i)
                        OtaItem(item.getString("name"), item.getLong("size"), item.getLong("sent"), item.getString("stage"))
                    }
                }.orEmpty(),
            )
        }
        val now = state.value
        if (now.otaProgress != before.otaProgress) followInstall(now.otaProgress)
        if (now.firmware != before.firmware) now.firmware?.let(::checkInstall)
        if (lesson != null && now.gestures > before.gestures) lesson.gesture(now.lastGesture.orEmpty())
        val level = now.battery
        if (level != null && (level != before.battery || now.charging != before.charging)) {
            Alerts.battery(this, now.band, level, now.charging)
        }
        refreshNotification()
        broadcastStatus()
    }.let {}

    /** What [broadcastStatus] last sent, so an unchanged state sends nothing. */
    private var sent: List<Any?>? = null

    /**
     * Tell the apps that announced themselves how the band is: from their side a flat battery
     * and a lost connection look the same (the gestures stop). Only those apps, so the band's
     * state isn't sent to the whole phone. [force]: send even if nothing changed (a new app).
     */
    private fun broadcastStatus(force: Boolean = false) {
        val s = state.value
        val connected = s.phase == Phase.CONNECTED
        val now = listOf(s.battery, s.charging, connected, s.paused)
        if (now == sent && !force) return
        sent = now
        for (pkg in prefs.announced) {
            runCatching {
                sendBroadcast(
                    Intent(ACTION_STATUS)
                        .setPackage(pkg)
                        .putExtra(EXTRA_BATTERY, s.battery ?: -1)
                        .putExtra(EXTRA_CHARGING, s.charging)
                        .putExtra(EXTRA_CONNECTED, connected)
                        .putExtra(EXTRA_CONTROLS, !s.paused),
                )
            }
        }
    }

    override fun onActions(actions: List<String>) = main.post {
        // In a lesson nothing runs; the lesson sees what would have.
        practice?.let { lesson -> actions.forEach(lesson::action) } ?: actions.forEach { action ->
            this.actions.run(action)?.let(::onLog)
        }
    }.let {}

    /** Run one action as a gesture would (e.g. a sequence's Try it); a failure goes to the log. */
    fun runAction(action: String) = actions.run(action)?.let(::onLog)

    /** What a practice lesson hears from the band while it's open. */
    interface Practice {
        fun gesture(label: String)
        fun action(id: String)
        fun held()
    }

    /** Set while a lesson is open: gestures are reported to it and run nothing. */
    var practice: Practice? = null
        private set
    /**
     * Start a lesson: the controls go on (the band reports more), and come back as they were
     * after. How they were is kept in the prefs, so a service restarted mid-lesson keeps it.
     */
    fun startPractice(lesson: Practice) {
        if (!prefs.practising) {
            prefs.practiceWasPaused = state.value.paused
            prefs.practising = true
        }
        practice = lesson
        if (state.value.paused) setPaused(false)
    }

    fun endPractice() {
        practice = null
        if (!prefs.practising) return
        prefs.practising = false
        if (state.value.paused != prefs.practiceWasPaused) setPaused(prefs.practiceWasPaused)
    }

    /**
     * After an install the band reboots; when it reconnects, say whether it now runs the firmware
     * that was committed (as Meta AI checks), and ask its update status for the rest (state, ML).
     */
    private fun checkInstall(firmware: String) {
        val expected = prefs.otaExpect.takeIf { it != 0L } ?: return
        prefs.otaExpect = 0
        val installed = firmware == expected.toString()
        onLog(if (installed) "ota: INSTALLED: the band runs $firmware"
              else "ota: INSTALL NOT CONFIRMED: the band runs $firmware, not $expected")
        state.update {
            val began = install?.since ?: android.os.SystemClock.uptimeMillis()
            copy(install = Install(expected, if (installed) "installed" else "failed", began, firmware))
        }
        // The band reports its version before it accepts requests: ask a moment later.
        main.postDelayed({ otaStatus() }, 3_000)
    }

    /**
     * The update's latest line: once the band accepts Install it restarts (the stage shows
     * that until it's back); a refused or failed update leaves nothing to check afterwards.
     */
    private fun followInstall(line: String?) {
        line ?: return
        val expected = prefs.otaExpect.takeIf { it != 0L } ?: return
        when {
            line.startsWith("COMMITTED") || line.startsWith("no answer to Commit") ->
                state.update { copy(install = Install(expected, "restarting", android.os.SystemClock.uptimeMillis())) }
            line.startsWith("commit answered") || line.startsWith("FAILED") || line.startsWith("abort after the failure") ->
                prefs.otaExpect = 0
        }
    }

    override fun onLog(line: String) = main.post {
        android.util.Log.i("AirGestures", line)
        state.update { copy(log = (log + line).takeLast(LOG_LINES)) }
    }.let {}

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
        Surfaces.refresh(this)
    }

    private fun notification(): Notification {
        val s = state.value
        val title = when {
            s.phase == Phase.CONNECTED && s.paused -> "Controls off"
            s.phase == Phase.CONNECTED -> "Connected"
            s.phase == Phase.STOPPED -> "Stopped"
            else -> "Looking for the band"
        }
        val text = listOfNotNull(s.band, s.battery?.let { "$it %" }, s.profile).joinToString(" · ")
        // The launcher's own intent, so the app comes back as it was (not a second Home on top).
        val launch = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val open = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE)
        fun action(label: String, action: String, code: Int) = Notification.Action.Builder(
            null, label,
            PendingIntent.getService(
                this, code, Intent(this, BandService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(if (s.paused) R.drawable.ic_hand_off else R.drawable.ic_hand)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(action(if (s.paused) "Turn controls on" else "Turn controls off", ACTION_TOGGLE_CONTROLS, 1))
            .addAction(action("Stop", ACTION_STOP, 2))
            .build()
    }

    /** What the activity shows; updated on the main thread. */
    data class State(
        val phase: Phase = Phase.STOPPED,
        val band: String? = null,
        val paused: Boolean = false,
        val battery: Int? = null,
        val charging: Boolean = false,
        val hand: String? = null,
        val dial: String = "volume",
        val lastGesture: String? = null,
        val gestures: Long = 0,
        val log: List<String> = emptyList(),
        /** A claim ([BandClaim]) is running; the service is off meanwhile. */
        val claiming: Boolean = false,
        /** The profile in force; null while there's only one (nothing to tell apart). */
        val profile: String? = null,
        /** The firmware version the band reported on connecting. */
        val firmware: String? = null,
        /** The band's last update status, the latest line of the running or last update, and whether one runs. */
        val otaStatus: String? = null,
        /** The key version the band reported with its update status. */
        val otaKek: Long? = null,
        val otaProgress: String? = null,
        val otaBusy: Boolean = false,
        /** Each file of that update and how far it is. */
        val otaItems: List<OtaItem> = emptyList(),
        /** The install after the band accepted it: restarting, then checked. */
        val install: Install? = null,
    )

    /**
     * An install the band accepted: [stage] is "restarting", "checking", "installed" or
     * "failed"; [since] (uptime ms) when it began; [running] what the band reported back.
     */
    data class Install(val version: Long, val stage: String, val since: Long, val running: String? = null)

    /** One file of a firmware update: [stage] is "waiting", "sending", "checking" or "done". */
    data class OtaItem(val name: String, val size: Long, val sent: Long, val stage: String)

    /** A tiny main-thread observable (no AndroidX in this app). */
    class Observable<T>(initial: T) {
        var value: T = initial
            private set
        private val listeners = mutableSetOf<(T) -> Unit>()

        fun update(change: T.() -> T) {
            value = value.change()
            listeners.forEach { it(value) }
        }

        fun observe(listener: (T) -> Unit) {
            listeners += listener
            listener(value)
        }

        fun remove(listener: (T) -> Unit) {
            listeners -= listener
        }
    }

    companion object {
        /** Pick a profile from outside the service (the widget): through it when it runs, else just stored. */
        fun pickProfile(context: Context, id: String) {
            val service = instance
            if (service != null) service.pick(id) else Profiles.update(context) { it.picking(id) }
            Surfaces.refresh(context)
        }

        private const val CHANNEL = "band"
        private const val NOTIFICATION = 1
        /** The channel of the removed controls on/off pop-up. */
        private const val OLD_ALERTS = "alerts"
        private const val LOG_LINES = 60
        const val ACTION_TOGGLE_CONTROLS = "dev.kb896.airgestures.TOGGLE_CONTROLS"
        const val ACTION_STOP = "dev.kb896.airgestures.STOP"
        const val ACTION_START = "dev.kb896.airgestures.START"

        /** From an app: `profile` = its package as it comes to the front, "" as it leaves. */
        const val ACTION_SET_PROFILE = "dev.kb896.airgestures.SET_PROFILE"
        const val EXTRA_PROFILE = "profile"

        /** The band's state, sent to the apps that have announced themselves. */
        const val ACTION_STATUS = "dev.kb896.airgestures.STATUS"
        const val EXTRA_BATTERY = "battery"
        const val EXTRA_CHARGING = "charging"
        const val EXTRA_CONNECTED = "connected"
        /** The controls are on (false while the middle hold, a tile or the app turned them off). */
        const val EXTRA_CONTROLS = "controls"

        val state = Observable(State())
        var instance: BandService? = null
            private set

        fun start(context: Context) {
            Prefs(context).running = true
            context.startForegroundService(Intent(context, BandService::class.java))
        }

        fun stop(context: Context) {
            Prefs(context).running = false
            context.stopService(Intent(context, BandService::class.java))
        }

        /** Store a gesture's action in [profile], live on the band when the service runs. */
        fun setAction(context: Context, profile: String, gesture: Gesture, id: String) {
            Prefs(context).setAction(profile, gesture, id)
            instance?.remap()
        }

        /** Store what a gesture does during calls, live on the band when the service runs. */
        fun setCallAction(context: Context, gesture: Gesture, id: String) {
            Prefs(context).setCallAction(gesture, id)
            instance?.remap()
        }

        /**
         * The accessibility service saw [pkg] come to the front: if that changes the profile
         * in force (into or out of an app's own), the band gets it and the user is told.
         */
        fun foreground(context: Context, pkg: String?) = front(context) { Profiles.seen = pkg }

        /**
         * An app said it came to the front ([pkg]) or left (null): its own profile applies until
         * it leaves, wherever the accessibility service thinks the user is. It also gets the
         * band's state from now on ([ACTION_STATUS]), and once right away.
         */
        fun announce(context: Context, pkg: String?) {
            front(context) { Profiles.announced = pkg }
            if (pkg == null) return
            val prefs = Prefs(context)
            val first = pkg !in prefs.announced
            prefs.recordAnnounced(pkg)
            if (first) instance?.broadcastStatus(force = true)
        }

        private fun front(context: Context, change: () -> Unit) {
            val list = Profiles.load(context)
            val before = list.active(Profiles.foreground)
            change()
            val after = list.active(Profiles.foreground)
            if (after.id != before.id) instance?.switched(after)
        }

        fun setDial(context: Context, profile: String, dial: DialChoice) {
            Prefs(context).setDial(profile, dial)
            instance?.remap()
        }
    }
}
