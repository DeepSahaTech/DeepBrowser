package com.deepbrowser.tv.browser

import android.os.Handler

/** Seam for the delayed callbacks the browser needs (OK long-press, fullscreen controls hide); tests use a fake clock. */
interface Scheduler {
    fun postDelayed(r: Runnable, delayMs: Long)
    fun cancel(r: Runnable)
}

/** Adapter: runs on the main looper. */
class HandlerScheduler(private val handler: Handler) : Scheduler {
    override fun postDelayed(r: Runnable, delayMs: Long) { handler.postDelayed(r, delayMs) }
    override fun cancel(r: Runnable) = handler.removeCallbacks(r)
}
