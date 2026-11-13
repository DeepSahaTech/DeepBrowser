package com.deepbrowser.tv.browser

/** Manual clock: callbacks run only when the test advances time. */
class FakeScheduler : Scheduler {
    private class Item(val at: Long, val r: Runnable)
    private val items = ArrayList<Item>()
    var now = 0L; private set
    val pending: Int get() = items.size

    override fun postDelayed(r: Runnable, delayMs: Long) { items += Item(now + delayMs, r) }
    override fun cancel(r: Runnable) { items.removeAll { it.r === r } }

    fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val next = items.filter { it.at <= end }.minByOrNull { it.at } ?: break
            items.remove(next); now = maxOf(now, next.at); next.r.run()
        }
        now = end
    }
}
