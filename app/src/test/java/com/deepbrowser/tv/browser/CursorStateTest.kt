package com.deepbrowser.tv.browser

import com.deepbrowser.tv.device.CapabilityProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorStateTest {
    private fun cursor() = CursorState(1920f, 1080f)

    @Test fun startsCentred() { val c = cursor(); assertEquals(960f, c.x, 0.01f); assertEquals(540f, c.y, 0.01f) }

    @Test fun movesRight() { val c = cursor(); c.step(1, 0); assertTrue(c.x > 960f); assertEquals(540f, c.y, 0.01f) }

    @Test fun acceleratesWhileHeldAndResetsOnRelease() {
        val c = cursor()
        val s0 = c.currentSpeed
        repeat(5) { c.step(1, 0) }
        assertTrue(c.currentSpeed > s0)
        c.release()
        assertEquals(s0, c.currentSpeed, 0.01f)
    }

    @Test fun neverLeavesScreen() {
        val c = cursor()
        repeat(200) { c.step(-1, -1) }
        assertEquals(0f, c.x, 0.01f); assertEquals(0f, c.y, 0.01f)
        repeat(400) { c.step(1, 1) }
        assertEquals(1919f, c.x, 0.01f); assertEquals(1079f, c.y, 0.01f)
    }

    @Test fun reportsEdgeForScrolling() {
        val c = cursor()
        var edge = Edge.NONE
        repeat(100) { edge = c.step(0, 1) }
        assertEquals(Edge.BOTTOM, edge)
        repeat(100) { edge = c.step(0, -1) }
        assertEquals(Edge.TOP, edge)
    }

    @Test fun resizeKeepsCursorInside() {
        val c = cursor(); c.moveTo(1900f, 1000f); c.resize(1280f, 720f)
        assertTrue(c.x <= 1279f && c.y <= 719f)
    }

    @Test fun tabLimitsScaleWithMemory() {
        assertEquals(2, CapabilityProbe.maxLiveTabs(lowRam = true, memoryClassMb = 512))
        assertEquals(2, CapabilityProbe.maxLiveTabs(lowRam = false, memoryClassMb = 192))
        assertEquals(3, CapabilityProbe.maxLiveTabs(lowRam = false, memoryClassMb = 256))
        assertEquals(4, CapabilityProbe.maxLiveTabs(lowRam = false, memoryClassMb = 512))
    }
}
