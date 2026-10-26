package dev.kb896.airgestures

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.telecom.TelecomManager
import android.view.KeyEvent

/** Runs the bridge's action names on the phone. */
class Actions(
    private val context: Context,
    /** Where a sequence's failing steps are told (the activity log). */
    private val log: (String) -> Unit = {},
    /** A successful band dial or slider step can be echoed as a detent. */
    private val stepDetent: () -> Unit = {},
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    /** Where the last seek aimed, so fast dial steps add up before the player reports back. */
    private var seekTarget = 0L
    private var seekAt = 0L
    private var seekSession: android.media.session.MediaSession.Token? = null
    private val cameras = context.getSystemService(CameraManager::class.java)

    /** The back camera's flash, if the phone has one. */
    private val torchCamera: String? = runCatching {
        cameras.cameraIdList.firstOrNull { id ->
            val info = cameras.getCameraCharacteristics(id)
            info.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                info.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }.getOrNull()

    /** Kept in step by the system, so the quick-settings tile and the band agree. */
    private var torchOn = false
    private val torchWatch = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchCamera) torchOn = enabled
        }
    }

    init {
        if (torchCamera != null) cameras.registerTorchCallback(torchWatch, Handler(Looper.getMainLooper()))
    }

    /** Stop following the flash and the speech engine (the service is going away). */
    fun close() {
        cameras.unregisterTorchCallback(torchWatch)
        main.removeCallbacksAndMessages(null)
        running = null
        tts?.shutdown()
        tts = null
    }

    /** Returns a line for the activity log when the action couldn't run. */
    fun run(action: String, fromBand: Boolean = false): String? {
        when (action) {
            "media.play_pause" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "media.next" -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            "media.previous" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "volume.up" -> volume(AudioManager.ADJUST_RAISE, fromBand)
            "volume.down" -> volume(AudioManager.ADJUST_LOWER, fromBand)
            "volume.mute" -> volume(AudioManager.ADJUST_TOGGLE_MUTE, fromBand)
            "brightness.up" -> return brightness(+1, fromBand)
            "brightness.down" -> return brightness(-1, fromBand)
            "screen.swipe_up", "screen.swipe_down", "screen.swipe_left", "screen.swipe_right",
            "screen.back", "screen.home", "screen.recents" ->
                return screen(action).also { if (it == null && fromBand && action.startsWith("screen.swipe")) stepDetent() }
            "torch.toggle" -> return torch()
            "dial.seek_forward" -> return seek(+SEEK_STEP, fromBand)
            "dial.seek_back" -> return seek(-SEEK_STEP, fromBand)
            "assistant.voice" -> return assistant()
            "notification.dismiss" -> return dismiss()
            "notification.read" -> return read()
            "call.answer" -> return call(answer = true)
            "call.end" -> return call(answer = false)
            "nav.next" -> return navigate { it.move(+1) }.also { if (it == null && fromBand) stepDetent() }
            "nav.previous" -> return navigate { it.move(-1) }.also { if (it == null && fromBand) stepDetent() }
            "nav.select" -> return navigate { it.select() }
            "nav.scroll_forward" -> return navigate { it.scroll(forward = true) }.also { if (it == null && fromBand) stepDetent() }
            "nav.scroll_back" -> return navigate { it.scroll(forward = false) }.also { if (it == null && fromBand) stepDetent() }
            else -> {
                val sent = Mappings.sent(action)
                return when {
                    action.startsWith(APP_PREFIX) -> open(action.removePrefix(APP_PREFIX))
                    action.startsWith(Tap.PREFIX) -> tap(action)
                    action.startsWith(Strokes.SWIPE) || action.startsWith(Strokes.PATH) -> stroke(action)
                    action.startsWith(Sequences.PREFIX) -> Sequences.started(action).let { (id, profile) -> sequence(id, profile) }
                    sent != null -> automation(sent).also { if (fromBand && sent is Mappings.Automation.Step) stepDetent() }
                    else -> "unknown action $action"
                }
            }
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
            "screen.swipe_left" -> touch.swipeSideways(left = true)
            "screen.swipe_right" -> touch.swipeSideways(left = false)
            "screen.back" -> touch.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            "screen.home" -> touch.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            "screen.recents" -> touch.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
        }
        return null
    }

    /**
     * Move the playing track by [delta] ms, through its media session (a key
     * event can't seek). Prefers the session that's playing.
     */
    private fun seek(delta: Long, fromBand: Boolean): String? {
        if (!MediaAccess.granted(context)) return "playback position needs notification access (Setup)"
        val sessions = context.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(MediaAccess.component(context))
        val player = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull() ?: return "nothing is playing"
        val state = player.playbackState ?: return "nothing is playing"
        if (state.actions and PlaybackState.ACTION_SEEK_TO == 0L) return "this player can't seek"
        val now = SystemClock.elapsedRealtime()
        val from = if (player.sessionToken == seekSession && now - seekAt < 1500) seekTarget else {
            val running = state.state == PlaybackState.STATE_PLAYING
            state.position + if (running) ((now - state.lastPositionUpdateTime) * state.playbackSpeed).toLong() else 0
        }
        val duration = player.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 } ?: Long.MAX_VALUE
        val to = (from + delta).coerceIn(0, duration)
        player.transportControls.seekTo(to)
        if (fromBand && to != from) stepDetent()
        seekSession = player.sessionToken
        seekTarget = to
        seekAt = now
        return null
    }

    /** The flash on or off; needs no permission, and works with the screen off. */
    private fun torch(): String? {
        val camera = torchCamera ?: return "this phone has no flashlight"
        return runCatching { cameras.setTorchMode(camera, !torchOn) }
            .exceptionOrNull()?.let { "flashlight busy (the camera is open?)" }
    }

    /**
     * Open an app. Android lets only a few kinds of app start one from the
     * background; an enabled accessibility service is one of them.
     */
    private fun open(packageName: String): String? {
        val touch = TouchService.instance ?: return "opening apps needs screen gestures (Setup)"
        val launch = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return "$packageName isn't installed any more"
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching { touch.startActivity(launch) }.exceptionOrNull()?.let { "couldn't open $packageName" }
    }

    /** Navigation goes through its own accessibility service, when it's switched on. */
    private fun navigate(how: (NavigateService) -> String?): String? {
        val service = NavigateService.instance ?: return "navigation needs Air Gestures navigation on (Setup)"
        // (Not instance?.let(how) ?: …: a move that worked returns null too.)
        return how(service)
    }

    private fun tap(action: String): String? {
        val touch = TouchService.instance ?: return "tap here needs screen gestures (Setup)"
        val (x, y) = Tap.parse(action) ?: return "the tap spot is broken; pick it again"
        touch.tap(x, y)
        return null
    }

    /** Swipe here / a drawn swipe: a finger along the stored path. */
    private fun stroke(action: String): String? {
        val touch = TouchService.instance ?: return "swipe here needs screen gestures (Setup)"
        val path = Strokes.parse(action) ?: return "the swipe is broken; set it again"
        touch.stroke(path)
        return null
    }

    /** The sequence running now (its id and name), if any: a gesture during it doesn't start another. */
    private var running: String? = null
    private var runningName = ""

    /**
     * Run a sequence's steps in order: actions, and waits between them. Steps without a wait
     * get a short pause, so the screen can catch up (an app opening, a list scrolling). A Send
     * to automation step sends the sequence's name, from [profile] (null: Try it, no gesture).
     */
    private fun sequence(id: String, profile: String?): String? {
        val sequence = Profiles.sequences(context).get(id) ?: return "that sequence is gone"
        if (running != null) return "$runningName is still running"
        running = id
        runningName = sequence.name
        fun step(at: Int) {
            if (at >= sequence.steps.size || running != id) {
                running = null
                return
            }
            val step = sequence.steps[at]
            val wait = Sequences.waitMs(step)
            val sent = Sequences.stepAutomation(sequence.name, profile, step)
            if (sent != null) send(sent) else if (wait == null) run(step)?.let { log("${sequence.name}, step ${at + 1}: $it") }
            main.postDelayed({ step(at + 1) }, wait ?: (STEP_PAUSE_MS + busyMs(step)))
        }
        step(0)
        return null
    }

    /**
     * How long a step keeps the screen busy: the next step waits for it, since a new gesture
     * cancels one still in progress (a slow swipe would be cut short).
     */
    private fun busyMs(step: String): Long = when {
        step.startsWith(Strokes.SWIPE) || step.startsWith(Strokes.PATH) -> Strokes.parse(step)?.duration ?: 0
        step.startsWith(Tap.PREFIX) -> 50
        step.startsWith("screen.swipe") -> 220
        else -> 0
    }

    /** The default assistant: the voice command screen, else the assist app. */
    private fun assistant(): String? {
        val touch = TouchService.instance ?: return "the voice assistant needs screen gestures (Setup)"
        if (touch.startFromBackground(Intent(Intent.ACTION_VOICE_COMMAND))) return null
        return if (touch.startFromBackground(Intent(Intent.ACTION_ASSIST))) null else "no voice assistant answered"
    }

    /** The newest notification the user could swipe away (see [Notes.newest]). */
    private fun newest(listener: MediaAccess): StatusBarNotification? {
        val active = runCatching { listener.activeNotifications }.getOrNull() ?: return null
        val pick = Notes.newest(active.map {
            Notes.Note(
                it.key,
                it.postTime,
                it.isClearable,
                it.isOngoing,
                ours = it.packageName == context.packageName,
                emptySummary = it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 &&
                    it.notification.extras.getCharSequence(Notification.EXTRA_TEXT).isNullOrBlank(),
            )
        }) ?: return null
        return active.first { it.key == pick.key }
    }

    private fun dismiss(): String? {
        val listener = MediaAccess.instance ?: return "notification gestures need notification access (Setup)"
        val note = newest(listener) ?: return "no notifications"
        listener.cancelNotification(note.key)
        return null
    }

    /** App, title and text aloud; only app and title while the phone is locked. */
    private fun read(): String? {
        val listener = MediaAccess.instance ?: return "notification gestures need notification access (Setup)"
        val note = newest(listener) ?: return "no notifications"
        val extras = note.notification.extras
        val app = runCatching {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(note.packageName, 0)).toString()
        }.getOrDefault(note.packageName)
        val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        return speak(Speech.sentence(
            app,
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString(),
            locked,
        ))
    }

    /** Say [text] aloud (a profile's name), in the same voice as a read notification. */
    fun announce(text: String) = speak(text)

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    /** The engine wouldn't start last time; the next read says so and tries again. */
    private var ttsFailed = false
    private var pendingSpeech: String? = null

    /** One engine for the service's life; the first sentence waits for it to start. */
    private fun speak(text: String): String? {
        if (ttsFailed) {
            ttsFailed = false
            return "text-to-speech didn't start (is a speech engine installed?)"
        }
        if (ttsReady) {
            say(text)
            return null
        }
        pendingSpeech = text
        if (tts == null) {
            tts = TextToSpeech(context) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    pendingSpeech?.let(::say)
                } else {
                    // Dropped so the next read starts a fresh engine.
                    tts?.shutdown()
                    tts = null
                    ttsFailed = true
                }
                pendingSpeech = null
            }.apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
            }
        }
        return null
    }

    private fun say(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "notification")
    }

    /** Answer the ringing call, or decline it / hang up (Android's endCall does both). */
    private fun call(answer: Boolean): String? {
        if (context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            return "call gestures need the Phone calls permission (Setup)"
        }
        val telecom = context.getSystemService(TelecomManager::class.java)
        @Suppress("DEPRECATION")
        if (answer) telecom.acceptRingingCall() else if (!telecom.endCall()) return "no call to end"
        return null
    }

    /**
     * For Tasker, MacroDroid, Home Assistant…: what made it ([Mappings.sent]), nothing else:
     * `gesture=<key>` (plus `palm` for an override), a dial or slider's steps, or
     * `gesture=drawing` with its `name`; each with its `profile`, and `device` when it has one.
     */
    private fun automation(made: Mappings.Automation): String? {
        if (made is Mappings.Automation.Step) dialStep(made) else send(made)
        return null
    }

    /** [made]'s broadcast; its device by the name it has now (a rename reaches every gesture). */
    private fun send(made: Mappings.Automation, steps: Int? = null) {
        val device = Profiles.devices(context).get(made.device)?.name
        context.sendBroadcast(Intent(AUTOMATION_ACTION).apply {
            for ((key, value) in Mappings.extras(made, device, steps)) {
                if (value is Int) putExtra(key, value) else putExtra(key, value.toString())
            }
        })
    }

    /**
     * One batch per dial or slider (`dial`, `middle_dial`, `index_left_right`, …), device and
     * profile ([Mappings.Automation.Step.key]), and whether its flush is posted.
     */
    private val dialBatches = mutableMapOf<Mappings.Automation.Step, StepBatch>()
    private val dialFlushes = mutableSetOf<Mappings.Automation.Step>()
    private val main = Handler(Looper.getMainLooper())

    /**
     * A dial or slider step for automation: sent as `gesture=<name>` with the net `steps` (+ up,
     * − down), at most once per [DIAL_BATCH_MS] per batch, so a smart light follows a quick turn smoothly.
     */
    private fun dialStep(step: Mappings.Automation.Step) {
        val key = step.key
        val batch = dialBatches.getOrPut(key) { StepBatch(DIAL_BATCH_MS) }
        val now = SystemClock.uptimeMillis()
        val sent = batch.add(step.step, now)
        if (sent != null) {
            sendDial(key, sent)
        } else if (dialFlushes.add(key)) {
            main.postAtTime({
                dialFlushes.remove(key)
                sendDial(key, batch.take(SystemClock.uptimeMillis()))
            }, batch.due())
        }
    }

    private fun sendDial(key: Mappings.Automation.Step, steps: Int) {
        if (steps != 0) send(key, steps)
    }

    /** Media volume, with the system volume panel as the on-screen feedback. */
    private fun volume(direction: Int, fromBand: Boolean) {
        val stream = AudioManager.STREAM_MUSIC
        val before = audio.getStreamVolume(stream)
        val muted = audio.isStreamMute(stream)
        audio.adjustStreamVolume(stream, direction, AudioManager.FLAG_SHOW_UI)
        if (fromBand && direction != AudioManager.ADJUST_TOGGLE_MUTE && !muted &&
            !audio.isStreamMute(stream) && audio.getStreamVolume(stream) != before) stepDetent()
    }

    /**
     * Screen brightness in 5% steps (as the desktop). Needs "Modify system
     * settings"; turns adaptive brightness off, or the system would undo the step.
     */
    private fun brightness(direction: Int, fromBand: Boolean): String? {
        if (!Settings.System.canWrite(context)) return "brightness needs Modify system settings (Setup)"
        val resolver = context.contentResolver
        Settings.System.putInt(
            resolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        )
        val current = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        val next = (current + direction * STEP).coerceIn(1, 255)
        val written = Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, next)
        if (fromBand && written && next != current) stepDetent()
        return null
    }

    companion object {
        /** 5% of the 0–255 range. */
        const val STEP = 13

        /** One dial step of playback position. */
        const val SEEK_STEP = 5_000L

        /** The broadcast "Send to automation" sends, with the gesture's name in `gesture` (see [Mappings.extras]). */
        const val AUTOMATION_ACTION = "dev.kb896.airgestures.GESTURE"

        /** The pause after a sequence step that has no wait of its own. */
        const val STEP_PAUSE_MS = 150L

        /** The dial's automation steps go out at most this often. */
        const val DIAL_BATCH_MS = 250L
    }
}
