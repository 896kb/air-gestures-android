package dev.kb896.airgestures

/** The Rust band-core session and gesture controller (rust/bridge). Not thread-safe: callers serialise. */
object Bridge {
    init {
        System.loadLibrary("airgestures_bridge")
    }

    /** A connection for the desktop's owner.key and band.json scheme guess, with the app's settings. */
    @JvmStatic external fun open(ownerKey: ByteArray, schemeGuess: Int, paused: Boolean, dial: String, mapping: String): Long
    /** The first bytes to write once the L2CAP channel is open. */
    @JvmStatic external fun request(handle: Long): ByteArray
    /** Bytes read from the band; returns bytes to write. */
    @JvmStatic external fun feed(handle: Long, bytes: ByteArray, now: Double): ByteArray
    /** Call every ~50 ms; returns bytes to write. */
    @JvmStatic external fun tick(handle: Long, now: Double): ByteArray
    /** Action names to run since the last call, '\n'-joined ("media.next", "dial.changed.brightness", …). */
    @JvmStatic external fun actions(handle: Long): String
    /** Log lines since the last call, '\n'-joined. */
    @JvmStatic external fun log(handle: Long): String
    /** Status snapshot JSON: connected, paused, battery, charging, hand, dial, last_gesture, last_action, gestures. */
    @JvmStatic external fun status(handle: Long): String
    @JvmStatic external fun setPaused(handle: Long, paused: Boolean, now: Double)
    @JvmStatic external fun setBandVibration(handle: Long, enabled: Boolean): ByteArray
    @JvmStatic external fun controlsHaptics(handle: Long, paused: Boolean): ByteArray
    @JvmStatic external fun syncControlsHaptics(handle: Long, paused: Boolean): ByteArray
    @JvmStatic external fun profileHaptics(handle: Long, previous: Boolean): ByteArray
    @JvmStatic external fun volumeDetent(handle: Long): ByteArray
    @JvmStatic external fun setDial(handle: Long, dial: String)
    /** `gesture=action;…` (see Prefs.mapping). */
    @JvmStatic external fun setMapping(handle: Long, mapping: String)
    /** Before [request]: also ask for these stream flags (`1,4,7`) and log what comes back (see Prefs.probe). */
    @JvmStatic external fun setProbe(handle: Long, fields: String)
    /** Debug: a ConfigReq as hex to write to the band; returns bytes to write. The answer goes to the log. */
    @JvmStatic external fun probeConfig(handle: Long, hex: String, now: Double): ByteArray
    /** Debug: ask the band's update status (read only); returns bytes to write. The answer goes to the log. */
    @JvmStatic external fun otaStatus(handle: Long): ByteArray
    @JvmStatic external fun otaStart(handle: Long, mode: String, dir: String, now: Double): ByteArray
    /** What the band said about itself: `{"firmware", "software", "hardware", "serial", "release_branch"}` (`{}` before). */
    @JvmStatic external fun deviceInfo(handle: Long): String
    /**
     * Debug: check, download and keep the firmware Meta offers this band under [dir] (blocking
     * HTTP; off the main thread). [version]: the band's, as reported to Meta ("1": the full current build).
     */
    @JvmStatic external fun firmwareArchive(session: String, serial: String, version: String, dir: String): String
    /** The firmware download in progress: `{"version", "done", "total"}`, or `{}`. */
    @JvmStatic external fun firmwareProgress(): String
    /** Debug: the band's own handwriting (deviceState HANDWRITING_CC) on or off; bytes to write. */
    @JvmStatic external fun setBandHandwriting(handle: Long, on: Boolean): ByteArray
    /** Debug: what it has sent: `{"on", "text", "tokens": [{"index", "symbol", "margin"}], "frames", ...}`. */
    @JvmStatic external fun bandHandwriting(handle: Long, clear: Boolean): String
    /** The band's handwriting into the text field in front, on or off; bytes to write. */
    @JvmStatic external fun setFieldTyping(handle: Long, on: Boolean, before: String, popup: Boolean, now: Double): ByteArray
    /** The field's edits since the last call, and the pen: `{"edits": [{"delete", "insert"}], "pen"}`. */
    @JvmStatic external fun fieldEdits(handle: Long, now: Double): String
    /** A swipe up in a text field: the word's suggestions show in its place; false when none. */
    @JvmStatic external fun fieldChoose(handle: Long): Boolean
    /** While choosing: the next (or previous) suggestion first. */
    @JvmStatic external fun fieldChooseStep(handle: Long, forward: Boolean)
    /** While choosing: leave it with the word as it was. */
    @JvmStatic external fun fieldChooseCancel(handle: Long, now: Double)
    /** While choosing: take the first. */
    @JvmStatic external fun fieldChoosePick(handle: Long, now: Double)
    /** A double tap in a text field: the pen goes down. */
    @JvmStatic external fun fieldPenDown(handle: Long, now: Double)
    /** The handwriting dictionary (`word count` lines, assets/words.txt); how many words it has. */
    @JvmStatic external fun wordsLoad(text: String): Int
    @JvmStatic external fun close(handle: Long)

    // Meta sign-in (blocking HTTP: never on the main thread). The session JSON is secret: never log it.

    /** `{"url": Meta's sign-in page, "native": the token that checks and finishes its callback}`. */
    @JvmStatic external fun signinStart(): String
    /** The navigation has the sign-in callback's shape (`fb-viewapp:`/`frl_login`): cancel it. */
    @JvmStatic external fun signinIsCallback(url: String): Boolean
    /** The callback is the real one (token digest of `native`, a blob). */
    @JvmStatic external fun signinAccepts(url: String, native: String): Boolean
    /** Decrypt the callback and log in; returns the session JSON to keep. Blocking. */
    @JvmStatic external fun signinFinish(url: String, native: String): String

    // The ownership claim of a band in pairing mode. Feed and tick may block on Meta's HTTP steps.

    /** A claim with the stored session, writing owner.pending.key / owner.key into `filesDir`. */
    @JvmStatic external fun claimOpen(sessionJson: String, filesDir: String, schemeGuess: Int): Long
    @JvmStatic external fun claimRequest(handle: Long): ByteArray
    @JvmStatic external fun claimFeed(handle: Long, bytes: ByteArray, now: Double): ByteArray
    @JvmStatic external fun claimTick(handle: Long, now: Double): ByteArray
    /** The channel closed or failed while the claim ran (`message`: why, or empty). */
    @JvmStatic external fun claimClosed(handle: Long, message: String)
    /** Progress lines since the last call, '\n'-joined. */
    @JvmStatic external fun claimLog(handle: Long): String
    /** "running", "done", "failed: …" or "expired: …" (sign in to Meta again). */
    @JvmStatic external fun claimState(handle: Long): String
    /** After a failure: the next encryption guess worth a fresh channel, or -1. */
    @JvmStatic external fun claimNextGuess(handle: Long): Int
    @JvmStatic external fun claimClose(handle: Long)
}
