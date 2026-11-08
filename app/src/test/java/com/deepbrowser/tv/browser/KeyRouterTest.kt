package com.deepbrowser.tv.browser

import com.deepbrowser.tv.browser.KeyAction.*
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyRouterTest {
    private fun down(k: Key, repeat: Int = 0) = KeyIn(k, true, repeat)
    private fun up(k: Key) = KeyIn(k, false)
    private fun r(k: KeyIn, s: KeyState = KeyState()) = KeyRouter.route(k, s)

    // ---- Back
    @Test fun backFullscreenExitsOnUpOnly() {
        val s = KeyState(fullscreen = true, fsControls = true)
        assertEquals(Consume, r(down(Key.BACK), s)); assertEquals(ExitFullscreen, r(up(Key.BACK), s))
    }
    @Test fun backClosesPanelOrCommandBar() {
        val s = KeyState(overlayOpen = true)
        assertEquals(CloseOverlays, r(up(Key.BACK), s)); assertEquals(Consume, r(down(Key.BACK), s))
    }
    @Test fun overlayPassesOtherKeysToCompose() = assertEquals(Pass, r(down(Key.DOWN), KeyState(overlayOpen = true)))
    @Test fun backClosesReaderFirst() {
        val s = KeyState(readerOn = true)
        assertEquals(ExitReader, r(up(Key.BACK), s)); assertEquals(Consume, r(down(Key.BACK), s))
    }
    @Test fun readerUpDownScroll() {
        val s = KeyState(readerOn = true)
        assertEquals(ReaderScroll(-450), r(down(Key.UP), s)); assertEquals(ReaderScroll(450), r(down(Key.DOWN), s))
    }
    @Test fun backOnNativeSiteGoesStraightToHistory() = assertEquals(BackPage(true), r(up(Key.BACK), KeyState(nativeKeys = true)))
    @Test fun backOnPageAsksPageFirst() = assertEquals(BackPage(false), r(up(Key.BACK)))
    @Test fun backOnHomeIsNotOurs() = assertEquals(Pass, r(up(Key.BACK), KeyState(isHome = true)))
    @Test fun imeGetsAllKeys() = assertEquals(Pass, r(up(Key.BACK), KeyState(imeVisible = true)))
    @Test fun menuOpensCommandBarOnRelease() {
        assertEquals(OpenCommandBar, r(up(Key.MENU))); assertEquals(Consume, r(down(Key.MENU)))
    }

    // ---- OK
    @Test fun okTapOnReleaseArmsOnFirstPress() {
        assertEquals(OkDown, r(down(Key.OK))); assertEquals(Consume, r(down(Key.OK, repeat = 1)))
        assertEquals(OkUpSmart, r(up(Key.OK)))
    }
    @Test fun okLongPressSwallowsTheRelease() = assertEquals(OkUpLong, r(up(Key.OK), KeyState(okLongFired = true)))
    @Test fun okReleaseAfterLongPressOpenedBarIsSwallowed() {
        val s = KeyState(overlayOpen = true, okLongFired = true)
        assertEquals(OkUpLong, r(up(Key.OK), s))
        assertEquals(Consume, r(down(Key.OK, repeat = 3), s))
        assertEquals(Pass, r(up(Key.OK), KeyState(overlayOpen = true)))
    }
    @Test fun okInCursorModeClicks() = assertEquals(OkUpClick, r(up(Key.OK), KeyState(cursorMode = true)))
    @Test fun okFullscreenTogglesPlayWithoutControlsAndClicksWithThem() {
        assertEquals(VideoToggle, r(down(Key.OK), KeyState(fullscreen = true)))
        assertEquals(FsOk, r(up(Key.OK), KeyState(fullscreen = true, fsControls = true)))
    }

    // ---- D-pad
    @Test fun dpadPassesThroughOnNativeKeySites() {
        val s = KeyState(nativeKeys = true)
        assertEquals(Pass, r(down(Key.LEFT), s)); assertEquals(Pass, r(up(Key.OK), s)); assertEquals(Pass, r(down(Key.OK), s))
    }
    @Test fun dpadMovesFocusAndFlagsRepeat() {
        assertEquals(Nav(Dir.RIGHT, false), r(down(Key.RIGHT)))
        assertEquals(Nav(Dir.DOWN, true), r(down(Key.DOWN, repeat = 2)))
        assertEquals(Consume, r(up(Key.DOWN)))
    }
    @Test fun dpadInCursorModeMovesAndReleasesCursor() {
        val s = KeyState(cursorMode = true)
        assertEquals(MoveCursor(Dir.UP), r(down(Key.UP), s)); assertEquals(ReleaseCursor, r(up(Key.UP), s))
    }
    @Test fun homeDpadIsCompose() = assertEquals(Pass, r(down(Key.LEFT), KeyState(isHome = true)))
    @Test fun fullscreenSeekAndReveal() {
        val s = KeyState(fullscreen = true)
        assertEquals(Seek(-10), r(down(Key.LEFT), s)); assertEquals(Seek(10), r(down(Key.RIGHT), s))
        assertEquals(FsReveal, r(down(Key.UP), s)); assertEquals(Consume, r(up(Key.UP), s))
    }
    @Test fun fullscreenKeyReleasesNeverReachThePagePlayer() {
        val s = KeyState(fullscreen = true)
        for (k in listOf(Key.LEFT, Key.RIGHT, Key.UP, Key.DOWN, Key.OK)) assertEquals(Consume, r(up(k), s))
    }

    @Test fun fullscreenControlsNavigateThePlayerUi() =
        assertEquals(FsNav(Dir.LEFT), r(down(Key.LEFT), KeyState(fullscreen = true, fsControls = true)))
    @Test fun pageKeysScroll() {
        assertEquals(Page(true), r(down(Key.PAGE_DOWN))); assertEquals(Page(false), r(down(Key.PAGE_UP)))
    }
    @Test fun unknownKeyIsNotOurs() = assertEquals(Pass, r(down(Key.OTHER)))

    // ---- native layers own the remote in every navigation mode
    @Test fun nativeLayersGetDpadAndOkEvenInCursorMode() {
        listOf(KeyState(overlayOpen = true, cursorMode = true), KeyState(isHome = true, cursorMode = true)).forEach { s ->
            listOf(Key.UP, Key.DOWN, Key.LEFT, Key.RIGHT, Key.OK).forEach { k ->
                assertEquals(Pass, r(down(k), s)); assertEquals(Pass, r(up(k), s))
            }
        }
    }
    @Test fun okNeverArmsTheLongPressWhileANativeLayerOwnsTheRemote() {
        assertEquals(Pass, r(down(Key.OK), KeyState(overlayOpen = true))); assertEquals(Pass, r(down(Key.OK), KeyState(isHome = true)))
        assertEquals(OkDown, r(down(Key.OK))) // the page owner still arms it
    }
    @Test fun cursorModeOnAPageMovesClicksAndScrollsWithDpadOnly() {
        val s = KeyState(cursorMode = true)
        assertEquals(MoveCursor(Dir.DOWN), r(down(Key.DOWN, repeat = 5), s))
        assertEquals(OkDown, r(down(Key.OK), s)); assertEquals(OkUpClick, r(up(Key.OK), s))
        assertEquals(Pass, r(down(Key.UP), KeyState(cursorMode = true, nativeKeys = true)))
    }
}
