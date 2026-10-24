package dev.kb896.airgestures

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Handwriting in text fields: while the setting is on and a text field has the focus, the band
 * goes into its handwriting state and what it writes is typed into that field (words picked
 * and corrected with the dictionary as the letters come: see rust/bridge/src/fieldtext.rs).
 *
 * The band goes back to gestures when the field loses the focus, another app comes up, the
 * screen goes off, nothing is written for [IDLE_MS], the band disconnects, or the setting or
 * this service is turned off. Password fields are left alone. It reads and changes only the
 * focused field's text.
 */
class WriteService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())

    /** The field being written into, and its app; null when the band isn't writing for us. */
    private var field: AccessibilityNodeInfo? = null
    private var app: String? = null
    private var lastWritten = 0L

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = leave("the screen went off")
    }

    override fun onServiceConnected() {
        instance = this
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        Words.load(this)
    }

    override fun onDestroy() {
        leave("the service stopped")
        runCatching { unregisterReceiver(screenOff) }
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() = leave("interrupted")

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val node = event.source ?: return
                if (node.isEditable && !node.isPassword) enter(node, event.packageName?.toString())
                else if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) leave("the focus moved off the field")
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val from = event.packageName?.toString() ?: return
                // The keyboard and system pop-ups come up over the field: they don't end it.
                if (field != null && from != app && from != "com.android.systemui" && !from.contains("inputmethod") &&
                    !from.contains("keyboard")) {
                    leave("$from came up")
                }
            }
        }
    }

    /** A text field has the focus: write into it, if the setting is on and the band can. */
    private fun enter(node: AccessibilityNodeInfo, from: String?) {
        if (!Prefs(this).writeInFields) return
        val service = BandService.instance ?: return
        val state = BandService.state.value
        if (state.phase != Phase.CONNECTED || state.otaBusy || service.bandWrites() != true) return
        val same = field?.let { it == node } == true
        field = node
        app = from
        lastWritten = SystemClock.uptimeMillis()
        if (same) return
        val text = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val cursor = node.textSelectionEnd.takeIf { it in 0..text.length } ?: text.length
        service.setFieldTyping(true, text.substring(0, cursor))
        log("handwriting: writing into a field in $from")
        main.removeCallbacks(tick)
        main.post(tick)
    }

    /** Back to gestures. */
    private fun leave(why: String) {
        if (field == null) return
        field = null
        app = null
        main.removeCallbacks(tick)
        BandService.instance?.setFieldTyping(false)
        log("handwriting: back to gestures ($why)")
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            val node = field ?: return
            val service = BandService.instance
            val state = BandService.state.value
            when {
                service == null || state.phase != Phase.CONNECTED -> return leave("the band disconnected")
                !Prefs(this@WriteService).writeInFields -> return leave("the setting was turned off")
                SystemClock.uptimeMillis() - lastWritten > IDLE_MS -> return leave("nothing written for a while")
            }
            val edits = service!!.fieldEdits()
            if (edits.length() > 0) {
                lastWritten = SystemClock.uptimeMillis()
                for (i in 0 until edits.length()) {
                    val edit = edits.getJSONObject(i)
                    apply(node, edit.optInt("delete"), edit.optString("insert"))
                }
            }
            main.postDelayed(this, POLL_MS)
        }
    }

    /** Delete [delete] characters before the cursor, then insert [insert] there. */
    private fun apply(node: AccessibilityNodeInfo, delete: Int, insert: String) {
        node.refresh()
        val current = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val end = node.textSelectionEnd.takeIf { it in 0..current.length } ?: current.length
        val start = (end - delete).coerceAtLeast(0)
        val text = current.substring(0, start) + insert + current.substring(end)
        val set = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, set)) {
            log("handwriting: the field didn't take the text")
            return
        }
        val cursor = start + insert.length
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        })
    }

    private fun log(line: String) {
        Log.i("AirGestures", line)
        BandService.state.update { copy(log = (log + line).takeLast(60)) }
    }

    companion object {
        /** Back to gestures after this long with nothing written. */
        const val IDLE_MS = 45_000L
        const val POLL_MS = 100L

        @Volatile var instance: WriteService? = null
            private set

        /** Whether the user has turned this service on in the system's accessibility settings. */
        fun enabled(context: Context): Boolean {
            val on = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return on.split(':').any { it.endsWith("/.WriteService") || it.endsWith("/dev.kb896.airgestures.WriteService") }
        }
    }
}
