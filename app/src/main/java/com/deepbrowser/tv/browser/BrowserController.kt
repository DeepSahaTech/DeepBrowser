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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.deepbrowser.tv.data.Library
import com.deepbrowser.tv.data.NavMode
import com.deepbrowser.tv.data.PrefsStore
import com.deepbrowser.tv.data.Settings
import com.deepbrowser.tv.data.UaMode
import com.deepbrowser.tv.device.CapabilityProbe
import com.deepbrowser.tv.device.DeviceCapabilities
import com.deepbrowser.tv.player.BridgePlayerActivity
import com.deepbrowser.tv.player.MediaUrl
import com.deepbrowser.tv.player.PlayerActivity

/**
 * Translates, does not decide: remote keys -> [KeySequencer] -> [KeyEffects] here; page events -> [PageLifecycle]/tab state.
 * UI-mode state lives in [ChromeState], numbers in [Viewport], tab state in [TabSession], focus scoring in [Spatial],
 * fullscreen in [FullscreenSession], the ad-filter update flow in [FilterUpdater] and bookmarks/history in [Library];
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
    /** Read by the panels directly (Compose state lives in the module). */
    val library = Library(store)
    private val uas = UserAgents(context)
    private val scripts = NavScripts(context)
    private val cursor = CursorState(viewport.width.toFloat(), viewport.height.toFloat())
    private val fullscreen = FullscreenSession(FullscreenHost(overlayContainer) { tab.view.focus() })
    private val pointer = PointerInjector({ fullscreen.shown ?: tab.view.hostView }, cursor, main)
    val filters = FilterUpdater(
        adFilter, filterChoice,
        background = { work -> Thread(work).apply { isDaemon = true }.start() },
        onMain = { main.post(it) },
        onUpdated = { if (adState.enabled && !isHome) tab.view.eval(adFilter.injection()) },
    )

    var uaMode by mutableStateOf(settings.ua); private set

    private val events = object : PageEvents {
        override fun started(v: TabView, url: String?) = lifecycle.documentStarted(v, url)
        override fun loaded(v: TabView) = inject(v)
        override fun progress(v: TabView, percent: Int) { if (isActive(v)) tab.progress = percent }
        override fun title(v: TabView, title: String) {
            if (!isActive(v)) return
            tab.title = title
            library.retitle(currentUrl, title)
        }
        override fun visited(v: TabView, url: String?, reload: Boolean) {
            if (!isActive(v)) return
            val web = lifecycle.visited(url)
            if (web != null && !reload) library.visit(web)
        }
        override fun shouldBlock(url: String, host: String?): Boolean {
            val blocked = adState.enabled && adFilter.shouldBlock(url, host)
            if (blocked) adState.recordBlocked()
            return blocked
        }
        override fun openMedia(url: String, referer: String?) = openPlayer(url, referer)
        override fun popup(url: String) = loadInput(url)
        override fun nativePlayback(sessionId: String) = BridgePlayerActivity.open(context, sessionId)
    }

    private val session = TabSession(::createTab)
    private val lifecycle = PageLifecycle(session, chrome, viewport, inject = ::inject, closeReader = ::exitReader)
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
    val navMode: NavMode get() = chrome.navMode
    val pointerVisible: Boolean get() = chrome.pointerVisible
    val focusOwner: FocusOwner get() = chrome.owner(isHome)
    val readerOn: Boolean get() = chrome.reader
    val zoomPercent: Int get() = chrome.zoomPercent
    var companionUrl by mutableStateOf<String?>(null)
    var companionHelp by mutableStateOf(false)
    var adBlockOn by mutableStateOf(adState.enabled); private set
    var cursorPos by mutableStateOf(Offset.Zero); private set

    val isHome: Boolean get() = lifecycle.isHome
    val isBookmarked: Boolean get() = library.isBookmarked(currentUrl)

    /** True once the first frame is drawn and the WebView has been built. */
    var warmed = false; private set
    var onWarm: (() -> Unit)? = null

    private var cursorFrameOn = false
    private var lastFrameNs = 0L
    private val cursorFrame = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!cursor.moving) { cursorFrameOn = false; return }
            val dt = if (lastFrameNs == 0L) 16f else ((frameTimeNanos - lastFrameNs) / 1_000_000f).coerceIn(1f, 50f)
            lastFrameNs = frameTimeNanos
            pointer.frame(dt)
            cursorPos = Offset(cursor.x, cursor.y)
            android.view.Choreographer.getInstance().postFrameCallback(this)
        }
    }
    private fun startCursorFrames() {
        if (cursorFrameOn) return
        cursorFrameOn = true; lastFrameNs = 0L
        android.view.Choreographer.getInstance().postFrameCallback(cursorFrame)
    }
    /** Pointer speed multiplier (Settings clamps it). */
    var pointerSpeed: Float
        get() = cursor.speedScale
        set(v) { settings.pointerSpeed = v; cursor.speedScale = settings.pointerSpeed }

    init {
        filters.start()
        cursorPos = Offset(cursor.x, cursor.y)
        chrome.navMode = settings.nav
        cursor.speedScale = settings.pointerSpeed
    }

    /** Switch between remote focus and the mouse pointer for web pages; persisted, with a brief on-screen label. */
    fun setNavMode(m: NavMode) {
        chrome.navMode = m; settings.nav = m
        tab.view.eval(PageApi.enable(chrome.ringVisible))
        android.widget.Toast.makeText(context, if (m == NavMode.POINTER) "Mouse pointer on" else "Remote focus on", android.widget.Toast.LENGTH_SHORT).show()
    }

    fun toggleNavMode() = setNavMode(navMode.toggle())

    /** The owning layer changed: only the page owner keeps the WebView focusable (and gives it Android focus back). */
    fun onOwnerChanged(o: FocusOwner) {
        pageOwns = o == FocusOwner.PAGE
        tabs.forEach { it.view.setFocusable(pageOwns) }
        if (pageOwns && tab.view.ready) tab.view.focus()
        tab.view.eval(PageApi.enable(chrome.ringVisible))
    }

    // ---------------------------------------------------------------- tab views

    private fun createTab(): TabView =
        WebTab(context, fullscreen, events, scripts) { TabConfig(uas.of(uaMode), chrome.zoomPercent, viewport.initialScale()) }
            .also { it.attachBridge(bridgeFor(it)); it.setFocusable(pageOwns) }

    private fun isActive(v: TabView) = v === tab.view

    /** One bridge per tab. The page owns focus and the ring now: only text-field taps come back, from the active tab. */
    private fun bridgeFor(v: TabView) = object : PageBridge {
        override fun ime(fx: Float, fy: Float) { if (isActive(v)) pointer.tapForKeyboard(fx, fy) }
    }

    private fun inject(v: TabView) {
        v.eval(scripts.all)
        v.eval(PageApi.enable(chrome.ringVisible)) // a new document starts with the engine on; cursor mode turns it off again
        if (adState.enabled) v.eval(adFilter.injection())
    }

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
        lifecycle.loadRequested()
        if (url == UrlResolver.HOME) { goHome(); return }
        if (MediaUrl.isDirectMedia(url)) { openPlayer(url, if (isHome) null else currentUrl); return }
        lifecycle.navigating(url)
        tab.view.load(url)
    }

    /** Hand a direct media URL to the native player with the page's referer, cookies and user agent. */
    private fun openPlayer(url: String, referer: String?) {
        PlayerActivity.open(context, url, referer, CookieManager.getInstance().getCookie(url), tab.view.userAgent ?: uas.of(uaMode))
    }

    fun goHome() = lifecycle.wentHome()
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

    fun adsBlocked(): Long = adState.totalBlocked()

    fun toggleBookmark() {
        if (isHome) return
        library.toggleBookmark(currentUrl, title.ifBlank { UrlResolver.displayHost(currentUrl) })
    }

    /** Cookies, cache, web storage, form data, back/forward lists and history. Bookmarks and settings are kept. */
    fun clearBrowsingData() {
        CookieManager.getInstance().apply { removeAllCookies(null); flush() }
        WebStorage.getInstance().deleteAllData()
        tabs.forEach { it.view.clearData() }
        library.clearHistory(); session.syncNav()
    }

    fun openPanel(p: Panel) = chrome.openPanel(p)

    // ---------------------------------------------------------------- tabs

    fun newTab() = lifecycle.newTab()
    fun switchTo(i: Int) = lifecycle.switchTo(i)
    fun closeTab(i: Int) = lifecycle.closeTab(i)

    /** Toggle TV reader mode on the current page; toasts when no article is found. */
    fun toggleReader() {
        if (!chrome.beginReader(isHome)) return
        tab.view.eval(PageApi.readerToggle()) { r ->
            if (!chrome.readerResult(r)) android.widget.Toast.makeText(context, "No article found on this page", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun exitReader() { chrome.apply(KeyAction.ExitReader); tab.view.eval(PageApi.readerClose()) }

    /** Insert [text] into the focused page field (companion "Paste"); appends, no cursor-position insert. */
    fun pasteText(text: String) { tab.view.eval(PageScripts.pasteJs(text)) }

    fun changeZoom(delta: Int) = tab.view.applyZoom(chrome.zoom(delta))

    fun onSizeChanged(w: Int, h: Int) {
        viewport.resize(w, h)
        cursor.resize(w.toFloat(), h.toFloat()); cursorPos = Offset(cursor.x, cursor.y)
        lifecycle.applyScale()
    }

    fun onPause() {
        // The native bridge player sits on top of this activity while the page keeps loading its video for it.
        if (!com.deepbrowser.tv.player.BridgeSessions.active()) tab.view.pause()
        adState.flush()
    }
    fun onResume() = tab.view.resume()
    fun destroy() { adState.flush(); session.destroyAll() }

    // ---------------------------------------------------------------- remote keys

    private fun imeVisible(): Boolean =
        ViewCompat.getRootWindowInsets(overlayContainer)?.isVisible(WindowInsetsCompat.Type.ime()) == true

    /** The key event being handled; [KeyEffects.forward] passes it on unchanged. Set only for the length of [handleKey]. */
    private var current: KeyEvent? = null
    /** Where page keys go: the fullscreen player's view when one is shown, else the WebView. */
    private fun keyTarget(): View = fullscreen.shown ?: tab.view.hostView

    /** The Android/page side of every key action; [KeySequencer] decides when each runs. */
    private val keyEffects: KeyEffects = object : KeyEffects {
        override fun exitFullscreen() = fullscreen.requestExit { reply -> tab.view.eval(PageApi.back(), reply) }
        override fun forward() { current?.let { keyTarget().dispatchKeyEvent(it) } }
        override fun enter() {
            val v = keyTarget()
            v.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            v.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
        override fun exitReader() = this@BrowserController.exitReader()
        override fun readerScroll(delta: Int) = tab.view.eval(PageApi.readerScroll(delta))
        override fun back(native: Boolean) =
            if (native) goBack() else tab.view.eval(PageApi.back()) { r -> if (!PageApi.wasClosed(r)) goBack() }
        override fun movePointer(dir: Dir) { cursor.hold(dir, true); startCursorFrames() }
        override fun releasePointer(dir: Dir?) { if (dir != null) cursor.hold(dir, false) else cursor.releaseAll() }
        override fun clickPointer() { pointer.click() }
        override fun page(down: Boolean) = tab.view.page(down)
    }

    private val keys: KeySequencer = KeySequencer(
        chrome, keyEffects, HandlerScheduler(main),
        context = { KeyContext(fullscreen.active, imeVisible(), isHome, viewport.nativeKeys) },
    )

    /** Adapter: Android key -> [KeySequencer]. @return true if the key was consumed. */
    fun handleKey(e: KeyEvent): Boolean {
        current = e
        try {
            return keys.onKey(KeyIn(KeyMap.of(e), e.action == KeyEvent.ACTION_DOWN, e.repeatCount))
        } finally {
            current = null
        }
    }
}
