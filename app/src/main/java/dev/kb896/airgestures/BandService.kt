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
        actions = Actions(this)
        link = BandLink(this, this)
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
        prefs.paused = paused
        link.setPaused(paused)
        pausedChanged(paused)
    }

    /** Record the controls' state; the status notification shows it. */
    private fun pausedChanged(paused: Boolean) {
        state.update { copy(paused = paused) }
        refreshNotification()
        watchIdle()
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

    /** Set the band to this wrist now and on every later connection. */
    fun setHand(hand: String) {
        prefs.hand = hand
        link.setMapping(prefs.mapping())
    }

    // BandLink.Listener: called from Bluetooth and link threads.

    override fun onPhase(phase: Phase, band: String?) = main.post {
        state.update { copy(phase = phase, band = band ?: this.band) }
        refreshNotification()
        watchIdle()
    }.let {}

    override fun onStatus(status: JSONObject) = main.post {
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
                dial = status.optString("dial", dial),
                lastGesture = status.optString("last_gesture").takeUnless { status.isNull("last_gesture") },
                gestures = status.optLong("gestures"),
            )
        }
        val now = state.value
        val level = now.battery
        if (level != null && (level != before.battery || now.charging != before.charging)) {
            Alerts.battery(this, now.band, level, now.charging)
        }
        refreshNotification()
    }.let {}

    override fun onActions(actions: List<String>) = main.post {
        for (action in actions) this.actions.run(action)?.let(::onLog)
    }.let {}

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
    )

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
        private const val CHANNEL = "band"
        private const val NOTIFICATION = 1
        /** The channel of the removed controls on/off pop-up. */
        private const val OLD_ALERTS = "alerts"
        private const val LOG_LINES = 60
        const val ACTION_TOGGLE_CONTROLS = "dev.kb896.airgestures.TOGGLE_CONTROLS"
        const val ACTION_STOP = "dev.kb896.airgestures.STOP"
        const val ACTION_START = "dev.kb896.airgestures.START"

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
        fun foreground(context: Context, pkg: String?) {
            val list = Profiles.load(context)
            val before = list.active(Profiles.foreground)
            Profiles.foreground = pkg
            val after = list.active(pkg)
            if (after.id != before.id) instance?.switched(after)
        }

        fun setDial(context: Context, profile: String, dial: DialChoice) {
            Prefs(context).setDial(profile, dial)
            instance?.remap()
        }
    }
}
