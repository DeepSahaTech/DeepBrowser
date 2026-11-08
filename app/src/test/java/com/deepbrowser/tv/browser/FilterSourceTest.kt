package com.deepbrowser.tv.browser

import com.deepbrowser.tv.data.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

private class MapStore : Store {
    val m = HashMap<String, String>()
    override fun read(key: String) = m[key]
    override fun write(key: String, value: String) { m[key] = value }
}

class FilterSourceTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun urlValidation() {
        assertEquals("https://example.org/list.txt", FilterUrl.parse("  https://example.org/list.txt \n"))
        assertEquals("HTTPS://Example.org/a", FilterUrl.parse("HTTPS://Example.org/a"))
        for (bad in listOf(
            "", "   ", "http://example.org/list.txt", "ftp://example.org/x", "file:///sdcard/x", "javascript:alert(1)",
            "https://", "https:///path", "example.org/list.txt", "https://exa mple.org/x", "https://user:pw@example.org/x",
            "https://example.org/" + "a".repeat(FilterUrl.MAX_LEN),
        )) assertNull(bad, FilterUrl.parse(bad))
    }

    @Test fun localAndPrivateHostsAreRejected() {
        for (h in listOf(
            "localhost", "LOCALHOST.", "app.localhost", "printer.local", "db.internal", "127.0.0.1", "127.1", "0.0.0.0", "10.0.0.5",
            "172.16.0.1", "172.31.255.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "224.0.0.1", "2130706433", "0x7f.0.0.1",
            "[::1]", "[::]", "[fe80::1]", "[fc00::1]", "[fd12:3456::1]", "[::ffff:192.168.0.1]", "[::ffff:127.0.0.1]",
        )) assertNull(h, FilterUrl.parse("https://$h/list.txt"))
        for (h in listOf("example.org", "8.8.8.8", "172.32.0.1", "100.63.0.1", "[2001:4860:4860::8888]", "local.example.org"))
            assertEquals(h, "https://$h/list.txt", FilterUrl.parse("https://$h/list.txt"))
        assertFalse(FilterUrl.hostAllowed(null))
    }

    @Test fun defaultIsGithubUntilCustomIsSet() {
        val c = FilterSourceChoice(MapStore())
        assertEquals(FILTER_LIST_URL, c.url); assertTrue(c.isDefault); assertEquals("Default (GitHub)", c.label)
        assertTrue(c.set("https://lists.example.org/my.txt"))
        assertEquals("https://lists.example.org/my.txt", c.url); assertFalse(c.isDefault)
        assertEquals("Custom: lists.example.org", c.label)
    }

    @Test fun rejectedLinkKeepsCurrentSource() {
        val store = MapStore(); val c = FilterSourceChoice(store)
        c.set("https://lists.example.org/my.txt")
        assertFalse(c.set("http://insecure.example/x"))
        assertEquals("https://lists.example.org/my.txt", c.url)
    }

    @Test fun resetAndPersistence() {
        val store = MapStore()
        FilterSourceChoice(store).set("https://lists.example.org/my.txt")
        val again = FilterSourceChoice(store)
        assertEquals("https://lists.example.org/my.txt", again.url)
        again.reset()
        assertEquals(FILTER_LIST_URL, FilterSourceChoice(store).url)
        again.set("https://lists.example.org/my.txt"); again.set(FILTER_LIST_URL)
        assertTrue(again.isDefault)
    }

    @Test fun tamperedStoredValueFallsBackToDefault() {
        val store = MapStore(); store.write("filterurl", "http://evil.example/x")
        assertEquals(FILTER_LIST_URL, FilterSourceChoice(store).url)
    }

    @Test fun validatorIsBoundToTheLinkItCameFrom() {
        var link = "https://a.example/list.txt"
        val s = HttpFileSource(tmp.newFolder()) { link }
        assertNull(s.validator())
        s.write("! list\n||x.example^", "E:\"1\"")
        assertEquals("E:\"1\"", s.validator())
        link = "https://b.example/list.txt"
        assertNull(s.validator())
    }

    @Test fun validatorIsSavedAgainstTheFetchedLinkNotTheCurrentOne() {
        var link = "https://b.example/list.txt" // the link changed while the download from a.example was running
        val s = HttpFileSource(tmp.newFolder()) { link }
        s.write("! list", "E:\"a\"", "https://a.example/list.txt")
        assertNull(s.validator())
        link = "https://a.example/list.txt"
        assertEquals("E:\"a\"", s.validator())
    }

    @Test fun cacheWriteIsAtomicAndLeavesNoTempFile() {
        val dir = tmp.newFolder(); val s = HttpFileSource(dir)
        s.write("! one", null); s.write("! two", "M:Sat")
        assertEquals("! two", s.read())
        assertEquals(setOf("easylist.txt", "easylist.tag"), dir.list()!!.toSet())
    }

    @Test fun nonHttpsSourceIsRefusedBeforeAnyNetworkCall() {
        try { HttpFileSource(tmp.newFolder()) { "http://example.org/x" }.fetch(null); fail() }
        catch (e: IOException) { assertEquals("not https", e.message) }
    }
}
