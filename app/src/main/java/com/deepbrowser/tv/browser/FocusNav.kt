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
    /** A move waiting for the page: the first snapshot (after [reveal]: the one with the controls) triggers it. */
    private sealed interface Pending { object Init : Pending; class Move(val dir: Dir) : Pending }
    private var pending: Pending? = null
    private var carousels = emptyList<Bounds>()
    private var anchorX = Float.NaN   // keeps your column while moving up/down through rows
    private var anchorFromCur = false
    private var lastSend = 0L
    private var queuedId = -1
    private val flush = Runnable { sendSel() }

    private fun Bounds.rect() = Rect(l, t, r, b)

    /** The player's timeline is selected: Left/Right seek, OK toggles play. */
    val sliderFocused: Boolean get() = items.firstOrNull { it.id == curId }?.slider == true

    fun reset() { items = emptyList(); curId = -1; pending = null; anchorX = Float.NaN; onRing(null) }

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
        when (val p = pending) {
            Pending.Init -> if (items.size >= 2) { pending = null; pickInitial() }
            is Pending.Move -> { pending = null; move(p.dir, fromSnapshot = true) }
            null -> {}
        }
    }

    /** Scroll tracking: the focused element moved because the page scrolled. */
    fun onCurRect(x: Float, y: Float, w: Float, h: Float) {
        if (curId < 0) return
        onRing(Viewport.toView(x, y, w, h, Viewport.factor(viewWidth(), cssWidth)).rect())
    }

    fun move(dir: Dir, fromSnapshot: Boolean = false): Boolean {
        if (items.isEmpty()) { if (!fromSnapshot) { pending = Pending.Move(dir); evalJs(PageApi.snap()) }; return true }
        val cur = items.firstOrNull { it.id == curId }
        if (cur == null) { Spatial.initial(items, viewH)?.let { select(it, dir) }; return true }
        if (cur.slider && (dir == Dir.LEFT || dir == Dir.RIGHT)) { onSeek(dir == Dir.RIGHT); return true }
        if (dir == Dir.UP || dir == Dir.DOWN) { if (anchorX.isNaN()) anchorX = cur.cx }
        when (val r = Spatial.decide(items, cur, dir, anchorX, carousels, viewWidth())) {
            is NavResult.Move -> select(r.item, dir)
            NavResult.CarouselSwitch -> evalJs(PageApi.carousel(dir))
            NavResult.None -> if (!fromSnapshot) { pending = Pending.Move(dir); evalJs(PageApi.scroll(dir)) }
        }
        return true
    }

    fun reveal() { evalJs(PageApi.reveal()); pending = Pending.Init }

    /** After a reveal: re-pick the first target. */
    private fun pickInitial() { Spatial.initial(items, viewH)?.let { select(it, null) } }

    private fun select(it: NavItem, dir: Dir?) {
        if (dir == Dir.LEFT || dir == Dir.RIGHT) { anchorX = it.cx; anchorFromCur = true } else if (dir == Dir.UP || dir == Dir.DOWN) { anchorFromCur = false; if (anchorX.isNaN() || anchorX < it.box.l || anchorX > it.box.r) anchorX = it.cx }
        curId = it.id
        onRing(it.box.rect())
        queuedId = it.id
        val now = SystemClock.uptimeMillis()
        if (now - lastSend >= 60) sendSel() else { main.removeCallbacks(flush); main.postDelayed(flush, 60 - (now - lastSend)) }
    }

    private fun sendSel() {
        lastSend = SystemClock.uptimeMillis()
        if (queuedId >= 0) evalJs(PageApi.select(queuedId))
    }
}
