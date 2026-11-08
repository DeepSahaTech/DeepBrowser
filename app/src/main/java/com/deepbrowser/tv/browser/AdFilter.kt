package com.deepbrowser.tv.browser

import org.json.JSONObject

/** Outcome of one [AdFilter.update]; [label] is the text shown in Settings. */
sealed class UpdateResult(val label: String) {
    object Updated : UpdateResult("Updated")
    object UpToDate : UpdateResult("Up to date")
    class Failed(reason: String) : UpdateResult("Failed: " + reason.take(40))
}

/** What Settings shows: cache time (null = never), rules in the active engine, last attempt (null = none yet). */
data class FilterStatus(val updatedAt: Long?, val rules: Int, val result: UpdateResult?)

/** Auto-update interval; the Settings note quotes it. */
const val AUTO_UPDATE_DAYS = 3

/**
 * Owns the ad-filter lifecycle: list cache (refreshed when older than [maxAgeMs]), manual/automatic [update], the
 * [FilterEngine], the built-in host list (always active, and the only filter when no list is loaded), and the script a
 * page gets ([injection]: cosmetic ad CSS + YouTube skipper + overlay removal + list hiding CSS). A downloaded list
 * is validated (header, minimum size by [minLines] or [minBytes], at most [maxBytes], at least one rule) before it
 * replaces the cache; the engine and script are swapped together. Does not know whether blocking is enabled;
 * [AdBlockState] owns that.
 */
class AdFilter(
    private val source: FilterListSource,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxAgeMs: Long = AUTO_UPDATE_DAYS * 24L * 3600 * 1000,
    private val minLines: Int = 1000,
    private val minBytes: Int = 50_000,
    private val maxBytes: Int = MAX_LIST_BYTES,
) {
    private class Loaded(val engine: FilterEngine?, val script: String)

    @Volatile private var loaded = Loaded(null, AdScript.SCRIPT)
    @Volatile var lastResult: UpdateResult? = null; private set
    val ruleCount: Int get() = loaded.engine?.ruleCount ?: 0

    fun status() = FilterStatus(source.lastModified(), ruleCount, lastResult)

    /** Blocking, run off the main thread: load the cache, then [update] if it is missing or older than [maxAgeMs]. */
    fun refresh() {
        try { source.read()?.let { swap(FilterEngine().apply { load(it) }) } } catch (_: Exception) { /* bad cache: host list only */ }
        val m = source.lastModified()
        if (m == null || now() - m > maxAgeMs) update()
    }

    /** Blocking, run off the main thread: conditional download, validate, then swap. A failure keeps the current list. */
    @Synchronized fun update(): UpdateResult {
        val r = try { download() } catch (e: Exception) { UpdateResult.Failed(e.message ?: e.javaClass.simpleName) }
        lastResult = r
        return r
    }

    private fun download(): UpdateResult {
        val cached = source.read()
        when (val f = source.fetch(if (cached != null) source.validator() else null)) {
            FetchResult.NotModified -> { source.touch(); return UpdateResult.UpToDate }
            is FetchResult.Fresh -> {
                if (f.text == cached) { source.touch(); return UpdateResult.UpToDate }
                val e = try { build(f.text) } catch (x: IllegalArgumentException) { return UpdateResult.Failed(x.message ?: "Invalid list") }
                swap(e) // the list is valid: activate it even if saving the cache or its validator fails
                try { source.write(f.text, f.validator, f.url) } catch (_: Exception) { /* best-effort: next update just refetches */ }
                return UpdateResult.Updated
            }
        }
    }

    private fun build(text: String): FilterEngine {
        require(text.length <= maxBytes) { "Too large" }
        require(text.trimStart().let { it.startsWith("[Adblock", true) || it.startsWith("!") }) { "Invalid list (no header)" }
        require(text.length >= minBytes || text.count { it == '\n' } + 1 >= minLines) { "Invalid list (too small)" }
        return FilterEngine().apply { load(text) }.also { require(it.ruleCount > 0) { "Invalid list (no rules)" } }
    }

    @Synchronized private fun swap(e: FilterEngine) {
        val css = e.hidingCss()
        loaded = Loaded(e, if (css.isEmpty()) AdScript.SCRIPT else AdScript.SCRIPT + "\n" + cssScript(css))
    }

    /** What to evaluate in a page (idempotent). */
    fun injection(): String = loaded.script

    /** Full-URL check: built-in host list, then the EasyList engine. */
    fun shouldBlock(url: String, host: String?): Boolean = BuiltInHosts.matches(host) || loaded.engine?.shouldBlock(url) == true

    private fun cssScript(css: String) =
        "(function(){if(window.__dbEc)return;window.__dbEc=1;var s=document.createElement('style');" +
            "s.textContent=" + JSONObject.quote(css) + ";(document.head||document.documentElement).appendChild(s);})()"
}
