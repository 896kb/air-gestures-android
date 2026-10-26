package dev.kb896.airgestures

import java.io.OutputStream

/** Keep encrypted frames in order without holding the session lock during Bluetooth I/O. */
internal fun writeBandBytes(lock: Any, output: OutputStream, build: () -> ByteArray) {
    synchronized(output) {
        val bytes = synchronized(lock) { build() }
        if (bytes.isNotEmpty()) output.write(bytes)
    }
}
