package com.deepbrowser.tv.data

/** Newest first, one entry per URL, capped at [max]. */
class History(private val store: Store, private val max: Int = 200) {
    var items: List<Page> = store.readPages(KEY).take(max); private set

    fun add(url: String, title: String = "") {
        val old = items.firstOrNull { it.url == url }
        items = (listOf(Page(url, title.ifBlank { old?.title.orEmpty() })) + items.filterNot { it.url == url }).take(max)
        store.writePages(KEY, items)
    }

    fun setTitle(url: String, title: String) {
        if (title.isBlank() || items.none { it.url == url }) return
        items = items.map { if (it.url == url) it.copy(title = title) else it }
        store.writePages(KEY, items)
    }

    fun clear() { items = emptyList(); store.writePages(KEY, items) }

    private companion object { const val KEY = "history" }
}
