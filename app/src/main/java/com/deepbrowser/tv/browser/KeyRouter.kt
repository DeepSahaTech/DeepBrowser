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
    val fsControls: Boolean = false,
    val imeVisible: Boolean = false,
    val overlayOpen: Boolean = false, // command bar, device info or a panel
    val isHome: Boolean = false,
    val readerOn: Boolean = false,
    val nativeKeys: Boolean = false, // TV-native site: the page handles the D-pad itself
    val cursorMode: Boolean = false,
    val okLongFired: Boolean = false,
)

sealed interface KeyAction {
    object Pass : KeyAction           // not ours: let the system / Compose handle it
    object Consume : KeyAction        // swallow, do nothing
    object ExitFullscreen : KeyAction
    object FsReveal : KeyAction       // first Up/Down in fullscreen: show the site's player controls
    data class FsNav(val dir: Dir) : KeyAction
    object FsOk : KeyAction
    data class Seek(val seconds: Int) : KeyAction
    object VideoToggle : KeyAction
    object CloseOverlays : KeyAction
    object OpenCommandBar : KeyAction
    object ExitReader : KeyAction
    data class ReaderScroll(val delta: Int) : KeyAction
    data class BackPage(val native: Boolean) : KeyAction // native: skip the page's own "close popover" step
    data class Nav(val dir: Dir, val repeat: Boolean) : KeyAction
    data class MoveCursor(val dir: Dir) : KeyAction
    object ReleaseCursor : KeyAction
    object OkDown : KeyAction         // first press: arm the long-press timer
    object OkUpLong : KeyAction       // release after the long press already fired
    object OkUpClick : KeyAction      // release in cursor mode: click under the cursor
    object OkUpSmart : KeyAction      // release in focus mode: tap the focus ring
    data class Page(val down: Boolean) : KeyAction
}

/** The remote's decision tree as a pure function; the controller only executes the returned action. */
object KeyRouter {
    fun route(k: KeyIn, s: KeyState): KeyAction {
        val dir = dirOf(k.key)
        if (s.fullscreen) return fullscreen(k, s, dir)
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
                s.nativeKeys -> KeyAction.Pass
                s.cursorMode -> if (k.down) KeyAction.MoveCursor(dir!!) else KeyAction.ReleaseCursor
                k.down -> KeyAction.Nav(dir!!, k.repeat > 0)
                else -> KeyAction.Consume
            }
            Key.OK -> when {
                s.nativeKeys -> KeyAction.Pass
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

    private fun fullscreen(k: KeyIn, s: KeyState, dir: Dir?): KeyAction {
        if (k.key == Key.BACK) return if (!k.down) KeyAction.ExitFullscreen else KeyAction.Consume
        if (s.fsControls) when (k.key) {
            Key.UP, Key.DOWN, Key.LEFT, Key.RIGHT -> return if (k.down) KeyAction.FsNav(dir!!) else KeyAction.Consume
            Key.OK -> return if (!k.down) KeyAction.FsOk else KeyAction.Consume
            else -> {}
        }
        if (k.down) when (k.key) {
            Key.UP, Key.DOWN -> return KeyAction.FsReveal
            Key.LEFT -> return KeyAction.Seek(-10)
            Key.RIGHT -> return KeyAction.Seek(10)
            Key.OK -> return KeyAction.VideoToggle
            else -> {}
        }
        // The page's own player also reacts to the key release (seek, toggle): swallow it so one press does one thing.
        if (!k.down) when (k.key) {
            Key.UP, Key.DOWN, Key.LEFT, Key.RIGHT, Key.OK -> return KeyAction.Consume
            else -> {}
        }
        return KeyAction.Pass
    }

    private fun dirOf(k: Key): Dir? = when (k) {
        Key.UP -> Dir.UP; Key.DOWN -> Dir.DOWN; Key.LEFT -> Dir.LEFT; Key.RIGHT -> Dir.RIGHT; else -> null
    }
}
