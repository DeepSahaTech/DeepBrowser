package com.deepbrowser.tv.companion

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.security.SecureRandom

/** One accepted connection; the in-memory adapter in tests and the socket adapter in production both implement it. */
interface CompanionConnection {
    val input: InputStream
    val output: OutputStream
    fun close()
}

/** Accept seam: [accept] blocks and returns null once closed. */
interface CompanionListener {
    fun accept(): CompanionConnection?
    fun close()
}

/** Socket adapter. Pass port 0 to bind an ephemeral port (tests); [port] is the bound one. */
class SocketListener(bindPort: Int) : CompanionListener {
    private val server = ServerSocket(bindPort)
    val port: Int get() = server.localPort
    override fun accept(): CompanionConnection? {
        val s = runCatching { server.accept() }.getOrNull() ?: return null
        s.soTimeout = READ_TIMEOUT_MS
        return object : CompanionConnection {
            override val input get() = s.getInputStream()
            override val output get() = s.getOutputStream()
            override fun close() { runCatching { s.close() } }
        }
    }
    override fun close() { runCatching { server.close() } }

    private companion object { const val READ_TIMEOUT_MS = 3000 }
}

/**
 * Tiny LAN web page so a phone can type or dictate (phone keyboard mic) into the TV browser.
 * Fire TV has no speech recognizer, so the phone does the voice part.
 * shortcut: plain HTTP + random path token, LAN only, one connection at a time; upgrade to pairing/HTTPS if exposed beyond home Wi-Fi.
 */
class CompanionServer internal constructor(
    private val listen: () -> CompanionListener?,
    private val address: () -> String?,
    val token: String,
    private val onAction: (CompanionAction) -> Unit,
) {
    constructor(onAction: (CompanionAction) -> Unit) :
        this({ try { SocketListener(CompanionServer.PORT) } catch (e: Exception) { null } }, { CompanionServer.lanIp() }, CompanionServer.newToken(), onAction)

    private var listener: CompanionListener? = null
    private var port = PORT
    @Volatile private var running = false

    /** Returns the URL to show on the TV, or null if there is no Wi-Fi/LAN address. */
    fun start(): String? {
        if (running) return url()
        if (address() == null) return null
        val l = listen() ?: return null
        if (l is SocketListener) port = l.port
        listener = l; running = true
        Thread({
            while (running) {
                val c = l.accept() ?: break
                runCatching { serve(c) }
                c.close()
            }
        }, "companion").apply { isDaemon = true }.start()
        return url()
    }

    fun stop() { running = false; listener?.close(); listener = null }

    private fun url(): String? = address()?.let { "http://$it:$port/$token" }

    /** Reads one request from [c], runs it through token/size/allow-list checks, writes one response. */
    internal fun serve(c: CompanionConnection) {
        val inp = BufferedInputStream(c.input)
        val out = c.output
        fun reply(code: String, body: String) {
            val b = body.toByteArray()
            out.write("HTTP/1.1 $code\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(b); out.flush()
        }
        val request = readLine(inp) ?: return reply("400 Bad Request", "")
        var length = 0
        var headers = 0
        while (true) {
            if (headers++ >= MAX_HEADERS) return reply("431 Request Header Fields Too Large", "")
            val h = readLine(inp) ?: return reply("400 Bad Request", "")
            if (h.isEmpty()) break
            if (h.startsWith("Content-Length:", ignoreCase = true)) length = h.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val parts = request.split(' ')
        val sub = CompanionProtocol.route(token, parts.getOrNull(1).orEmpty()) ?: return reply("403 Forbidden", "Forbidden")
        if (parts[0] == "POST") {
            if (length > CompanionProtocol.MAX_BODY) return reply("413 Payload Too Large", "Too large")
            val buf = ByteArray(length.coerceAtLeast(0))
            var n = 0
            while (n < buf.size) { val r = inp.read(buf, n, buf.size - n); if (r < 0) break; n += r }
            val action = CompanionProtocol.action(sub, String(buf, 0, n, Charsets.UTF_8))
            if (action != null) onAction(action)
            return if (sub == "") reply("200 OK", PAGE.replace("{{msg}}", "Sent \u2713"))
            else reply(if (action != null) "200 OK" else "400 Bad Request", "")
        }
        reply("200 OK", PAGE.replace("{{msg}}", ""))
    }

    /** CRLF/LF-terminated line, null on EOF or when longer than [MAX_LINE] (caps memory a LAN peer can make us use). */
    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length >= MAX_LINE) return null
            sb.append(c.toChar())
        }
    }

    companion object {
        const val PORT = 8765
        private const val MAX_LINE = 4096
        private const val MAX_HEADERS = 64
        internal fun newToken() = (SecureRandom().nextInt(900000) + 100000).toString()
        internal fun lanIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()
        private const val PAGE = """<!doctype html><meta name=viewport content="width=device-width,initial-scale=1,user-scalable=no">
<body style="font-family:sans-serif;background:#0b1020;color:#fff;padding:16px;margin:0">
<style>input,textarea,button{font-size:20px;padding:12px;border-radius:12px;border:0;box-sizing:border-box}
.w{width:100%}.g{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin:8px 0}
.g button,.b{background:#26345f;color:#fff;padding:18px 0}#pad{height:200px;border-radius:16px;background:#16204a;
display:flex;align-items:center;justify-content:center;touch-action:none;color:#8fa0d8;margin:8px 0}</style>
<h2>DeepBrowser remote</h2>
<form method=post><input class=w name=t autocomplete=off placeholder="Search or type an address">
<button class=b style="margin-top:8px">Send to TV</button></form><p id=m>{{msg}}</p>
<h3>Touchpad (swipe = arrows, tap = OK)</h3><div id=pad>swipe / tap here</div>
<div class=g><span></span><button onclick="k('up')">▲</button><span></span>
<button onclick="k('left')">◀</button><button onclick="k('ok')">OK</button><button onclick="k('right')">▶</button>
<button onclick="k('back')">Back</button><button onclick="k('down')">▼</button><span></span></div>
<h3>Send URL to TV</h3><input class=w id=u placeholder="https://..." type=url><button class=b onclick="s('url','u','u')" style="margin-top:8px">Open on TV</button>
<h3>Paste text into focused field</h3><textarea class=w id=p rows=3 placeholder="Select a text box on the TV first"></textarea>
<button class=b onclick="s('paste','t','p')" style="margin-top:8px">Paste on TV</button>
<script>
var B=location.pathname.replace(/\/$/,'');
function post(r,b){return fetch(B+'/'+r,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:b}).then(function(x){return x.ok})}
function k(c){post('key','k='+c);if(navigator.vibrate)navigator.vibrate(15)}
function s(r,f,id){var e=document.getElementById(id);post(r,f+'='+encodeURIComponent(e.value)).then(function(ok){document.getElementById('m').textContent=ok?'Sent':'Failed';if(ok&&r=='paste')e.value=''})}
var pad=document.getElementById('pad'),x0=0,y0=0;
pad.addEventListener('touchstart',function(e){x0=e.touches[0].clientX;y0=e.touches[0].clientY},{passive:true});
pad.addEventListener('touchend',function(e){var t=e.changedTouches[0],dx=t.clientX-x0,dy=t.clientY-y0;
if(Math.abs(dx)<30&&Math.abs(dy)<30)return k('ok');
k(Math.abs(dx)>Math.abs(dy)?(dx>0?'right':'left'):(dy>0?'down':'up'))},{passive:true});
</script></body>"""
    }
}
