package com.deepbrowser.tv.browser

import android.view.KeyEvent

/** Adapter: Android key events to the pure [Key] vocabulary. */
object KeyMap {
    fun of(e: KeyEvent): Key {
        if (e.action != KeyEvent.ACTION_DOWN && e.action != KeyEvent.ACTION_UP) return Key.OTHER
        return when (e.keyCode) {
            KeyEvent.KEYCODE_BACK -> Key.BACK
            KeyEvent.KEYCODE_DPAD_UP -> Key.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Key.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> Key.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Key.RIGHT
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Key.OK
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SEARCH -> Key.MENU
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> Key.PAGE_DOWN
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> Key.PAGE_UP
            else -> Key.OTHER
        }
    }
}
