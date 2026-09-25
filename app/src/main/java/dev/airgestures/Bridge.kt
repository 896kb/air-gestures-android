package dev.airgestures

/** The Rust band-core session (rust/bridge). Not thread-safe: callers serialise. */
object Bridge {
    init {
        System.loadLibrary("airgestures_bridge")
    }

    /** A connection handle for the desktop's owner.key and band.json scheme guess. */
    @JvmStatic external fun open(ownerKey: ByteArray, schemeGuess: Int): Long
    /** The first bytes to write once the L2CAP channel is open. */
    @JvmStatic external fun request(handle: Long): ByteArray
    /** Bytes read from the band; returns bytes to write. */
    @JvmStatic external fun feed(handle: Long, bytes: ByteArray, now: Double): ByteArray
    /** Call every ~250 ms; returns keepalive bytes to write. */
    @JvmStatic external fun tick(handle: Long, now: Double): ByteArray
    /** Event lines since the last call, '\n'-joined ("connected", "gesture swipe left", …). */
    @JvmStatic external fun events(handle: Long): String
    @JvmStatic external fun close(handle: Long)
}
