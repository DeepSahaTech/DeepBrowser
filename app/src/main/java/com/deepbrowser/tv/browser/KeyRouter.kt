package com.deepbrowser.tv.browser

enum class Key { BACK, UP, DOWN, LEFT, RIGHT, OK, MENU, PAGE_UP, PAGE_DOWN, OTHER }

enum class Dir(val js: String) {
    UP("up"), DOWN("down"), LEFT("left"), RIGHT("right")
}

/** A key event with no Android types. [down] false means key-up. */
data class KeyIn(val key: Key, val down: Boolean, val repeat: Int = 0)

/** Everything the decision needs to know about the browser right now. */
data class KeyState(
    val fullscreen: Boolean = false,
    val imeVisible: Boolean = false,
    val overlayOpen: Boolean = false, // command bar, device info or a panel
    val isHome: Boolean = false,
    val readerOn: Boolean = false,
    val nativeKeys: Boolean = false, // TV-native site: the page handles the D-pad and OK itself
    val cursorMode: Boolean = false,
    val okLongFired: Boolean = false,
)

sealed interface KeyAction {
    object Pass : KeyAction           // not ours: let the system / Compose handle it
    object Consume : KeyAction        // swallow, do nothing
    object Forward : KeyAction        // hand the same key event to the page (or the fullscreen view); the in-page engine acts
    object ExitFullscreen : KeyAction
    object CloseOverlays : KeyAction
    object OpenCommandBar : KeyAction
    object ExitReader : KeyAction
    data class ReaderScroll(val delta: Int) : KeyAction
    data class BackPage(val native: Boolean) : KeyAction // native: skip the page's own "close popover" step
    data class MoveCursor(val dir: Dir) : KeyAction
    object ReleaseCursor : KeyAction
    object OkDown : KeyAction         // first press: arm the long-press timer
    object OkUpLong : KeyAction       // release after the long press already fired
    object OkUpClick : KeyAction      // release in cursor mode: click under the cursor
    object OkUpSmart : KeyAction      // release in focus mode: send a real Enter to the page
    data class Page(val down: Boolean) : KeyAction
}

/** The remote's decision tree as a pure function; the controller only executes the returned action. */
object KeyRouter {
    fun route(k: KeyIn, s: KeyState): KeyAction {
        if (s.fullscreen) return fullscreen(k, s)
        // The long press already opened the command bar: swallow the rest of that press so its release cannot click an item.
        if (k.key == Key.OK && s.okLongFired) return if (k.down) KeyAction.Consume else KeyAction.OkUpLong
        if (s.imeVisible) return KeyAction.Pass
        if (s.overlayOpen) return if (k.key == Key.BACK) (if (!k.down) KeyAction.CloseOverlays else KeyAction.Consume) else KeyAction.Pass
        if (k.key == Key.MENU) return if (!k.down) KeyAction.OpenCommandBar else KeyAction.Consume
        if (s.isHome) return KeyAction.Pass // BACK exits the app; D-pad moves focus between tiles
        if (s.readerOn) when (k.key) {
            Key.BACK -> return if (!k.down) KeyAction.ExitReader else KeyAction.Consume
            Key.UP, Key.DOWN -> return if (k.down) KeyAction.ReaderScroll(if (k.key == Key.UP) -450 else 450) else KeyAction.Consume
            else -> {}
        }
        return when (k.key) {
            Key.BACK -> if (!k.down) KeyAction.BackPage(s.nativeKeys) else KeyAction.Consume
            Key.UP, Key.DOWN, Key.LEFT, Key.RIGHT -> when {
                s.nativeKeys -> KeyAction.Forward
                s.cursorMode -> if (k.down) KeyAction.MoveCursor(dirOf(k.key)!!) else KeyAction.ReleaseCursor
                else -> KeyAction.Forward // page focus: the in-page engine moves the ring, key time
            }
            Key.OK -> when {
                s.nativeKeys -> KeyAction.Forward
                k.down -> if (k.repeat == 0) KeyAction.OkDown else KeyAction.Consume
                s.okLongFired -> KeyAction.OkUpLong
                s.cursorMode -> KeyAction.OkUpClick
                else -> KeyAction.OkUpSmart
            }
            Key.PAGE_DOWN -> if (k.down) KeyAction.Page(true) else KeyAction.Consume
            Key.PAGE_UP -> if (k.down) KeyAction.Page(false) else KeyAction.Consume
            else -> KeyAction.Pass
        }
    }

    private fun fullscreen(k: KeyIn, s: KeyState): KeyAction = when (k.key) {
        Key.BACK -> if (!k.down) KeyAction.ExitFullscreen else KeyAction.Consume
        // Pointer mode wins in fullscreen too, with the same actions as the page path.
        Key.UP, Key.DOWN, Key.LEFT, Key.RIGHT -> when {
            s.cursorMode -> if (k.down) KeyAction.MoveCursor(dirOf(k.key)!!) else KeyAction.ReleaseCursor
            else -> KeyAction.Forward
        }
        Key.OK -> when {
            s.cursorMode -> when {
                k.down -> if (k.repeat == 0) KeyAction.OkDown else KeyAction.Consume
                s.okLongFired -> KeyAction.OkUpLong
                else -> KeyAction.OkUpClick
            }
            else -> if (!k.down) KeyAction.OkUpSmart else KeyAction.Consume // OK acts once, on release, as Enter
        }
        else -> KeyAction.Pass
    }

    private fun dirOf(k: Key): Dir? = when (k) {
        Key.UP -> Dir.UP; Key.DOWN -> Dir.DOWN; Key.LEFT -> Dir.LEFT; Key.RIGHT -> Dir.RIGHT; else -> null
    }
}
