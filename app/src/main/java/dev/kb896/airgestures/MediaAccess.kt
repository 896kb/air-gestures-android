package dev.kb896.airgestures

import android.app.NotificationManager
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Notification access, which is what Android asks of an app that wants to see
 * and steer other apps' media sessions (for the playback-position dial). The
 * notification gestures use it too: only when one is made does the app read
 * (aloud) or clear the newest notification. Optional Band alerts follow new notifications.
 */
class MediaAccess : NotificationListenerService() {
    private val buzz = NotificationBuzz()

    override fun onListenerConnected() {
        buzz.connected(activeNotifications?.associate { it.key to alertTime(it) }.orEmpty())
        instance = this
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val note = sbn.notification
        val eligible = sbn.packageName != packageName && !sbn.isOngoing &&
            note.flags and Notification.FLAG_GROUP_SUMMARY == 0
        val onlyOnce = note.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0
        if (buzz.posted(sbn.key, alertTime(sbn), eligible, onlyOnce)) BandService.instance?.notificationHaptics()
    }

    private fun alertTime(sbn: StatusBarNotification) = sbn.notification.`when`.takeIf { it > 0 } ?: sbn.postTime

    override fun onNotificationRemoved(sbn: StatusBarNotification) { buzz.removed(sbn.key) }

    override fun onListenerDisconnected() {
        instance = null
    }

    companion object {
        /** Set while notification access is granted and the system has bound the listener. */
        @Volatile
        var instance: MediaAccess? = null
            private set

        fun component(context: Context) = ComponentName(context, MediaAccess::class.java)

        fun granted(context: Context) =
            context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component(context))
    }
}
