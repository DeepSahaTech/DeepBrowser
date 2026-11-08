package com.deepbrowser.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerLogicTest {
    @Test fun stepGrowsEveryFourRepeatsAndCaps() {
        assertEquals(10_000L, PlayerLogic.seekStepMs(0))
        assertEquals(10_000L, PlayerLogic.seekStepMs(3))
        assertEquals(20_000L, PlayerLogic.seekStepMs(4))
        assertEquals(60_000L, PlayerLogic.seekStepMs(20))
        assertEquals(60_000L, PlayerLogic.seekStepMs(500))
    }

    @Test fun seekTargetClampsToBounds() {
        assertEquals(0L, PlayerLogic.seekTarget(5_000, 10_000, true, 100_000))
        assertEquals(100_000L, PlayerLogic.seekTarget(95_000, 10_000, false, 100_000))
        assertEquals(15_000L, PlayerLogic.seekTarget(5_000, 10_000, false, 100_000))
        assertEquals(1_005_000L, PlayerLogic.seekTarget(1_000_000, 5_000, false, null))
    }

    @Test fun videoLabels() {
        assertEquals("720p (2500 kbps)", PlayerLogic.videoLabel(720, 2_500_000, 3))
        assertEquals("1080p", PlayerLogic.videoLabel(1080, 0, 3))
        assertEquals("Track 2", PlayerLogic.videoLabel(0, 900_000, 2))
    }

    @Test fun namedLabelsPreferLabelThenLanguage() {
        assertEquals("English", PlayerLogic.namedLabel("English", "en", 1))
        assertEquals("en", PlayerLogic.namedLabel(null, "en", 1))
        assertEquals("Track 4", PlayerLogic.namedLabel(null, null, 4))
    }
}
