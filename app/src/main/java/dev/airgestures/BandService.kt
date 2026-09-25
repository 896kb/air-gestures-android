package dev.airgestures

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
import org.json.JSONObject

/**
 * Keeps the band connected in the background (a connected-device foreground
 * service) and runs its gestures. The activity watches it through [state].
 */
class BandService : Service(), BandLink.Listener {
    private lateinit var link: BandLink
    private lateinit var actions: Actions
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        actions = Actions(this)
        link = BandLink(this, this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Band connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows while the band is kept connected."
                setShowBadge(false)
            },
        )
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        when (intent?.action) {
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
        link.stop()
        instance = null
        state.update { copy(phase = Phase.STOPPED) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun setPaused(paused: Boolean) {
        prefs.paused = paused
        link.setPaused(paused)
        state.update { copy(paused = paused) }
        refreshNotification()
    }

    fun setAction(gesture: Gesture, action: Action) {
        prefs.setAction(gesture, action)
        link.setMapping(prefs.mapping())
    }

    fun setDial(dial: String) {
        prefs.dial = dial
        link.setDial(dial)
        state.update { copy(dial = dial) }
    }

    // BandLink.Listener: called from Bluetooth and link threads.

    override fun onPhase(phase: Phase, band: String?) = main.post {
        state.update { copy(phase = phase, band = band ?: this.band) }
        refreshNotification()
    }.let {}

    override fun onStatus(status: JSONObject) = main.post {
        val paused = status.optBoolean("paused")
        if (paused != prefs.paused) prefs.paused = paused
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
        refreshNotification()
    }.let {}

    override fun onActions(actions: List<String>) = main.post {
        for (action in actions) this.actions.run(action)?.let(::onLog)
    }.let {}

    override fun onLog(line: String) = main.post {
        android.util.Log.i("AirGestures", line)
        state.update { copy(log = (log + line).takeLast(LOG_LINES)) }
    }.let {}

    private fun refreshNotification() =
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())

    private fun notification(): Notification {
        val s = state.value
        val title = when {
            s.phase == Phase.CONNECTED && s.paused -> "Controls off"
            s.phase == Phase.CONNECTED -> "Connected"
            s.phase == Phase.STOPPED -> "Stopped"
            else -> "Looking for the band"
        }
        val text = listOfNotNull(s.band, s.battery?.let { "$it %" }).joinToString(" · ")
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(label: String, action: String, code: Int) = Notification.Action.Builder(
            null, label,
            PendingIntent.getService(
                this, code, Intent(this, BandService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_hand)
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
        private const val LOG_LINES = 60
        const val ACTION_TOGGLE_CONTROLS = "dev.airgestures.TOGGLE_CONTROLS"
        const val ACTION_STOP = "dev.airgestures.STOP"

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
    }
}
