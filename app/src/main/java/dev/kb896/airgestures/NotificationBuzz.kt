package dev.kb896.airgestures

import android.app.Notification
import android.app.NotificationManager

/**
 * Whether a posted notification may buzz: another app's alert that would make a sound or
 * pop up now (Do Not Disturb allows it). Not an ongoing one, a group summary, or a player's
 * or progress bar's update, which apps repost on every change.
 */
internal fun buzzes(
    own: Boolean,
    ongoing: Boolean,
    summary: Boolean,
    importance: Int,
    interrupts: Boolean,
    media: Boolean,
    category: String?,
) = !own && !ongoing && !summary &&
    importance >= NotificationManager.IMPORTANCE_DEFAULT && interrupts &&
    !media && category != Notification.CATEGORY_TRANSPORT && category != Notification.CATEGORY_PROGRESS

/** New alerts, including a new message in an existing conversation, without replaying old ones. */
internal class NotificationBuzz {
    private val seen = mutableMapOf<String, Long>()

    fun connected(notifications: Map<String, Long>) {
        seen.clear()
        seen.putAll(notifications)
    }

    fun posted(key: String, time: Long, eligible: Boolean, onlyOnce: Boolean = false): Boolean {
        val before = seen.put(key, time)
        return eligible && (before == null || (!onlyOnce && time > before))
    }

    fun removed(key: String) { seen.remove(key) }
}
