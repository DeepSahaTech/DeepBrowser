package com.deepbrowser.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class BridgeSessionTest {

    private val sent = ArrayList<String>()
    private val session = BridgeSession("s1", listOf(BridgeTrack("v", "video/mp4", 90000)), 0.0) { sent += it }
    private val inbox = BridgeInbox(session)

    private fun bytes(s: String) = s.toByteArray(Charsets.US_ASCII)

    /** Reads everything currently available; stops at end of stream or after a short timeout. */
    private fun readAll(input: StreamStore.Input): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16)
        while (true) {
            val n = try { input.read(buf, 0, buf.size, 100) } catch (_: java.io.InterruptedIOException) { break }
            if (n == -1) break
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.US_ASCII.name())
    }

    @Test
    fun headerThenBytesIsAppendedToTheStore() {
        inbox.onText("""{"type":"init","sb":"v","gen":0}""")
        inbox.onBytes(bytes("abc"))
        inbox.onText("""{"type":"seg","sb":"v","gen":0,"t":1.5}""")
        inbox.onBytes(bytes("def"))
        assertEquals("abcdef", readAll(session.store.open("v")))
    }

    @Test
    fun bytesWithoutHeaderAreIgnored() {
        inbox.onBytes(bytes("x"))
        inbox.onText("""{"type":"seg","sb":"v","gen":0}""")
        inbox.onBytes(bytes("ab"))
        inbox.onBytes(bytes("y")) // header was consumed by the previous frame
        assertEquals("ab", readAll(session.store.open("v")))
    }

    @Test
    fun resetCallsOnResetAndEndsOldInputs() {
        val seen = ArrayList<Pair<Int, Double>>()
        session.onResetListener = { gen, t -> seen += gen to t }
        inbox.onText("""{"type":"init","sb":"v","gen":0}""")
        inbox.onBytes(bytes("old"))
        val oldInput = session.store.open("v")
        inbox.onText("""{"type":"reset","gen":1,"t":10.0}""")
        assertEquals(1, session.gen)
        assertEquals(listOf(1 to 10.0), seen)
        assertEquals(10.0, session.resetTime, 0.0)
        assertEquals(-1, oldInput.read(ByteArray(4), 0, 4, 100))
    }

    @Test
    fun commandsAreSentAsJson() {
        session.seek(12.5)
        session.pause()
        session.play()
        session.stop()
        assertEquals(
            listOf("""{"cmd":"seek","t":12.5}""", """{"cmd":"pause"}""", """{"cmd":"play"}""", """{"cmd":"stop"}"""),
            sent,
        )
    }

    @Test
    fun stopClosesTheStore() {
        session.stop()
        assertEquals("", readAll(session.store.open("v")))
    }

    @Test
    fun malformedTextIsIgnored() {
        inbox.onText("not json")
        inbox.onText("""{"type":"start"}""")
        inbox.onBytes(bytes("z"))
        assertEquals(0, session.gen)
        assertEquals("", readAll(session.store.open("v")))
    }
}
