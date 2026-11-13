package com.deepbrowser.tv.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageApiTest {
    @Test fun commandsCallTheNamedPageFunctionGuarded() {
        assertEquals("window.__dbSnap&&window.__dbSnap()", PageApi.snap())
        assertEquals("window.__dbSel&&window.__dbSel(7)", PageApi.select(7))
        assertEquals("window.__dbCarousel&&window.__dbCarousel('left')", PageApi.carousel(Dir.LEFT))
        assertEquals("window.__dbScroll&&window.__dbScroll('down')", PageApi.scroll(Dir.DOWN))
        assertEquals("window.__dbReveal&&window.__dbReveal()", PageApi.reveal())
        assertEquals("window.__dbReaderScroll&&window.__dbReaderScroll(-450)", PageApi.readerScroll(-450))
        assertTrue(PageApi.ok().contains("__dbOk")); assertTrue(PageApi.back().contains("__dbBack"))
        assertTrue(PageApi.menuBack().contains("__dbMenuBack")); assertTrue(PageApi.readerClose().contains("__dbReader"))
        assertTrue(PageApi.readerToggle().endsWith(";window.__dbReader()"))
    }

    @Test fun seekClampsBackwardsAtZeroAndAddsForwards() {
        assertTrue(PageApi.seekBy(-10).contains("v.currentTime=Math.max(0,v.currentTime-10)"))
        assertTrue(PageApi.seekBy(30).contains("v.currentTime=v.currentTime+30"))
        assertTrue(PageApi.togglePlay().contains("v.paused?v.play():v.pause()"))
    }

    @Test fun repliesAreParsedFromTheirJsonStringForm() {
        assertTrue(PageApi.wasClosed("\"closed\"")); assertFalse(PageApi.wasClosed("\"none\"")); assertFalse(PageApi.wasClosed(null))
        assertTrue(PageApi.readerIsOn("\"on\"")); assertFalse(PageApi.readerIsOn("\"off\""))
        assertFalse(PageApi.readerFoundArticle("\"none\"")); assertTrue(PageApi.readerFoundArticle("\"off\"")); assertTrue(PageApi.readerFoundArticle(null))
    }

    @Test fun everyEntryPointIsDefinedByTheInjectedScripts() {
        listOf("__dbSnap", "__dbSel", "__dbCarousel", "__dbScroll", "__dbReveal", "__dbOk", "__dbBack", "__dbMenuBack")
            .forEach { assertTrue(it, SmartNav.SCRIPT.contains("window.$it=")) }
        listOf("__dbReader", "__dbReaderScroll").forEach { assertTrue(it, ReaderMode.SCRIPT.contains("window.$it=")) }
    }
}
