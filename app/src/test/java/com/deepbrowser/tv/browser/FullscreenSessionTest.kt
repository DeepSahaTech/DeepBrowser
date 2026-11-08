package com.deepbrowser.tv.browser

import android.view.View
import android.webkit.WebChromeClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullscreenSessionTest {
    private class FakeSurface : FullscreenSurface {
        var open = false; var exits = 0
        override val active get() = open
        override val shown: View? get() = null // a real View cannot exist in a JVM test
        override fun show(v: View, cb: WebChromeClient.CustomViewCallback) { open = true }
        override fun exit() { if (open) { open = false; exits++ } }
    }

    private val surface = FakeSurface()
    private val chrome = ChromeState()
    private val clock = FakeScheduler()
    private val fs = FullscreenSession(surface, chrome, clock)

    @Test fun controlsHideSixSecondsAfterTheLastRequest() {
        surface.open = true
        chrome.apply(KeyAction.FsReveal); fs.armHide()
        clock.advance(5_999); assertTrue(chrome.fsControls)
        clock.advance(1); assertFalse(chrome.fsControls)
    }

    @Test fun keyActivityRearmsTheTimerExceptBack() {
        surface.open = true
        chrome.apply(KeyAction.FsReveal); fs.armHide()
        clock.advance(4_000); fs.onKey(Key.UP)
        clock.advance(4_000); assertTrue(chrome.fsControls)
        clock.advance(2_000); assertFalse(chrome.fsControls)
        chrome.apply(KeyAction.FsReveal); fs.armHide(); fs.onKey(Key.BACK)
        assertEquals(1, clock.pending)
    }

    @Test fun keysWithoutVisibleControlsOrOutsideFullscreenDoNotArm() {
        surface.open = true; fs.onKey(Key.UP); assertEquals(0, clock.pending)
        surface.open = false; chrome.apply(KeyAction.FsReveal); fs.onKey(Key.UP); assertEquals(0, clock.pending)
    }

    @Test fun requestExitLeavesFullscreenOnlyWhenThePageClosedNothing() {
        surface.open = true
        fs.requestExit { it("\"closed\"") }
        assertTrue(fs.active); assertEquals(0, surface.exits)
        fs.requestExit { it("\"none\"") }
        assertFalse(fs.active); assertEquals(1, surface.exits)
    }

    @Test fun exitWhenNotActiveIsANoOp() { fs.exit(); assertEquals(0, surface.exits) }
}
