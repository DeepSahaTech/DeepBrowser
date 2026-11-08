package com.deepbrowser.tv.browser

import com.deepbrowser.tv.data.NavMode
import com.deepbrowser.tv.data.UaMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChromeStateTest {
    private val c = ChromeState()

    @Test fun openPanelClosesBarAndInfo() {
        c.commandBar = true; c.info = true; c.openPanel(Panel.Tabs)
        assertFalse(c.commandBar); assertFalse(c.info); assertEquals(Panel.Tabs, c.panel); assertTrue(c.overlayOpen)
        c.apply(KeyAction.CloseOverlays); assertFalse(c.overlayOpen)
    }

    @Test fun okLongPressOpensBarAndReleaseResets() {
        c.apply(KeyAction.OkDown); assertFalse(c.okLong)
        c.okLongFired(); assertTrue(c.okLong); assertTrue(c.commandBar)
        assertTrue(c.keyState(false, false, false, false).okLongFired)
        c.apply(KeyAction.OkUpLong); assertFalse(c.okLong)
    }

    @Test fun fullscreenControlsStayAliveOnKeysExceptBack() {
        assertFalse(c.keepsControls(Key.UP, true))
        c.apply(KeyAction.FsReveal)
        assertTrue(c.keepsControls(Key.UP, true)); assertFalse(c.keepsControls(Key.BACK, true)); assertFalse(c.keepsControls(Key.UP, false))
        c.hideControls(); assertFalse(c.keyState(true, false, false, false).fsControls)
    }

    @Test fun readerRules() {
        assertFalse(c.beginReader(isHome = true))
        c.commandBar = true; assertTrue(c.beginReader(false)); assertFalse(c.commandBar)
        assertTrue(c.readerResult("\"on\"")); assertTrue(c.reader)
        c.documentChanged(); assertFalse(c.reader)
        assertFalse(c.readerResult("\"none\"")); assertFalse(c.reader)
        c.readerResult("\"on\""); c.apply(KeyAction.ExitReader); assertFalse(c.reader)
        c.readerResult("\"on\""); c.tabEntered(); assertFalse(c.reader)
        c.readerResult("\"on\""); c.wentHome(); assertFalse(c.reader)
    }

    @Test fun loadAndTabSwitchDismissMenus() {
        c.commandBar = true; c.panel = Panel.History; c.loadRequested(); assertFalse(c.overlayOpen)
        c.panel = Panel.Tabs; c.tabEntered(); assertEquals(null, c.panel)
        c.showInfo(); assertTrue(c.info)
    }

    @Test fun imeIgnoredInFullscreenAndKeyStateReflectsModes() {
        c.navMode = NavMode.POINTER; c.readerResult("\"on\"")
        val s = c.keyState(fullscreen = true, imeVisible = true, isHome = false, nativeKeys = true)
        assertFalse(s.imeVisible); assertTrue(s.cursorMode); assertTrue(s.readerOn); assertTrue(s.nativeKeys)
    }

    @Test fun zoomIsClamped() {
        assertEquals(ChromeState.MAX_ZOOM, c.zoom(1000)); assertEquals(ChromeState.MIN_ZOOM, c.zoom(-1000)); assertEquals(60, c.zoom(10))
    }

    @Test fun uaCyclesThroughAllModes() {
        assertEquals(UaMode.MOBILE, UaMode.TV.next()); assertEquals(UaMode.TV, UaMode.DESKTOP.next())
    }

    @Test fun schemeAllowList() {
        assertTrue(UrlResolver.isLoadableScheme("HTTPS")); assertTrue(UrlResolver.isLoadableScheme("about"))
        assertFalse(UrlResolver.isLoadableScheme("intent")); assertFalse(UrlResolver.isLoadableScheme(null))
    }

    @Test fun seekStepGrowsWithRepeats() {
        assertEquals(10, ChromeState.seekStep(0)); assertEquals(20, ChromeState.seekStep(3))
        assertEquals(30, ChromeState.seekStep(6)); assertEquals(60, ChromeState.seekStep(20))
    }
}
