package com.deepbrowser.tv.player

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** One MSE track as the page reports it: source buffer id, MIME type and media timescale. */
data class BridgeTrack(val sb: String, val mime: String, val timescale: Long)

/**
 * A page video handed over to the native player. [commands] sends JSON to the page (seek/pause/play/stop).
 * The page's generation ([gen]) increases on every seek; a `reset` message from the page starts a new [store] generation.
 */
class BridgeSession(
    val id: String,
    val tracks: List<BridgeTrack>,
    val startTime: Double,
    private val commands: (String) -> Unit,
) {
    val store = StreamStore()

    @Volatile var gen = 0
        private set

    /** Title time (seconds) the page video was at when the generation started; the new source seeks there. */
    @Volatile var resetTime = 0.0
        private set

    /** Called on the thread that delivers page messages; the player activity hops to its UI thread. */
    @Volatile var onResetListener: ((Int, Double) -> Unit)? = null

    fun seek(t: Double) = commands("""{"cmd":"seek","t":$t}""")
    fun pause() = commands("""{"cmd":"pause"}""")
    fun play() = commands("""{"cmd":"play"}""")
    /** Native position report: the page moves its own clock there so the site keeps loading ahead of it. */
    fun sync(t: Double) = commands("""{"cmd":"sync","t":$t}""")

    fun stop() {
        commands("""{"cmd":"stop"}""")
        store.stop()
    }

    fun onReset(gen: Int, t: Double) {
        this.gen = gen
        resetTime = t
        store.reset(gen)
        onResetListener?.invoke(gen, t)
    }
}

object BridgeSessions {
    private val sessions = ConcurrentHashMap<String, BridgeSession>()

    fun put(s: BridgeSession) { sessions[s.id] = s }
    fun get(id: String): BridgeSession? = sessions[id]
    fun remove(id: String) { sessions.remove(id) }
    /** While a native player is fed from a page, that page must keep running (it is the data pump). */
    fun active(): Boolean = sessions.isNotEmpty()
}

/**
 * Routes page messages into a session. Text messages describe the binary frame that follows:
 * `{type:'init'|'seg', sb, gen}` sets a pending header, the next [onBytes] is appended and clears it.
 * `{type:'reset', gen}` starts a new generation. `start` is handled by whoever creates the session.
 */
class BridgeInbox(private val session: BridgeSession) {
    private var pendingSb: String? = null
    private var pendingGen = 0

    fun onText(json: String) {
        val msg = try { JSONObject(json) } catch (_: org.json.JSONException) { return }
        when (msg.optString("type")) {
            "init", "seg" -> {
                pendingSb = msg.optString("sb").takeIf { it.isNotEmpty() }
                pendingGen = msg.optInt("gen", session.gen)
            }
            "reset" -> {
                pendingSb = null
                session.onReset(msg.optInt("gen", session.gen), msg.optDouble("t", 0.0))
            }
        }
    }

    fun onBytes(bytes: ByteArray) {
        val sb = pendingSb ?: return
        pendingSb = null
        session.store.append(sb, pendingGen, bytes)
    }
}
