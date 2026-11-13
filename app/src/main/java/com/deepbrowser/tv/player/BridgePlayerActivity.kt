package com.deepbrowser.tv.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView

/**
 * Plays a page's MSE video from a [BridgeSession]. Same keys and renderers as [PlayerActivity]; the page is told
 * about every seek, pause and play so both sides stay in step.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class BridgePlayerActivity : Activity() {
    private var session: BridgeSession? = null
    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var buffering: TextView
    private var stopped = false
    private var seekPending = false
    // Right after a rebuild the player reports position 0 until the first sample, so a quick next press uses the last target.
    private var lastTargetMs = 0L
    private var lastSeekAt = 0L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val syncTick = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && p.isPlaying && !stopped) session?.sync(p.currentPosition / 1000.0)
            handler.postDelayed(this, 10_000)
        }
    }
    // A start or seek that gets no READY in time shows an error instead of "Buffering..." forever.
    private val noData = Runnable {
        val state = player?.playbackState
        if (!stopped && state != Player.STATE_READY && state != Player.STATE_ENDED) {
            seekPending = true
            buffering.text = "Playback error: no data"
            buffering.visibility = android.view.View.VISIBLE
        }
    }

    private fun armNoData() {
        handler.removeCallbacks(noData)
        handler.postDelayed(noData, NO_DATA_MS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val s = BridgeSessions.get(intent.getStringExtra(EXTRA_SESSION) ?: "") ?: run { finish(); return }
        session = s
        val p = ExoPlayer.Builder(this, renderers()).build()
        player = p
        view = PlayerView(this).apply {
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            this.player = p
            controllerShowTimeoutMs = 4000
            isFocusable = false
        }
        buffering = TextView(this).apply {
            text = "Buffering..."
            textSize = 24f
            gravity = Gravity.CENTER
            visibility = android.view.View.GONE
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
        }
        setContentView(FrameLayout(this).apply { addView(view); addView(buffering) })
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                android.util.Log.w(TAG, "state=$playbackState pos=${p.currentPosition}")
                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                    handler.removeCallbacks(noData)
                    hideBuffering()
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e(TAG, "playback error ${error.errorCodeName}", error)
                buffering.text = "Playback error: ${error.errorCodeName}"
                buffering.visibility = android.view.View.VISIBLE
            }
        })
        p.setMediaSource(buildSource(s, s.gen))
        p.prepare()
        // Fragments carry absolute timestamps: align the player clock with them, otherwise it starts at 0 and waits
        // for the first frame's time. On this unseekable stream the seek reopens it from byte 0, which the store
        // still holds (StreamStore keeps a window behind its readers).
        p.seekTo((s.startTime * 1000).toLong())
        p.playWhenReady = true
        s.onResetListener = { gen, t -> runOnUiThread { rebuild(gen, t) } }
        handler.postDelayed(syncTick, 10_000)
        armNoData()
    }

    private fun rebuild(gen: Int, t: Double) {
        val s = session ?: return
        val p = player ?: return
        if (stopped) return
        android.util.Log.w(TAG, "rebuild gen=$gen t=$t")
        p.setMediaSource(buildSource(s, gen))
        p.prepare()
        p.seekTo((t * 1000).toLong())   // align the clock with the replayed fragment's timestamps (see onCreate)
        p.play()
        armNoData()
    }

    /** One progressive source per track (growing, non-seekable), merged when there are audio and video tracks. */
    private fun buildSource(s: BridgeSession, gen: Int): MediaSource {
        val sources = s.tracks.map { track ->
            ProgressiveMediaSource.Factory(GrowingDataSourceFactory(s.store, track.sb), DefaultExtractorsFactory())
                .createMediaSource(MediaItem.fromUri("bridge://${s.id}/${track.sb}?g=$gen"))
        }
        return if (sources.size == 1) sources[0] else MergingMediaSource(*sources.toTypedArray())
    }

    /** Copied from PlayerActivity: FFmpeg audio only as a fallback after MediaCodec. */
    private fun renderers() = DefaultRenderersFactory(this)
        .setEnableDecoderFallback(true)
        .setExtensionRendererMode(
            if (ffmpegAvailable()) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF,
        )

    private fun ffmpegAvailable(): Boolean = try {
        Class.forName("androidx.media3.decoder.ffmpeg.FfmpegLibrary").getMethod("isAvailable").invoke(null) as Boolean
    } catch (_: Throwable) { false }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val p = player ?: return super.dispatchKeyEvent(e)
        val s = session
        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) leave()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                    if (p.isPlaying) { p.pause(); s?.pause() } else { p.play(); s?.play() }
                    view.showController()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (e.action == KeyEvent.ACTION_DOWN) {
                    val step = PlayerLogic.seekStepMs(e.repeatCount)
                    val back = e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND
                    val dur = if (p.duration == C.TIME_UNSET) null else p.duration
                    val now = android.os.SystemClock.uptimeMillis()
                    val base = if (now - lastSeekAt < 4_000) lastTargetMs else p.currentPosition
                    val target = PlayerLogic.seekTarget(base, step, back, dur)
                    lastTargetMs = target; lastSeekAt = now
                    s?.seek(target / 1000.0)
                    showBuffering()
                    view.showController()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> { if (e.action == KeyEvent.ACTION_DOWN) view.showController(); return true }
        }
        return super.dispatchKeyEvent(e)
    }

    private fun showBuffering() {
        seekPending = true
        buffering.visibility = android.view.View.VISIBLE
        armNoData()
    }

    private fun hideBuffering() {
        if (!seekPending) return
        seekPending = false
        buffering.visibility = android.view.View.GONE
    }

    /** Back: tell the page to stop, drop the session and return to the page. */
    private fun leave() {
        if (stopped) return
        stopped = true
        session?.let { it.stop(); BridgeSessions.remove(it.id) }
        finish()
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) leave() else player?.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(syncTick)
        handler.removeCallbacks(noData)
        super.onDestroy()
        session?.onResetListener = null
        player?.release()
        player = null
        val s = session
        if (!stopped) { stopped = true; s?.stop() }
        s?.let { BridgeSessions.remove(it.id) }
    }

    companion object {
        private const val TAG = "BridgePlayer"
        private const val EXTRA_SESSION = "session"
        private const val NO_DATA_MS = 20_000L

        fun open(context: Context, sessionId: String) =
            context.startActivity(Intent(context, BridgePlayerActivity::class.java).putExtra(EXTRA_SESSION, sessionId))
    }
}
