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

    @Test fun velocityRampsWithHoldTime() {
        val c = cursor()
        assertTrue(c.velocityAt(0f) < c.velocityAt(400f))
        assertTrue(c.velocityAt(400f) < c.velocityAt(900f))
        assertEquals(c.velocityAt(900f), c.velocityAt(5000f), 0.01f)
    }

    @Test fun tickIsFrameRateIndependent() {
        val a = cursor(); val b = cursor()
        a.hold(Dir.RIGHT, true); b.hold(Dir.RIGHT, true)
        a.tick(32f)
        b.tick(16f); b.tick(16f)
        assertEquals(a.x, b.x, 3f)
    }

    @Test fun releaseStopsAndResetsRamp() {
        val c = cursor(); c.hold(Dir.RIGHT, true); repeat(30) { c.tick(16f) }
        c.hold(Dir.RIGHT, false)
        val x = c.x; c.tick(16f)
        assertEquals(x, c.x, 0.001f)
        c.hold(Dir.RIGHT, true); c.tick(16f)
        assertTrue(c.x - x < 6f)
    }

    @Test fun diagonalFromTwoHeldKeysIsNotFaster() {
        val d = cursor(); d.hold(Dir.RIGHT, true); d.hold(Dir.DOWN, true); d.tick(100f)
        val s = cursor(); s.hold(Dir.RIGHT, true); s.tick(100f)
        val diag = Math.hypot((d.x - 960f).toDouble(), (d.y - 540f).toDouble())
        assertEquals((s.x - 960f).toDouble(), diag, 0.5)
    }

    @Test fun oppositeKeysCancel() {
        val c = cursor(); c.hold(Dir.LEFT, true); c.hold(Dir.RIGHT, true)
        c.tick(100f); assertEquals(960f, c.x, 0.001f)
    }

    @Test fun tickReportsEdge() {
        val c = cursor(); c.hold(Dir.DOWN, true)
        var e = Edge.NONE; repeat(200) { e = c.tick(16f) }
        assertEquals(Edge.BOTTOM, e)
    }

    @Test fun speedScaleScalesVelocity() {
        val c = cursor(); val base = c.velocityAt(300f); c.speedScale = 2f
        assertEquals(base * 2, c.velocityAt(300f), 0.01f)
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
