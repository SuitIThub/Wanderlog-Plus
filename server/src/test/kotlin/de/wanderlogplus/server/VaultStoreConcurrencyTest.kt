package de.wanderlogplus.server

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression for the server-wide hang where one stalled image upload (a slow/dropped client) froze every other
 * vault request: [VaultStore.addImage] used to hold the store monitor while reading the upload off the network.
 */
class VaultStoreConcurrencyTest {
    @TempDir
    lateinit var dir: Path

    /** Hands out [head] once, then blocks inside read() until [release] fires (or a safety timeout). */
    private class StallingStream(private val head: ByteArray, private val release: CountDownLatch) : InputStream() {
        private var sent = false
        override fun read(): Int {
            val b = ByteArray(1)
            val n = read(b, 0, 1)
            return if (n < 0) -1 else b[0].toInt() and 0xFF
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!sent) {
                sent = true
                val n = minOf(len, head.size)
                System.arraycopy(head, 0, b, off, n)
                return n
            }
            release.await(10, TimeUnit.SECONDS)
            return -1
        }
    }

    @Test
    fun `a stalled upload does not block other vault calls`() {
        val store = VaultStore(dir.resolve("vault"), maxBytes = 10_000_000)
        val release = CountDownLatch(1)

        val stuck = Thread {
            try {
                store.addImage("alice", StallingStream(ByteArray(16) { 1 }, release), "stuck.jpg", "image/jpeg", 0.5)
            } catch (_: Throwable) {
                // The upload is abandoned at the end of the test; any resulting failure is expected.
            }
        }
        stuck.start()
        // Let the stalled upload reach the blocking read (where the old code held the monitor).
        Thread.sleep(300)

        val error = AtomicReference<Throwable?>()
        val other = Thread {
            try {
                assertTrue(store.images("bob").isEmpty())
                val info = store.addImage("bob", ByteArray(32) { 7 }.inputStream(), "ok.jpg", "image/jpeg", 0.1)
                assertNotNull(info)
                assertTrue(store.images("bob").size == 1)
            } catch (t: Throwable) {
                error.set(t)
            }
        }
        other.start()
        other.join(5000)

        assertTrue(!other.isAlive, "a stalled upload blocked concurrent vault calls (store monitor held during the network read)")
        error.get()?.let { throw it }

        release.countDown()
        stuck.join(5000)
    }
}
