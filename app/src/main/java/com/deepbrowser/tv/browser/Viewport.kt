package com.deepbrowser.tv.browser

/**
 * The one module that owns screen size, page layout width and the CSS px -> view px scale.
 * Instance = the live viewport (measured view size + current page); companion = the pure numbers and conversions.
 */
class Viewport {
    var width = SCREEN_W; private set
    var height = SCREEN_H; private set
    private var measured = false
    var pageUrl: String? = null; private set

    fun resize(w: Int, h: Int) { width = w; height = h; measured = w > 0 }
    /** The page now showing (null on Home). */
    fun page(url: String?) { pageUrl = url }

    /** TV-native sites (YouTube TV) handle the D-pad themselves. */
    val nativeKeys: Boolean get() = SitePolicy.isTvNative(pageUrl)
    /** WebView initial-scale percent for the current page; null until the first layout. */
    fun initialScale(): Int? = if (measured) initialScalePercent(width, layoutWidth(pageUrl)) else null
    /** CSS -> view factor for a page that reports [cssWidth]. */
    fun factor(cssWidth: Float): Float = factor(width, cssWidth)

    companion object {
        const val SCREEN_W = 1920
        const val SCREEN_H = 1080
        const val LAYOUT_WIDTH = 1280      // CSS width ordinary pages get
        const val TV_LAYOUT_WIDTH = 1920   // TV-native sites
        const val DEFAULT_CSS_HEIGHT = 720f

        fun layoutWidth(url: String?): Int = if (SitePolicy.isTvNative(url)) TV_LAYOUT_WIDTH else LAYOUT_WIDTH
        fun factor(viewWidth: Int, cssWidth: Float): Float = viewWidth.coerceAtLeast(1) / cssWidth.coerceAtLeast(1f)
        fun initialScalePercent(viewWidth: Int, cssWidth: Int): Int = (viewWidth * 100f / cssWidth).toInt()
        fun toView(x: Float, y: Float, w: Float, h: Float, s: Float) = Bounds(x * s, y * s, (x + w) * s, (y + h) * s)
        /** View width relative to the ordinary layout width (distance thresholds scale with it). */
        fun relative(viewWidth: Int): Float = viewWidth / LAYOUT_WIDTH.toFloat()
    }
}
