package dev.airgestures

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService

/**
 * Notification access, which is what Android asks of an app that wants to see
 * and steer other apps' media sessions (for the playback-position dial). It
 * reads no notifications.
 */
class MediaAccess : NotificationListenerService() {
    companion object {
        fun component(context: Context) = ComponentName(context, MediaAccess::class.java)

        fun granted(context: Context) =
            context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component(context))
    }
}
