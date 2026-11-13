package com.deepbrowser.tv.browser

/**
 * The page-side entry points (defined by [SmartNav] and [ReaderMode]) as typed Kotlin calls, plus parsing of their
 * replies. Nothing else builds a `window.__db*` call or compares against a reply literal.
 */
object PageApi {
    /** What `evaluateJavascript` returns for the string "closed": the page closed something and consumed Back. */
    const val CLOSED = "\"closed\""
    private const val READER_ON = "\"on\""
    private const val READER_NONE = "\"none\""

    fun snap() = "window.__dbSnap&&window.__dbSnap()"
    fun select(id: Int) = "window.__dbSel&&window.__dbSel($id)"
    fun carousel(d: Dir) = "window.__dbCarousel&&window.__dbCarousel('${d.js}')"
    fun scroll(d: Dir) = "window.__dbScroll&&window.__dbScroll('${d.js}')"
    fun reveal() = "window.__dbReveal&&window.__dbReveal()"
    fun ok() = "(window.__dbOk?window.__dbOk():'none')"
    fun back() = "(window.__dbBack?window.__dbBack():'none')"
    /** Closes a player menu opened from the control bar; replies [CLOSED] or "none". */
    fun menuBack() = "(window.__dbMenuBack&&window.__dbMenuBack()?'closed':'none')"
    fun readerToggle() = ReaderMode.SCRIPT + ";window.__dbReader()"
    fun readerClose() = "(window.__dbReader&&window.__dbReader())"
    fun readerScroll(delta: Int) = "window.__dbReaderScroll&&window.__dbReaderScroll($delta)"

    /** The biggest video seeks by [seconds] (negative = back, clamped at 0). */
    fun seekBy(seconds: Int) = video(
        if (seconds < 0) "v.currentTime=Math.max(0,v.currentTime$seconds)" else "v.currentTime=v.currentTime+$seconds")
    fun togglePlay() = video("v.paused?v.play():v.pause()")
    private fun video(action: String) = PageScripts.videoJs(action)

    fun wasClosed(reply: String?) = reply == CLOSED
    fun readerIsOn(reply: String?) = reply == READER_ON
    fun readerFoundArticle(reply: String?) = reply != READER_NONE
}
