package com.deepbrowser.tv.browser

import android.os.Handler
import android.os.SystemClock
import androidx.compose.ui.geometry.Rect

/**
 * Native spatial navigation. The page reports a snapshot of its focusable elements (see SmartNav);
 * the next target is chosen by [Spatial] with no page round trip, and the ring (a Compose overlay) animates to it.
 * Only the already-decided selection is sent back to the page (coalesced to ~16/s while a key repeats).
 * This class keeps only the Handler/evalJs plumbing and the focus state.
 */
class FocusNav(
    private val main: Handler,
    private val evalJs: (String) -> Unit,
    private val viewWidth: () -> Int,
    private val onRing: (Rect?) -> Unit,
    private val onSeek: (forward: Boolean) -> Unit = {},
) {
    private var items = emptyList<NavItem>()
    private var viewH = Viewport.SCREEN_H.toFloat()
    var cssWidth = Viewport.LAYOUT_WIDTH.toFloat(); private set
    private var curId = -1
    private var pendingDir: String? = null
    private var carousels = emptyList<Bounds>()
    private var anchorX = Float.NaN   // keeps your column while moving up/down through rows
    private var anchorFromCur = false
    private var lastSend = 0L
    private var queuedId = -1
    private val flush = Runnable { sendSel() }

    private fun Bounds.rect() = Rect(l, t, r, b)

    /** The player's timeline is selected: Left/Right seek, OK toggles play. */
    val sliderFocused: Boolean get() = items.firstOrNull { it.id == curId }?.slider == true

    fun reset() { items = emptyList(); curId = -1; pendingDir = null; anchorX = Float.NaN; onRing(null) }

    fun onSnapshot(json: String) {
        val snap = SnapshotParser.parse(json, viewWidth()) ?: return
        cssWidth = snap.cssWidth; viewH = snap.viewH; carousels = snap.carousels
        val prev = items.firstOrNull { it.id == curId }
        items = snap.items
        if (prev != null && items.none { it.id == curId }) {
            Spatial.carry(prev, items, viewWidth())?.let { curId = it.id; queuedId = it.id; sendSel() }
        }
        if (curId < 0 || items.none { it.id == curId }) curId = if (items.any { it.id == snap.pageCur }) snap.pageCur else -1
        items.firstOrNull { it.id == curId }?.let {
            onRing(it.box.rect())
            if (anchorFromCur) anchorX = it.cx
        } ?: onRing(null)
        // after a reveal the player's controls arrive in a later snapshot: a lone element (the video) is not the answer yet
        pendingDir?.let { if (it == "init" && items.size < 2) return; pendingDir = null; move(it, fromSnapshot = true) }
    }

    /** Scroll tracking: the focused element moved because the page scrolled. */
    fun onCurRect(x: Float, y: Float, w: Float, h: Float) {
        if (curId < 0) return
        onRing(Viewport.toView(x, y, w, h, Viewport.factor(viewWidth(), cssWidth)).rect())
    }

    fun move(dir: String, fromSnapshot: Boolean = false): Boolean {
        if (items.isEmpty()) { if (!fromSnapshot) { pendingDir = dir; evalJs("window.__dbSnap&&window.__dbSnap()") }; return true }
        val cur = items.firstOrNull { it.id == curId }
        val d = Dir.parse(dir) // null for "init" (after reveal): re-pick the first target
        if (cur == null || d == null) { Spatial.initial(items, viewH)?.let { select(it, dir) }; return true }
        if (cur.slider && (d == Dir.LEFT || d == Dir.RIGHT)) { onSeek(d == Dir.RIGHT); return true }
        if (d == Dir.UP || d == Dir.DOWN) { if (anchorX.isNaN()) anchorX = cur.cx }
        when (val r = Spatial.decide(items, cur, d, anchorX, carousels, viewWidth())) {
            is NavResult.Move -> select(r.item, dir)
            NavResult.CarouselSwitch -> evalJs("window.__dbCarousel&&window.__dbCarousel('$dir')")
            NavResult.None -> if (!fromSnapshot) { pendingDir = dir; evalJs("window.__dbScroll&&window.__dbScroll('$dir')") }
        }
        return true
    }

    fun reveal() { evalJs("window.__dbReveal&&window.__dbReveal()"); pendingDir = "init" }

    private fun select(it: NavItem, dir: String?) {
        if (dir == "left" || dir == "right") { anchorX = it.cx; anchorFromCur = true } else if (dir == "up" || dir == "down") { anchorFromCur = false; if (anchorX.isNaN() || anchorX < it.box.l || anchorX > it.box.r) anchorX = it.cx }
        curId = it.id
        onRing(it.box.rect())
        queuedId = it.id
        val now = SystemClock.uptimeMillis()
        if (now - lastSend >= 60) sendSel() else { main.removeCallbacks(flush); main.postDelayed(flush, 60 - (now - lastSend)) }
    }

    private fun sendSel() {
        lastSend = SystemClock.uptimeMillis()
        if (queuedId >= 0) evalJs("window.__dbSel&&window.__dbSel($queuedId)")
    }
}
