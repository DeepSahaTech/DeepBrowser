package com.deepbrowser.tv.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageLifecycleTest {
    private class FakeView(override var ready: Boolean = true) : TabView {
        override val canGoBack = false
        override val canGoForward = false
        val log = ArrayList<String>()
        override val hostView: android.view.View get() = error("no android view in JVM tests")
        override val userAgent: String? = null
        override fun load(url: String) { log += "load" }
        override fun reload() {}
        override fun goBack() {}
        override fun goForward() {}
        override fun eval(js: String, onResult: ((String?) -> Unit)?) { log += "eval:$js" }
        override fun applyUserAgent(ua: String) {}
        override fun applyScale(percent: Int) { log += "scale:$percent" }
        override fun applyZoom(percent: Int) {}
        override fun clearData() {}
        override fun attachBridge(bridge: PageBridge) {}
        override fun page(down: Boolean) {}
        override fun focus() { log += "focus" }
        override fun pause() {}
        override fun resume() {}
        override fun destroy() {}
    }

    private val views = ArrayList<FakeView>()
    private val order = ArrayList<String>()
    private val chrome = ChromeState()
    private val viewport = Viewport().also { it.resize(1920, 1080) }
    private val session = TabSession({ FakeView().also { views += it } })
    private val life = PageLifecycle(session, chrome, viewport, { order += "focus-reset" }, { order += "inject" }, { order += "close-reader" })

    private fun openReader() { chrome.beginReader(false); chrome.readerResult("\"on\"") }

    @Test fun activeDocumentResetsEverythingThenInjects() {
        openReader()
        life.documentStarted(views[0], "https://a.test/")
        assertEquals(5, session.active.progress); assertFalse(chrome.reader)
        assertEquals("https://a.test/", viewport.pageUrl)
        assertEquals(listOf("focus-reset", "inject"), order)
        assertTrue(views[0].log.contains("scale:150"))
    }

    @Test fun backgroundDocumentOnlyGetsScripts() {
        life.newTab(); order.clear(); views[0].log.clear()
        life.documentStarted(views[0], "https://a.test/")
        assertEquals(listOf("inject"), order); assertTrue(views[0].log.isEmpty())
    }

    @Test fun loadingClosesBarAndPanelThenMarksNewDocument() {
        chrome.commandBar = true; chrome.panel = Panel.History; openReader()
        life.loadRequested(); assertFalse(chrome.commandBar); assertNull(chrome.panel); assertTrue(chrome.reader)
        life.navigating("https://b.test/")
        assertEquals("https://b.test/", session.active.url); assertFalse(chrome.reader)
    }

    @Test fun goingHomeClearsBarAndReader() {
        session.active.url = "https://a.test/"; chrome.commandBar = true; openReader()
        life.wentHome()
        assertTrue(life.isHome); assertFalse(chrome.commandBar); assertFalse(chrome.reader)
    }

    @Test fun visitedAcceptsOnlyWebUrlsAndClosesReader() {
        openReader()
        assertEquals("https://c.test/x", life.visited("https://c.test/x"))
        assertEquals(listOf("close-reader"), order); assertEquals("https://c.test/x", session.active.url)
        assertNull(life.visited("about:blank")); assertNull(life.visited(null))
        assertEquals("https://c.test/x", session.active.url)
    }

    @Test fun newTabClosesReaderThenEntersTheEmptyTab() {
        openReader(); session.active.url = "https://a.test/"
        life.newTab()
        assertEquals(listOf("close-reader", "focus-reset"), order)
        assertEquals(2, session.tabs.size); assertTrue(life.isHome); assertNull(viewport.pageUrl); assertFalse(chrome.reader)
    }

    @Test fun enteringALiveTabInjectsSnapshotsAndFocuses() {
        life.newTab(); session.active.url = "https://b.test/"; life.switchTo(0); order.clear()
        session.active.url = "https://a.test/"; life.switchTo(1)
        assertEquals("https://b.test/", viewport.pageUrl)
        assertEquals(listOf("focus-reset", "inject"), order)
        assertEquals(listOf("eval:${PageApi.snap()}", "focus"), views[1].log.takeLast(2))
    }

    @Test fun switchToSameTabJustClosesThePanel() {
        chrome.panel = Panel.Tabs; life.switchTo(0)
        assertNull(chrome.panel); assertTrue(order.isEmpty())
    }

    @Test fun closingTheOnlyTabGoesHomeAndClosesPanel() {
        session.active.url = "https://a.test/"; chrome.panel = Panel.Tabs
        life.closeTab(0)
        assertTrue(life.isHome); assertNull(chrome.panel); assertEquals(1, session.tabs.size)
    }

    @Test fun closingTheActiveTabEntersTheNeighbour() {
        life.newTab(); order.clear()
        life.closeTab(1)
        assertEquals(1, session.tabs.size); assertEquals(listOf("focus-reset"), order)
    }

    @Test fun tabLimitIsRespected() {
        repeat(MAX_TABS + 2) { life.newTab() }
        assertEquals(MAX_TABS, session.tabs.size)
    }
}
