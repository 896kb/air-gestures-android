package dev.kb896.airgestures

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BandWriterTest {
    @Test fun `a blocked Bluetooth write leaves the session lock available`() {
        val lock = Any()
        val pending = ConcurrentLinkedQueue<ByteArray>()
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        val output = object : OutputStream() {
            override fun write(value: Int) {
                writing.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            val writer = workers.submit { writeBandBytes(lock, output, pending) { byteArrayOf(1) } }
            assertTrue(writing.await(2, TimeUnit.SECONDS))
            val state = workers.submit<Boolean> { synchronized(lock) { true } }
            assertTrue(state.get(1, TimeUnit.SECONDS))
            release.countDown()
            writer.get(2, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun `an empty build does not wait for a blocked Bluetooth write`() {
        val lock = Any()
        val pending = ConcurrentLinkedQueue<ByteArray>()
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        val output = object : OutputStream() {
            override fun write(value: Int) {
                writing.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            val writer = workers.submit { writeBandBytes(lock, output, pending) { byteArrayOf(1) } }
            assertTrue(writing.await(2, TimeUnit.SECONDS))
            val reader = workers.submit { writeBandBytes(lock, output, pending) { byteArrayOf() } }
            reader.get(1, TimeUnit.SECONDS)
            release.countDown()
            writer.get(2, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun `concurrent senders write frames in their construction order`() {
        val lock = Any()
        val pending = ConcurrentLinkedQueue<ByteArray>()
        val output = ByteArrayOutputStream()
        val workers = Executors.newFixedThreadPool(4)
        var sequence = 0
        try {
            val writes = (1..100).map {
                workers.submit { writeBandBytes(lock, output, pending) { byteArrayOf((++sequence).toByte()) } }
            }
            writes.forEach { it.get(2, TimeUnit.SECONDS) }
            assertArrayEquals((1..100).map { it.toByte() }.toByteArray(), output.toByteArray())
        } finally { workers.shutdownNow() }
    }
}
