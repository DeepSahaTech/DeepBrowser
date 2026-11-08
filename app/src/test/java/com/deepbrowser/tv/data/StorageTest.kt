package com.deepbrowser.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class MapStore : Store {
    val m = HashMap<String, String>()
    override fun read(key: String) = m[key]
    override fun write(key: String, value: String) { m[key] = value }
}

class StorageTest {
    @Test fun bookmarkToggleAddsThenRemovesAndPersists() {
        val s = MapStore()
        val b = Bookmarks(s)
        assertTrue(b.toggle("https://a.com", "A"))
        assertTrue(b.has("https://a.com"))
        assertEquals(listOf(Page("https://a.com", "A")), Bookmarks(s).items) // survives a restart
        assertFalse(b.toggle("https://a.com", "A"))
        assertTrue(Bookmarks(s).items.isEmpty())
    }

    @Test fun historyIsNewestFirstDedupedAndCapped() {
        val h = History(MapStore(), max = 3)
        listOf("1", "2", "3", "4").forEach { h.add("https://x/$it") }
        h.add("https://x/3")
        assertEquals(listOf("https://x/3", "https://x/4", "https://x/2"), h.items.map { it.url })
    }

    @Test fun historyKeepsTitleAndClears() {
        val s = MapStore()
        val h = History(s)
        h.add("https://a.com")
        h.setTitle("https://a.com", "A")
        h.add("https://a.com") // revisit without a title keeps the old one
        assertEquals("A", History(s).items.single().title)
        h.clear()
        assertTrue(History(s).items.isEmpty())
    }

    @Test fun corruptDataFallsBackToEmpty() {
        val s = MapStore().also { it.m["history"] = "not json"; it.m["settings"] = "{" }
        assertTrue(History(s).items.isEmpty())
        assertEquals(UaMode.TV, Settings(s).ua)
    }

    @Test fun settingsDefaultsAndPersist() {
        val s = MapStore()
        val a = Settings(s)
        assertEquals(UaMode.TV, a.ua)
        a.ua = UaMode.DESKTOP
        val b = Settings(s)
        assertEquals(UaMode.DESKTOP, b.ua)
    }
}
