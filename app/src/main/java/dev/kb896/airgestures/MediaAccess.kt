package dev.kb896.airgestures

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService

/**
 * Notification access, which is what Android asks of an app that wants to see
 * and steer other apps' media sessions (for the playback-position dial). The
 * notification gestures use it too: only when one is made does the app read
 * (aloud) or clear the newest notification.
 */
class MediaAccess : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
    }

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
