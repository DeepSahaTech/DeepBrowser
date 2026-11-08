package com.deepbrowser.tv.browser

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/** Plain rectangle in view pixels. */
data class Bounds(val l: Float, val t: Float, val r: Float, val b: Float) {
    val cx get() = (l + r) / 2
    val cy get() = (t + b) / 2
    val w get() = r - l
    val h get() = b - t
}

data class NavItem(val id: Int, val box: Bounds, val slider: Boolean = false) {
    val cx get() = box.cx
    val cy get() = box.cy
}

/** A page snapshot already converted to view pixels. [pageCur] is the id the page says is focused (-1 none). */
data class Snapshot(val cssWidth: Float, val viewH: Float, val carousels: List<Bounds>, val items: List<NavItem>, val pageCur: Int)

/** Wire format from SmartNav: {iw,ih,cur,car:[[x,y,w,h]],items:[[id,x,y,w,h]]} in CSS px. */
object SnapshotParser {
    fun parse(json: String, viewWidth: Int): Snapshot? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val iw = o.optDouble("iw", Viewport.LAYOUT_WIDTH.toDouble()).toFloat().coerceAtLeast(1f)
        val s = Viewport.factor(viewWidth, iw)
        val car = o.optJSONArray("car")
        val carousels = if (car == null) emptyList() else (0 until car.length()).map {
            val a = car.getJSONArray(it)
            Viewport.toView(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat(), s)
        }
        val arr = o.optJSONArray("items")
        val items = ArrayList<NavItem>(arr?.length() ?: 0)
        if (arr != null) for (i in 0 until arr.length()) {
            val a = arr.getJSONArray(i)
            items.add(NavItem(a.getInt(0), Viewport.toView(a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat(), a.getDouble(4).toFloat(), s), a.optInt(5, 0) == 1))
        }
        return Snapshot(iw, o.optDouble("ih", Viewport.DEFAULT_CSS_HEIGHT.toDouble()).toFloat() * s, carousels, items, o.optInt("cur", -1))
    }
}

sealed interface NavResult {
    data class Move(val item: NavItem) : NavResult
    object CarouselSwitch : NavResult // Left/Right at a row end beside slider dots: switch the slide
    object None : NavResult           // nothing in that direction: the caller scrolls the page
}

/** Spatial scoring over plain rectangles (Netflix-style rows, column memory, carousel dots). */
object Spatial {
    /** First focus lands on the main content (below the header band), not the site logo/nav. */
    fun initial(items: List<NavItem>, viewH: Float): NavItem? {
        val visible = items.filter { it.box.b > 0 && it.box.t < viewH }
        val body = visible.filter { it.cy > viewH * 0.18f }
        return (body.ifEmpty { visible }).minByOrNull { it.box.t * 2 + it.box.l * 0.5 } ?: items.firstOrNull()
    }

    /** [anchorX] is the remembered column for Up/Down (NaN = use the current item's centre). */
    fun decide(items: List<NavItem>, cur: NavItem, dir: Dir, anchorX: Float, carousels: List<Bounds>, viewWidth: Int): NavResult {
        val horizontal = dir == Dir.LEFT || dir == Dir.RIGHT
        // Same-row candidates first for left/right; Up/Down take anything in that direction.
        val hit = if (horizontal) pick(items, cur, dir, anchorX, rowOnly = true) else pick(items, cur, dir, anchorX, rowOnly = false)
        if (hit != null) return NavResult.Move(hit)
        if (!horizontal) return NavResult.None
        val near = carousels.any { max(0f, max(it.t - cur.box.b, cur.box.t - it.b)) <= 150f * Viewport.relative(viewWidth) }
        return if (near) NavResult.CarouselSwitch else NavResult.None // Left/Right never jumps to unrelated items
    }

    /** The focused element vanished (a slider auto-advanced): the item now sitting where it was, if close enough. */
    fun carry(prev: NavItem, now: List<NavItem>, viewWidth: Int): NavItem? {
        val near = now.minByOrNull { abs(it.cx - prev.cx) + abs(it.cy - prev.cy) } ?: return null
        return if (abs(near.cx - prev.cx) + abs(near.cy - prev.cy) < viewWidth * 0.25f) near else null
    }

    private fun pick(items: List<NavItem>, c: NavItem, dir: Dir, anchorX: Float, rowOnly: Boolean): NavItem? {
        var best: NavItem? = null
        var bs = Float.MAX_VALUE
        val ax = if (anchorX.isNaN()) c.cx else anchorX
        val cb = c.box
        for (o in items) {
            if (o.id == c.id) continue
            val ob = o.box
            val gap: Float; val cross: Float; val overlap: Boolean; val ok: Boolean
            when (dir) {
                Dir.RIGHT -> { ok = ob.cx - cb.cx > 4 && ob.l > cb.l + 4; gap = max(0f, ob.l - cb.r); cross = abs(ob.cy - cb.cy); overlap = rowOverlap(cb, ob) }
                Dir.LEFT -> { ok = cb.cx - ob.cx > 4 && ob.r < cb.r - 4; gap = max(0f, cb.l - ob.r); cross = abs(ob.cy - cb.cy); overlap = rowOverlap(cb, ob) }
                Dir.DOWN -> { ok = ob.cy - cb.cy > 4 && ob.t > cb.t + 4; gap = max(0f, ob.t - cb.b); cross = abs(ob.cx - ax); overlap = ax >= ob.l && ax <= ob.r }
                Dir.UP -> { ok = cb.cy - ob.cy > 4 && ob.b < cb.b - 4; gap = max(0f, cb.t - ob.b); cross = abs(ob.cx - ax); overlap = ax >= ob.l && ax <= ob.r }
            }
            if (!ok || (rowOnly && !overlap)) continue
            val score = gap + cross * (if (overlap) 0.6f else 3f)
            if (score < bs) { bs = score; best = o }
        }
        return best
    }

    /** Vertical overlap of at least 40% of the shorter item = same row. */
    private fun rowOverlap(a: Bounds, b: Bounds): Boolean = minOf(a.b, b.b) - max(a.t, b.t) > 0.4f * minOf(a.h, b.h)
}
