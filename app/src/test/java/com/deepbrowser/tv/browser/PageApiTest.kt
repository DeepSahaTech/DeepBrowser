package com.deepbrowser.tv.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageApiTest {
    @Test fun commandsCallTheNamedPageFunctionGuarded() {
        assertEquals("window.__dbReaderScroll&&window.__dbReaderScroll(-450)", PageApi.readerScroll(-450))
        assertEquals("(window.__dbNav?window.__dbNav.back():'none')", PageApi.back())
        assertEquals("window.__dbNav&&window.__dbNav.enable(true)", PageApi.enable(true))
        assertEquals("window.__dbNav&&window.__dbNav.enable(false)", PageApi.enable(false))
        assertTrue(PageApi.readerClose().contains("__dbReader"))
        assertTrue(PageApi.readerToggle().endsWith(";window.__dbReader()"))
    }

    @Test fun repliesAreParsedFromTheirJsonStringForm() {
        assertTrue(PageApi.wasClosed("\"closed\"")); assertFalse(PageApi.wasClosed("\"none\"")); assertFalse(PageApi.wasClosed(null))
        assertTrue(PageApi.readerIsOn("\"on\"")); assertFalse(PageApi.readerIsOn("\"off\""))
        assertFalse(PageApi.readerFoundArticle("\"none\"")); assertTrue(PageApi.readerFoundArticle("\"off\"")); assertTrue(PageApi.readerFoundArticle(null))
    }

    @Test fun everyEntryPointIsDefinedByTheInjectedScripts() {
        listOf("__dbReader", "__dbReaderScroll").forEach { assertTrue(it, ReaderMode.SCRIPT.contains("window.$it=")) }
    }
}
