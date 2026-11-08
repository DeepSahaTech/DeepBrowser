package com.deepbrowser.tv.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private const val DAY = 24L * 3600 * 1000
private const val NOW = 100 * DAY

private class FixtureSource(
    var cached: String?, var modified: Long?, var remote: String?,
    var tag: String? = null, var failure: String? = null, var tagWriteFails: Boolean = false,
) : FilterListSource {
    var fetches = 0
    var sent: String? = "unset"
    var touched = 0
    override fun lastModified() = modified
    override fun read() = cached
    override fun validator() = if (cached != null) tag else null
    override fun fetch(validator: String?): FetchResult {
        fetches++; sent = validator
        failure?.let { throw IOException(it) }
        if (validator != null && validator == tag) return FetchResult.NotModified
        return FetchResult.Fresh(remote ?: throw IOException("Not found (404)"), tag)
    }
    override fun write(text: String, validator: String?, url: String?) {
        cached = text
        if (tagWriteFails) throw IOException("disk full")
        tag = validator; modified = NOW
    }
    override fun touch() { touched++; modified = NOW }
}

class AdFilterTest {
    private fun filter(s: FixtureSource, maxBytes: Int = MAX_LIST_BYTES) =
        AdFilter(s, { NOW }, minLines = 1, minBytes = 1, maxBytes = maxBytes)
    private val oldList = "! old\n||old-ads.example^\n##.old-banner"
    private val newList = "! new\n||new-ads.example^\n##.new-banner"
    private fun blocks(f: AdFilter, host: String) = f.shouldBlock("https://$host/x.js", host)

    @Test fun staleCacheIsRefreshedAndUsed() {
        val s = FixtureSource(oldList, NOW - 4 * DAY, newList)
        val f = filter(s); f.refresh()
        assertEquals(1, s.fetches)
        assertTrue(blocks(f, "new-ads.example"))
        assertFalse(blocks(f, "old-ads.example"))
        assertTrue(f.injection().contains(".new-banner"))
    }

    @Test fun freshCacheIsUsedWithoutDownload() {
        val s = FixtureSource(oldList, NOW - 2 * DAY, newList)
        val f = filter(s); f.refresh()
        assertEquals(0, s.fetches)
        assertTrue(blocks(f, "old-ads.example"))
        assertEquals(2, f.ruleCount)
    }

    @Test fun validatorWriteFailureStillActivatesTheList() {
        val s = FixtureSource(oldList, NOW - 4 * DAY, newList, tagWriteFails = true)
        val f = filter(s)
        assertEquals(UpdateResult.Updated, f.update())
        assertTrue(blocks(f, "new-ads.example"))
        assertEquals(UpdateResult.UpToDate, f.update()) // cached text now equals the download
    }

    @Test fun missingCacheDownloads() {
        val s = FixtureSource(null, null, newList)
        filter(s).refresh()
        assertEquals(1, s.fetches); assertEquals(newList, s.cached)
    }

    @Test fun failedDownloadKeepsStaleCache() {
        val s = FixtureSource(oldList, NOW - 9 * DAY, null)
        val f = filter(s); f.refresh()
        assertTrue(blocks(f, "old-ads.example"))
        assertEquals(oldList, s.cached)
    }

    @Test fun noListFallsBackToHostListAndPlainScript() {
        for (s in listOf(FixtureSource(null, null, null), FixtureSource(null, null, null, failure = "No network"))) {
            val f = filter(s); f.refresh()
            assertEquals(0, f.ruleCount)
            assertTrue(f.shouldBlock("https://ads.doubleclick.net/x", "ads.doubleclick.net"))
            assertFalse(f.shouldBlock("https://example.org/", "example.org"))
            assertEquals(AdScript.SCRIPT, f.injection())
        }
    }

    @Test fun notModifiedIsUpToDateWithoutBodyAndKeepsCache() {
        val s = FixtureSource(oldList, NOW - 5 * DAY, newList, tag = "E:\"1\"")
        val f = filter(s); f.refresh()
        assertEquals("E:\"1\"", s.sent)
        assertEquals(UpdateResult.UpToDate, f.lastResult)
        assertEquals(oldList, s.cached)
        assertEquals(NOW, s.modified)
        assertTrue(blocks(f, "old-ads.example"))
    }

    @Test fun identicalBodyIsUpToDateWithoutValidator() {
        val s = FixtureSource(oldList, NOW - 5 * DAY, oldList)
        assertEquals(UpdateResult.UpToDate, filter(s).update())
        assertEquals(1, s.touched)
    }

    @Test fun validatorIsSavedAndSentNextTime() {
        val s = FixtureSource(null, null, newList, tag = "E:\"7\"")
        val f = filter(s)
        assertEquals(UpdateResult.Updated, f.update())
        assertEquals(UpdateResult.UpToDate, f.update())
        assertEquals("E:\"7\"", s.sent)
    }

    @Test fun invalidPayloadsAreRejectedAndOldCacheKept() {
        for (bad in listOf("<html><body>404</body></html>", "! only a comment\n! and another", "")) {
            val s = FixtureSource(oldList, NOW - 9 * DAY, bad)
            val f = filter(s); f.refresh()
            assertTrue(f.lastResult is UpdateResult.Failed)
            assertEquals(oldList, s.cached)
            assertEquals(NOW - 9 * DAY, s.modified)
            assertTrue(blocks(f, "old-ads.example"))
        }
    }

    @Test fun tooSmallListIsRejectedWithDefaultLimits() {
        val s = FixtureSource(null, null, newList)
        val r = AdFilter(s, { NOW }).update()
        assertEquals("Failed: Invalid list (too small)", r.label)
        assertEquals(null, s.cached)
    }

    @Test fun sizeCapRejectsOversizeList() {
        val s = FixtureSource(oldList, NOW - 9 * DAY, newList)
        val r = filter(s, maxBytes = newList.length - 1).update()
        assertEquals("Failed: Too large", r.label)
        assertEquals(oldList, s.cached)
    }

    @Test fun updateResultLabels() {
        val s = FixtureSource(oldList, NOW - 9 * DAY, newList)
        val f = filter(s)
        assertEquals(null, f.lastResult)
        assertEquals("Updated", f.update().label)
        assertEquals("Up to date", f.update().label)
        s.failure = "Not found (404)"
        assertEquals("Failed: Not found (404)", f.update().label)
        assertEquals("Failed: Not found (404)", f.status().result?.label)
        assertEquals(2, f.status().rules)
        assertTrue(blocks(f, "new-ads.example"))
    }
}
