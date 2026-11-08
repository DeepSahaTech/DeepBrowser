package com.deepbrowser.tv.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue

/** Owns the tabs and the open/switch/close transitions (at most [max]). Android-free: views come from [create]. */
class TabSession(private val create: () -> TabView, private val max: Int = MAX_TABS) {
    private val list = mutableStateListOf(Tab(create()))
    val tabs: List<Tab> get() = list
    var activeIndex by mutableIntStateOf(0); private set
    val active: Tab get() = list[activeIndex]

    /** @return false at the tab limit. */
    fun open(): Boolean {
        if (list.size >= max) return false
        active.view.pause()
        list.add(Tab(create()))
        activate(list.lastIndex)
        return true
    }

    /** @return false when [i] is already active or invalid. */
    fun switchTo(i: Int): Boolean {
        if (i == activeIndex || i !in list.indices) return false
        active.view.pause()
        activate(i)
        return true
    }

    /** @return false when the only tab was reset to Home instead of removed. */
    fun close(i: Int): Boolean {
        if (i !in list.indices) return true
        if (list.size == 1) { active.url = UrlResolver.HOME; return false }
        list.removeAt(i).view.destroy()
        if (i == activeIndex) activate(i.coerceAtMost(list.lastIndex)) else if (i < activeIndex) activeIndex--
        return true
    }

    /** Re-read back/forward availability from the live view. */
    fun syncNav() { active.canGoBack = active.view.canGoBack; active.canGoForward = active.view.canGoForward }

    fun destroyAll() = list.forEach { it.view.destroy() }

    private fun activate(i: Int) {
        activeIndex = i
        active.progress = 100
        syncNav()
        active.view.resume()
    }
}
