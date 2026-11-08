package com.deepbrowser.tv.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.deepbrowser.tv.data.NavMode

/**
 * Pure UI-mode state of the browser: overlays (command bar, device info, panels), reader on/off, fullscreen player
 * controls, OK long-press, cursor mode and zoom. The controller only reads/publishes it and runs the timers/Android calls.
 */
class ChromeState {
    var commandBar by mutableStateOf(false)
    var info by mutableStateOf(false)
    var panel by mutableStateOf<Panel?>(null)
    var reader by mutableStateOf(false); private set
    var navMode by mutableStateOf(NavMode.FOCUS) // smart D-pad focus is the default; the cursor is optional
    var zoomPercent by mutableIntStateOf(100); private set
    var fsControls = false; private set
    var okLong = false; private set

    val overlayOpen: Boolean get() = commandBar || info || panel != null
    /** The focus ring is drawn on a page only when no overlay owns the remote. */
    val ringVisible: Boolean get() = navMode == NavMode.FOCUS && !overlayOpen
    val pointerVisible: Boolean get() = navMode == NavMode.POINTER && !overlayOpen

    fun owner(isHome: Boolean) = FocusOwner.of(panel != null, commandBar, info, isHome)

    fun keyState(fullscreen: Boolean, imeVisible: Boolean, isHome: Boolean, nativeKeys: Boolean) = KeyState(
        fullscreen = fullscreen, fsControls = fsControls, imeVisible = !fullscreen && imeVisible,
        overlayOpen = overlayOpen, isHome = isHome, readerOn = reader,
        nativeKeys = nativeKeys, cursorMode = navMode == NavMode.POINTER, okLongFired = okLong,
    )

    /** Key activity in fullscreen keeps the site's controls up: true = re-arm the [FS_CONTROLS_MS] hide timer. */
    fun keepsControls(key: Key, fullscreen: Boolean): Boolean = fullscreen && fsControls && key != Key.BACK

    /** The state part of every [KeyAction]; the controller runs the Android part afterwards. */
    fun apply(a: KeyAction) {
        when (a) {
            KeyAction.FsReveal -> fsControls = true
            KeyAction.CloseOverlays -> closeOverlays()
            KeyAction.OpenCommandBar -> commandBar = true
            KeyAction.ExitReader -> reader = false
            KeyAction.OkDown -> okLong = false
            KeyAction.OkUpLong, KeyAction.OkUpClick, KeyAction.OkUpSmart -> okLong = false
            else -> {}
        }
    }

    fun hideControls() { fsControls = false }
    /** The [OK_LONG_MS] timer fired: holding OK opens the command bar. */
    fun okLongFired() { okLong = true; commandBar = true }

    fun closeOverlays() { commandBar = false; info = false; panel = null }
    fun openPanel(p: Panel) { commandBar = false; info = false; panel = p }
    fun closePanel() { panel = null }
    fun showInfo() { commandBar = false; info = true }

    /** A load was requested from the UI/companion. */
    fun loadRequested() { commandBar = false; panel = null }
    /** A new document replaced the page: it has no reader overlay. */
    fun documentChanged() { reader = false }
    fun wentHome() { commandBar = false; reader = false }
    /** Another tab became live. */
    fun tabEntered() { commandBar = false; panel = null; reader = false }

    /** @return false when there is nothing to toggle (Home). */
    fun beginReader(isHome: Boolean): Boolean { commandBar = false; return !isHome }
    /** Page answered the toggle with "on" / "off" / "none". @return false when no article was found. */
    fun readerResult(r: String?): Boolean { reader = PageApi.readerIsOn(r); return PageApi.readerFoundArticle(r) }

    fun zoom(delta: Int): Int { zoomPercent = (zoomPercent + delta).coerceIn(MIN_ZOOM, MAX_ZOOM); return zoomPercent }

    companion object {
        /** Seconds per timeline press after [run] quick repeats: 10, 10, 10, 20, 30, 60. */
        fun seekStep(run: Int): Int = when { run < 3 -> 10; run < 5 -> 20; run < 8 -> 30; else -> 60 }
        const val OK_LONG_MS = 600L
        const val FS_CONTROLS_MS = 6000L
        const val MIN_ZOOM = 50
        const val MAX_ZOOM = 250
    }
}
