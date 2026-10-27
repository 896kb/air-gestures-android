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
 * Keeps the bands connected in the background (a connected-device foreground service) and runs
 * their gestures: one [Band] per band this phone keeps ([Bands]). The activities watch the band
 * the home screen shows through [state], any band through [stateOf].
 */
class BandService : Service() {
    private lateinit var actions: Actions
    private lateinit var calls: CallWatch
    private val main = Handler(Looper.getMainLooper())
    /** Each band kept here, by slot; band 2 only once it's added and has a key. */
    private val bands = arrayOfNulls<Band>(Bands.MAX)
    /** The service was started (not only created): new bands connect at once. */
    private var started = false
    /** The band whose action runs now: its step detents go to it. */
    private var acting = 0

    private fun all() = bands.filterNotNull()

    /** The band the home screen shows (its screens act on it), if it's kept here. */
    private fun shown() = bands.getOrNull(Bands.selected(this))

    private fun band(slot: Int) = bands.getOrNull(slot)

    override fun onCreate() {
        super.onCreate()
        actions = Actions(this, ::onLog, ::stepDetent)
        selectionChanged(this)
        // Unlocking the phone: the band is likely near and about to be used.
        registerReceiver(unlocked, android.content.IntentFilter(Intent.ACTION_USER_PRESENT))
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
        bandsChanged()
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
            ACTION_START -> Prefs(this).running = true
            // Every band at once: off while any is on, else all on.
            ACTION_TOGGLE_CONTROLS -> setPausedAll(all().any { !it.prefs.paused })
            ACTION_STOP -> {
                stop(this)
                return START_NOT_STICKY
            }
        }
        started = true
        all().forEach { it.link.start() }
        return START_STICKY
    }

    /**
     * A band was added, removed, got a key, or its power button was pressed: each band whose
     * connection is on ([Prefs.connect]) is kept, band 1 even without a key (it says it has
     * none), band 2 once it's added and has one.
     */
    fun bandsChanged() {
        for (slot in 0 until Bands.MAX) {
            val wanted = slot < Bands.count(this) && (slot == 0 || Identity.present(this, slot)) && Prefs(this, slot).connect
            val band = bands[slot]
            if (wanted && band == null) {
                bands[slot] = Band(slot).also { if (started) it.link.start() }
            } else if (!wanted && band != null) {
                band.link.stop()
                bands[slot] = null
                states[slot].update { State() }
            }
        }
        remap()
        refreshNotification()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(unlocked) }
        main.removeCallbacks(updateCheck)
        main.removeCallbacks(idleCheck)
        Alerts.notIdle(this)
        calls.stop()
        all().forEach { it.link.stop() }
        actions.close()
        instance = null
        for (slot in 0 until Bands.MAX) states[slot].update { copy(phase = Phase.STOPPED) }
        Surfaces.refresh(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** From the app (button, tile, widget, notification) for one band; the band's own switch comes as `@controls`. */
    fun setPaused(paused: Boolean, slot: Int = Bands.selected(this)) {
        val band = band(slot) ?: return
        val changed = paused != band.state.value.paused
        band.prefs.paused = paused
        band.link.setPaused(paused)
        if (changed && band.connected) band.link.controlsHaptics(paused)
        else band.link.syncControlsHaptics(paused)
        band.pausedChanged(paused)
    }

    /** Every band's controls at once (the notification, the tile, the widget). */
    fun setPausedAll(paused: Boolean) = all().forEach { setPaused(paused, it.slot) }

    /** Whether every band's controls are off (none kept: as stored). */
    fun allPaused() = all().all { it.prefs.paused }

    fun setBandVibration(enabled: Boolean) {
        Prefs(this).bandVibration = enabled
        all().forEach { it.link.setBandVibration(enabled) }
    }

    /** The band writing into a text field ([Bands.writer]), if it's kept here. */
    private fun writer() = Bands.writer(this)?.let(::band)

    fun typingHaptics(down: Boolean) {
        val band = writer() ?: return
        if (band.prefs.bandVibration && band.connected) band.link.profileHaptics(previous = !down)
    }

    private fun stepDetent() {
        val band = band(acting) ?: return
        if (band.prefs.bandVibration && band.connected) band.link.stepDetent()
    }

    fun notificationHaptics() = main.post {
        for (band in all()) {
            if (band.prefs.bandVibration && band.prefs.notificationVibration && band.connected && !band.state.value.otaBusy) {
                band.link.notificationHaptics()
            }
        }
    }

    /** A buzz on [slot] (the band the home screen shows unless said). */
    fun feedbackHaptics(previous: Boolean = false, slot: Int = Bands.selected(this)) {
        val band = band(slot) ?: return
        if (band.prefs.bandVibration && band.connected) band.link.profileHaptics(previous)
    }

    private val idleCheck = Runnable { if (idleNow()) Alerts.idle(this) }
    private var idleWaiting = false

    /** Every connected band's controls are off (and one is connected). */
    private fun idleNow() = all().filter { it.connected }.let { on -> on.isNotEmpty() && on.all { it.state.value.paused } }

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

    /** Send the profiles, the call state and the app in front to every band again (any of them changed). */
    fun remap() = all().forEach { it.remap() }

    /** The widget's picker chose a profile for the band the home screen shows: it gets it, and the user is told. */
    fun pick(id: String): String? {
        val slot = Bands.selected(this)
        if (Bands.profiles(this, slot).locked(Profiles.foreground)) return "this app has its own profile"
        Bands.pick(this, slot, id)
        val band = band(slot) ?: return null
        band.remap()
        band.announce(Bands.profiles(this, slot).active(Profiles.foreground))
        return null
    }

    /** Start watching calls, e.g. right after the permission is granted (again is harmless). */
    fun watchCalls() = calls.start()

    /** Debug: write a raw ConfigReq (hex) to the band; the answer goes to the log. */
    fun probeConfig(hex: String, slot: Int = Bands.selected(this)) = band(slot)?.link?.probeConfig(hex)

    /** Ask a band's update status (read only); the answer goes to the log. */
    fun otaStatus(slot: Int = Bands.selected(this)) = band(slot)?.link?.otaStatus()

    /** One step of a firmware update on [slot] (the firmware screen's band). */
    fun otaStart(mode: String, dir: String, slot: Int = Bands.selected(this)) {
        val band = band(slot) ?: return
        // A new start or a cancel ends whatever the last install showed; a cancel leaves nothing to check.
        band.state.update { copy(install = null) }
        if (mode == "cancel") band.prefs.otaExpect = 0
        // What an install would put on the band; checked after its reboot only once the band
        // accepted it (see followInstall), so a dropped or cancelled update leaves nothing to check.
        band.installing = if (mode == "install" || mode == "update") {
            val manifest = runCatching { JSONObject(java.io.File(dir, "manifest.json").readText()) }.getOrNull()
            manifest?.optJSONArray("items")?.optJSONObject(0)?.optLong("version")
        } else null
        band.link.otaStart(mode, dir)
    }

    /** The writing band's link ([Bands.writer]); null without one kept here. */
    private fun pen() = writer()?.link

    /** The band's handwriting into the text field in front (see WriteService): the band it's on for. */
    /** [before]: the field's text before the cursor, so a sentence's first word gets its capital. */
    fun setFieldTyping(on: Boolean, before: String = "") {
        // Off goes to every band: the switch may have moved to the other one meanwhile.
        if (!on) all().forEach { it.link.setFieldTyping(false, before, it.prefs.choicesPopup) }
        else writer()?.let { it.link.setFieldTyping(true, before, it.prefs.choicesPopup) }
    }
    fun fieldEdits() = pen()?.fieldEdits()
    fun fieldPenDown() = pen()?.fieldPenDown()
    fun fieldChoose() = pen()?.fieldChoose() == true
    fun fieldChooseStep(forward: Boolean) = pen()?.fieldChooseStep(forward)
    fun fieldChoosePick() = pen()?.fieldChoosePick()
    fun fieldChooseCancel() = pen()?.fieldChooseCancel()

    /** Whether a band's firmware reads handwriting (a `hw_` model); null before it said. */
    fun bandWrites(slot: Int = Bands.selected(this)): Boolean? =
        band(slot)?.link?.deviceInfo()?.takeIf { it.has("handwriting") }?.optBoolean("handwriting")

    /** Debug: the band's own handwriting (see BandLink.setBandHandwriting). */
    fun setBandHandwriting(on: Boolean, slot: Int = Bands.selected(this)) = band(slot)?.link?.setBandHandwriting(on)
    fun bandHandwriting(clear: Boolean = false, slot: Int = Bands.selected(this)) = band(slot)?.link?.bandHandwriting(clear)

    /** What a band said about itself (see BandLink.deviceInfo). */
    fun deviceInfo(slot: Int = Bands.selected(this)) = band(slot)?.link?.deviceInfo()

    private val unlocked = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = nudge()
    }

    /** The bands are probably near: connect directly if still waiting for them. */
    fun nudge() = all().forEach { it.link.nudge() }

    /** Set a band to this wrist now and on every later connection. */
    fun setHand(hand: String, slot: Int = Bands.selected(this)) {
        Prefs(this, slot).hand = hand
        // The other band's `band` tag and log mark may follow the wrist.
        remap()
    }

    /** One band kept connected: its link, settings and state. */
    private inner class Band(val slot: Int) : BandLink.Listener {
        val prefs = Prefs(this@BandService, slot)
        val link = BandLink(this@BandService, this, slot)
        val state get() = states[slot]
        val connected get() = state.value.phase == Phase.CONNECTED
        /** The firmware an install or update in progress would put on the band. */
        var installing: Long? = null
        /** What [broadcastStatus] last sent, so an unchanged state sends nothing. */
        private var sent: List<Any?>? = null

        fun remap() {
            link.setProfiles(prefs.profilesJson())
            link.setInCall(CallWatch.inCall)
            link.setForeground(Profiles.foreground ?: "")
            showProfile()
        }

        /** The profile's name in the state and the notification, while the band has more than one. */
        fun showProfile() {
            val list = Bands.profiles(this@BandService, slot)
            val name = list.active(Profiles.foreground).name.takeIf { list.profiles.size > 1 }
            if (name != state.value.profile) {
                state.update { copy(profile = name) }
                refreshNotification()
            }
        }

        /** Another profile is in force on this band: tell the user which. */
        fun announce(profile: Profile, back: Boolean = false) {
            showProfile()
            val on = if (Bands.two(this@BandService)) " (${Bands.name(this@BandService, slot).lowercase()})" else ""
            Toast.makeText(this@BandService, "Profile: ${profile.name}$on", Toast.LENGTH_SHORT).show()
            if (Profiles.speak(this@BandService)) actions.announce(profile.name)
            if (Profiles.vibrate(this@BandService)) feedbackHaptics(previous = back, slot = slot)
            onLog("profile: ${profile.name}")
        }

        /** The band moved to another profile (a gesture with Next or Previous profile): store the pick, then tell the user. */
        private fun switched(index: Int, step: Int) {
            val id = Bands.profiles(this@BandService, slot).profiles.getOrNull(index)?.id ?: return
            Bands.pick(this@BandService, slot, id)
            // Again to the band: a remap sent meanwhile (e.g. a call starting) carried the old pick.
            remap()
            announce(Bands.profiles(this@BandService, slot).active(Profiles.foreground), back = step < 0)
        }

        /** The band turned the controls on or off (a gesture with Controls on/off). */
        private fun toggled(on: Boolean) {
            prefs.paused = !on
            link.controlsHaptics(paused = !on)
            pausedChanged(!on)
        }

        /** Record the controls' state; the status notification shows it. */
        fun pausedChanged(paused: Boolean) {
            state.update { copy(paused = paused) }
            refreshNotification()
            watchIdle()
            broadcastStatus()
        }

        // BandLink.Listener: called from Bluetooth and link threads.

        override fun onPhase(phase: Phase, band: String?) = main.post {
            // A disconnect ends an open draw session without a word from band-core: close the pad.
            if (phase != Phase.CONNECTED) pad.cancelled()
            state.update {
                val back = phase == Phase.CONNECTED && install?.stage == "restarting"
                copy(phase = phase, band = band ?: this.band, install = if (back) install?.copy(stage = "checking") else install)
            }
            // Its wrist may tell it from the other band now: the `band` tags and marks follow.
            if (phase == Phase.CONNECTED && Bands.two(this@BandService)) this@BandService.remap()
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
                Alerts.battery(this@BandService, now.band, level, now.charging, slot)
            }
            refreshNotification()
            broadcastStatus()
        }.let {}

        /**
         * Tell the apps that announced themselves how the band is: from their side a flat battery
         * and a lost connection look the same (the gestures stop). Only those apps, so the band's
         * state isn't sent to the whole phone. [force]: send even if nothing changed (a new app).
         * With two bands each sends its own, with a `band` extra ([Bands.tag]).
         */
        fun broadcastStatus(force: Boolean = false) {
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
                            .putExtra(EXTRA_CONTROLS, !s.paused)
                            .apply { Bands.tag(this@BandService, slot)?.let { putExtra(EXTRA_BAND, it) } },
                    )
                }
            }
        }

        override fun onActions(actions: List<String>) = main.post {
            // The palm the next action was made in (`@palm`, just before it).
            var palm: String? = null
            for (line in actions) {
                if (!line.startsWith("@")) {
                    // In a lesson nothing runs (the bridge sends `@practiced` instead).
                    if (practice == null) {
                        acting = slot
                        this@BandService.actions.run(line, fromBand = true, palm = palm, band = Bands.tag(this@BandService, slot))?.let(::onLog)
                    }
                    palm = null
                    continue
                }
                val kind = line.removePrefix("@").substringBefore(' ')
                val rest = line.substringAfter(' ', "")
                when (kind) {
                    // The Activity screen's line: the gesture, its palm and what it did, in the app's words.
                    "activity" -> ActivityLine.format(
                        rest,
                        label = { id -> ActivityLine.label(id, device = { Profiles.devices(this@BandService).get(it)?.name }) { Labels.action(this@BandService, it) } },
                        profile = { Bands.profiles(this@BandService, slot).profiles.getOrNull(it)?.name },
                    )?.let(::onLog)
                    "palm" -> palm = Palm.of(rest)?.key
                    "gesture" -> practice?.gesture(rest)
                    "practiced" -> practice?.action(rest)
                    "field-gesture" -> field?.gesture(rest)
                    "field" -> if (rest == "off") field?.ended()
                    "controls" -> toggled(rest == "on")
                    "profile" -> rest.split(' ').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 }
                        ?.let { (index, step) -> switched(index, step) }
                    "held" -> {
                        Toast.makeText(this@BandService, "This app has its own profile", Toast.LENGTH_SHORT).show()
                        onLog("profile: this app has its own profile")
                    }
                    // In a lesson Draw only shows it's there: the session (a hold can't cancel it) closes again.
                    "draw-started" -> if (practice != null && drawing == null) link.cancelDrawing() else pad.started()
                    "pen" -> pad.pen(rest == "down")
                    "draw-point" -> rest.split(' ').takeIf { it.size == 3 }?.let { (s, x, y) ->
                        val stroke = s.toIntOrNull() ?: return@let
                        pad.point(stroke, x.toFloatOrNull() ?: return@let, y.toFloatOrNull() ?: return@let)
                    }
                    "draw-finished" -> pad.finished(rest)
                    "drawn" -> pad.drawn(rest)
                    "not-drawn" -> {
                        pad.notDrawn()
                        feedbackHaptics(previous = true, slot = slot)
                    }
                    "draw-cancelled" -> pad.cancelled()
                    "drag-sample" -> rest.split(' ').takeIf { it.size == 5 }?.let { w ->
                        val numbers = w.drop(1).map { it.toDoubleOrNull() ?: return@let }
                        dragTeaching?.sample(w[0], doubleArrayOf(numbers[0], numbers[1], numbers[2]), numbers[3])
                    }
                }
            }
        }.let {}

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
            main.postDelayed({ link.otaStatus() }, 3_000)
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

        /** A line for the Activity log, marked with the band's L or R when there are two. */
        override fun onLog(line: String) = main.post {
            val mark = Bands.mark(this@BandService, slot)
            this@BandService.onLog(if (mark == null) line else "$mark  $line")
        }.let {}
    }

    /** A line for the Activity log (the bands' own come marked). */
    private fun onLog(line: String) {
        android.util.Log.i("AirGestures", line)
        log(line)
    }

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
     * Start a lesson: every band's layer goes into practice (gestures are reported, nothing runs,
     * the built-ins don't act), whether the controls are on or off.
     */
    fun startPractice(lesson: Practice) {
        // A field being written into would take the lesson's gestures (writing wins over practice).
        WriteService.instance?.lessonStarted()
        practice = lesson
        all().forEach { it.link.setPractice(true) }
    }

    fun endPractice() {
        practice = null
        all().forEach { it.link.setPractice(false) }
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

    /**
     * Teach a drawing on the band the home screen shows: practice everywhere (nothing runs) and
     * teaching there (its sessions report their strokes).
     */
    fun startTeachingDrawing(listener: Drawing) {
        drawing = listener
        startPractice(object : Practice { override fun gesture(label: String) = Unit; override fun action(id: String) = Unit })
        teacher = Bands.selected(this)
        band(teacher)?.link?.setTeaching(true)
    }
    /** The band drawings are taught on. */
    private var teacher = 0
    /** One try: a draw session the screen opened. */
    fun drawTry() = band(teacher)?.link?.startDrawing()
    fun endTeachingDrawing() {
        band(teacher)?.link?.let {
            it.cancelDrawing()
            it.setTeaching(false)
        }
        endPractice()
        drawing = null
    }
    /** Teach your drags, on the band the home screen shows (its calibration is that band's). */
    fun startDragTeaching(listener: DragTeaching) {
        dragTeaching = listener
        dragTeacher = Bands.selected(this)
        band(dragTeacher)?.link?.setDragTeaching(true)
    }
    private var dragTeacher = 0
    fun endDragTeaching() {
        band(dragTeacher)?.link?.setDragTeaching(false)
        dragTeaching = null
    }
    fun cancelDrawing() = all().forEach { it.link.cancelDrawing() }
    /** The palm right now on the band the home screen shows (calibration). */
    fun palmNow() = shown()?.link?.palmNow()
    /** The drawings changed: the bands match against the new ones. */
    fun drawingsChanged() {
        val library = Drawings.load(this)
        // Never pruned from an unreadable file: its drawings are only in drawings.broken now.
        if (Drawings.readable(this)) Profiles.pruneDrawingActions(this, library.names.toSet())
        all().forEach { it.link.setLibrary(library.toJson()) }
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
        Surfaces.refresh(this)
    }

    private fun notification(): Notification {
        val kept = all()
        val on = kept.filter { it.connected }
        val title = when {
            on.isNotEmpty() && on.all { it.state.value.paused } -> "Controls off"
            on.isNotEmpty() && kept.size > 1 && on.size < kept.size -> "1 of 2 bands connected"
            on.isNotEmpty() -> "Connected"
            kept.all { it.state.value.phase == Phase.STOPPED } -> "Stopped"
            kept.size > 1 -> "Looking for the bands"
            else -> "Looking for the band"
        }
        // Each band: its name, battery and profile (with two, the wrist's name and its controls).
        val text = kept.joinToString("  ·  ") { band ->
            val s = band.state.value
            if (kept.size == 1) listOfNotNull(s.band, s.battery?.let { "$it %" }, s.profile).joinToString(" · ")
            else listOfNotNull(
                Bands.name(this, band.slot),
                s.battery?.let { "$it %" },
                "off".takeIf { s.paused && band.connected },
                s.profile,
            ).joinToString(" ")
        }
        val paused = kept.isNotEmpty() && kept.all { it.prefs.paused }
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
            .setSmallIcon(if (paused) R.drawable.ic_hand_off else R.drawable.ic_hand)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(action(if (paused) "Turn controls on" else "Turn controls off", ACTION_TOGGLE_CONTROLS, 1))
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

        /** As [observe], without a first call now. */
        fun listen(listener: (T) -> Unit) {
            listeners += listener
        }

        fun remove(listener: (T) -> Unit) {
            listeners -= listener
        }
    }

    companion object {
        /** Pick a profile from outside the service (the widget) for the band the home screen shows: through it when it runs, else just stored. */
        fun pickProfile(context: Context, id: String) {
            val service = instance
            if (service != null) service.pick(id) else Bands.pick(context, Bands.selected(context), id)
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
        /** Which band it's about, with two ([Bands.tag]). */
        const val EXTRA_BAND = "band"
        const val EXTRA_BATTERY = "battery"
        const val EXTRA_CHARGING = "charging"
        const val EXTRA_CONNECTED = "connected"
        /** The controls are on (false while the middle hold, a tile or the app turned them off). */
        const val EXTRA_CONTROLS = "controls"

        /**
         * The band the home screen shows ([Bands.selected]), as [stateOf] has it, with the log and
         * the claim, which are the app's (not a band's).
         */
        val state = Observable(State())

        /** Each band's own state, by slot. */
        private val states: List<Observable<State>> = List(Bands.MAX) { slot -> Observable(State()).also { it.listen { if (slot == shown) mirror() } } }

        fun stateOf(slot: Int) = states[slot.coerceIn(0, Bands.MAX - 1)]

        /** The slot [state] mirrors. */
        @Volatile private var shown = 0
        private var lines = emptyList<String>()
        private var claiming = false

        private fun mirror(): Unit = state.update { states[shown].value.copy(log = lines, claiming = claiming) }

        /** The home screen shows another band (or the bands changed): [state] follows it. On the main thread. */
        fun selectionChanged(context: Context) {
            shown = Bands.selected(context)
            mirror()
        }

        /** A line for the Activity log. On the main thread. */
        fun log(line: String) {
            lines = (lines + line).takeLast(LOG_LINES)
            mirror()
        }

        /** A claim is running ([BandClaim]); the service is off meanwhile. */
        fun setClaiming(on: Boolean) {
            claiming = on
            mirror()
        }

        var instance: BandService? = null
            private set

        /** Keep the bands connected; with every band's connection off, all of them come on. */
        fun start(context: Context) {
            Prefs(context).running = true
            if (Bands.slots(context).none { Prefs(context, it).connect }) Bands.slots(context).forEach { Prefs(context, it).connect = true }
            context.startForegroundService(Intent(context, BandService::class.java))
        }

        /** Whether [slot] is kept connected now (the service runs and its connection is on). */
        fun connected(context: Context, slot: Int = Bands.selected(context)) = instance != null && Prefs(context, slot).connect

        /**
         * One band's power button: its connection on (starting the service if need be) or off
         * (stopping the service once no band is on).
         */
        fun connect(context: Context, slot: Int, on: Boolean) {
            Prefs(context, slot).connect = on
            when {
                on && instance == null -> start(context)
                on -> instance?.bandsChanged()
                Bands.slots(context).none { Prefs(context, it).connect } -> stop(context)
                else -> instance?.bandsChanged()
            }
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
            if (first) instance?.all()?.forEach { it.broadcastStatus(force = true) }
        }

        /** Each band whose profile in force changes gets it and says so; the others just hear which app is in front. */
        private fun front(context: Context, change: () -> Unit) {
            val app = Profiles.foreground
            val lists = Bands.slots(context).map { Bands.profiles(context, it) }
            val before = lists.map { it.active(app).id }
            change()
            val service = instance ?: return
            for (band in service.all()) {
                val list = lists.getOrNull(band.slot) ?: continue
                val after = list.active(Profiles.foreground)
                if (after.id != before[band.slot]) {
                    band.remap()
                    band.announce(after)
                } else if (Profiles.foreground != app) {
                    // The band's layer follows the app in front too (its own profile; whether next and previous may switch).
                    band.link.setForeground(Profiles.foreground ?: "")
                }
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
