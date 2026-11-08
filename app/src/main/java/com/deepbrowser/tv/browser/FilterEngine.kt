package com.deepbrowser.tv.browser

/**
 * Small Adblock-Plus-syntax engine (EasyList subset), pure Kotlin so it is unit-testable.
 * Supports: `||host^` and `||host/path` network rules, plain substring rules, `|`/`^` anchors,
 * `@@` exceptions, generic `##selector` cosmetic rules. Rule options after `$` are ignored except
 * `third-party` is not enforced.
 * shortcut: no regex rules, no domain= options, no per-domain cosmetic rules; upgrade to adblock-rust via JNI if EasyList accuracy matters.
 */
class FilterEngine {
    private val blockHosts = HashSet<String>()
    private val allowHosts = HashSet<String>()
    private val blockParts = ArrayList<String>()   // substring rules (lowercase)
    private val allowParts = ArrayList<String>()
    private val hostPaths = HashMap<String, MutableList<String>>() // ||host/path rules
    private val hide = LinkedHashSet<String>()

    var ruleCount = 0; private set

    fun load(text: String) {
        text.lineSequence().forEach { add(it.trim()) }
    }

    fun add(line: String) {
        if (line.isEmpty() || line[0] == '!' || line[0] == '[') return
        if (line.contains("#@#") || line.contains("#?#") || line.contains("#\$#")) return
        val h = line.indexOf("##")
        if (h >= 0) {
            if (h == 0 && hide.size < 3000) { hide.add(line.substring(2)); ruleCount++ }
            return // per-domain cosmetic rules not supported
        }
        var rule = line
        val exception = rule.startsWith("@@")
        if (exception) rule = rule.substring(2)
        val d = rule.indexOf('$')
        if (d >= 0) rule = rule.substring(0, d)
        if (rule.isEmpty() || rule.startsWith("/") && rule.endsWith("/")) return
        rule = rule.lowercase()
        if (rule.startsWith("||")) {
            var r = rule.substring(2)
            val end = r.indexOfFirst { it == '^' || it == '/' || it == '*' || it == ':' || it == '?' }
            val host = if (end < 0) r else r.substring(0, end)
            val rest = if (end < 0) "" else r.substring(end)
            if (host.isEmpty() || host.contains('*')) return
            if (rest.isEmpty() || rest == "^" ) {
                (if (exception) allowHosts else blockHosts).add(host)
            } else if (!exception) {
                hostPaths.getOrPut(host) { ArrayList() }.add(rest.trimEnd('^').trimStart('^'))
            }
            ruleCount++
        } else {
            val part = rule.trim('|', '^').replace("^", "")
            if (part.length < 6 || part.contains('*')) return
            val list = if (exception) allowParts else blockParts
            if (list.size < 6000) { list.add(part); ruleCount++ }
        }
    }

    private fun hostMatches(set: Set<String>, host: String): Boolean {
        var h = host
        while (true) {
            if (h in set) return true
            val i = h.indexOf('.'); if (i < 0) return false
            h = h.substring(i + 1)
        }
    }

    /** True if [url] should be blocked. */
    fun shouldBlock(url: String): Boolean {
        val u = url.lowercase()
        val start = u.indexOf("://").let { if (it < 0) 0 else it + 3 }
        val end = u.indexOfAny(charArrayOf('/', '?', '#', ':'), start).let { if (it < 0) u.length else it }
        val host = u.substring(start, end)
        if (host.isEmpty()) return false
        if (hostMatches(allowHosts, host) || allowParts.any { u.contains(it) }) return false
        if (hostMatches(blockHosts, host)) return true
        var h = host
        while (true) {
            hostPaths[h]?.let { paths -> val rest = u.substring(end); if (paths.any { rest.contains(it) }) return true }
            val i = h.indexOf('.'); if (i < 0) break
            h = h.substring(i + 1)
        }
        return blockParts.any { u.contains(it) }
    }

    /** CSS for generic element-hiding rules, or empty. */
    fun hidingCss(): String = if (hide.isEmpty()) "" else hide.joinToString(",") + "{display:none!important}"
}
