package com.deepbrowser.tv.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/** Native player for direct media URLs. D-pad: OK play/pause, Left/Right seek, Up controls, Down menu, Back exit. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : Activity() {
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val url = intent.data?.toString() ?: run { finish(); return }
        val props = PlayerHeaders.requestProperties(intent.getStringExtra(EXTRA_REFERER), intent.getStringExtra(EXTRA_COOKIES))
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(false)
            .apply { PlayerHeaders.userAgent(intent.getStringExtra(EXTRA_UA))?.let { setUserAgent(it) } }
        // Referer/Cookie are added per request and only for the page/media host. The one factory feeds progressive, HLS and DASH.
        val scoped = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec ->
            spec.withAdditionalHeaders(PlayerHeaders.forRequest(props, url, spec.uri.toString()))
        }
        player = ExoPlayer.Builder(this, renderers())
            .setMediaSourceFactory(DefaultMediaSourceFactory(scoped))
            .build()
        view = PlayerView(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK) // letterbox bars: black, not the app theme colour
            setShutterBackgroundColor(android.graphics.Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            this.player = this@PlayerActivity.player
            controllerShowTimeoutMs = 4000
            isFocusable = false
        }
        setContentView(view)
        player.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        player.prepare()
        player.playWhenReady = true
    }

    /** Extension (FFmpeg) audio renderer only as a fallback after MediaCodec, i.e. for AC-3/E-AC-3/DTS the device lacks. */
    private fun renderers() = DefaultRenderersFactory(this)
        .setEnableDecoderFallback(true)
        .setExtensionRendererMode(
            if (ffmpegAvailable()) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF,
        )

    // Reflection keeps a missing/incompatible extension (or its native lib) from ever crashing playback.
    private fun ffmpegAvailable(): Boolean = try {
        Class.forName("androidx.media3.decoder.ffmpeg.FfmpegLibrary").getMethod("isAvailable").invoke(null) as Boolean
    } catch (_: Throwable) { false }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (!::player.isInitialized) return super.dispatchKeyEvent(e)
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) { if (player.isPlaying) player.pause() else player.play(); view.showController() }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (e.action == KeyEvent.ACTION_DOWN) {
                    val step = PlayerLogic.seekStepMs(e.repeatCount)
                    val back = e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND
                    val dur = if (player.duration == C.TIME_UNSET) null else player.duration
                    player.seekTo(PlayerLogic.seekTarget(player.currentPosition, step, back, dur))
                    view.showController()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> { if (e.action == KeyEvent.ACTION_DOWN) view.showController(); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> { if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) showMenu(); return true }
        }
        return super.dispatchKeyEvent(e)
    }

    private fun showMenu() {
        val items = arrayOf("Speed", "Quality", "Audio", "Subtitles")
        AlertDialog.Builder(this).setItems(items) { _, i ->
            when (i) {
                0 -> speedMenu()
                1 -> trackMenu(C.TRACK_TYPE_VIDEO, "Quality")
                2 -> trackMenu(C.TRACK_TYPE_AUDIO, "Audio")
                else -> trackMenu(C.TRACK_TYPE_TEXT, "Subtitles")
            }
        }.show()
    }

    private fun speedMenu() {
        val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        AlertDialog.Builder(this).setTitle("Speed")
            .setItems(speeds.map { "${it}x" }.toTypedArray()) { _, i -> player.setPlaybackSpeed(speeds[i]) }.show()
    }

    private fun trackMenu(type: Int, title: String) {
        val groups = player.currentTracks.groups.filter { it.type == type && it.isSupported }
        val labels = ArrayList<String>(); val acts = ArrayList<() -> Unit>()
        val off = type == C.TRACK_TYPE_TEXT
        labels += if (off) "Off" else "Auto"
        acts += {
            val b = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
            player.trackSelectionParameters = (if (off) b.setTrackTypeDisabled(type, true) else b.setTrackTypeDisabled(type, false)).build()
        }
        for (g in groups) for (t in 0 until g.length) {
            if (!g.isTrackSupported(t)) continue
            val f = g.getTrackFormat(t)
            labels += when (type) {
                C.TRACK_TYPE_VIDEO -> PlayerLogic.videoLabel(f.height, f.bitrate, labels.size)
                else -> PlayerLogic.namedLabel(f.label, f.language, labels.size)
            }
            acts += {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(type, false)
                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, t)).build()
            }
        }
        AlertDialog.Builder(this).setTitle(title).setItems(labels.toTypedArray()) { _, i -> acts[i]() }.show()
    }

    override fun onStop() { super.onStop(); if (::player.isInitialized) player.pause() }
    override fun onDestroy() { super.onDestroy(); if (::player.isInitialized) player.release() }

    companion object {
        private const val EXTRA_REFERER = "referer"
        private const val EXTRA_COOKIES = "cookies"
        private const val EXTRA_UA = "ua"

        /** [referer], [cookies] and [userAgent] come from the page that exposed the stream and go on every media request. */
        fun open(ctx: Context, url: String, referer: String? = null, cookies: String? = null, userAgent: String? = null) =
            ctx.startActivity(
                Intent(ctx, PlayerActivity::class.java).setData(Uri.parse(url))
                    .putExtra(EXTRA_REFERER, referer).putExtra(EXTRA_COOKIES, cookies).putExtra(EXTRA_UA, userAgent),
            )
    }
}
