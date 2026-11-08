package com.deepbrowser.tv.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The ad-filter update flow and what Settings shows about it: [start] loads the cache at launch, [update] downloads
 * off the main thread, and a link change during an update queues exactly one more update from the new link.
 * [background] runs blocking work off the main thread, [onMain] publishes back; [onUpdated] fires on the main thread
 * after an update replaced the list so the current page can pick it up.
 */
class FilterUpdater(
    private val filter: AdFilter,
    private val choice: FilterSourceChoice,
    private val background: (() -> Unit) -> Unit,
    private val onMain: (() -> Unit) -> Unit,
    private val onUpdated: () -> Unit,
) {
    var status by mutableStateOf(FilterStatus(null, 0, null)); private set
    var updating by mutableStateOf(false); private set
    var source by mutableStateOf(choice.label); private set
    var linkError by mutableStateOf<String?>(null); private set
    private var relink = false

    /** Load the cached list, fetching when it is stale or missing. */
    fun start() = background { filter.refresh(); val s = filter.status(); onMain { status = s } }

    /** Manual or link-change update. */
    fun update() {
        if (updating) return
        updating = true
        background {
            val r = filter.update(); val s = filter.status()
            onMain {
                updating = false; status = s
                if (relink) { relink = false; update() } // the link changed mid-update: fetch the new one
                if (r is UpdateResult.Updated) onUpdated()
            }
        }
    }

    /** Use a custom https list link (validated, persisted) and update from it now. False when the link is rejected. */
    fun setLink(input: String): Boolean {
        if (!choice.set(input)) { linkError = "Enter a full https:// link"; return false }
        linkError = null; source = choice.label
        updateForNewLink()
        return true
    }

    fun resetLink() {
        choice.reset(); linkError = null; source = choice.label
        updateForNewLink()
    }

    private fun updateForNewLink() { if (updating) relink = true else update() }
}
