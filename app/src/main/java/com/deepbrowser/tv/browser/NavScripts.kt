package com.deepbrowser.tv.browser

/** Page scripts read from app assets: page tweaks, and tweaks plus the in-page navigation engine. */
class NavScripts(private val context: android.content.Context) {
    val tweaks: String by lazy { asset("nav/tweaks.js") }
    // msecore and mse first: MediaSource must be wrapped before any page script runs
    val all: String by lazy {
        asset("nav/msecore.js") + "\n" + asset("nav/mse.js") + "\n" + tweaks + "\n" + asset("nav/core.js") + "\n" + asset("nav/engine.js")
    }

    private fun asset(p: String) = context.assets.open(p).bufferedReader().use { it.readText() }
}
