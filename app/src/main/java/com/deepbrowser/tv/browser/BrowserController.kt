package com.deepbrowser.tv.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.deepbrowser.tv.data.Bookmarks
import com.deepbrowser.tv.data.History
import com.deepbrowser.tv.data.NavMode
import com.deepbrowser.tv.data.Page
import com.deepbrowser.tv.data.PrefsStore
import com.deepbrowser.tv.data.Settings
import com.deepbrowser.tv.data.UaMode
import com.deepbrowser.tv.device.CapabilityProbe
import com.deepbrowser.tv.device.DeviceCapabilities
import com.deepbrowser.tv.player.MediaUrl
import com.deepbrowser.tv.player.PlayerActivity

/**
 * Translates, does not decide: remote keys -> [KeyRouter] -> [ChromeState]/Android calls; page events -> tab state.
 * UI-mode state lives in [ChromeState], numbers in [Viewport], tab state in [TabSession], focus scoring in [Spatial];
 * the WebView itself is behind the [TabView] seam ([WebTab]). Views are built lazily (see [warmUp]).
 */
class BrowserController(
    private val context: Context,
    private val overlayContainer: FrameLayout,
) {
    private val chrome = ChromeState()
    private var pageOwns = true // before [session]: the first tab is built with it
    private val viewport = Viewport()
    private val main = Handler(Looper.getMainLooper())
    private val store = PrefsStore(context)
    private val adState = AdBlockState(store) // before Settings: adopts the old settings-JSON flag/count once
    private val filterChoice = FilterSourceChoice(store)
    private val adFilter = AdFilter(HttpFileSource(context.filesDir, url = { filterChoice.url }))
    private val settings = Settings(store)
    private val bookmarkStore = Bookmarks(store)
    private val historyStore = History(store)
    private val uas = UserAgents(context)
    private val cursor = CursorState(viewport.width.toFloat(), viewport.height.toFloat())
    private val fullscreen = FullscreenHost(overlayContainer) { tab.view.focus() }
    private val pointer = PointerInjector({ tab.view.hostView }, cursor, main)

    var uaMode by mutableStateOf(settings.ua); private set

    private val events = object : PageEvents {
        override fun started(v: TabView, url: String?) {
            if (isActive(v)) { tab.progress = 5; nav.reset(); viewport.page(url); chrome.documentChanged(); applyScale() }
            inject(v)
        }
        override fun loaded(v: TabView) = inject(v)
        override fun progress(v: TabView, percent: Int) { if (isActive(v)) tab.progress = percent }
        override fun title(v: TabView, title: String) {
            if (!isActive(v)) return
            tab.title = title
            historyStore.setTitle(currentUrl, title); historyItems = historyStore.items
        }
        override fun visited(v: TabView, url: String?, reload: Boolean) {
            if (!isActive(v)) return
            if (chrome.reader) exitReader() // SPA navigation keeps the document, so the overlay must be closed explicitly
            if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                tab.url = url
                if (!reload) { historyStore.add(url); historyItems = historyStore.items }
            }
            session.syncNav()
        }
        override fun shouldBlock(url: String, host: String?): Boolean {
            val blocked = adState.enabled && adFilter.shouldBlock(url, host)
            if (blocked) adState.recordBlocked()
            return blocked
        }
        override fun openMedia(url: String, referer: String?) = openPlayer(url, referer)
        override fun popup(url: String) = loadInput(url)
    }

    private val session = TabSession(::createTab)
    val tabs: List<Tab> get() = session.tabs
    val activeTab: Int get() = session.activeIndex
    private val tab: Tab get() = session.active
    /** The active tab's view to host; builds it on first use. */
    val webView: View get() = tab.view.hostView
    /** False until the active tab's view exists: the UI hosts it only then. */
    val webReady: Boolean get() = tab.view.ready

    val capabilities: DeviceCapabilities by lazy { CapabilityProbe.probe(context) }

    val currentUrl: String get() = tab.url
    val title: String get() = tab.title
    val progress: Int get() = tab.progress
    val canGoBack: Boolean get() = tab.canGoBack
    val canGoForward: Boolean get() = tab.canGoForward
    var commandBarVisible by chrome::commandBar
    var infoVisible by chrome::info
    var panel by chrome::panel
    var cursorEnabled by chrome::cursorMode
    val navMode: NavMode get() = if (cursorEnabled) NavMode.POINTER else NavMode.FOCUS
    val focusOwner: FocusOwner get() = chrome.owner(isHome)
    val readerOn: Boolean get() = chrome.reader
    val zoomPercent: Int get() = chrome.zoomPercent
    var companionUrl by mutableStateOf<String?>(null)
    var companionHelp by mutableStateOf(false)
    var focusRect by mutableStateOf<Rect?>(null); private set
    var adBlockOn by mutableStateOf(adState.enabled); private set
    var bookmarks by mutableStateOf(bookmarkStore.items); private set
    var historyItems by mutableStateOf(historyStore.items); private set
    var cursorPos by mutableStateOf(Offset.Zero); private set

    val isHome: Boolean get() = currentUrl == UrlResolver.HOME
    val isBookmarked: Boolean get() = bookmarks.any { it.url == currentUrl }
    val filterRules: Int get() = filterStatus.rules
    var filterStatus by mutableStateOf(FilterStatus(null, 0, null)); private set
    var filterUpdating by mutableStateOf(false); private set
    var filterSource by mutableStateOf(filterChoice.label); private set
    var filterLinkError by mutableStateOf<String?>(null)

    /** True once the first frame is drawn and the WebView has been built. */
    var warmed = false; private set
    var onWarm: (() -> Unit)? = null

    private val nav = FocusNav(
        main = main,
        evalJs = { js -> tab.view.eval(js) },
        viewWidth = { viewport.width },
        onRing = { focusRect = it },
    )
    private val okLongPress = Runnable { chrome.okLongFired() }
    private val fsControlsOff = Runnable { chrome.hideControls() }

    init {
        Thread { adFilter.refresh(); val s = adFilter.status(); main.post { filterStatus = s } }.apply { isDaemon = true }.start()
        cursorPos = Offset(cursor.x, cursor.y)
        cursorEnabled = settings.nav == NavMode.POINTER
    }

    /** Switch between remote focus and the mouse pointer for web pages; persisted, with a brief on-screen label. */
    fun setNavMode(m: NavMode) {
        cursorEnabled = m == NavMode.POINTER; settings.nav = m
        android.widget.Toast.makeText(context, if (m == NavMode.POINTER) "Mouse pointer on" else "Remote focus on", android.widget.Toast.LENGTH_SHORT).show()
    }

    fun toggleNavMode() = setNavMode(navMode.toggle())

    /** The owning layer changed: only the page owner keeps the WebView focusable (and gives it Android focus back). */
    fun onOwnerChanged(o: FocusOwner) {
        pageOwns = o == FocusOwner.PAGE
        tabs.forEach { it.view.setFocusable(pageOwns) }
        if (pageOwns && tab.view.ready) tab.view.focus()
    }

    // ---------------------------------------------------------------- tab views

    private fun createTab(): TabView =
        WebTab(context, fullscreen, events) { TabConfig(uas.of(uaMode), chrome.zoomPercent, viewport.initialScale()) }
            .also { it.attachBridge(bridgeFor(it)); it.setFocusable(pageOwns) }

    private fun isActive(v: TabView) = v === tab.view

    /** One bridge per tab; only the active tab's reports reach FocusNav. */
    private fun bridgeFor(v: TabView) = object : PageBridge {
        override fun snap(json: String) { if (isActive(v)) nav.onSnapshot(json) }
        override fun ime(x: Float, y: Float) { if (isActive(v)) pointer.tapForKeyboard(x, y, viewport.factor(nav.cssWidth)) }
        override fun cur(x: Float, y: Float, w: Float, h: Float) { if (isActive(v)) nav.onCurRect(x, y, w, h) }
    }

    private fun inject(v: TabView) {
        v.eval(SmartNav.SCRIPT)
        if (adState.enabled) v.eval(adFilter.injection())
    }

    private fun applyScale() { viewport.initialScale()?.let { tab.view.applyScale(it) } }

    /** Called by the UI after the first frames: builds the WebView off the critical path. */
    fun warmUp() {
        if (warmed) return
        webView
        warmed = true
        onWarm?.invoke()
    }

    // ---------------------------------------------------------------- navigation API (used by UI)

    fun loadInput(input: String) {
        val url = UrlResolver.resolve(input)
        chrome.loadRequested()
        if (url == UrlResolver.HOME) { goHome(); return }
        if (MediaUrl.isDirectMedia(url)) { openPlayer(url, if (isHome) null else currentUrl); return }
        tab.url = url; chrome.documentChanged()
        tab.view.load(url)
    }

    /** Hand a direct media URL to the native player with the page's referer, cookies and user agent. */
    private fun openPlayer(url: String, referer: String?) {
        PlayerActivity.open(context, url, referer, CookieManager.getInstance().getCookie(url), tab.view.userAgent ?: uas.of(uaMode))
    }

    fun goHome() { tab.url = UrlResolver.HOME; chrome.wentHome() }
    fun reload() { chrome.commandBar = false; tab.view.reload() }
    fun goBack() { if (tab.view.canGoBack) tab.view.goBack() else goHome() }
    fun goForward() = tab.view.goForward()

    fun setUa(mode: UaMode) {
        uaMode = mode; settings.ua = mode
        tabs.forEach { it.view.applyUserAgent(uas.of(mode)) }
        if (!isHome) tab.view.reload()
    }

    fun cycleUa() = setUa(uaMode.next())

    fun setAdBlock(on: Boolean) {
        adState.enabled = on; adBlockOn = on
        if (!isHome) tab.view.reload()
    }

    /** Manual or link-change update: download off the main thread, then show the result and re-inject when the list changed. */
    fun updateFilterList() {
        if (filterUpdating) return
        filterUpdating = true
        Thread {
            val r = adFilter.update(); val s = adFilter.status()
            main.post {
                filterUpdating = false; filterStatus = s
                if (filterRelink) { filterRelink = false; updateFilterList() } // the link changed mid-update: fetch the new one
                if (r is UpdateResult.Updated && adState.enabled && !isHome) tab.view.eval(adFilter.injection())
            }
        }.apply { isDaemon = true }.start()
    }

    private var filterRelink = false
    private fun updateForNewLink() { if (filterUpdating) filterRelink = true else updateFilterList() }

    /** Use a custom https list link (validated, persisted) and update from it now. False when the link is rejected. */
    fun setFilterLink(input: String): Boolean {
        if (!filterChoice.set(input)) { filterLinkError = "Enter a full https:// link"; return false }
        filterLinkError = null; filterSource = filterChoice.label
        updateForNewLink()
        return true
    }

    fun resetFilterLink() {
        filterChoice.reset(); filterLinkError = null; filterSource = filterChoice.label
        updateForNewLink()
    }

    fun adsBlocked(): Long = adState.totalBlocked()

    fun toggleBookmark() {
        if (isHome) return
        bookmarkStore.toggle(currentUrl, title.ifBlank { UrlResolver.displayHost(currentUrl) })
        bookmarks = bookmarkStore.items
    }

    fun removeBookmark(p: Page) { bookmarkStore.toggle(p.url, p.title); bookmarks = bookmarkStore.items }

    fun clearHistory() { historyStore.clear(); historyItems = historyStore.items }

    /** Cookies, cache, web storage, form data, back/forward lists and history. Bookmarks and settings are kept. */
    fun clearBrowsingData() {
        CookieManager.getInstance().apply { removeAllCookies(null); flush() }
        WebStorage.getInstance().deleteAllData()
        tabs.forEach { it.view.clearData() }
        clearHistory(); session.syncNav()
    }

    fun openPanel(p: Panel) = chrome.openPanel(p)

    // ---------------------------------------------------------------- tabs

    fun newTab() {
        if (tabs.size >= MAX_TABS) return
        leaveTab(); session.open(); enterTab()
    }

    fun switchTo(i: Int) {
        if (i == activeTab) { chrome.closePanel(); return }
        leaveTab(); session.switchTo(i); enterTab()
    }

    fun closeTab(i: Int) {
        val wasActive = i == activeTab
        if (!session.close(i)) { goHome(); chrome.closePanel(); return }
        if (wasActive) enterTab()
    }

    /** The reader overlay lives in the old tab's page: close it before that tab goes to the background. */
    private fun leaveTab() { if (chrome.reader) exitReader() }

    /** The new active tab is live: re-aim scale/focus at its page. */
    private fun enterTab() {
        viewport.page(if (isHome) null else tab.url)
        nav.reset(); applyScale(); chrome.tabEntered()
        if (!tab.view.ready || isHome) return
        inject(tab.view); tab.view.eval("(window.__dbSnap?window.__dbSnap():0)"); tab.view.focus()
    }

    /** Toggle TV reader mode on the current page; toasts when no article is found. */
    fun toggleReader() {
        if (!chrome.beginReader(isHome)) return
        tab.view.eval(ReaderMode.SCRIPT + ";window.__dbReader()") { r ->
            if (!chrome.readerResult(r)) android.widget.Toast.makeText(context, "No article found on this page", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun exitReader() { chrome.apply(KeyAction.ExitReader); tab.view.eval("(window.__dbReader&&window.__dbReader())") }

    /** Insert [text] into the focused page field (companion "Paste"); appends, no cursor-position insert. */
    fun pasteText(text: String) { tab.view.eval(PageScripts.pasteJs(text)) }

    fun changeZoom(delta: Int) = tab.view.applyZoom(chrome.zoom(delta))

    fun onSizeChanged(w: Int, h: Int) {
        viewport.resize(w, h)
        cursor.resize(w.toFloat(), h.toFloat()); cursorPos = Offset(cursor.x, cursor.y)
        applyScale()
    }

    fun onPause() { tab.view.pause(); adState.flush() }
    fun onResume() = tab.view.resume()
    fun destroy() { adState.flush(); session.destroyAll() }

    // ---------------------------------------------------------------- remote keys

    private fun imeVisible(): Boolean =
        ViewCompat.getRootWindowInsets(overlayContainer)?.isVisible(WindowInsetsCompat.Type.ime()) == true

    /** Adapter: Android key -> [KeyRouter] -> execute. @return true if the key was consumed. */
    fun handleKey(e: KeyEvent): Boolean {
        val key = KeyMap.of(e)
        val fs = fullscreen.active
        if (chrome.keepsControls(key, fs)) armControlsHide()
        val state = chrome.keyState(fs, imeVisible(), isHome, viewport.nativeKeys)
        return execute(KeyRouter.route(KeyIn(key, e.action == KeyEvent.ACTION_DOWN, e.repeatCount), state))
    }

    private fun armControlsHide() { main.removeCallbacks(fsControlsOff); main.postDelayed(fsControlsOff, ChromeState.FS_CONTROLS_MS) }

    private fun execute(a: KeyAction): Boolean {
        chrome.apply(a)
        when (a) {
            KeyAction.Pass -> return false
            KeyAction.Consume, KeyAction.CloseOverlays, KeyAction.OpenCommandBar -> {}
            KeyAction.ExitFullscreen -> fullscreen.exit()
            KeyAction.FsReveal -> { armControlsHide(); nav.reveal() }
            is KeyAction.FsNav -> smartNav(a.dir.js)
            KeyAction.FsOk -> smartNav("ok")
            is KeyAction.Seek -> videoCommand(
                if (a.seconds < 0) "v.currentTime=Math.max(0,v.currentTime${a.seconds})" else "v.currentTime=v.currentTime+${a.seconds}")
            KeyAction.VideoToggle -> videoCommand("v.paused?v.play():v.pause()")
            KeyAction.ExitReader -> exitReader()
            is KeyAction.ReaderScroll -> tab.view.eval("window.__dbReaderScroll&&window.__dbReaderScroll(${a.delta})")
            is KeyAction.BackPage ->
                if (a.native) goBack()
                else tab.view.eval("(window.__dbBack?window.__dbBack():'none')") { r -> if (r != "\"closed\"") goBack() }
            is KeyAction.Nav -> smartNav(a.dir.js)
            is KeyAction.MoveCursor -> { pointer.moveCursor(a.dir); cursorPos = Offset(cursor.x, cursor.y) }
            KeyAction.ReleaseCursor -> cursor.release()
            KeyAction.OkDown -> main.postDelayed(okLongPress, ChromeState.OK_LONG_MS) // tap on release; holding OK opens the command bar
            KeyAction.OkUpLong -> main.removeCallbacks(okLongPress)
            KeyAction.OkUpClick -> { main.removeCallbacks(okLongPress); pointer.click() }
            KeyAction.OkUpSmart -> { main.removeCallbacks(okLongPress); smartNav("ok") }
            is KeyAction.Page -> tab.view.page(a.down)
        }
        return true
    }

    private fun smartNav(dir: String) {
        when (dir) {
            // A real touch (not a script click) counts as a user gesture: fullscreen, keyboard and player UIs all work.
            "ok" -> if (!pointer.tapRing(focusRect)) tab.view.eval("(window.__dbOk?window.__dbOk():'none')")
            "reveal" -> nav.reveal()
            else -> nav.move(dir)
        }
    }

    private fun videoCommand(action: String) { tab.view.eval(PageScripts.videoJs(action)) }
}
