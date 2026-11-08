package com.deepbrowser.tv.player

/** Pure builder for the HTTP request headers the page context hands to the native player. */
object PlayerHeaders {
    /** Header value with CR/LF removed (no header injection through a crafted cookie/referer) and trimmed; null if blank. */
    private fun clean(v: String?): String? = v?.filter { it != '\r' && it != '\n' }?.trim()?.takeIf { it.isNotEmpty() }

    /** Default request properties (Referer, Cookie) sent on every request, including HLS/DASH segments. */
    fun requestProperties(referer: String?, cookies: String?): Map<String, String> = buildMap {
        clean(referer)?.let { put("Referer", it) }
        clean(cookies)?.let { put("Cookie", it) }
    }

    /**
     * The part of [props] (Referer, Cookie) that may go to [requestUrl]: only when its host is the media's or the page's
     * host, and never over plain http when that origin was https. Other hosts (CDNs, redirect targets) get nothing.
     */
    fun forRequest(props: Map<String, String>, mediaUrl: String, requestUrl: String): Map<String, String> {
        if (props.isEmpty()) return props
        val req = origin(requestUrl) ?: return emptyMap()
        val ok = listOfNotNull(origin(mediaUrl), props["Referer"]?.let(::origin)).any {
            it.first == req.first && !(it.second == "https" && req.second != "https")
        }
        return if (ok) props else emptyMap()
    }

    private fun origin(url: String): Pair<String, String>? = runCatching {
        java.net.URI(url).let { it.host!!.lowercase() to it.scheme.lowercase() }
    }.getOrNull()

    fun userAgent(ua: String?): String? = clean(ua)
}
