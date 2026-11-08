package com.deepbrowser.tv.companion

import android.view.KeyEvent
import java.net.URLDecoder

sealed interface CompanionAction {
    data class Text(val s: String) : CompanionAction
    data class Url(val s: String) : CompanionAction
    data class Key(val code: Int) : CompanionAction
    data class Paste(val s: String) : CompanionAction
}

/** Pure request parsing/validation for [CompanionServer]; no Android calls beyond KeyEvent constants. */
object CompanionProtocol {
    const val MAX_BODY = 4096
    const val MAX_TEXT = 2000
    private val BAD_SCHEMES = listOf("javascript:", "file:", "data:", "intent:", "content:", "blob:")

    private val KEYS = mapOf(
        "up" to KeyEvent.KEYCODE_DPAD_UP, "down" to KeyEvent.KEYCODE_DPAD_DOWN,
        "left" to KeyEvent.KEYCODE_DPAD_LEFT, "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "ok" to KeyEvent.KEYCODE_DPAD_CENTER, "back" to KeyEvent.KEYCODE_BACK,
    )

    /** Sub-route ("" , "key", "url", "text", "paste") for a path under the exact token, or null if forbidden/unknown. */
    fun route(token: String, path: String): String? {
        val p = path.substringBefore('?')
        if (p == "/$token" || p == "/$token/") return ""
        val sub = p.removePrefix("/$token/")
        return if (p.startsWith("/$token/") && sub in setOf("key", "url", "text", "paste")) sub else null
    }

    /** Value of form field [name] in an urlencoded [body], trimmed; null if absent. */
    fun field(body: String, name: String): String? =
        body.split('&').firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }?.trim()

    fun action(sub: String, body: String): CompanionAction? = when (sub) {
        "", "text" -> field(body, "t")?.takeIf { it.isNotEmpty() }?.take(MAX_TEXT)?.let { CompanionAction.Text(it) }
        "url" -> field(body, "u")?.takeIf { it.isNotEmpty() && it.length <= MAX_TEXT && BAD_SCHEMES.none { s -> it.startsWith(s, true) } }
            ?.let { CompanionAction.Url(it) }
        "key" -> KEYS[field(body, "k")]?.let { CompanionAction.Key(it) }
        "paste" -> field(body, "t")?.takeIf { it.isNotEmpty() }?.take(MAX_TEXT)?.let { CompanionAction.Paste(it) }
        else -> null
    }

    /** Quote [s] as a JS string literal safe to embed in script (no `</script>` or line-terminator breaks). */
    fun jsString(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '\\' -> append("\\\\"); '"' -> append("\\\""); '\n' -> append("\\n"); '\r' -> append("\\r")
            '<' -> append("\\u003c"); '\u2028' -> append("\\u2028"); '\u2029' -> append("\\u2029")
            else -> append(c)
        }
        append('"')
    }
}
