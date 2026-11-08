package com.deepbrowser.tv.data

import org.json.JSONObject

enum class UaMode(val label: String) {
    TV("TV"), MOBILE("Mobile"), DESKTOP("Desktop");
    fun next(): UaMode = values()[(ordinal + 1) % values().size]
}

class Settings(private val store: Store) {
    private val o = runCatching { JSONObject(store.read(KEY) ?: "{}") }.getOrDefault(JSONObject())

    var ua: UaMode = runCatching { UaMode.valueOf(o.optString("ua", "TV")) }.getOrDefault(UaMode.TV)
        set(v) { field = v; save() }

    private fun save() {
        store.write(KEY, JSONObject().put("ua", ua.name).toString())
    }

    private companion object { const val KEY = "settings" }
}
