package com.deepbrowser.tv.browser

import kotlin.math.max
import kotlin.math.min

enum class Edge { NONE, TOP, BOTTOM, LEFT, RIGHT }

/**
 * Virtual mouse pointer driven by the D-pad. Pure Kotlin so it can be unit-tested.
 * Holding a direction accelerates the pointer; releasing resets the speed.
 */
class CursorState(
    var width: Float,
    var height: Float,
    private val baseSpeedPx: Float = 14f,
    private val maxSpeedPx: Float = 70f,
    private val accelPerStep: Float = 1.18f,
    private val edgeMarginPx: Float = 24f,
) {
    var x: Float = width / 2f
        private set
    var y: Float = height / 2f
        private set
    private var speed: Float = baseSpeedPx

    fun resize(w: Float, h: Float) {
        width = w; height = h
        x = x.coerceIn(0f, max(0f, w - 1)); y = y.coerceIn(0f, max(0f, h - 1))
    }

    /** One key-repeat step. dx/dy are -1, 0 or 1. Returns the edge being pushed, if any. */
    fun step(dx: Int, dy: Int): Edge {
        x = (x + dx * speed).coerceIn(0f, max(0f, width - 1))
        y = (y + dy * speed).coerceIn(0f, max(0f, height - 1))
        speed = min(maxSpeedPx, speed * accelPerStep)
        return edge(dx, dy)
    }

    fun release() { speed = baseSpeedPx }

    fun moveTo(nx: Float, ny: Float) {
        x = nx.coerceIn(0f, max(0f, width - 1)); y = ny.coerceIn(0f, max(0f, height - 1))
    }

    private fun edge(dx: Int, dy: Int): Edge = when {
        dy < 0 && y <= edgeMarginPx -> Edge.TOP
        dy > 0 && y >= height - 1 - edgeMarginPx -> Edge.BOTTOM
        dx < 0 && x <= edgeMarginPx -> Edge.LEFT
        dx > 0 && x >= width - 1 - edgeMarginPx -> Edge.RIGHT
        else -> Edge.NONE
    }

    val currentSpeed: Float get() = speed

    // ---- frame-driven movement: held directions ramp the speed with hold time and scale by the frame delta ----
    private var heldL = false; private var heldR = false; private var heldU = false; private var heldD = false
    private var heldMs = 0f
    /** User speed multiplier (Settings). */
    var speedScale: Float = 1f

    val moving: Boolean get() = (heldL != heldR) || (heldU != heldD)

    fun hold(dir: Dir, down: Boolean) {
        val was = moving
        when (dir) { Dir.LEFT -> heldL = down; Dir.RIGHT -> heldR = down; Dir.UP -> heldU = down; Dir.DOWN -> heldD = down }
        if (!moving) heldMs = 0f else if (!was) heldMs = 0f
    }

    fun releaseAll() { heldL = false; heldR = false; heldU = false; heldD = false; heldMs = 0f }

    /** Pixels per second for a pointer held for [heldMs]: eases from [MIN_PX_S] up to [MAX_PX_S] over [RAMP_MS]. */
    fun velocityAt(heldMs: Float): Float {
        val t = (heldMs / RAMP_MS).coerceIn(0f, 1f)
        return (MIN_PX_S + (MAX_PX_S - MIN_PX_S) * t * t) * speedScale
    }

    /** Advance by [dtMs]; diagonals normalise so they are not faster. Returns the edge pushed, if any. */
    fun tick(dtMs: Float): Edge {
        if (!moving) return Edge.NONE
        val dx = (if (heldR) 1 else 0) - (if (heldL) 1 else 0)
        val dy = (if (heldD) 1 else 0) - (if (heldU) 1 else 0)
        heldMs += dtMs
        val norm = if (dx != 0 && dy != 0) 0.7071f else 1f
        val d = velocityAt(heldMs) * norm * dtMs / 1000f
        x = (x + dx * d).coerceIn(0f, max(0f, width - 1))
        y = (y + dy * d).coerceIn(0f, max(0f, height - 1))
        lastStepPx = velocityAt(heldMs) * dtMs / 1000f
        return edge(dx, dy)
    }

    /** Pixels the last [tick] asked to move (used to scroll when the pointer sits on an edge). */
    var lastStepPx: Float = 0f; private set

    companion object {
        const val MIN_PX_S = 260f
        const val MAX_PX_S = 1500f
        const val RAMP_MS = 900f
    }
}
