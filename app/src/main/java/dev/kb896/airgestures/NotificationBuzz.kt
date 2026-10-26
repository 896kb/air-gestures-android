package dev.kb896.airgestures

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
