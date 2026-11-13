package com.deepbrowser.tv.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Runs the Node unit tests for the navigation core (tools/navtest). Skipped when no node binary exists. */
class NavEngineJsTest {

    private fun findNode(): String? {
        val onPath = (System.getenv("PATH") ?: "").split(File.pathSeparator).map { File(it, "node") }
        val candidates = onPath + listOf(File("/usr/local/bin/node"), File("/opt/homebrew/bin/node"))
        return candidates.firstOrNull { it.isFile && it.canExecute() }?.absolutePath
    }

    @Test
    fun navCoreNodeTestsPass() {
        val node = findNode()
        // CI must never silently skip the Node tests: missing node is a failure there.
        if (System.getenv("CI") != null) assertNotNull("node binary not found in CI", node)
        Assume.assumeTrue(node != null)
        // Node 24 rejects a bare directory for --test, so name the test file.
        val process = ProcessBuilder(node, "--test",
            "../tools/navtest/core.test.mjs", "../tools/navtest/engine-smoke.test.mjs",
            "../tools/navtest/msecore.test.mjs", "../tools/navtest/mse-smoke.test.mjs")
            .directory(File("."))
            .redirectErrorStream(true)
            .start()
        var output = ""
        val reader = Thread { output = process.inputStream.bufferedReader().readText() }
        reader.start()
        val finished = process.waitFor(120, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(5_000)
        assertEquals(output, 0, if (finished) process.exitValue() else -1)
    }
}
