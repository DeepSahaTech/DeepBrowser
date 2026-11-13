package com.deepbrowser.tv.player

/** Pure seek/label rules for the player, kept free of ExoPlayer so they can be unit-tested. */
object PlayerLogic {
    /** 10s, then +10s every 4 key repeats, capped at 60s while a key is held. */
    fun seekStepMs(repeatCount: Int): Long = 10_000L * (1 + repeatCount / 4).coerceAtMost(6)

    /** Target position after a seek; [durationMs] null means unknown. */
    fun seekTarget(positionMs: Long, stepMs: Long, back: Boolean, durationMs: Long?): Long =
        (positionMs + if (back) -stepMs else stepMs).coerceIn(0, durationMs ?: Long.MAX_VALUE)

    /** Quality label: "720p (2500 kbps)", falling back to "Track N" when height is unknown. */
    fun videoLabel(height: Int, bitrate: Int, fallbackIndex: Int): String =
        if (height > 0) "${height}p" + if (bitrate > 0) " (${bitrate / 1000} kbps)" else "" else "Track $fallbackIndex"

    /** Audio/subtitle label: track label, else language, else "Track N". */
    fun namedLabel(label: String?, language: String?, fallbackIndex: Int): String =
        label ?: language ?: "Track $fallbackIndex"
}
