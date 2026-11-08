package com.deepbrowser.tv.browser

import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What the page reports back through the injected JS bridge (already on the main thread). */
interface PageBridge {
    fun snap(json: String)
    fun ime(x: Float, y: Float)
    fun cur(x: Float, y: Float, w: Float, h: Float)
}

/** What a tab's page-load machinery reports upward; one implementation is shared by every tab. */
interface PageEvents {
    fun started(v: TabView, url: String?)
    fun loaded(v: TabView)
    fun progress(v: TabView, percent: Int)
    fun title(v: TabView, title: String)
    fun visited(v: TabView, url: String?, reload: Boolean)
    fun shouldBlock(url: String, host: String?): Boolean
    fun openMedia(url: String, referer: String?)
    fun popup(url: String)
}

/** Settings a tab's view takes when it is first built. */
data class TabConfig(val userAgent: String, val zoomPercent: Int, val scalePercent: Int?)

/**
 * Seam between tab state and the real WebView: everything the controller needs from a tab, so nothing casts.
 * Adapters: [WebTab] (production) and a fake in the tests. Calls that need a built view are no-ops until [ready].
 */
interface TabView {
    /** False while the view has not been created yet (lazy start: Home needs no WebView). */
    val ready: Boolean
    val canGoBack: Boolean
    val canGoForward: Boolean
    /** The Android view to host; builds it on first use. */
    val hostView: View
    /** The user agent in effect, null while not built. */
    val userAgent: String?
    fun load(url: String)          // builds on first use, loads, takes focus
    fun reload()
    fun goBack()
    fun goForward()
    fun eval(js: String, onResult: ((String?) -> Unit)? = null)
    fun applyUserAgent(ua: String)
    fun applyScale(percent: Int)
    fun applyZoom(percent: Int)
    /** Cache, back/forward list and form data of this tab (cookies and web storage are process-wide). */
    fun clearData()
    fun attachBridge(bridge: PageBridge)
    fun page(down: Boolean)
    fun focus()
    fun pause()
    fun resume()
    fun destroy()
}

/** One browser tab's state. The single source of truth: the controller and UI read it, nothing mirrors it. */
class Tab(val view: TabView) {
    var url by mutableStateOf(UrlResolver.HOME)
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(100)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
}

enum class Panel(val title: String) { Bookmarks("Bookmarks"), History("History"), Settings("Settings"), Tabs("Tabs") }

const val MAX_TABS = 4
