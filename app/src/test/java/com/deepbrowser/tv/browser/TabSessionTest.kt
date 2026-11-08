package com.deepbrowser.tv.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabSessionTest {
    private class FakeView(var back: Boolean = false) : TabView {
        override val ready = true
        override val canGoBack get() = back
        override val canGoForward = false
        val log = ArrayList<String>()
        override val hostView: android.view.View get() = error("no android view in JVM tests")
        override val userAgent: String? = null
        override fun load(url: String) { log += "load" }
        override fun reload() {}
        override fun goBack() {}
        override fun goForward() {}
        override fun eval(js: String, onResult: ((String?) -> Unit)?) { log += "eval" }
        override fun applyUserAgent(ua: String) { log += "ua" }
        override fun applyScale(percent: Int) {}
        override fun applyZoom(percent: Int) {}
        override fun clearData() { log += "clear" }
        override fun attachBridge(bridge: PageBridge) {}
        override fun page(down: Boolean) {}
        override fun focus() {}
        override fun pause() { log += "pause" }
        override fun resume() { log += "resume" }
        override fun destroy() { log += "destroy" }
    }

    private val created = ArrayList<FakeView>()
    private fun session(max: Int = MAX_TABS) = TabSession({ FakeView().also { created += it } }, max)

    @Test fun startsWithOneHomeTab() {
        val s = session()
        assertEquals(1, s.tabs.size); assertEquals(0, s.activeIndex); assertEquals(UrlResolver.HOME, s.active.url)
    }

    @Test fun openStopsAtTheLimit() {
        val s = session()
        repeat(MAX_TABS - 1) { assertTrue(s.open()) }
        assertEquals(MAX_TABS, s.tabs.size); assertEquals(MAX_TABS - 1, s.activeIndex)
        assertFalse(s.open()); assertEquals(MAX_TABS, s.tabs.size)
    }

    @Test fun openPausesOldAndResumesNew() {
        val s = session(); s.open()
        assertEquals(listOf("pause"), created[0].log); assertEquals(listOf("resume"), created[1].log)
    }

    @Test fun switchKeepsEachTabsState() {
        val s = session(); s.active.url = "https://a.test/"; s.open(); s.active.url = "https://b.test/"
        assertTrue(s.switchTo(0)); assertEquals("https://a.test/", s.active.url)
        assertTrue(s.switchTo(1)); assertEquals("https://b.test/", s.active.url)
        assertFalse(s.switchTo(1)); assertFalse(s.switchTo(9))
    }

    @Test fun switchRefreshesBackAvailabilityFromTheView() {
        val s = session(); s.open(); created[0].back = true
        s.switchTo(0); assertTrue(s.active.canGoBack)
    }

    @Test fun closingActiveActivatesNeighbourAndDestroysView() {
        val s = session(); s.open(); s.open()           // active = 2
        assertTrue(s.close(2))
        assertEquals(1, s.activeIndex); assertEquals(2, s.tabs.size); assertTrue("destroy" in created[2].log)
    }

    @Test fun closingEarlierTabShiftsActiveIndex() {
        val s = session(); s.open(); s.open()           // active = 2
        s.close(0); assertEquals(1, s.activeIndex); assertTrue(s.active.view === created[2])
    }

    @Test fun closingLaterTabKeepsActive() {
        val s = session(); s.open(); s.open(); s.switchTo(0)
        s.close(2); assertEquals(0, s.activeIndex); assertEquals(2, s.tabs.size)
    }

    @Test fun closingOnlyTabResetsToHomeInstead() {
        val s = session(); s.active.url = "https://a.test/"
        assertFalse(s.close(0)); assertEquals(1, s.tabs.size); assertEquals(UrlResolver.HOME, s.active.url)
        assertFalse("destroy" in created[0].log)
    }

    @Test fun reopenAfterCloseIsAllowed() {
        val s = session(); repeat(MAX_TABS - 1) { s.open() }; s.close(1)
        assertTrue(s.open()); assertEquals(MAX_TABS, s.tabs.size)
    }
}
