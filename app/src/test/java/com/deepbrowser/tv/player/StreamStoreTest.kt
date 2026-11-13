package com.deepbrowser.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch

class StreamStoreTest {

    private val track = "v"

    private fun bytes(s: String) = s.toByteArray(Charsets.US_ASCII)

    /** Reads until end of stream, using a short timeout so a bug fails fast instead of hanging. */
    private fun readAll(input: StreamStore.Input): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(3)
        while (true) {
            val n = input.read(buf, 0, buf.size, 200)
            if (n == -1) break
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.US_ASCII.name())
    }

    @Test
    fun appendedChunksReadBackInOrderAndConcatenated() {
        val store = StreamStore(keepBehind = 0)
        val input = store.open(track)
        store.append(track, 0, bytes("ab"))
        store.append(track, 0, bytes("cde"))
        store.append(track, 0, bytes("f"))
        store.close()
        assertEquals("abcdef", readAll(input))
    }

    @Test
    fun blockedReadWakesWhenAnotherThreadAppends() {
        val store = StreamStore(keepBehind = 0)
        val input = store.open(track)
        val started = CountDownLatch(1)
        val writer = Thread {
            started.countDown()
            Thread.sleep(50)
            store.append(track, 0, bytes("xyz"))
        }
        writer.start()
        started.await()
        val buf = ByteArray(8)
        val n = input.read(buf, 0, buf.size, 5_000)
        writer.join()
        assertEquals("xyz", String(buf, 0, n, Charsets.US_ASCII))
    }

    @Test
    fun resetEndsOldInputAndNewInputSeesOnlyNewData() {
        val store = StreamStore(keepBehind = 0)
        store.append(track, 0, bytes("old"))
        val oldInput = store.open(track)
        store.reset(1)
        store.append(track, 0, bytes("stale"))
        store.append(track, 1, bytes("new"))
        assertEquals(-1, oldInput.read(ByteArray(8), 0, 8, 200))
        val newInput = store.open(track)
        store.close()
        assertEquals("new", readAll(newInput))
    }

    @Test
    fun resetWakesBlockedOldReaderWithEndOfStream() {
        val store = StreamStore(keepBehind = 0)
        val input = store.open(track)
        val writer = Thread {
            Thread.sleep(50)
            store.reset(1)
        }
        writer.start()
        val n = input.read(ByteArray(4), 0, 4, 5_000)
        writer.join()
        assertEquals(-1, n)
    }

    @Test
    fun closeReturnsEndOfStreamAfterDraining() {
        val store = StreamStore(keepBehind = 0)
        store.append(track, 0, bytes("hi"))
        val input = store.open(track)
        store.close()
        store.append(track, 0, bytes("ignored"))
        assertEquals("hi", readAll(input))
        assertEquals(-1, input.read(ByteArray(4), 0, 4, 200))
    }

    @Test
    fun readTimesOutWhenNothingArrives() {
        val store = StreamStore(keepBehind = 0)
        val input = store.open(track)
        try {
            input.read(ByteArray(4), 0, 4, 200)
            fail("expected InterruptedIOException")
        } catch (e: InterruptedIOException) {
            assertEquals("timeout", e.message)
        }
    }

    @Test
    fun appendsForStaleGenerationAreIgnored() {
        val store = StreamStore(keepBehind = 0)
        store.reset(5)
        store.append(track, 0, bytes("stale"))
        val input = store.open(track)
        try {
            input.read(ByteArray(8), 0, 8, 200)
            fail("stale append must not produce data")
        } catch (e: InterruptedIOException) {
            assertEquals("timeout", e.message)
        }
        store.append(track, 5, bytes("ok"))
        store.close()
        assertEquals("ok", readAll(input))
    }

    /** Reads exactly [n] bytes, looping over short reads. */
    private fun readN(input: StreamStore.Input, n: Int): String {
        val out = java.io.ByteArrayOutputStream()
        while (out.size() < n) {
            val tmp = ByteArray(n - out.size())
            val k = input.read(tmp, 0, tmp.size, 200)
            out.write(tmp, 0, k)
        }
        return out.toString(Charsets.US_ASCII.name())
    }

    @Test
    fun trimmingKeepsInitAndFreesChunksEveryReaderPassed() {
        val store = StreamStore(keepBehind = 0)
        store.append(track, 0, bytes("INIT"))
        store.append(track, 0, bytes("aaaa"))
        store.append(track, 0, bytes("bbbb"))
        store.append(track, 0, bytes("cccc"))
        val input = store.open(track)
        assertEquals("INIT", readN(input, 4))
        assertEquals("aaaa", readN(input, 4))
        assertEquals(8L, store.retainedBytes(track))  // "aaaa" is gone, "bbbb" and "cccc" are still needed
        assertEquals("bbbb", readN(input, 4))
        assertEquals(4L, store.retainedBytes(track))
    }

    @Test
    fun newReaderAfterTrimmingReadsInitThenThrowsUntilItSeeksPastTheGap() {
        val store = StreamStore(keepBehind = 0)
        store.append(track, 0, bytes("INIT"))
        store.append(track, 0, bytes("aaaa"))
        store.append(track, 0, bytes("bbbb"))
        val first = store.open(track)
        readN(first, 8)  // init and "aaaa": "aaaa" is dropped
        val late = store.open(track)
        assertEquals("INIT", readN(late, 4))
        try {
            late.read(ByteArray(4), 0, 4, 200)
            fail("expected IOException(trimmed)")
        } catch (e: java.io.IOException) {
            assertEquals("trimmed", e.message)
        }
        try {
            late.seekTo(6)
            fail("a seek into dropped data must throw")
        } catch (e: java.io.IOException) {
            assertEquals("trimmed", e.message)
        }
        late.seekTo(8)
        assertEquals("bbbb", readN(late, 4))
    }

    @Test
    fun closedReaderNoLongerHoldsBackTrimming() {
        val store = StreamStore(keepBehind = 0)
        store.append(track, 0, bytes("INIT"))
        store.append(track, 0, bytes("aaaa"))
        val idle = store.open(track)  // never reads
        readN(store.open(track), 8)   // the other reader passes "aaaa"; idle still holds it
        assertEquals(4L, store.retainedBytes(track))
        idle.close()
        store.append(track, 0, bytes("bbbb"))
        assertEquals(4L, store.retainedBytes(track))  // closing the idle reader freed "aaaa"
    }

    @Test
    fun unreadCapRefusesAppendsUntilReadersCatchUp() {
        val store = StreamStore(unreadCap = 10)
        val input = store.open(track)
        store.append(track, 0, bytes("0123456789"))
        store.append(track, 0, bytes("ab"))
        store.append(track, 0, bytes("cd"))  // 12 unread > cap: refused
        assertEquals("0123456789ab", readN(input, 12))
        store.append(track, 0, bytes("ef"))  // reader caught up: accepted
        store.close()
        assertEquals("ef", readAll(input))
    }

    @Test
    fun resetAfterStopStaysClosed() {
        val store = StreamStore(keepBehind = 0)
        store.stop()
        store.reset(1)
        store.append(track, 1, bytes("x"))
        assertEquals(-1, store.open(track).read(ByteArray(4), 0, 4, 200))
    }

    @Test
    fun reopenFromZeroAfterReadingStillFindsTheDataWithinTheKeepWindow() {
        // The player reads a little, then its seek reopens the unseekable stream from byte 0: nothing may be trimmed yet.
        val store = StreamStore(keepBehind = 1024)
        store.append(track, 0, bytes("INIT"))
        store.append(track, 0, bytes("aaaa"))
        store.append(track, 0, bytes("bbbb"))
        val first = store.open(track)
        val buf = ByteArray(12)
        var got = 0
        while (got < 12) got += first.read(buf, got, 12 - got, 200)
        first.close()
        val again = store.open(track)
        again.seekTo(0)
        assertEquals("INITaaaabbbb", String(ByteArray(12).also { b -> var n = 0; while (n < 12) n += again.read(b, n, 12 - n, 200) }, Charsets.US_ASCII))
    }
}
