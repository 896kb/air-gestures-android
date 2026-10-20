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

    /** A battery reading from the band: remember it, and post what it calls for. */
    fun battery(context: Context, band: String?, level: Int, charging: Boolean) {
        val prefs = prefs(context)
        val flags = BatteryAlerts.Flags(
            prefs.getBoolean("battery.low20", false),
            prefs.getBoolean("battery.low10", false),
            prefs.getBoolean("battery.charged", false),
        )
        val (alert, next) = BatteryAlerts.check(level, charging, flags)
        prefs.edit()
            .putInt("battery.level", level)
            .putBoolean("battery.charging", charging)
            .putLong("battery.at", System.currentTimeMillis())
            .putBoolean("battery.low20", next.low20)
            .putBoolean("battery.low10", next.low10)
            .putBoolean("battery.charged", next.charged)
            .apply()
        val name = band ?: "Meta Band"
        when (alert?.kind) {
            BatteryAlerts.Kind.LOW -> post(context, BATTERY, LOW_ID, "$name is at ${alert.level} %", "Charge it soon.")
            BatteryAlerts.Kind.CHARGED -> {
                manager(context).cancel(LOW_ID)
                post(context, BATTERY, CHARGED_ID, "$name is charged", "You can take it off the charger.")
            }
            null -> Unit
        }
    }

    /** The evening alarm went off: remind to charge if the last reading calls for it. */
    fun reminder(context: Context) {
        val prefs = prefs(context)
        val level = prefs.getInt("battery.level", -1).takeIf { it >= 0 }
        val at = prefs.getLong("battery.at", 0)
        if (!Reminder.due(level, prefs.getBoolean("battery.charging", false), at, System.currentTimeMillis())) return
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))
        post(context, BATTERY, REMINDER_ID, "Charge your band tonight?", "It was at $level % at $time.")
    }

    /** The daily alarm for [reminder], at the next 21:00; inexact, so no special permission. */
    fun scheduleReminder(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ReminderReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
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
