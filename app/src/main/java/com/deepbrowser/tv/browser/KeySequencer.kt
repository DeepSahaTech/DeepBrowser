package com.deepbrowser.tv.browser

/** What the world looks like for one key event; read fresh per key (the keyboard and fullscreen come and go). */
data class KeyContext(val fullscreen: Boolean, val imeVisible: Boolean, val isHome: Boolean, val nativeKeys: Boolean)

/** Adapter seam: the Android/page side of every [KeyAction]. The sequencer decides *when*, this does the *how*. */
interface KeyEffects {
    fun exitFullscreen()
    /** The key event the page should see (D-pad), delivered to the WebView or the fullscreen view. */
    fun forward()
    /** A real Enter down and up to the same view: OK released on a page. */
    fun enter()
    fun exitReader()
    fun readerScroll(delta: Int)
    fun back(native: Boolean)
    fun movePointer(dir: Dir)
    /** [dir] null = stop the pointer in every direction. */
    fun releasePointer(dir: Dir?)
    fun clickPointer()
    fun page(down: Boolean)
}

/**
 * The whole key-to-effect sequence in one place: [KeyRouter] decides, [ChromeState] takes the state part, then the
 * [KeyEffects] run. Owns what must outlive a single event: the OK long-press timer and which key was just released
 * (the router only says "release the pointer").
 */
class KeySequencer(
    private val chrome: ChromeState,
    private val effects: KeyEffects,
    private val timer: Scheduler,
    private val context: () -> KeyContext,
) {
    private var released: Key? = null
    private val okLongPress = Runnable { chrome.okLongFired() }

    /** @return true if the key was consumed. */
    fun onKey(k: KeyIn): Boolean {
        released = if (k.down) null else k.key
        val c = context()
        val state = chrome.keyState(c.fullscreen, c.imeVisible, c.isHome, c.nativeKeys)
        return run(KeyRouter.route(k, state))
    }

    private fun run(a: KeyAction): Boolean {
        chrome.apply(a)
        when (a) {
            KeyAction.Pass -> return false
            KeyAction.Consume, KeyAction.CloseOverlays, KeyAction.OpenCommandBar -> {}
            KeyAction.Forward -> effects.forward()
            KeyAction.ExitFullscreen -> effects.exitFullscreen()
            KeyAction.ExitReader -> effects.exitReader()
            is KeyAction.ReaderScroll -> effects.readerScroll(a.delta)
            is KeyAction.BackPage -> effects.back(a.native)
            is KeyAction.MoveCursor -> effects.movePointer(a.dir)
            KeyAction.ReleaseCursor -> effects.releasePointer(released?.let(::dirOf))
            KeyAction.OkDown -> timer.postDelayed(okLongPress, ChromeState.OK_LONG_MS) // tap on release; holding OK opens the command bar
            KeyAction.OkUpLong, KeyAction.OkUpClick, KeyAction.OkUpSmart -> {
                timer.cancel(okLongPress)
                when (a) {
                    KeyAction.OkUpClick -> effects.clickPointer()
                    KeyAction.OkUpSmart -> effects.enter()
                    else -> {}
                }
            }
            is KeyAction.Page -> effects.page(a.down)
        }
        return true
    }

    private fun dirOf(k: Key): Dir? = when (k) {
        Key.UP -> Dir.UP; Key.DOWN -> Dir.DOWN; Key.LEFT -> Dir.LEFT; Key.RIGHT -> Dir.RIGHT; else -> null
    }
}
