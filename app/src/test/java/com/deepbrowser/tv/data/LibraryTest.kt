package com.deepbrowser.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {
    private class Mem : Store {
        val m = HashMap<String, String>()
        override fun read(key: String) = m[key]
        override fun write(key: String, value: String) { m[key] = value }
    }

    private val store = Mem()
    private val lib = Library(store)

    @Test fun bookmarkToggleRepublishesAndPersists() {
        assertTrue(lib.toggleBookmark("https://a.test", "A"))
        assertTrue(lib.isBookmarked("https://a.test")); assertEquals(listOf(Page("https://a.test", "A")), lib.bookmarks)
        assertEquals(lib.bookmarks, Library(store).bookmarks)
        assertFalse(lib.toggleBookmark("https://a.test", "A"))
        assertFalse(lib.isBookmarked("https://a.test")); assertTrue(Library(store).bookmarks.isEmpty())
    }

    @Test fun visitsAreNewestFirstAndRetitleUpdatesTheMirror() {
        lib.visit("https://a.test"); lib.visit("https://b.test")
        lib.retitle("https://a.test", "Title A"); lib.retitle("https://missing.test", "x"); lib.retitle("https://b.test", "")
        assertEquals(listOf(Page("https://b.test", ""), Page("https://a.test", "Title A")), lib.history)
        assertEquals(lib.history, Library(store).history)
    }

    @Test fun historyCapAndClearApplyToTheMirror() {
        val small = Library(store, historyMax = 2)
        listOf("1", "2", "3").forEach { small.visit("https://x/$it") }
        assertEquals(listOf("https://x/3", "https://x/2"), small.history.map { it.url })
        small.clearHistory(); assertTrue(small.history.isEmpty()); assertTrue(Library(store).history.isEmpty())
    }

    @Test fun clearingHistoryKeepsBookmarks() {
        lib.toggleBookmark("https://a.test", "A"); lib.visit("https://a.test"); lib.clearHistory()
        assertEquals(1, lib.bookmarks.size)
    }
}
