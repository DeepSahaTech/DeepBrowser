package com.deepbrowser.tv.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayRulesTest {
    @Test fun scamTextMatchesKnownLures() {
        listOf("Please confirm you are NOT A ROBOT", "Scan the QR code", "scan the qr", "Open camera: QR-code with your phone",
            "Verify that you are a human", "verify you are human", "Click Allow to continue", "press allow")
            .forEach { assertTrue(it, OverlayRules.isScamText(it)) }
    }

    @Test fun scamTextRejectsNormalOrOversized() {
        assertFalse(OverlayRules.isScamText(""))
        assertFalse(OverlayRules.isScamText("Allowed formats: mp4. Robotics club news."))
        assertFalse(OverlayRules.isScamText("not a robot " + "x".repeat(OverlayRules.MAX_TEXT)))
        assertTrue(OverlayRules.isScamText("not a robot" + "x".repeat(OverlayRules.MAX_TEXT - 12)))
    }

    @Test fun overlayIframeNeedsFixedMaxZAndBlankSrc() {
        val z = OverlayRules.MIN_IFRAME_Z
        assertTrue(OverlayRules.isOverlayIframe("fixed", 2147483647L, ""))
        assertTrue(OverlayRules.isOverlayIframe("fixed", z, "about:blank"))
        assertTrue(OverlayRules.isOverlayIframe("fixed", z, "javascript:void(0)"))
        assertFalse(OverlayRules.isOverlayIframe("fixed", z - 1, ""))
        assertFalse(OverlayRules.isOverlayIframe("absolute", z, ""))
        assertFalse(OverlayRules.isOverlayIframe("fixed", null, ""))
        assertFalse(OverlayRules.isOverlayIframe("fixed", z, "https://player.example/embed"))
    }

    @Test fun clickTrapThresholdsAndVideoExemption() {
        assertTrue(OverlayRules.isClickTrap(1600.0, 900.0, 1920.0, 1080.0, false))
        assertFalse(OverlayRules.isClickTrap(1536.0, 864.0, 1920.0, 1080.0, false)) // exactly 80%: not over
        assertFalse(OverlayRules.isClickTrap(1920.0, 1080.0, 1920.0, 1080.0, true)) // contains video
    }

    @Test fun scriptIsMergedIntoOneObserverAndOneTimer() {
        val js = AdScript.SCRIPT
        assertEquals(1, Regex("MutationObserver\\(").findAll(js).count())
        assertEquals(2, Regex("setInterval\\(").findAll(js).count()) // yt skipper (youtube only) + overlay fallback
        assertEquals(1, Regex("function scan\\(").findAll(js).count())
        assertTrue(js.contains(OverlayRules.SCAM_PATTERN))
        assertTrue(js.contains(OverlayRules.MIN_IFRAME_Z.toString()))
        assertTrue(js.contains("z-index: " + OverlayRules.TRAP_Z_STYLE))
        assertTrue(js.contains("!n.querySelector('video')"))
        assertFalse(js.contains("\$")) // no unresolved Kotlin template
    }
}
