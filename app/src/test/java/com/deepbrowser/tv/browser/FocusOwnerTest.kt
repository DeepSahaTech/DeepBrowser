package com.deepbrowser.tv.browser

import com.deepbrowser.tv.browser.FocusOwner.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusOwnerTest {
    private fun o(panel: Boolean = false, bar: Boolean = false, info: Boolean = false, home: Boolean = false) =
        FocusOwner.of(panel, bar, info, home)

    @Test fun pageOwnsWhenNothingIsOpen() = assertEquals(PAGE, o())
    @Test fun homeBeatsPage() = assertEquals(HOME, o(home = true))
    @Test fun infoBeatsHome() = assertEquals(INFO, o(info = true, home = true))
    @Test fun commandBarBeatsInfo() = assertEquals(COMMAND_BAR, o(bar = true, info = true, home = true))
    @Test fun panelBeatsEverything() = assertEquals(PANEL, o(panel = true, bar = true, info = true, home = true))
    @Test fun onlyThePageIsNotCompose() {
        assertFalse(PAGE.isCompose)
        listOf(PANEL, COMMAND_BAR, INFO, HOME).forEach { assertTrue(it.isCompose) }
    }

    @Test fun chromeStateReportsTheOwner() {
        val c = ChromeState()
        assertEquals(PAGE, c.owner(false)); assertEquals(HOME, c.owner(true))
        c.commandBar = true; assertEquals(COMMAND_BAR, c.owner(true))
        c.openPanel(Panel.Settings); assertEquals(PANEL, c.owner(false))
        c.closeOverlays(); assertEquals(PAGE, c.owner(false))
    }
}
