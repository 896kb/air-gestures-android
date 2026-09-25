package dev.airgestures

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent

/** Runs the bridge's action names on the phone. */
class Actions(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var lastTrackSkip = 0L

    /** Returns a line for the activity log when the action couldn't run. */
    fun run(action: String): String? {
        when (action) {
            "media.play_pause" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "media.next" -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            "media.previous" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "volume.up" -> volume(AudioManager.ADJUST_RAISE)
            "volume.down" -> volume(AudioManager.ADJUST_LOWER)
            "volume.mute" -> volume(AudioManager.ADJUST_TOGGLE_MUTE)
            "brightness.up" -> return brightness(+1)
            "brightness.down" -> return brightness(-1)
            "screen.swipe_up", "screen.swipe_down", "screen.back", "screen.home", "screen.recents" ->
                return screen(action)
            // A wrist turn makes many dial steps: skip at most one track per 0.7 s.
            "dial.track_next", "dial.track_previous" -> {
                val now = SystemClock.uptimeMillis()
                if (now - lastTrackSkip < 700) return null
                lastTrackSkip = now
                mediaKey(if (action == "dial.track_next") KeyEvent.KEYCODE_MEDIA_NEXT else KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            }
            else -> return "unknown action $action"
        }
        return null
    }

    /** A key press to whichever media session is active. */
    private fun mediaKey(code: Int) {
        val time = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, code, 0))
    }

    /** Screen gestures through the accessibility service, when it's switched on. */
    private fun screen(action: String): String? {
        val touch = TouchService.instance ?: return "screen gestures are off (Setup)"
        when (action) {
            "screen.swipe_up" -> touch.swipe(up = true)
            "screen.swipe_down" -> touch.swipe(up = false)
            "screen.back" -> touch.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            "screen.home" -> touch.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            "screen.recents" -> touch.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
        }
        return null
    }

    /** Media volume, with the system volume panel as the on-screen feedback. */
    private fun volume(direction: Int) =
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)

    /**
     * Screen brightness in 5% steps (as the desktop). Needs "Modify system
     * settings"; turns adaptive brightness off, or the system would undo the step.
     */
    private fun brightness(direction: Int): String? {
        if (!Settings.System.canWrite(context)) return "brightness needs Modify system settings (Setup)"
        val resolver = context.contentResolver
        Settings.System.putInt(
            resolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        )
        val current = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        val next = (current + direction * STEP).coerceIn(1, 255)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, next)
        return null
    }

    companion object {
        /** 5% of the 0–255 range. */
        const val STEP = 13
    }
}
