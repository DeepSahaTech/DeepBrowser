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
}
