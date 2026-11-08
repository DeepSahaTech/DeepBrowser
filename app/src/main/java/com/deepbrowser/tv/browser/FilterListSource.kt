package com.deepbrowser.tv.browser

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URI
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import com.deepbrowser.tv.data.Store

/** Where the ad-filter list is downloaded from (an Adblock-Plus-syntax file in the project repository). */
const val FILTER_LIST_URL = "https://raw.githubusercontent.com/DeepSahaTech/DeepBrowser/main/filters/easylist.txt"

/** Hard cap on a downloaded list; anything larger is rejected. */
const val MAX_LIST_BYTES = 20 * 1024 * 1024

sealed interface FetchResult {
    /** New list text plus an opaque [validator] (ETag / Last-Modified) for the next conditional request; [url] is the link it was fetched from. */
    class Fresh(val text: String, val validator: String?, val url: String? = null) : FetchResult
    /** The server confirmed the cached copy is current; no body was transferred. */
    object NotModified : FetchResult
}

/** Accepts only a plain https link of sane length; the source link the user may supply in Settings. */
object FilterUrl {
    const val MAX_LEN = 2048

    /** The trimmed link, or null when it is not acceptable. */
    fun parse(input: String): String? {
        val s = input.trim()
        if (s.isEmpty() || s.length > MAX_LEN || s.any { it.isWhitespace() || it.isISOControl() }) return null
        val u = runCatching { URI(s) }.getOrNull() ?: return null
        return s.takeIf { u.scheme.equals("https", true) && !u.host.isNullOrEmpty() && u.userInfo == null && hostAllowed(u.host) }
    }

    private val NUMERIC = Regex("^(0x[0-9a-f]+|[0-9]+)(\\.(0x[0-9a-f]+|[0-9]+))*$", RegexOption.IGNORE_CASE)
    private val QUAD = Regex("^(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])(\\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}$")

    /**
     * False for local-network targets: localhost, .local/.internal names, and literal IPs that are private, loopback,
     * link-local, shared (CGNAT), multicast or unspecified. Odd numeric spellings (decimal/hex/short forms) are refused.
     * A public name that resolves to a private address is not caught here.
     */
    fun hostAllowed(host: String?): Boolean {
        val h = host?.trim('[', ']')?.trimEnd('.')?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")) return false
        val literal = h.contains(':')
        if (!literal) {
            if (!NUMERIC.matches(h)) return true
            if (!QUAD.matches(h)) return false
        }
        val a = runCatching { java.net.InetAddress.getByName(h) }.getOrNull() ?: return false // literal only: no DNS lookup
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return false
        val b = a.address
        return when (b.size) {
            4 -> !(b[0].toInt() == 100 && (b[1].toInt() and 0xC0) == 64)
            else -> (b[0].toInt() and 0xFE) != 0xFC
        }
    }
}

/** Which link the list comes from: the project default unless the user set a valid custom one (persisted in [store]). */
class FilterSourceChoice(private val store: Store) {
    private val custom: String? get() = store.read(KEY)?.let(FilterUrl::parse)
    val url: String get() = custom ?: FILTER_LIST_URL
    val isDefault: Boolean get() = custom == null
    /** Short text for Settings: "Default (GitHub)" or "Custom: host". */
    val label: String get() = custom?.let { "Custom: " + (runCatching { URI(it).host }.getOrNull() ?: it) } ?: "Default (GitHub)"

    /** Stores [input] when valid (the default link counts as a reset); false when rejected. */
    fun set(input: String): Boolean {
        val u = FilterUrl.parse(input) ?: return false
        store.write(KEY, if (u == FILTER_LIST_URL) "" else u)
        return true
    }

    fun reset() = store.write(KEY, "")

    private companion object { const val KEY = "filterurl" }
}

/** Seam for the filter list text: where the cached copy lives and how a fresh one is fetched. */
interface FilterListSource {
    /** Cache time (epoch ms; moved forward by [touch]), or null when nothing is cached. */
    fun lastModified(): Long?
    fun read(): String?
    /** Validator saved with the cached copy, or null (also null when nothing is cached). */
    fun validator(): String?
    /** Conditional download. Throws [IOException] with a short reason on any failure. */
    fun fetch(validator: String?): FetchResult
    /** Replace the cache atomically; a failed write leaves the old copy intact. [url] is the link [validator] belongs to (default: the current link). */
    fun write(text: String, validator: String?, url: String? = null)
    /** Record that the cached copy was just confirmed current. */
    fun touch()
}

/**
 * Production adapter: https GET of [url] (conditional; read on every fetch so the source can change), cache in
 * [dir]/easylist.txt (temp file + rename). The saved validator is bound to the link it came from, so a different
 * link never gets another link's ETag.
 */
class HttpFileSource(
    private val dir: File,
    private val maxBytes: Int = MAX_LIST_BYTES,
    private val url: () -> String = { FILTER_LIST_URL },
) : FilterListSource {
    private val file get() = File(dir, "easylist.txt")
    private val tagFile get() = File(dir, "easylist.tag")

    override fun lastModified(): Long? = file.takeIf { it.exists() }?.lastModified()
    override fun read(): String? = runCatching { file.readText() }.getOrNull()
    override fun validator(): String? {
        if (!file.exists()) return null
        val (savedUrl, tag) = runCatching { tagFile.readText().split('\n', limit = 2) }.getOrNull()?.takeIf { it.size == 2 } ?: return null
        return tag.takeIf { savedUrl == url() && it.isNotBlank() }
    }
    override fun touch() { file.setLastModified(System.currentTimeMillis()) }

    override fun write(text: String, validator: String?, url: String?) {
        replace(file, text)
        if (validator != null) replace(tagFile, (url ?: url()) + "\n" + validator) else tagFile.delete()
    }

    private fun replace(target: File, text: String) {
        val tmp = File(dir, target.name + ".tmp")
        try {
            tmp.writeText(text)
            if (!tmp.renameTo(target)) throw IOException("cannot save list")
        } finally { tmp.delete() }
    }

    override fun fetch(validator: String?): FetchResult {
        val start = url() // the link may change while we download; the validator is saved against this one
        var target = URL(start)
        try {
            repeat(MAX_REDIRECTS + 1) {
                if (target.protocol != "https") throw IOException("not https")
                if (!FilterUrl.hostAllowed(target.host)) throw IOException("blocked host")
                val c = target.openConnection() as HttpURLConnection
                try {
                    c.instanceFollowRedirects = false
                    c.connectTimeout = 10_000; c.readTimeout = 30_000
                    when {
                        validator?.startsWith("E:") == true -> c.setRequestProperty("If-None-Match", validator.substring(2))
                        validator?.startsWith("M:") == true -> c.setRequestProperty("If-Modified-Since", validator.substring(2))
                    }
                    when (val code = c.responseCode) {
                        304 -> return FetchResult.NotModified
                        200 -> {
                            if (c.contentLength > maxBytes) throw IOException("Too large")
                            val tag = c.getHeaderField("ETag")?.let { "E:$it" } ?: c.getHeaderField("Last-Modified")?.let { "M:$it" }
                            return FetchResult.Fresh(readCapped(c.inputStream), tag, start)
                        }
                        301, 302, 303, 307, 308 -> target = URL(target, c.getHeaderField("Location") ?: throw IOException("bad redirect"))
                        404 -> throw IOException("Not found (404)")
                        else -> throw IOException("HTTP $code")
                    }
                } finally { c.disconnect() }
            }
        } catch (e: UnknownHostException) { throw IOException("No network")
        } catch (e: ConnectException) { throw IOException("No network")
        } catch (e: SocketTimeoutException) { throw IOException("Timed out") }
        throw IOException("too many redirects")
    }

    private fun readCapped(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        input.use {
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                if (out.size() + n > maxBytes) throw IOException("Too large")
                out.write(buf, 0, n)
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private companion object { const val MAX_REDIRECTS = 5 }
}
