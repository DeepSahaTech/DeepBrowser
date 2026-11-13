package com.deepbrowser.tv.browser

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager

/** Adapter: synthesises touches / hover on the active WebView (script clicks are not user gestures; real touches are). */
class PointerInjector(
    private val web: () -> View,
    private val cursor: CursorState,
    private val main: Handler,
) {
    private var lastHover = 0L

    private fun tap(x: Float, y: Float, holdMs: Long) {
        val w = web()
        val t = SystemClock.uptimeMillis()
        val d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        val u = MotionEvent.obtain(t, t + holdMs, MotionEvent.ACTION_UP, x, y, 0)
        w.requestFocus(); w.dispatchTouchEvent(d); w.dispatchTouchEvent(u)
        d.recycle(); u.recycle()
    }

    /** A real tap on a text field gives WebView the user gesture it needs to raise the TV keyboard. [fx], [fy] are viewport fractions. */
    fun tapForKeyboard(fx: Float, fy: Float) {
        val w = web()
        tap(fx * w.width, fy * w.height, 50)
        main.postDelayed({
            val v = web()
            (v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    fun click() = tap(cursor.x, cursor.y, 60)

    /** One animation frame of held-key movement: scroll when pushing an edge, otherwise hover under the pointer. */
    fun frame(dtMs: Float) {
        val dx = cursor.x; val dy = cursor.y
        val edge = cursor.tick(dtMs)
        val w = web()
        val step = (cursor.lastStepPx * 1.2f).toInt().coerceAtLeast(1)
        when (edge) {
            Edge.TOP -> w.scrollBy(0, -step)
            Edge.BOTTOM -> w.scrollBy(0, step)
            Edge.LEFT -> w.scrollBy(-step, 0)
            Edge.RIGHT -> w.scrollBy(step, 0)
            Edge.NONE -> {}
        }
        if (edge == Edge.NONE && (dx != cursor.x || dy != cursor.y)) hover(w)
    }

    /** Synthesised mouse-hover so menus and tooltips that open on hover work. */
    private fun hover(w: View) {
        val now = SystemClock.uptimeMillis()
        if (now - lastHover < 33) return
        lastHover = now
        val ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, cursor.x, cursor.y, 0)
        ev.source = InputDevice.SOURCE_MOUSE
        w.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }
}
