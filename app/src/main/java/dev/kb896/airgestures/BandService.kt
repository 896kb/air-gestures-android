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
        calls = CallWatch(this) { remap() }.also { it.start() }
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

    /** From the app (button, tile, widget, notification); the band's own switch comes as `@controls`. */
    fun setPaused(paused: Boolean) {
        prefs.paused = paused
        link.setPaused(paused)
        pausedChanged(paused)
    }

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

    /** Send the profiles, the call state and the app in front to the band again (any of them changed). */
    fun remap() {
        link.setProfiles(prefs.profilesJson())
        link.setInCall(CallWatch.inCall)
        link.setForeground(Profiles.foreground ?: "")
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

    /** The widget's picker chose a profile: the band gets it, and the user is told. */
    fun pick(id: String): String? {
        if (Profiles.load(this).locked(Profiles.foreground)) return "this app has its own profile"
        val now = Profiles.update(this) { it.picking(id) }.active(Profiles.foreground)
        remap()
        announce(now)
        return null
    }

    /** The band moved to another profile (a gesture with Next or Previous profile): store the pick, then tell the user. */
    private fun bandSwitched(index: Int, step: Int) {
        val id = Profiles.load(this).profiles.getOrNull(index)?.id ?: return
        val now = Profiles.update(this) { it.picking(id) }.active(Profiles.foreground)
        // Again to the band: a remap sent meanwhile (e.g. a call starting) carried the old pick.
        remap()
        announce(now, back = step < 0)
    }

    /** The band turned the controls on or off (a gesture with Controls on/off). */
    private fun bandToggled(on: Boolean) {
        prefs.paused = !on
        pausedChanged(!on)
    }

    /** Another profile is in force: tell the user which. */
    private fun announce(profile: Profile, back: Boolean = false) {
        showProfile()
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
        // A new start or a cancel ends whatever the last install showed; a cancel leaves nothing to check.
        state.update { copy(install = null) }
        if (mode == "cancel") prefs.otaExpect = 0
        // What an install would put on the band; checked after its reboot only once the band
        // accepted it (see followInstall), so a dropped or cancelled update leaves nothing to check.
        installing = if (mode == "install" || mode == "update") {
            val manifest = runCatching { JSONObject(java.io.File(dir, "manifest.json").readText()) }.getOrNull()
            manifest?.optJSONArray("items")?.optJSONObject(0)?.optLong("version")
        } else null
        link.otaStart(mode, dir)
    }


    /** The band's handwriting into the text field in front (see WriteService). */
    /** [before]: the field's text before the cursor, so a sentence's first word gets its capital. */
    fun setFieldTyping(on: Boolean, before: String = "") = link.setFieldTyping(on, before, prefs.choicesPopup)
    fun fieldEdits() = link.fieldEdits()
    fun fieldPenDown() = link.fieldPenDown()
    fun fieldChoose() = link.fieldChoose()
    fun fieldChooseStep(forward: Boolean) = link.fieldChooseStep(forward)
    fun fieldChoosePick() = link.fieldChoosePick()
    fun fieldChooseCancel() = link.fieldChooseCancel()

    /** Whether the band's firmware reads handwriting (a `hw_` model); null before it said. */
    fun bandWrites(): Boolean? = link.deviceInfo()?.takeIf { it.has("handwriting") }?.optBoolean("handwriting")

    /** Debug: the band's own handwriting (see BandLink.setBandHandwriting). */
    fun setBandHandwriting(on: Boolean) = link.setBandHandwriting(on)
    fun bandHandwriting(clear: Boolean = false) = link.bandHandwriting(clear)

    /** What the band said about itself (see BandLink.deviceInfo). */
    fun deviceInfo() = link.deviceInfo()

    private val unlocked = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = nudge()
    }

    /** The band is probably near: connect directly if still waiting for it. */
    fun nudge() = link.nudge()

    /** Set the band to this wrist now and on every later connection. */
    fun setHand(hand: String) {
        prefs.hand = hand
        link.setProfiles(prefs.profilesJson())
    }

    // BandLink.Listener: called from Bluetooth and link threads.

    override fun onPhase(phase: Phase, band: String?) = main.post {
        // A disconnect ends an open draw session without a word from band-core: close the pad.
        if (phase != Phase.CONNECTED) pad.cancelled()
        state.update {
            val back = phase == Phase.CONNECTED && install?.stage == "restarting"
            copy(phase = phase, band = band ?: this.band, install = if (back) install?.copy(stage = "checking") else install)
        }
        refreshNotification()
        watchIdle()
        broadcastStatus()
    }.let {}

    override fun onStatus(status: JSONObject) = main.post {
        // Mirrored as the bridge has it (right after a reconnect too); `@controls` says it first.
        val paused = status.optBoolean("paused")
        if (paused != prefs.paused) prefs.paused = paused
        if (paused != state.value.paused) pausedChanged(paused)
        val before = state.value
        state.update {
            copy(
                paused = paused,
                battery = if (status.isNull("battery")) null else status.getInt("battery"),
                charging = status.optBoolean("charging"),
                hand = status.optString("hand").takeUnless { status.isNull("hand") },
                lastGesture = status.optString("last_gesture").takeUnless { status.isNull("last_gesture") },
                gestures = status.optLong("gestures"),
                firmware = status.optString("firmware").takeUnless { status.isNull("firmware") },
                otaStatus = status.optString("ota_status").takeUnless { status.isNull("ota_status") },
                otaKek = status.optLong("ota_kek").takeUnless { status.isNull("ota_kek") },
                otaProgress = status.optString("ota_progress").takeUnless { status.isNull("ota_progress") },
                otaBusy = status.optBoolean("ota_busy"),
                otaCancellable = status.optBoolean("ota_cancellable", true),
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
        for (line in actions) {
            if (!line.startsWith("@")) {
                // In a lesson nothing runs (the bridge sends `@practiced` instead).
                if (practice == null) this.actions.run(line)?.let(::onLog)
                continue
            }
            val kind = line.removePrefix("@").substringBefore(' ')
            val rest = line.substringAfter(' ', "")
            when (kind) {
                // The Activity screen's line: the gesture, its palm and what it did, in the app's words.
                "activity" -> ActivityLine.format(
                    rest,
                    label = { id -> ActivityLine.label(id, device = { Profiles.devices(this).get(it)?.name }) { Labels.action(this, it) } },
                    profile = { Profiles.load(this).profiles.getOrNull(it)?.name },
                )?.let(::onLog)
                "gesture" -> practice?.gesture(rest)
                "practiced" -> practice?.action(rest)
                "field-gesture" -> field?.gesture(rest)
                "field" -> if (rest == "off") field?.ended()
                "controls" -> bandToggled(rest == "on")
                "profile" -> rest.split(' ').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 }
                    ?.let { (index, step) -> bandSwitched(index, step) }
                "held" -> {
                    Toast.makeText(this, "This app has its own profile", Toast.LENGTH_SHORT).show()
                    onLog("profile: this app has its own profile")
                }
                "draw-started" -> pad.started()
                "pen" -> pad.pen(rest == "down")
                "draw-point" -> rest.split(' ').takeIf { it.size == 3 }?.let { (s, x, y) ->
                    val stroke = s.toIntOrNull() ?: return@let
                    pad.point(stroke, x.toFloatOrNull() ?: return@let, y.toFloatOrNull() ?: return@let)
                }
                "draw-finished" -> pad.finished(rest)
                "drawn" -> pad.drawn(rest)
                "not-drawn" -> {
                    pad.notDrawn()
                    buzz(times = 2)
                }
                "draw-cancelled" -> pad.cancelled()
                "drag-sample" -> rest.split(' ').takeIf { it.size == 5 }?.let { w ->
                    val numbers = w.drop(1).map { it.toDoubleOrNull() ?: return@let }
                    dragTeaching?.sample(w[0], doubleArrayOf(numbers[0], numbers[1], numbers[2]), numbers[3])
                }
            }
        }
    }.let {}

    /** Run one action as a gesture would (e.g. a sequence's Try it); a failure goes to the log. */
    fun runAction(action: String) = actions.run(action)?.let(::onLog)

    /** What a practice lesson hears from the band while it's open: each gesture's label, and what it would run. */
    interface Practice {
        fun gesture(label: String)
        fun action(id: String)
    }

    /** Set while a lesson is open: gestures are reported to it and run nothing. */
    var practice: Practice? = null
        private set

    /**
     * Start a lesson: the band's layer goes into practice (gestures are reported, nothing runs,
     * the built-ins don't act), whether the controls are on or off.
     */
    fun startPractice(lesson: Practice) {
        // A field being written into would take the lesson's gestures (writing wins over practice).
        WriteService.instance?.lessonStarted()
        practice = lesson
        link.setPractice(true)
    }

    fun endPractice() {
        practice = null
        link.setPractice(false)
    }

    /**
     * The text field the band writes into (WriteService): the gestures made for it (the band's
     * own actions don't run meanwhile; Controls on/off does), and the controls going off, which
     * ended it in the bridge.
     */
    interface Field {
        fun gesture(label: String)
        fun ended()
    }

    /** Set while the band writes into a text field (with [setFieldTyping]). */
    var field: Field? = null

    /** A draw session's listener: a teach screen while one is open, else the pad over the app in front (or toasts). */
    interface Drawing {
        fun started() {}
        /** The pen went down (the next point starts its stroke; a dropped stroke's number comes again: draw it afresh) or up. */
        fun pen(down: Boolean) {}
        fun point(stroke: Int, x: Float, y: Float) {}
        /** The session's strokes (DrawingLibrary.strokes reads them). */
        fun finished(strokes: String) {}
        fun drawn(name: String) {}
        fun notDrawn() {}
        fun cancelled() {}
    }

    /** Teach your drags' listener. */
    fun interface DragTeaching {
        fun sample(finger: String, direction: DoubleArray, roll: Double)
    }

    var drawing: Drawing? = null
        private set
    private var dragTeaching: DragTeaching? = null

    /** Results without a pad (no accessibility service, judgement 9): toasts. */
    private val toasts = object : Drawing {
        override fun drawn(name: String) = toast(DrawPad.result(this@BandService, name))
        override fun notDrawn() = toast("Not one of your drawings")
    }
    /** Where a draw session shows: a teach screen while one is open, else the pad over the app in front, else toasts. */
    private val pad: Drawing get() = drawing ?: TouchService.instance?.pad ?: toasts

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    /** Teach a drawing: practice (nothing runs) and teaching (sessions report their strokes). */
    fun startTeachingDrawing(listener: Drawing) {
        drawing = listener
        startPractice(object : Practice { override fun gesture(label: String) = Unit; override fun action(id: String) = Unit })
        link.setTeaching(true)
    }
    /** One try: a draw session the screen opened. */
    fun drawTry() = link.startDrawing()
    fun endTeachingDrawing() {
        link.cancelDrawing()
        link.setTeaching(false)
        endPractice()
        drawing = null
    }
    fun startDragTeaching(listener: DragTeaching) {
        dragTeaching = listener
        link.setDragTeaching(true)
    }
    fun endDragTeaching() {
        link.setDragTeaching(false)
        dragTeaching = null
    }
    fun cancelDrawing() = link.cancelDrawing()
    fun palmNow() = link.palmNow()
    /** The drawings changed: the band matches against the new ones. */
    fun drawingsChanged() {
        val library = Drawings.load(this)
        // Never pruned from an unreadable file: its drawings are only in drawings.broken now.
        if (Drawings.readable(this)) Profiles.pruneDrawingActions(this, library.names.toSet())
        link.setLibrary(library.toJson())
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
        val expected = installing ?: return
        if (line.startsWith("COMMITTED") || line.startsWith("no answer to Commit")) {
            installing = null
            prefs.otaExpect = expected
            state.update { copy(install = Install(expected, "restarting", android.os.SystemClock.uptimeMillis())) }
        }
    }

    /** The firmware an install or update in progress would put on the band. */
    private var installing: Long? = null

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
        /** Cancelling is safe now: the band hasn't been told to install. */
        val otaCancellable: Boolean = true,
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
            val app = Profiles.foreground
            val before = list.active(app)
            change()
            val after = list.active(Profiles.foreground)
            val service = instance ?: return
            if (after.id != before.id) {
                service.remap()
                service.announce(after)
            } else if (Profiles.foreground != app) {
                // The band's layer follows the app in front too (its own profile; whether next and previous may switch).
                service.link.setForeground(Profiles.foreground ?: "")
            }
        }

        /** Store what the index finger's pinch and turn does in [profile] (a [DialChoice] key, or automation for a device). */
        fun setDial(context: Context, profile: String, dial: String) {
            Prefs(context).setDial(profile, dial)
            instance?.remap()
        }

        /** Store what the middle finger's pinch and turn does in [profile], live on the band when the service runs. */
        fun setMiddleDial(context: Context, profile: String, dial: String) {
            Profiles.update(context) { it.withMiddleDial(profile, dial) }
            instance?.remap()
        }

        /** Store a finger's drags (gestures or sliders) in [profile], live on the band when the service runs. */
        fun setDrags(context: Context, profile: String, part: Part, drags: Drags) {
            Profiles.update(context) { it.withDrags(profile, part, drags) }
            instance?.remap()
        }
    }
}
