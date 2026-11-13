package com.deepbrowser.tv.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInHostsTest {
    @Test fun exactHostMatches() = assertTrue(BuiltInHosts.matches("doubleclick.net"))
    @Test fun subdomainMatches() = assertTrue(BuiltInHosts.matches("Ads.Static.DoubleClick.net"))
    @Test fun unrelatedHostDoesNotMatch() = assertFalse(BuiltInHosts.matches("example.com"))
    @Test fun lookalikeSuffixDoesNotMatch() = assertFalse(BuiltInHosts.matches("notdoubleclick.net"))
    @Test fun nullHostDoesNotMatch() = assertFalse(BuiltInHosts.matches(null))
    @Test fun entriesAreBareHosts() =
        assertTrue(BuiltInHosts.all.all { '/' !in it && ':' !in it })
}
