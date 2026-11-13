package com.deepbrowser.tv.browser

/**
 * The page-side entry points (defined by the in-page engine and [ReaderMode]) as typed Kotlin calls, plus parsing of
 * their replies. Nothing else builds a `window.__db*` call or compares against a reply literal.
 */
object PageApi {
    /** What `evaluateJavascript` returns for the string "closed": the page closed something and consumed Back. */
    const val CLOSED = "\"closed\""
    private const val READER_ON = "\"on\""
    private const val READER_NONE = "\"none\""

    /** Asks the page first: replies [CLOSED] when it handled Back, "none" when Kotlin should go back in history. */
    fun back() = "(window.__dbNav?window.__dbNav.back():'none')"
    /** Turns the in-page navigation (and its focus ring) on or off. */
    fun enable(on: Boolean) = "window.__dbNav&&window.__dbNav.enable($on)"
    fun readerToggle() = ReaderMode.SCRIPT + ";window.__dbReader()"
    fun readerClose() = "(window.__dbReader&&window.__dbReader())"
    fun readerScroll(delta: Int) = "window.__dbReaderScroll&&window.__dbReaderScroll($delta)"

    fun wasClosed(reply: String?) = reply == CLOSED
    fun readerIsOn(reply: String?) = reply == READER_ON
    fun readerFoundArticle(reply: String?) = reply != READER_NONE
}
