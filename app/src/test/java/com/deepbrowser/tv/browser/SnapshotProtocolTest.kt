package com.deepbrowser.tv.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotProtocolTest {
    private fun item(id: Int, l: Float, t: Float, w: Float = 100f, h: Float = 50f) = NavItem(id, Bounds(l, t, l + w, t + h))
    private fun decide(items: List<NavItem>, cur: Int, d: Dir, anchor: Float = Float.NaN, car: List<Bounds> = emptyList(), vw: Int = 1280) =
        Spatial.decide(items, items.first { it.id == cur }, d, anchor, car, vw)
    private fun moved(r: NavResult) = (r as NavResult.Move).item.id

    // ---- scale + parser
    @Test fun scaleFactorGuardsZero() {
        assertEquals(1.5f, Viewport.factor(1920, 1280f), 0.001f)
        assertEquals(1f, Viewport.factor(0, 1f), 0.001f)
        assertEquals(150, Viewport.initialScalePercent(1920, 1280))
    }

    @Test fun parserScalesCssToView() {
        val s = SnapshotParser.parse("""{"iw":960,"ih":540,"cur":7,"car":[[10,20,30,40]],"items":[[7,100,50,200,60]]}""", 1920)!!
        assertEquals(2f, 1920 / s.cssWidth, 0.001f); assertEquals(1080f, s.viewH, 0.01f); assertEquals(7, s.pageCur)
        assertEquals(Bounds(200f, 100f, 600f, 220f), s.items[0].box)
        assertEquals(Bounds(20f, 40f, 80f, 120f), s.carousels[0])
    }

    @Test fun parserRejectsGarbageAndDefaultsMissingFields() {
        assertNull(SnapshotParser.parse("not json", 1920))
        val s = SnapshotParser.parse("{}", 1280)!!
        assertEquals(1280f, s.cssWidth, 0.01f); assertEquals(-1, s.pageCur); assertTrue(s.items.isEmpty())
        assertNotNull(s)
    }

    @Test fun curRectUsesTheSameScale() {
        val b = Viewport.toView(10f, 20f, 30f, 40f, Viewport.factor(1920, 1280f))
        assertEquals(Bounds(15f, 30f, 60f, 90f), b)
    }

    // ---- row navigation
    private val row = listOf(item(1, 0f, 100f), item(2, 150f, 100f), item(3, 300f, 100f), item(9, 150f, 400f))

    @Test fun rightMovesAlongTheRowNotToTheRowBelow() {
        assertEquals(2, moved(decide(row, 1, Dir.RIGHT))); assertEquals(3, moved(decide(row, 2, Dir.RIGHT)))
    }
    @Test fun rowEndWithNothingBesideItScrolls() = assertEquals(NavResult.None, decide(row, 3, Dir.RIGHT))
    @Test fun leftNeverJumpsToAnotherRow() = assertEquals(NavResult.None, decide(row, 9, Dir.LEFT))
    @Test fun downGoesToTheRowBelowAndUpComesBack() {
        assertEquals(9, moved(decide(row, 2, Dir.DOWN))); assertEquals(2, moved(decide(row, 9, Dir.UP)))
    }

    // ---- column memory
    @Test fun anchorKeepsYourColumnThroughANarrowRow() {
        val items = listOf(item(1, 0f, 0f, w = 400f), item(2, 20f, 200f, w = 60f), item(3, 300f, 400f, w = 100f), item(4, 20f, 400f, w = 60f))
        // anchored at x=350 (from item 1's right side): down from 2 must prefer the item under x=350
        assertEquals(3, moved(decide(items, 2, Dir.DOWN, anchor = 350f)))
        // without memory it would pick the one directly below (4)
        assertEquals(4, moved(decide(items, 2, Dir.DOWN)))
    }

    // ---- carousel
    @Test fun rowEndNearSliderDotsSwitchesSlide() {
        val dots = listOf(Bounds(200f, 160f, 300f, 180f))
        assertEquals(NavResult.CarouselSwitch, decide(row, 3, Dir.RIGHT, car = dots))
    }
    @Test fun farSliderDotsAreIgnored() {
        val dots = listOf(Bounds(200f, 900f, 300f, 920f))
        assertEquals(NavResult.None, decide(row, 3, Dir.RIGHT, car = dots))
    }
    @Test fun carouselRangeScalesWithViewWidth() {
        val dots = listOf(Bounds(200f, 350f, 300f, 370f)) // 200px below the row: out at 1280, inside at 2560
        assertEquals(NavResult.None, decide(row, 3, Dir.RIGHT, car = dots, vw = 1280))
        assertEquals(NavResult.CarouselSwitch, decide(row, 3, Dir.RIGHT, car = dots, vw = 2560))
    }

    // ---- vanished focus
    @Test fun focusCarriesToTheItemNowWhereItWas() {
        val prev = item(1, 100f, 100f)
        val now = listOf(item(5, 110f, 100f), item(6, 900f, 100f))
        assertEquals(5, Spatial.carry(prev, now, 1280)!!.id)
    }
    @Test fun noCarryWhenNothingIsClose() = assertNull(Spatial.carry(item(1, 0f, 0f), listOf(item(2, 1200f, 600f)), 1280))

    // ---- initial focus
    @Test fun initialSkipsTheHeaderBand() {
        val items = listOf(item(1, 0f, 10f), item(2, 400f, 300f), item(3, 100f, 500f))
        assertEquals(2, Spatial.initial(items, 720f)!!.id)
    }
    @Test fun initialFallsBackToHeaderWhenThatIsAllThereIs() =
        assertEquals(1, Spatial.initial(listOf(item(1, 0f, 10f)), 720f)!!.id)
}
