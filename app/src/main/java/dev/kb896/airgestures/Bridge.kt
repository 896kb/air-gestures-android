package dev.kb896.airgestures

/** The Rust band-core session and shared controls (rust/bridge). Not thread-safe: callers serialise. */
object Bridge {
    init {
        System.loadLibrary("airgestures_bridge")
    }

    /** A connection for the desktop's owner.key and band.json scheme guess, with the lock and the profiles JSON (Prefs.profilesJson). */
    @JvmStatic external fun open(ownerKey: ByteArray, schemeGuess: Int, paused: Boolean, profiles: String): Long
    /** The first bytes to write once the L2CAP channel is open. */
    @JvmStatic external fun request(handle: Long): ByteArray
    /** Bytes read from the band; returns bytes to write. */
    @JvmStatic external fun feed(handle: Long, bytes: ByteArray, now: Double): ByteArray
    /** Call every ~50 ms; returns bytes to write. */
    @JvmStatic external fun tick(handle: Long, now: Double): ByteArray
    /**
     * Action names to run since the last call, '\n'-joined ("media.next", …), and the controls'
     * events, starting with `@`: `@gesture <label>`, `@controls on|off`, `@profile <index> <step>`,
     * `@held <app>`, `@practiced <action>`; a draw session's `@draw-started`, `@pen down|up`,
     * `@draw-point <stroke> <x> <y>` (degrees; the first after `@pen down` starts its stroke, a
     * dropped stroke's number comes again), `@draw-finished <strokes JSON>`, `@drawn <name>`,
     * `@not-drawn`, `@draw-cancelled`; and while teaching drags `@drag-sample <index|middle> <x> <y> <z> <roll>`.
     */
    @JvmStatic external fun actions(handle: Long): String
    /** Log lines since the last call, '\n'-joined. */
    @JvmStatic external fun log(handle: Long): String
    /** Status snapshot JSON: connected, paused, battery, charging, hand, last_gesture, last_action, gestures, drawing (a draw session is open), the update's. */
    @JvmStatic external fun status(handle: Long): String
    @JvmStatic external fun setPaused(handle: Long, paused: Boolean, now: Double)
    /** New profiles, hand and call layer (Prefs.profilesJson); throws on bad JSON. A switch it causes isn't reported. */
    @JvmStatic external fun setProfiles(handle: Long, json: String)
    /** The app in front ("": none known); a switch it causes isn't reported. */
    @JvmStatic external fun setForeground(handle: Long, app: String)
    /** A call is ringing or running: the During calls layer goes on top. */
    @JvmStatic external fun setInCall(handle: Long, inCall: Boolean)
    /** Practice: gestures and what they'd run come as `@gesture` / `@practiced`; nothing runs. */
    @JvmStatic external fun setPractice(handle: Long, practice: Boolean)
    /** What the screens show about a profiles JSON (Report): warnings, invalid, paused, paused_sliders, uses, hold_waits; bad JSON: `{"error"}`. */
    @JvmStatic external fun profileReport(json: String): String
    /**
     * Where band control [control] (controls, next, previous, draw) could go with [palm] ("" or no known
     * position: any; else "up,down"), and what it would pause; `{"error": "bad-json" | "unknown-control"}`.
     */
    @JvmStatic external fun bandChoices(json: String, control: String, palm: String): String
    /** `{"down", "up"}` rolls, or `{"error"}` (empty / not-still / too-close). */
    @JvmStatic external fun palmCalibration(down: DoubleArray, up: DoubleArray): String
    /** Taught drags (`{"roll", "turn", "drags"}`) as stored prototypes, or `{"error"}`. */
    @JvmStatic external fun dragPrototypes(samples: String): String
    /** "" when the tries can teach a drawing, else `too-small <i>`, `not-alike <i> <j>`, `no-tries`. */
    @JvmStatic external fun checkTries(tries: String): String
    /** The drawing in [library] these tries look like: `{"like": name | null}`, or `{"error": "bad-library" | "bad-tries"}`. */
    @JvmStatic external fun looksLike(library: String, tries: String): String
    /** The drawings draw sessions match against (DrawingLibrary JSON); throws on bad JSON. */
    @JvmStatic external fun setLibrary(handle: Long, library: String)
    @JvmStatic external fun startDrawing(handle: Long, now: Double)
    @JvmStatic external fun cancelDrawing(handle: Long)
    /** Teaching drawings: sessions report `@draw-finished` only. */
    @JvmStatic external fun setTeaching(handle: Long, on: Boolean)
    /** Teaching drags: pinches report `@drag-sample`, nothing acts. */
    @JvmStatic external fun setDragTeaching(handle: Long, on: Boolean)
    /** `{"palm", "roll"}` right now (nulls: no fresh reading). */
    @JvmStatic external fun palmNow(handle: Long, now: Double): String
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
    /**
     * The band's handwriting into the text field in front, on or off; bytes to write. On throws
     * while the controls are off; turning them off ends it (`@field off`).
     */
    @JvmStatic external fun setFieldTyping(handle: Long, on: Boolean, before: String, popup: Boolean, now: Double): ByteArray
    /**
     * The field's edits since the last call, and the pen: `{"active", "edits": [{"delete", "insert"}], "pen", …}`;
     * with no field typed into, `"off"`: the controls are off.
     */
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
