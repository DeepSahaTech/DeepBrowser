package com.deepbrowser.tv.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The user's bookmarks and history as observable state: every change is persisted through the [Store] and republished
 * so Compose lists recompose; nothing else mirrors these lists.
 */
class Library(store: Store, historyMax: Int = 200) {
    private val bookmarkStore = Bookmarks(store)
    private val historyStore = History(store, historyMax)

    var bookmarks by mutableStateOf(bookmarkStore.items); private set
    var history by mutableStateOf(historyStore.items); private set

    fun isBookmarked(url: String) = bookmarks.any { it.url == url }

    /** Add or remove [url]. @return true if it is bookmarked afterwards. */
    fun toggleBookmark(url: String, title: String): Boolean =
        bookmarkStore.toggle(url, title).also { bookmarks = bookmarkStore.items }

    /** A page was opened (not a reload). */
    fun visit(url: String) { historyStore.add(url); history = historyStore.items }

    /** The page's title arrived after the visit. */
    fun retitle(url: String, title: String) { historyStore.setTitle(url, title); history = historyStore.items }

    fun clearHistory() { historyStore.clear(); history = historyStore.items }
}
