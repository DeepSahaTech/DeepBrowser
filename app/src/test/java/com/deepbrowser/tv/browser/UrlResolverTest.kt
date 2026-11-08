package com.deepbrowser.tv.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlResolverTest {
    @Test fun emptyGoesHome() = assertEquals(UrlResolver.HOME, UrlResolver.resolve("  "))
    @Test fun fullUrlKept() = assertEquals("https://a.com/x?y=1", UrlResolver.resolve("https://a.com/x?y=1"))
    @Test fun bareDomainGetsHttps() = assertEquals("https://example.com", UrlResolver.resolve("example.com"))
    @Test fun domainWithPath() = assertEquals("https://example.com/a/b", UrlResolver.resolve("example.com/a/b"))
    @Test fun localAddressUsesHttp() = assertEquals("http://192.168.1.10:8080", UrlResolver.resolve("192.168.1.10:8080"))
    @Test fun localhostUsesHttp() = assertEquals("http://localhost:3000", UrlResolver.resolve("localhost:3000"))
    @Test fun spokenDot() = assertEquals("https://youtube.com", UrlResolver.resolve("youtube dot com"))
    @Test fun wordsBecomeSearch() {
        val u = UrlResolver.resolve("best 4k hdr movies")
        assertTrue(u, u.startsWith("https://duckduckgo.com/?q=best+4k+hdr+movies"))
    }
    @Test fun singleWordIsSearch() = assertTrue(UrlResolver.resolve("weather").contains("q=weather"))
    @Test fun searchEncodesSymbols() = assertTrue(UrlResolver.resolve("a&b c").contains("a%26b"))
    @Test fun hostDisplay() {
        assertEquals("example.com", UrlResolver.displayHost("https://www.example.com/page"))
        assertEquals("Home", UrlResolver.displayHost(UrlResolver.HOME))
    }
}
