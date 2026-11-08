package com.deepbrowser.tv.browser

/** What the world looks like for one key event; read fresh per key (the keyboard and fullscreen come and go). */
data class KeyContext(val fullscreen: Boolean, val imeVisible: Boolean, val isHome: Boolean, val nativeKeys: Boolean)

/** Adapter seam: the Android/page side of every [KeyAction]. The sequencer decides *when*, this does the *how*. */
interface KeyEffects {
    /** Any key event, before routing (keeps fullscreen controls alive). */
    fun keyTouched(key: Key)
    fun exitFullscreen()
    fun fsReveal()
    fun move(dir: Dir)
    fun fsOk()
    fun seek(seconds: Int)
    fun toggleVideo()
    fun exitReader()
    fun readerScroll(delta: Int)
    fun back(native: Boolean)
    fun movePointer(dir: Dir)
    /** [dir] null = stop the pointer in every direction. */
    fun releasePointer(dir: Dir?)
    fun clickPointer()
    fun smartOk()
    fun page(down: Boolean)
}

/**
 * The whole key-to-effect sequence in one place: [KeyRouter] decides, [ChromeState] takes the state part, then the
 * [KeyEffects] run. Owns what must outlive a single event: the OK long-press timer, which key was just released
 * (the router only says "release the pointer") and the quick-repeat run that grows the timeline seek step.
 */
class KeySequencer(
    private val chrome: ChromeState,
    private val effects: KeyEffects,
    private val timer: Scheduler,
    private val now: () -> Long,
    private val context: () -> KeyContext,
) {
    private var released: Key? = null
    private val okLongPress = Runnable { chrome.okLongFired() }
    private var lastSeekAt = 0L
    private var seekRun = 0

    /** @return true if the key was consumed. */
    fun onKey(k: KeyIn): Boolean {
        effects.keyTouched(k.key)
        released = if (k.down) null else k.key
        val c = context()
        val state = chrome.keyState(c.fullscreen, c.imeVisible, c.isHome, c.nativeKeys)
        return run(KeyRouter.route(k, state))
    }

    /** Timeline Left/Right: 10 s per press, growing while the key repeats. */
    fun seekHeld(forward: Boolean) {
        val t = now()
        seekRun = if (t - lastSeekAt < SEEK_RUN_MS) seekRun + 1 else 0
        lastSeekAt = t
        val step = ChromeState.seekStep(seekRun)
        effects.seek(if (forward) step else -step)
    }

    private fun run(a: KeyAction): Boolean {
        chrome.apply(a)
        when (a) {
            KeyAction.Pass -> return false
            KeyAction.Consume, KeyAction.CloseOverlays, KeyAction.OpenCommandBar -> {}
            KeyAction.ExitFullscreen -> effects.exitFullscreen()
            KeyAction.FsReveal -> effects.fsReveal()
            is KeyAction.FsNav -> effects.move(a.dir)
            KeyAction.FsOk -> effects.fsOk()
            is KeyAction.Seek -> effects.seek(a.seconds)
            KeyAction.VideoToggle -> effects.toggleVideo()
            KeyAction.ExitReader -> effects.exitReader()
            is KeyAction.ReaderScroll -> effects.readerScroll(a.delta)
            is KeyAction.BackPage -> effects.back(a.native)
            is KeyAction.Nav -> effects.move(a.dir)
            is KeyAction.MoveCursor -> effects.movePointer(a.dir)
            KeyAction.ReleaseCursor -> effects.releasePointer(released?.let(::dirOf))
            KeyAction.OkDown -> timer.postDelayed(okLongPress, ChromeState.OK_LONG_MS) // tap on release; holding OK opens the command bar
            KeyAction.OkUpLong, KeyAction.OkUpClick, KeyAction.OkUpSmart -> {
                timer.cancel(okLongPress)
                if (a == KeyAction.OkUpClick) effects.clickPointer() else if (a == KeyAction.OkUpSmart) effects.smartOk()
            }
            is KeyAction.Page -> effects.page(a.down)
        }
        return true
    }

    private fun dirOf(k: Key): Dir? = when (k) {
        Key.UP -> Dir.UP; Key.DOWN -> Dir.DOWN; Key.LEFT -> Dir.LEFT; Key.RIGHT -> Dir.RIGHT; else -> null
    }

    private companion object { const val SEEK_RUN_MS = 500 }
}
