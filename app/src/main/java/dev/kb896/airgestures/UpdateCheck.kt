package dev.kb896.airgestures

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * Every two hours at most, ask GitLab for the newest release. If it is newer than
 * this build, say so once, in a notification that carries the release notes and
 * opens the APK's permanent address (the same one the site's button uses); the
 * app also shows it at the top of the screen. Nothing is downloaded by the app:
 * the browser does that, and the user installs.
 *
 * A dev build compares by its base version: 0.3.9-dev is the work after 0.3.8,
 * so a 0.3.9 release does not nag it, and a 0.4.0 does.
 */
object UpdateCheck {
    private const val TAG = "AirGesturesUpdate"
    private const val PREFS = "update_check"
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 4711
    const val EVERY_MS = 2L * 60 * 60 * 1000
    private const val API = "https://gitlab.com/api/v4/projects/86888806/releases?per_page=1"
    const val APK = "https://gitlab.com/896kb/air-gestures-android/-/releases/permalink/latest/downloads/air-gestures.apk"
    const val RELEASES = "https://gitlab.com/896kb/air-gestures-android/-/releases"

    data class Release(val tag: String, val notes: String)

    fun version(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    /** Check in the background if it's time; [then] runs on that thread once a newer release is known. */
    fun maybe(context: Context, then: (() -> Unit)? = null) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_check", 0L) < EVERY_MS) return
        prefs.edit().putLong("last_check", now).apply()
        Thread {
            val release = runCatching { fetch() }.onFailure { Log.w(TAG, "check failed: ${it.message}") }.getOrNull()
                ?: return@Thread
            if (!newer(release.tag, version(app))) return@Thread
            val told = prefs.getString("notified", "") == release.tag
            prefs.edit().putString("notified", release.tag).putString("notes", release.notes).apply()
            if (!told) notify(app, release)
            then?.invoke()
        }.start()
    }

    /** The release the check last found, while it's still newer than this build. */
    fun available(context: Context): Release? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tag = prefs.getString("notified", "").orEmpty()
        if (tag.isEmpty() || !newer(tag, version(context))) return null
        return Release(tag, prefs.getString("notes", "").orEmpty())
    }

    private fun fetch(): Release? {
        val connection = URL(API).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", "air-gestures-android")
        val body = connection.run {
            try {
                if (responseCode == 200) inputStream.bufferedReader().readText() else null
            } finally {
                disconnect()
            }
        } ?: return null
        val first = org.json.JSONArray(body).optJSONObject(0) ?: return null
        val tag = first.optString("tag_name").ifEmpty { return null }
        // The notes are the changelog, then an install section the app doesn't need.
        return Release(tag, first.optString("description").substringBefore("## Install").trim())
    }

    /** "v0.3.9" against "0.3.8" or "0.3.9-dev": numerically, by the base version. */
    fun newer(remoteTag: String, local: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
        val r = parts(remoteTag)
        val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** The release notes as plain text: the markdown that reads badly in a notification stripped. */
    fun plain(notes: String): String = notes
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("`([^`]*)`"), "$1")
        .replace(Regex("^#+[ \\t]*", RegexOption.MULTILINE), "")
        .replace(Regex("^[ \\t]*[-*][ \\t]+", RegexOption.MULTILINE), "• ")
        .replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
        .trim()

    private fun notify(context: Context, release: Release) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "A new release of this app, once, with its notes." },
        )
        fun open(url: String, code: Int): PendingIntent = PendingIntent.getActivity(
            context, code, Intent(Intent.ACTION_VIEW, Uri.parse(url)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val version = release.tag.removePrefix("v")
        val text = plain(release.notes).ifBlank { "Tap to download." }
        val notification = Notification.Builder(context, CHANNEL)
            .setContentTitle("Air Gestures $version is out, you have ${version(context)}")
            .setContentText(text.lineSequence().firstOrNull().orEmpty())
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_hand)
            .setContentIntent(open(APK, 1))
            .addAction(Notification.Action.Builder(null, "Download APK", open(APK, 1)).build())
            .addAction(Notification.Action.Builder(null, "All releases", open(RELEASES, 2)).build())
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        Log.i(TAG, "told about ${release.tag}")
    }
}
