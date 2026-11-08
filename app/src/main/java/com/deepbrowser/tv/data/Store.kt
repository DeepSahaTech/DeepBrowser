package com.deepbrowser.tv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Tiny key/value seam so the storage classes run in plain JUnit; production uses SharedPreferences. */
interface Store {
    fun read(key: String): String?
    fun write(key: String, value: String)
}

class PrefsStore(context: Context, name: String = "deepbrowser") : Store {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun read(key: String): String? = prefs.getString(key, null)
    override fun write(key: String, value: String) { prefs.edit().putString(key, value).apply() }
}

data class Page(val url: String, val title: String)

internal fun Store.readPages(key: String): List<Page> = runCatching {
    val a = JSONArray(read(key) ?: "[]")
    (0 until a.length()).map { i -> a.getJSONObject(i).let { Page(it.getString("u"), it.optString("t")) } }
}.getOrDefault(emptyList())

internal fun Store.writePages(key: String, pages: List<Page>) {
    val a = JSONArray()
    pages.forEach { a.put(JSONObject().put("u", it.url).put("t", it.title)) }
    write(key, a.toString())
}
