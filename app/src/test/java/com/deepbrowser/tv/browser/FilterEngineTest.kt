package com.deepbrowser.tv.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterEngineTest {
    private val e = FilterEngine().apply {
        load(
            """
            ! comment
            ||ads.example.com^
            ||tracker.net/pixel
            /banner/ads/img
            -advert-frame.
            @@||ads.example.com/allowed^
            ##.ad-box
            """.trimIndent()
        )
    }

    @Test fun blocksHostAndSubdomain() {
        assertTrue(e.shouldBlock("https://ads.example.com/x.js"))
        assertTrue(e.shouldBlock("https://a.ads.example.com/x.js"))
        assertFalse(e.shouldBlock("https://example.com/x.js"))
    }

    @Test fun hostPathRule() {
        assertTrue(e.shouldBlock("https://tracker.net/pixel?id=1"))
        assertFalse(e.shouldBlock("https://tracker.net/app.js"))
    }

    @Test fun substringRuleAndException() {
        assertTrue(e.shouldBlock("https://cdn.site.org/banner/ads/img1.png"))
        assertFalse(e.shouldBlock("https://site.org/clean.png"))
    }

    @Test fun cosmeticCss() {
        assertEquals(".ad-box{display:none!important}", e.hidingCss())
    }
}
