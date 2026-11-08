package com.deepbrowser.tv.browser

import java.net.URLEncoder

/** Turns whatever the user typed or spoke into a loadable URL (or a search URL). */
object UrlResolver {
    const val SEARCH_TEMPLATE = "https://duckduckgo.com/?q=%s"
    const val HOME = "about:home"

    private val schemeRegex = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")
    private val hostRegex = Regex(
        "^(localhost|((\\d{1,3}\\.){3}\\d{1,3})|([a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,})(:\\d+)?([/?#].*)?$"
    )

    fun resolve(input: String, searchTemplate: String = SEARCH_TEMPLATE): String {
        val text = input.trim()
        if (text.isEmpty()) return HOME
        if (text == HOME) return HOME
        if (schemeRegex.matches(text)) return text
        // spoken "dot": "youtube dot com" -> youtube.com
        val spoken = text.replace(Regex("\\s+dot\\s+", RegexOption.IGNORE_CASE), ".")
        if (!spoken.contains(' ') && hostRegex.matches(spoken)) {
            val isLocal = spoken.startsWith("localhost") || spoken.startsWith("192.168.") ||
                spoken.startsWith("10.") || spoken.startsWith("127.")
            return (if (isLocal) "http://" else "https://") + spoken
        }
        return searchTemplate.format(URLEncoder.encode(text, "UTF-8"))
    }

    /** Only these schemes load inside the WebView; anything else (intent:, market:, ...) is refused. */
    fun isLoadableScheme(scheme: String?): Boolean = scheme?.lowercase() in setOf("http", "https", "about", "data")

    /** Short text for the address pill: host without "www.". */
    fun displayHost(url: String?): String {
        if (url.isNullOrBlank() || url == HOME) return "Home"
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return url
        return host.removePrefix("www.")
    }
}
