package com.deepbrowser.tv.browser

import android.view.View
import android.webkit.WebChromeClient

/** Seam to whatever draws the page's fullscreen video view; [FullscreenHost] is the Android adapter. */
interface FullscreenSurface {
    val active: Boolean
    /** The page's fullscreen view, null when not in fullscreen. */
    val shown: View?
    fun show(v: View, cb: WebChromeClient.CustomViewCallback)
    fun exit()
}

/**
 * One owner for fullscreen: enter/exit, and how long the site's player controls count as visible ([ChromeState.fsControls]
 * is the flag, this class runs its [ChromeState.FS_CONTROLS_MS] lifetime). Key activity keeps the controls up.
 */
class FullscreenSession(
    private val surface: FullscreenSurface,
    private val chrome: ChromeState,
    private val timer: Scheduler,
) {
    val active: Boolean get() = surface.active
    /** Touches meant for the player go to this view (it sits above the WebView). */
    val shown: View? get() = surface.shown
    private val hide = Runnable { chrome.hideControls() }

    fun show(v: View, cb: WebChromeClient.CustomViewCallback) = surface.show(v, cb)
    fun exit() = surface.exit()

    /** Back: the page gets to close its own player menu first ([askPage] replies with the page's answer), else leave. */
    fun requestExit(askPage: ((String?) -> Unit) -> Unit) = askPage { r -> if (!PageApi.wasClosed(r)) exit() }

    /** Every key event: while the controls are up, activity (except Back) re-arms their hide timer. */
    fun onKey(key: Key) { if (chrome.keepsControls(key, active)) armHide() }

    /** The controls were just asked for: show them for another [ChromeState.FS_CONTROLS_MS]. */
    fun armHide() { timer.cancel(hide); timer.postDelayed(hide, ChromeState.FS_CONTROLS_MS) }
}
