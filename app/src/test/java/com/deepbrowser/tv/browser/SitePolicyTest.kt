package com.deepbrowser.tv.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SitePolicyTest {
    @Test fun youtubeTvIsNativeAtTvWidth() {
        listOf("https://www.youtube.com/tv", "http://youtube.com/tv#/browse", "HTTPS://M.YouTube.com/tv?x=1").forEach {
            assertTrue(it, SitePolicy.isTvNative(it))
        }
    }

    @Test fun otherSitesUseViewportWidth() {
        listOf(null, "", "https://www.youtube.com/watch?v=1", "https://notyoutube.com/tv", "https://evil.example/?u=youtube.com/tv").forEach {
            assertFalse(it, SitePolicy.isTvNative(it))
        }
    }

}
