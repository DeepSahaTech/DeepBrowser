package com.deepbrowser.tv.browser

import com.deepbrowser.tv.data.Store
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the ad-block enabled flag and the lifetime blocked counter, and persists both through a [Store] seam
 * (SharedPreferences adapter in production, in-memory in tests). The counter is flushed every [flushEvery] hits
 * or [flushMs] after the last flush, so a crash loses at most a few counts; [flush] is for onPause.
 * shortcut: flushing is driven by hits, no timer thread; a few trailing hits wait for the next hit or [flush].
 */
class AdBlockState(
    private val store: Store,
    private val now: () -> Long = System::currentTimeMillis,
    private val flushEvery: Int = 25,
    private val flushMs: Long = 10_000,
) {
    private val total = AtomicLong()
    private val unflushed = AtomicInteger()
    @Volatile private var lastFlush = now()

    @Volatile var enabled: Boolean = true
        set(v) { field = v; flush() }

    init {
        val saved = runCatching { JSONObject(store.read(KEY) ?: "") }.getOrNull()
        // First run: adopt the values the old Settings JSON held so history is not lost.
        val seed = saved ?: runCatching { JSONObject(store.read("settings") ?: "") }.getOrNull()
        if (seed != null) {
            total.set(seed.optLong(if (saved != null) "total" else "blocked", 0))
            enabled = seed.optBoolean(if (saved != null) "on" else "ad", true)
        }
    }

    /** Lifetime blocked requests (persisted total plus everything since). */
    fun totalBlocked(): Long = total.get()

    fun recordBlocked() {
        total.incrementAndGet()
        if (unflushed.incrementAndGet() >= flushEvery || now() - lastFlush >= flushMs) flush()
    }

    @Synchronized fun flush() {
        unflushed.set(0); lastFlush = now()
        store.write(KEY, JSONObject().put("on", enabled).put("total", total.get()).toString())
    }

    companion object { const val KEY = "adblock" }
}
