package com.deepbrowser.tv.browser

import com.deepbrowser.tv.data.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeStore : Store {
    val m = HashMap<String, String>()
    override fun read(key: String) = m[key]
    override fun write(key: String, value: String) { m[key] = value }
}

class AdBlockStateTest {
    private var t = 0L
    private fun state(s: FakeStore, every: Int = 3) = AdBlockState(s, { t }, flushEvery = every, flushMs = 10_000)

    @Test fun countPersistsEveryNHitsWithoutPause() {
        val s = FakeStore(); val a = state(s)
        repeat(3) { a.recordBlocked() }
        assertEquals(3L, AdBlockState(s).totalBlocked()) // "crash": a never flushed explicitly
        a.recordBlocked()
        assertEquals(3L, AdBlockState(s).totalBlocked()) // below threshold, not yet persisted
    }

    @Test fun countPersistsAfterInterval() {
        val s = FakeStore(); val a = state(s, every = 100)
        a.recordBlocked()
        t = 10_000
        a.recordBlocked()
        assertEquals(2L, AdBlockState(s).totalBlocked())
    }

    @Test fun enabledFlagPersistsAndDefaultsOn() {
        val s = FakeStore()
        assertTrue(state(s).enabled)
        state(s).enabled = false
        assertFalse(state(s).enabled)
    }

    @Test fun firstRunAdoptsOldSettingsJson() {
        val s = FakeStore().apply { m["settings"] = """{"ad":false,"ua":"TV","blocked":41}""" }
        val a = AdBlockState(s)
        assertEquals(41L, a.totalBlocked()); assertFalse(a.enabled)
        assertTrue(s.m.containsKey(AdBlockState.KEY))
        s.m["settings"] = """{"ad":true,"blocked":1}""" // once migrated, own key wins
        assertEquals(41L, AdBlockState(s).totalBlocked())
    }

    @Test fun corruptStoreFallsBackToDefaults() {
        val s = FakeStore().apply { m[AdBlockState.KEY] = "{{not json" }
        val a = AdBlockState(s)
        assertTrue(a.enabled); assertEquals(0L, a.totalBlocked())
        assertNull(s.m["settings"])
    }
}
