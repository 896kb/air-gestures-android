package dev.kb896.airgestures

import java.io.OutputStream
import java.util.Queue

/**
 * Build bytes under [lock] and queue them there, then write the queue under [output] with
 * [lock] released. Encrypted frames carry sequence counters, so they go out in the order they
 * were built; a build that produced nothing never waits for a slow Bluetooth write.
 * [pending] belongs to one connection and must be safe to use from several threads.
 */
internal fun writeBandBytes(lock: Any, output: OutputStream, pending: Queue<ByteArray>, build: () -> ByteArray) {
    val queued = synchronized(lock) {
        val bytes = build()
        bytes.isNotEmpty() && pending.add(bytes)
    }
    if (!queued) return
    synchronized(output) {
        while (true) output.write(pending.poll() ?: return)
    }
}
