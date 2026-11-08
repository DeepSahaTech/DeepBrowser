package com.deepbrowser.tv.data

class Bookmarks(private val store: Store) {
    var items: List<Page> = store.readPages(KEY); private set

    fun has(url: String) = items.any { it.url == url }

    /** @return true if the page is bookmarked after the call. */
    fun toggle(url: String, title: String): Boolean {
        val added = !has(url)
        items = if (added) items + Page(url, title) else items.filterNot { it.url == url }
        store.writePages(KEY, items)
        return added
    }

    private companion object { const val KEY = "bookmarks" }
}
