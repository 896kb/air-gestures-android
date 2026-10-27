package dev.kb896.airgestures

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.text.DateFormat
import java.time.ZonedDateTime
import java.util.Date

/**
 * The band's own notifications besides the connection: battery (low, charged, the evening
 * reminder) and idle (controls off a while). Each kind has its channel, so either can be
 * muted in Android's settings.
 */
object Alerts {
    private const val BATTERY = "battery"
    private const val IDLE = "idle"
    private const val LOW_ID = 20
    private const val CHARGED_ID = 21
    private const val REMINDER_ID = 22
    private const val IDLE_ID = 23
    /** Band 2's battery notifications: band 1's ids plus this. */
    private const val BAND_IDS = 10

    /** After this long with the controls off (and the band connected), suggest disconnecting. */
    const val IDLE_MS = 15 * 60_000L

    private fun manager(context: Context): NotificationManager {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(BATTERY, "Band battery", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Low, charged, and an evening reminder to charge." },
        )
        manager.createNotificationChannel(
            NotificationChannel(IDLE, "Band unused", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "When the controls have been off a while, to disconnect and save battery." },
        )
        return manager
    }

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun post(context: Context, channel: String, id: Int, title: String, text: String, build: Notification.Builder.() -> Unit = {}) {
        val notification = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_battery_medium)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .apply(build)
            .build()
        runCatching { manager(context).notify(id, notification) }
    }

    // ---- battery ----

    private fun prefs(context: Context) = context.getSharedPreferences("air-gestures", Context.MODE_PRIVATE)

    /** A battery reading from band [slot]: remember it, and post what it calls for (each band its own). */
    fun battery(context: Context, band: String?, level: Int, charging: Boolean, slot: Int = 0) {
        val prefs = prefs(context)
        fun key(name: String) = Bands.key(slot, name)
        val flags = BatteryAlerts.Flags(
            prefs.getBoolean(key("battery.low20"), false),
            prefs.getBoolean(key("battery.low10"), false),
            prefs.getBoolean(key("battery.charged"), false),
        )
        val (alert, next) = BatteryAlerts.check(level, charging, flags)
        prefs.edit()
            .putInt(key("battery.level"), level)
            .putBoolean(key("battery.charging"), charging)
            .putLong(key("battery.at"), System.currentTimeMillis())
            .putBoolean(key("battery.low20"), next.low20)
            .putBoolean(key("battery.low10"), next.low10)
            .putBoolean(key("battery.charged"), next.charged)
            .apply()
        val name = band ?: "Meta Band"
        val low = LOW_ID + slot * BAND_IDS
        // On the charger, a low warning no longer applies.
        if (charging) runCatching { manager(context).cancel(low) }
        when (alert?.kind) {
            BatteryAlerts.Kind.LOW -> post(context, BATTERY, low, "$name is at ${alert.level} %", "Charge it soon.")
            BatteryAlerts.Kind.CHARGED -> {
                manager(context).cancel(low)
                post(context, BATTERY, CHARGED_ID + slot * BAND_IDS, "$name is charged", "You can take it off the charger.")
            }
            null -> Unit
        }
    }

    /** The evening alarm went off: remind to charge each band whose last reading calls for it. */
    fun reminder(context: Context) {
        val prefs = prefs(context)
        for (slot in Bands.slots(context)) {
            fun key(name: String) = Bands.key(slot, name)
            val level = prefs.getInt(key("battery.level"), -1).takeIf { it >= 0 }
            val at = prefs.getLong(key("battery.at"), 0)
            if (!Reminder.due(level, prefs.getBoolean(key("battery.charging"), false), at, System.currentTimeMillis())) continue
            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))
            val band = if (Bands.two(context)) "your ${Bands.name(context, slot).lowercase()}" else "your band"
            post(context, BATTERY, REMINDER_ID + slot * BAND_IDS, "Charge $band tonight?", "It was at $level % at $time.")
        }
    }

    /**
     * The daily alarm for [reminder], at the next 21:00; inexact, so no special permission.
     * Only when it isn't set yet (or [again], after a reboot or an update): setting it again
     * moves it to the next 21:00, and an inexact one may still be due tonight.
     */
    fun scheduleReminder(context: Context, again: Boolean = false) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val target = Intent(context, ReminderReceiver::class.java)
        if (!again && PendingIntent.getBroadcast(context, 0, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE) != null) return
        val intent = PendingIntent.getBroadcast(context, 0, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val first = Reminder.next(ZonedDateTime.now()).toInstant().toEpochMilli()
        alarms.setInexactRepeating(AlarmManager.RTC_WAKEUP, first, AlarmManager.INTERVAL_DAY, intent)
    }

    // ---- idle ----

    /** The controls have been off [IDLE_MS] with the band connected: offer to disconnect. */
    fun idle(context: Context) = post(
        context,
        IDLE,
        IDLE_ID,
        "Controls have been off for 15 minutes",
        "Not using the band? Disconnect it to save battery.",
    ) {
        setSmallIcon(R.drawable.ic_hand_off)
        addAction(
            Notification.Action.Builder(
                null,
                "Disconnect",
                PendingIntent.getForegroundService(
                    context,
                    IDLE_ID,
                    Intent(context, BandService::class.java).setAction(BandService.ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            ).build(),
        )
    }

    /** Controls are back on, or the band is gone: the idle notification no longer applies. */
    fun notIdle(context: Context) = runCatching { manager(context).cancel(IDLE_ID) }
}

/** The daily evening alarm (see [Alerts.scheduleReminder]). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Alerts.reminder(context)
}
