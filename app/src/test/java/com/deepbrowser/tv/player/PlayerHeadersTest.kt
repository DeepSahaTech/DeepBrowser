package com.deepbrowser.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerHeadersTest {
    @Test fun blankValuesAreOmitted() {
        assertEquals(emptyMap<String, String>(), PlayerHeaders.requestProperties(null, "  "))
        assertEquals(mapOf("Referer" to "https://a.com/p"), PlayerHeaders.requestProperties(" https://a.com/p ", null))
    }

    private val props = mapOf("Referer" to "https://page.example/watch", "Cookie" to "sid=1")
    private fun scoped(req: String, media: String = "https://media.example/v.m3u8") = PlayerHeaders.forRequest(props, media, req)

    @Test fun credentialsGoOnlyToThePageAndMediaHost() {
        assertEquals(props, scoped("https://media.example/seg1.ts"))
        assertEquals(props, scoped("https://MEDIA.example/seg1.ts"))
        assertEquals(props, scoped("https://page.example/key"))
        assertEquals(emptyMap<String, String>(), scoped("https://cdn.other.net/seg1.ts"))
        assertEquals(emptyMap<String, String>(), scoped("https://evil.media.example/seg1.ts"))
        assertEquals(emptyMap<String, String>(), scoped("not a url"))
    }

    @Test fun credentialsAreNotSentOverPlainHttpFromHttpsOrigin() {
        assertEquals(emptyMap<String, String>(), scoped("http://media.example/seg1.ts"))
        val plain = mapOf("Referer" to "http://media.example/p", "Cookie" to "sid=1")
        assertEquals(plain, PlayerHeaders.forRequest(plain, "http://media.example/v.mp4", "http://media.example/seg1.ts"))
    }

    @Test fun bothHeadersAndInjectionStripped() {
        val m = PlayerHeaders.requestProperties("https://a.com", "sid=1; x=2\r\nX-Evil: 1")
        assertEquals("sid=1; x=2X-Evil: 1", m["Cookie"])
        assertEquals("https://a.com", m["Referer"])
        assertNull(PlayerHeaders.userAgent("\r\n"))
        assertEquals("UA/1", PlayerHeaders.userAgent(" UA/1\n"))
    }
}
