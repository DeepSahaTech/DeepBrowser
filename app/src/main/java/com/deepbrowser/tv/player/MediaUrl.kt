package com.deepbrowser.tv.player

/** Classifies direct media URLs that should open in DeepPlayer instead of the WebView. */
object MediaUrl {
    private val EXT = setOf("mp4", "mkv", "webm", "m3u8", "mpd", "mov", "ts")

    fun isDirectMedia(url: String?): Boolean {
        if (url == null) return false
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        val path = lower.substringBefore('#').substringBefore('?').substringAfter("://").substringAfter('/', "")
        if (path.isEmpty() || path.endsWith("/")) return false
        return path.substringAfterLast('/').substringAfterLast('.', "") in EXT
    }
}
