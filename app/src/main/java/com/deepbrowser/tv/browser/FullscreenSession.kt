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

/** One owner for fullscreen: enter and exit. The page's own player controls are the page's business now. */
class FullscreenSession(private val surface: FullscreenSurface) {
    val active: Boolean get() = surface.active
    /** Keys and touches meant for the player go to this view (it sits above the WebView). */
    val shown: View? get() = surface.shown

    fun show(v: View, cb: WebChromeClient.CustomViewCallback) = surface.show(v, cb)
    fun exit() = surface.exit()

    /** Back: the page gets to close its own player menu first ([askPage] replies with the page's answer), else leave. */
    fun requestExit(askPage: ((String?) -> Unit) -> Unit) = askPage { r -> if (!PageApi.wasClosed(r)) exit() }
}
