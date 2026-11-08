package com.deepbrowser.tv.browser

/** Pure URL decision: which sites ship their own TV UI (layout widths live in [Viewport]). */
object SitePolicy {
    private val tvNative = Regex("^https?://([a-z0-9-]+\\.)*youtube\\.com/tv", RegexOption.IGNORE_CASE)

    /** Sites that ship their own 10-foot UI: lay out at 1920 and let the page handle the D-pad itself. */
    fun isTvNative(url: String?): Boolean = url != null && tvNative.containsMatchIn(url)
}
