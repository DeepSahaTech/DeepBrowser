package com.deepbrowser.tv.browser

/**
 * Orders everything that must change together when the page under the user changes: the tab's url/progress, the
 * [Viewport]'s page, the focus ring ([resetFocus]), [ChromeState] (overlays, reader) and the injected scripts
 * ([inject]). Covers a new document, a requested load, going Home and entering/leaving/closing tabs.
 * Android-free: pages are reached through [TabView].
 */
class PageLifecycle(
    private val session: TabSession,
    private val chrome: ChromeState,
    private val viewport: Viewport,
    private val resetFocus: () -> Unit,
    private val inject: (TabView) -> Unit,
    /** Closes the reader overlay in the page that is about to go to the background. */
    private val closeReader: () -> Unit,
) {
    private val tab: Tab get() = session.active
    val isHome: Boolean get() = tab.url == UrlResolver.HOME

    /** A document started loading in [v]; only the active tab's document touches shared state. */
    fun documentStarted(v: TabView, url: String?) {
        if (v === tab.view) { tab.progress = 5; resetFocus(); viewport.page(url); chrome.documentChanged(); applyScale() }
        inject(v)
    }

    /** SPA navigation keeps the document, so the reader overlay must be closed explicitly. @return the url if it is http(s). */
    fun visited(url: String?): String? {
        if (chrome.reader) closeReader()
        val web = url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        if (web != null) tab.url = web
        session.syncNav()
        return web
    }

    /** The UI/companion asked for a load (before the target is known). */
    fun loadRequested() = chrome.loadRequested()

    /** The active tab is about to load [url]. */
    fun navigating(url: String) { tab.url = url; chrome.documentChanged() }

    fun wentHome() { tab.url = UrlResolver.HOME; chrome.wentHome() }

    fun applyScale() { viewport.initialScale()?.let { tab.view.applyScale(it) } }

    fun newTab() {
        if (session.tabs.size >= MAX_TABS) return
        leave(); session.open(); enter()
    }

    fun switchTo(i: Int) {
        if (i == session.activeIndex) { chrome.closePanel(); return }
        leave(); session.switchTo(i); enter()
    }

    fun closeTab(i: Int) {
        val wasActive = i == session.activeIndex
        if (!session.close(i)) { wentHome(); chrome.closePanel(); return }
        if (wasActive) enter()
    }

    private fun leave() { if (chrome.reader) closeReader() }

    /** The new active tab is live: re-aim scale/focus at its page. */
    private fun enter() {
        viewport.page(if (isHome) null else tab.url)
        resetFocus(); applyScale(); chrome.tabEntered()
        if (!tab.view.ready || isHome) return
        inject(tab.view); tab.view.eval(PageApi.snap()); tab.view.focus()
    }
}
