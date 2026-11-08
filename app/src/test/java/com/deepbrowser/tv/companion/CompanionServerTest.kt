package com.deepbrowser.tv.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/** In-memory adapter: feeds raw request bytes, captures the raw response. */
private class MemConn(request: String) : CompanionConnection {
    override val input: InputStream = ByteArrayInputStream(request.toByteArray())
    val out = ByteArrayOutputStream()
    override val output: OutputStream get() = out
    override fun close() {}
    val response get() = out.toString("UTF-8")
    val status get() = response.lineSequence().first().removePrefix("HTTP/1.1 ")
}

class CompanionServerTest {
    private val token = "424242"
    private val actions = ArrayList<CompanionAction>()
    private val server = CompanionServer({ null }, { "10.0.0.5" }, token) { actions += it }

    private fun post(path: String, body: String, declared: Int = body.length) =
        "POST $path HTTP/1.1\r\nHost: tv\r\nContent-Length: $declared\r\n\r\n$body"

    private fun send(raw: String) = MemConn(raw).also { server.serve(it) }

    @Test fun wrongTokenIsForbiddenAndRunsNothing() {
        assertEquals("403 Forbidden", send(post("/000000/key", "k=ok")).status)
        assertEquals("403 Forbidden", send("GET / HTTP/1.1\r\n\r\n").status)
        assertEquals("403 Forbidden", send(post("/${token}x/key", "k=ok")).status)
        assertTrue(actions.isEmpty())
    }

    @Test fun getServesThePage() {
        val c = send("GET /$token HTTP/1.1\r\nHost: tv\r\n\r\n")
        assertEquals("200 OK", c.status)
        assertTrue(c.response.contains("DeepBrowser remote"))
    }

    @Test fun keyAllowListMapsToKeyAction() {
        assertEquals("200 OK", send(post("/$token/key", "k=left")).status)
        assertEquals(1, actions.size); assertTrue(actions[0] is CompanionAction.Key)
        assertEquals("400 Bad Request", send(post("/$token/key", "k=power")).status)
        assertEquals(1, actions.size)
    }

    @Test fun urlSchemeRefusalAndAcceptance() {
        assertEquals("400 Bad Request", send(post("/$token/url", "u=javascript%3Aalert(1)")).status)
        assertEquals("400 Bad Request", send(post("/$token/url", "u=intent%3A%2F%2Fx")).status)
        assertTrue(actions.isEmpty())
        assertEquals("200 OK", send(post("/$token/url", "u=https%3A%2F%2Fexample.com%2Fv")).status)
        assertEquals(listOf<CompanionAction>(CompanionAction.Url("https://example.com/v")), actions)
    }

    @Test fun declaredOversizeBodyIsRejectedBeforeReading() {
        val c = send(post("/$token/paste", "t=hi", declared = CompanionProtocol.MAX_BODY + 1))
        assertEquals("413 Payload Too Large", c.status)
        assertTrue(actions.isEmpty())
        // exactly at the cap is accepted (and the text itself is clamped by MAX_TEXT)
        val body = "t=" + "a".repeat(CompanionProtocol.MAX_BODY - 2)
        assertEquals("200 OK", send(post("/$token/paste", body)).status)
        assertEquals(CompanionProtocol.MAX_TEXT, (actions.single() as CompanionAction.Paste).s.length)
    }

    @Test fun formPostOnRootSendsText() {
        val c = send(post("/$token", "t=hello+world"))
        assertEquals("200 OK", c.status)
        assertEquals(listOf<CompanionAction>(CompanionAction.Text("hello world")), actions)
    }

    @Test fun oversizedLineAndTooManyHeadersAreRefused() {
        assertEquals("400 Bad Request", send("GET /" + "a".repeat(10_000) + " HTTP/1.1\r\n\r\n").status)
        val many = (1..70).joinToString("") { "X-$it: 1\r\n" }
        assertEquals("431 Request Header Fields Too Large", send("GET /$token HTTP/1.1\r\n$many\r\n").status)
        assertEquals("400 Bad Request", send("").status)
    }

    @Test fun realSocketAdapterEndToEnd() {
        val acts = java.util.concurrent.LinkedBlockingQueue<CompanionAction>()
        val s = CompanionServer({ SocketListener(0) }, { "127.0.0.1" }, token) { acts.add(it) }
        val url = s.start()!!
        try {
            assertTrue(url.endsWith("/$token"))
            val port = url.substringAfterLast(':').substringBefore('/').toInt()
            Socket("127.0.0.1", port).use { sock ->
                sock.getOutputStream().write(post("/$token/key", "k=up").toByteArray())
                val resp = sock.getInputStream().readBytes().toString(Charsets.UTF_8)
                assertTrue(resp.startsWith("HTTP/1.1 200 OK"))
            }
            val a = acts.poll(2, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(a is CompanionAction.Key)
            Socket("127.0.0.1", port).use { sock ->
                sock.getOutputStream().write(post("/nope/key", "k=up").toByteArray())
                assertTrue(sock.getInputStream().readBytes().toString(Charsets.UTF_8).startsWith("HTTP/1.1 403"))
            }
            assertNull(acts.poll())
        } finally { s.stop() }
    }

    @Test fun noLanAddressMeansNoServer() {
        assertNull(CompanionServer({ error("must not bind") }, { null }, token) {}.start())
    }
}
