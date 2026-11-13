package com.deepbrowser.tv.browser

import com.deepbrowser.tv.data.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class FilterUpdaterTest {
    private class Source(var cached: String? = null, var remote: String? = "! list\n||ads.example^\n##.banner") : FilterListSource {
        var fetches = 0
        override fun lastModified(): Long? = if (cached != null) 1L else null
        override fun read() = cached
        override fun validator(): String? = null
        override fun fetch(validator: String?): FetchResult {
            fetches++
            return FetchResult.Fresh(remote ?: throw IOException("Not found (404)"), null)
        }
        override fun write(text: String, validator: String?, url: String?) { cached = text }
        override fun touch() {}
    }

    private class Mem : Store {
        val m = HashMap<String, String>()
        override fun read(key: String) = m[key]
        override fun write(key: String, value: String) { m[key] = value }
    }

    private val source = Source()
    private val background = ArrayList<() -> Unit>()
    private val main = ArrayList<() -> Unit>()
    private var updated = 0
    private val updater = FilterUpdater(
        AdFilter(source, { 1L }, minLines = 1, minBytes = 1), FilterSourceChoice(Mem()),
        background = { background += it }, onMain = { main += it }, onUpdated = { updated++ },
    )

    private fun runBackground() { background.toList().also { background.clear() }.forEach { it() } }
    private fun runMain() { main.toList().also { main.clear() }.forEach { it() } }
    private fun settle() { while (background.isNotEmpty() || main.isNotEmpty()) { runBackground(); runMain() } }

    @Test fun startLoadsTheListAndPublishesStatus() {
        updater.start(); assertEquals(0, updater.status.rules)
        settle()
        assertEquals(1, source.fetches); assertEquals(2, updater.status.rules); assertEquals(UpdateResult.Updated, updater.status.result)
    }

    @Test fun updateFlagsBusyUntilPublishedAndTellsThePageOnlyWhenTheListChanged() {
        updater.update(); assertTrue(updater.updating)
        runBackground(); assertTrue(updater.updating) // result computed, not yet on the main thread
        runMain()
        assertFalse(updater.updating); assertEquals(1, updated); assertEquals(UpdateResult.Updated, updater.status.result)
        updater.update(); settle()
        assertEquals(1, updated); assertEquals(UpdateResult.UpToDate, updater.status.result)
    }

    @Test fun updateWhileBusyIsIgnored() {
        updater.update(); updater.update()
        assertEquals(1, background.size)
        settle(); assertEquals(1, source.fetches)
    }

    @Test fun failureKeepsTheAppAliveAndIsReported() {
        source.remote = null
        updater.update(); settle()
        assertTrue(updater.status.result is UpdateResult.Failed); assertEquals(0, updated); assertFalse(updater.updating)
    }

    @Test fun linkChangedMidUpdateQueuesExactlyOneMoreUpdate() {
        updater.update()
        assertTrue(updater.setLink("https://example.org/a.txt")); assertTrue(updater.setLink("https://example.org/b.txt"))
        assertEquals(1, background.size)
        runBackground(); runMain()
        assertEquals(1, background.size) // the follow-up update
        settle()
        assertEquals(2, source.fetches); assertFalse(updater.updating)
    }

    @Test fun linkWhileIdleUpdatesNowAndShowsTheSource() {
        assertEquals("Default (GitHub)", updater.source)
        assertTrue(updater.setLink("https://example.org/a.txt"))
        assertEquals("Custom: example.org", updater.source); assertNull(updater.linkError); assertTrue(updater.updating)
        settle()
        updater.resetLink(); assertEquals("Default (GitHub)", updater.source); assertTrue(updater.updating)
    }

    @Test fun rejectedLinkSetsAnErrorAndFetchesNothing() {
        assertFalse(updater.setLink("http://example.org/a.txt"))
        assertEquals("Enter a full https:// link", updater.linkError); assertTrue(background.isEmpty())
        assertTrue(updater.setLink("https://example.org/a.txt")); assertNull(updater.linkError)
    }
}
