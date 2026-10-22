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
    @JvmStatic external fun setDial(handle: Long, dial: String)
    /** `gesture=action;…` (see Prefs.mapping). */
    @JvmStatic external fun setMapping(handle: Long, mapping: String)
    /** Before [request]: also ask for these stream flags (`1,4,7`) and log what comes back (see Prefs.probe). */
    @JvmStatic external fun setProbe(handle: Long, fields: String)
    /** Debug: a ConfigReq as hex to write to the band; returns bytes to write. The answer goes to the log. */
    @JvmStatic external fun probeConfig(handle: Long, hex: String, now: Double): ByteArray
    /** Debug: the raw sEMG stream on or off; returns bytes to write. Its rate goes to the log. */
    @JvmStatic external fun setRawEmg(handle: Long, on: Boolean, now: Double): ByteArray
    /** Debug: record raw sEMG and marks into `dir` (emg.bin, marks.csv) until [recordStop]. */
    @JvmStatic external fun recordStart(handle: Long, dir: String)
    @JvmStatic external fun recordMark(handle: Long, event: String, label: String, now: Double)
    /** `{"batches", "missing", "unreadable", "marks"}`, or `{}` when no recording runs. */
    @JvmStatic external fun recordStats(handle: Long): String
    /**
     * The letter written between [start] and [end] read against the known ones:
     * `[{"letter", "distance"}…]`, nearest first. With a [label] it's known from then on.
     */
    @JvmStatic external fun recordGuess(handle: Long, label: String, start: Double, end: Double): String
    @JvmStatic external fun recordStop(handle: Long)
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
