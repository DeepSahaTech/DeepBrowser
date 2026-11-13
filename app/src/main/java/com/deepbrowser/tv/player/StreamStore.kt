package com.deepbrowser.tv.player

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thread-safe, append-only byte streams (one per track id) for the current generation.
 * Pure Kotlin: no Android types, so it can be unit-tested on the JVM.
 *
 * Readers are bound to the generation that was current when they were opened. A [reset] ends
 * every reader of the old generation (they get end of stream) and starts an empty new one.
 *
 * Memory is bounded. The first chunk of a track (its init segment) is kept for good. Other chunks are dropped once
 * every open reader of the track has read past them (with no open reader nothing is dropped). A track with more than
 * [unreadCap] bytes not yet read by its readers refuses further appends until they catch up.
 * Positions are logical byte offsets, so a reader keeps its place after earlier chunks are gone.
 */
class StreamStore(
    initialGen: Int = 0,
    private val unreadCap: Long = UNREAD_CAP_BYTES,
    private val keepBehind: Long = KEEP_BEHIND_BYTES,
) {

    internal class Generation(val gen: Int) {
        val tracks = HashMap<String, TrackBuf>()
        var ended = false
    }

    internal class Piece(val start: Long, val bytes: ByteArray) {
        val end: Long get() = start + bytes.size
    }

    internal class TrackBuf {
        var init: ByteArray? = null
        var end = 0L                                  // logical length of everything appended, init included
        val pieces = ArrayList<Piece>()               // chunks after the init, contiguous, oldest first
        val readers = ArrayList<StreamStore.Input>()  // open readers of this track in this generation

        val initLen: Long get() = init?.size?.toLong() ?: 0L

        /** Logical position of the first chunk still held; [initLen] until [end] when nothing is held. */
        val firstHeld: Long get() = if (pieces.isEmpty()) end else pieces[0].start

        fun minReaderPos(): Long? = readers.minOfOrNull { it.pos }

        fun unread(): Long = end - (minReaderPos() ?: 0L)

        fun pieceAt(pos: Long): Piece? {
            var lo = 0
            var hi = pieces.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val p = pieces[mid]
                when {
                    pos < p.start -> hi = mid - 1
                    pos >= p.end -> lo = mid + 1
                    else -> return p
                }
            }
            return null
        }

        /**
         * Drops leading chunks every open reader has read past, keeping a [keepBehind] window so a reopen right after
         * a read (load retry) still finds its data. Keeps everything while no reader is open.
         */
        fun trim(keepBehind: Long) {
            val min = minReaderPos() ?: return
            while (pieces.isNotEmpty() && pieces[0].end <= min - keepBehind) pieces.removeAt(0)
        }
    }

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var current = Generation(initialGen)
    private var closed = false
    private var stopped = false

    val gen: Int get() = lock.withLock { current.gen }

    /** Appends a copy of [bytes]. Ignored if [gen] is not the current generation, the store is closed or the track is full. */
    fun append(track: String, gen: Int, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        lock.withLock {
            if (closed || gen != current.gen) return
            val tb = current.tracks.getOrPut(track) { TrackBuf() }
            if (tb.unread() > unreadCap) return
            val copy = bytes.copyOf()
            if (tb.init == null) tb.init = copy else tb.pieces.add(Piece(tb.end, copy))
            tb.end += copy.size
            changed.signalAll()
        }
    }

    /** Switches to a new generation: drops all old data and ends readers of the old generation. */
    fun reset(gen: Int) {
        lock.withLock {
            current.ended = true
            current = Generation(gen)
            closed = stopped
            changed.signalAll()
        }
    }

    /** Readers get end of stream once they have consumed what is there. A later [reset] reopens the store. */
    fun close() {
        lock.withLock {
            closed = true
            changed.signalAll()
        }
    }

    /** Like [close], but sticky: a later [reset] does not reopen the store. */
    fun stop() {
        lock.withLock {
            stopped = true
            closed = true
            changed.signalAll()
        }
    }

    /** Opens [track] for reading from position 0 of the current generation. */
    fun open(track: String): Input = lock.withLock { Input(current, track) }

    /** Bytes of [track] still held in memory (test hook). */
    internal fun retainedBytes(track: String): Long = lock.withLock {
        current.tracks[track]?.pieces?.sumOf { it.bytes.size.toLong() } ?: 0L
    }

    inner class Input internal constructor(private val generation: Generation, track: String) {
        private val tb: TrackBuf = generation.tracks.getOrPut(track) { TrackBuf() }
        internal var pos = 0L
        private var closedInput = false

        init {
            tb.readers.add(this)
        }

        /** Moves to logical [position]. Positions inside dropped data throw IOException("trimmed"). */
        @Throws(IOException::class)
        fun seekTo(position: Long) {
            lock.withLock {
                if (position >= tb.initLen && position < tb.firstHeld) throw IOException("trimmed")
                pos = position
            }
        }

        /**
         * Returns bytes read (> 0), or -1 at end of stream. Blocks until data arrives.
         * @throws InterruptedIOException("timeout") if nothing arrives within [timeoutMs].
         * @throws IOException("trimmed") if the position is inside data already dropped.
         */
        @Throws(IOException::class)
        fun read(buf: ByteArray, off: Int, len: Int, timeoutMs: Long = 15_000): Int {
            if (len == 0) return 0
            var remainingNs = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            lock.lock()
            try {
                while (true) {
                    if (generation.ended || closedInput) return -1
                    val head = tb.init
                    if (head != null && pos < head.size) {
                        val n = minOf(len.toLong(), head.size - pos).toInt()
                        System.arraycopy(head, pos.toInt(), buf, off, n)
                        return advance(n)
                    }
                    if (pos < tb.end) {
                        if (pos < tb.firstHeld) throw IOException("trimmed")
                        val piece = tb.pieceAt(pos) ?: throw IOException("trimmed")
                        val o = (pos - piece.start).toInt()
                        val n = minOf(len, piece.bytes.size - o)
                        System.arraycopy(piece.bytes, o, buf, off, n)
                        return advance(n)
                    }
                    if (closed) return -1
                    if (remainingNs <= 0L) throw InterruptedIOException("timeout")
                    try {
                        remainingNs = changed.awaitNanos(remainingNs)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw InterruptedIOException("interrupted").apply { initCause(e) }
                    }
                }
            } finally {
                lock.unlock()
            }
        }

        /** Called under the lock after a read: moves the position and frees what every reader has passed. */
        private fun advance(n: Int): Int {
            pos += n
            tb.trim(keepBehind)
            return n
        }

        /** Stops this reader; its position no longer holds back the chunks other readers have passed. */
        fun close() {
            lock.withLock {
                if (closedInput) return
                closedInput = true
                tb.readers.remove(this)
                tb.trim(keepBehind)
            }
        }
    }

    companion object {
        const val UNREAD_CAP_BYTES = 192L * 1024 * 1024
        const val KEEP_BEHIND_BYTES = 16L * 1024 * 1024
    }
}
