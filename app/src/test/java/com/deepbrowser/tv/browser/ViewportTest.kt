package com.deepbrowser.tv.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportTest {
    @Test fun layoutWidthFollowsSite() {
        assertEquals(Viewport.TV_LAYOUT_WIDTH, Viewport.layoutWidth("https://www.youtube.com/tv"))
        assertEquals(Viewport.LAYOUT_WIDTH, Viewport.layoutWidth("https://example.com/"))
        assertEquals(Viewport.LAYOUT_WIDTH, Viewport.layoutWidth(null))
    }

    @Test fun tweaksScriptUsesTheViewportWidths() {
        val js = java.io.File("src/main/assets/nav/tweaks.js").readText()
        assertTrue(js.contains("1280"))
        assertTrue(js.contains("1920"))
    }

    @Test fun scaleUnknownUntilMeasuredThenFollowsPage() {
        val v = Viewport()
        assertNull(v.initialScale())
        v.resize(1920, 1080); v.page("https://example.com/")
        assertEquals(150, v.initialScale())
        v.page("https://youtube.com/tv"); assertEquals(100, v.initialScale()); assertTrue(v.nativeKeys)
        v.page(null); assertFalse(v.nativeKeys)
    }

    @Test fun defaultsToTvScreenAndFactorUsesViewWidth() {
        val v = Viewport(); assertEquals(1920, v.width); assertEquals(1080, v.height)
        v.resize(960, 540); assertEquals(0.75f, v.factor(1280f), 0.001f)
        assertEquals(1f, Viewport.relative(1280), 0.001f)
    }
}
