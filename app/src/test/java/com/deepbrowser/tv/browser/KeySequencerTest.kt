package com.deepbrowser.tv.browser

import com.deepbrowser.tv.data.NavMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeySequencerTest {
    private class Recorder : KeyEffects {
        val log = ArrayList<String>()
        override fun exitFullscreen() { log += "exitFs" }
        override fun forward() { log += "forward" }
        override fun enter() { log += "enter" }
        override fun exitReader() { log += "exitReader" }
        override fun readerScroll(delta: Int) { log += "scroll:$delta" }
        override fun back(native: Boolean) { log += "back:$native" }
        override fun movePointer(dir: Dir) { log += "ptr:$dir" }
        override fun releasePointer(dir: Dir?) { log += "release:$dir" }
        override fun clickPointer() { log += "click" }
        override fun page(down: Boolean) { log += "page:$down" }
    }

    private val fx = Recorder()
    private val chrome = ChromeState()
    private val clock = FakeScheduler()
    private var ctx = KeyContext(fullscreen = false, imeVisible = false, isHome = false, nativeKeys = false)
    private val keys = KeySequencer(chrome, fx, clock) { ctx }

    private fun down(k: Key, repeat: Int = 0) = keys.onKey(KeyIn(k, true, repeat))
    private fun up(k: Key) = keys.onKey(KeyIn(k, false))

    @Test fun okReleaseOnAPageSendsEnter() {
        assertTrue(down(Key.OK)); assertTrue(up(Key.OK))
        assertEquals(listOf("enter"), fx.log); assertEquals(0, clock.pending)
    }

    @Test fun shortOkInPointerModeClicks() {
        chrome.navMode = NavMode.POINTER
        down(Key.OK); clock.advance(100); up(Key.OK)
        assertEquals(listOf("click"), fx.log); assertFalse(chrome.commandBar)
    }

    @Test fun longOkOpensTheBarAndItsReleaseDoesNothing() {
        down(Key.OK); clock.advance(ChromeState.OK_LONG_MS)
        assertTrue(chrome.commandBar); assertTrue(chrome.okLong)
        assertTrue(down(Key.OK, repeat = 1)) // key repeat swallowed
        assertTrue(up(Key.OK))
        assertFalse(chrome.okLong); assertTrue(fx.log.isEmpty()); assertEquals(0, clock.pending)
    }

    @Test fun releasingBeforeTheLongPressCancelsTheTimer() {
        down(Key.OK); clock.advance(ChromeState.OK_LONG_MS - 1); up(Key.OK); clock.advance(10)
        assertFalse(chrome.commandBar)
    }

    @Test fun dpadIsForwardedOnPressAndRelease() {
        down(Key.UP); up(Key.UP); down(Key.LEFT, repeat = 2)
        assertEquals(listOf("forward", "forward", "forward"), fx.log)
    }

    @Test fun pointerModeHoldsThenReleasesTheSameDirection() {
        chrome.navMode = NavMode.POINTER
        down(Key.RIGHT); up(Key.RIGHT)
        down(Key.UP); down(Key.DOWN); up(Key.UP)
        assertEquals(listOf("ptr:RIGHT", "release:RIGHT", "ptr:UP", "ptr:DOWN", "release:UP"), fx.log)
    }

    @Test fun passedKeysAreNotConsumedAndRunNoEffect() {
        ctx = ctx.copy(isHome = true)
        assertFalse(down(Key.UP)); assertTrue(fx.log.isEmpty())
    }

    @Test fun menuReleaseOpensTheBarAndOverlayBackCloses() {
        assertTrue(down(Key.MENU)); assertFalse(chrome.commandBar)
        up(Key.MENU); assertTrue(chrome.commandBar)
        up(Key.BACK); assertFalse(chrome.commandBar); assertTrue(fx.log.isEmpty())
    }

    @Test fun fullscreenBackExitsAndOkSendsEnter() {
        ctx = ctx.copy(fullscreen = true)
        down(Key.RIGHT); up(Key.RIGHT); down(Key.BACK); down(Key.OK); up(Key.OK); up(Key.BACK)
        assertEquals(listOf("forward", "forward", "enter", "exitFs"), fx.log)
        assertEquals(0, clock.pending)
    }

    @Test fun nativeSitesGetDpadAndOkForwardedNotEnter() {
        ctx = ctx.copy(nativeKeys = true)
        down(Key.LEFT); down(Key.OK); up(Key.OK)
        assertEquals(listOf("forward", "forward", "forward"), fx.log); assertEquals(0, clock.pending)
    }

    @Test fun readerScrollsAndBackLeavesIt() {
        chrome.beginReader(false); chrome.readerResult("\"on\"")
        down(Key.DOWN); down(Key.UP); up(Key.BACK)
        assertEquals(listOf("scroll:450", "scroll:-450", "exitReader"), fx.log); assertFalse(chrome.reader)
    }

    @Test fun backOnAPageAsksThePageUnlessNative() {
        up(Key.BACK); ctx = ctx.copy(nativeKeys = true); up(Key.BACK)
        assertEquals(listOf("back:false", "back:true"), fx.log)
    }

    @Test fun pageKeysScroll() { down(Key.PAGE_DOWN); down(Key.PAGE_UP); assertEquals(listOf("page:true", "page:false"), fx.log) }
}
