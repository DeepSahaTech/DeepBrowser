package com.deepbrowser.tv.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompanionProtocolTest {
    @Test fun routeRequiresExactToken() {
        assertEquals("", CompanionProtocol.route("123456", "/123456"))
        assertEquals("key", CompanionProtocol.route("123456", "/123456/key"))
        assertNull(CompanionProtocol.route("123456", "/1234567"))
        assertNull(CompanionProtocol.route("123456", "/123456/other"))
        assertNull(CompanionProtocol.route("123456", "/"))
    }

    @Test fun fieldDecodesAndTrims() {
        assertEquals("a b&c", CompanionProtocol.field("t=+a+b%26c+&x=1", "t"))
        assertNull(CompanionProtocol.field("x=1", "t"))
    }

    @Test fun urlRejectsDangerousSchemes() {
        assertNull(CompanionProtocol.action("url", "u=javascript%3Aalert(1)"))
        assertNull(CompanionProtocol.action("url", "u=FILE%3A%2F%2F%2Fetc"))
        assertEquals(CompanionAction.Url("https://a.com"), CompanionProtocol.action("url", "u=https%3A%2F%2Fa.com"))
    }

    @Test fun keyAllowListAndLimits() {
        assertNull(CompanionProtocol.action("key", "k=power"))
        assertEquals(true, CompanionProtocol.action("key", "k=ok") is CompanionAction.Key)
        val big = "t=" + "a".repeat(5000)
        assertEquals(CompanionProtocol.MAX_TEXT, (CompanionProtocol.action("paste", big) as CompanionAction.Paste).s.length)
        assertNull(CompanionProtocol.action("text", "t="))
    }

    @Test fun jsStringEscapes() {
        assertEquals("\"a\\\"b\\\\\\n\\u003c/script>\"", CompanionProtocol.jsString("a\"b\\\n</script>"))
    }
}
