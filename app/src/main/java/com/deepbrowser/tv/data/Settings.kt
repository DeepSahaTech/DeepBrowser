package com.deepbrowser.tv.data

import org.json.JSONObject

enum class UaMode(val label: String) {
    TV("TV"), MOBILE("Mobile"), DESKTOP("Desktop");
    fun next(): UaMode = values()[(ordinal + 1) % values().size]
}

/** How the D-pad drives a web page: spatial focus (default) or a mouse pointer. Native screens always use focus. */
enum class NavMode(val label: String) {
    FOCUS("Remote focus"), POINTER("Mouse pointer");
    fun toggle(): NavMode = if (this == FOCUS) POINTER else FOCUS
}

class Settings(private val store: Store) {
    private val o = runCatching { JSONObject(store.read(KEY) ?: "{}") }.getOrDefault(JSONObject())

    var ua: UaMode = runCatching { UaMode.valueOf(o.optString("ua", "TV")) }.getOrDefault(UaMode.TV)
        set(v) { field = v; save() }

    var nav: NavMode = runCatching { NavMode.valueOf(o.optString("nav", "FOCUS")) }.getOrDefault(NavMode.FOCUS)
        set(v) { field = v; save() }

    /** Mouse pointer speed multiplier. */
    var pointerSpeed: Float = o.optDouble("ptr", 1.0).toFloat().coerceIn(0.5f, 2.5f)
        set(v) { field = v; save() }

    private fun save() {
        store.write(KEY, JSONObject().put("ua", ua.name).put("nav", nav.name).put("ptr", pointerSpeed.toDouble()).toString())
    }

    private companion object { const val KEY = "settings" }
}
