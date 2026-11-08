package com.deepbrowser.tv.browser

import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.widget.FrameLayout

/** Adapter: shows a page's fullscreen video view above everything in [container]. */
class FullscreenHost(private val container: FrameLayout, private val onExit: () -> Unit) : FullscreenSurface {
    private var view: View? = null
    private var callback: WebChromeClient.CustomViewCallback? = null
    override val active: Boolean get() = view != null
    override val shown: View? get() = view

    override fun show(v: View, cb: WebChromeClient.CustomViewCallback) {
        if (view != null) { cb.onCustomViewHidden(); return }
        view = v; callback = cb
        // The video surface is punched through the window: nothing opaque may sit behind or around it.
        v.setBackgroundColor(android.graphics.Color.BLACK)
        // alpha, not visibility: an INVISIBLE WebView pauses video decoding
        (0 until container.childCount).forEach { container.getChildAt(it).alpha = 0f }
        container.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun exit() {
        val v = view ?: return
        container.removeView(v)
        (0 until container.childCount).forEach { container.getChildAt(it).alpha = 1f }
        view = null
        callback?.onCustomViewHidden(); callback = null
        onExit()
    }
}
