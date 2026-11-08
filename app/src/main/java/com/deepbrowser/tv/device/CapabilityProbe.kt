package com.deepbrowser.tv.device

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.media.MediaDrm
import android.os.Build
import android.view.Display
import android.view.WindowManager
import java.util.UUID

/** What this particular TV / stick can actually do. Drives which features the UI offers. */
data class DeviceCapabilities(
    val model: String,
    val androidApi: Int,
    val isLowRam: Boolean,
    val memoryClassMb: Int,
    val maxLiveTabs: Int,
    val widevineLevel: String,      // "L1", "L3" or "none"
    val hdrTypes: List<String>,     // e.g. HDR10, HLG, Dolby Vision
    val videoDecoders: List<String>,// e.g. H.264, HEVC, VP9, AV1
    val displayModes: List<String>, // e.g. 1920x1080@60
    val supportsSystemBlur: Boolean,
) {
    fun summary(): String = buildString {
        appendLine("Device: $model (API $androidApi)")
        appendLine("RAM class: ${memoryClassMb}MB, low-RAM: $isLowRam, live tabs: $maxLiveTabs")
        appendLine("Widevine: $widevineLevel")
        appendLine("HDR: ${hdrTypes.ifEmpty { listOf("none") }.joinToString()}")
        appendLine("Decoders: ${videoDecoders.joinToString()}")
        append("Display: ${displayModes.take(4).joinToString()}")
    }
}

object CapabilityProbe {
    private val WIDEVINE_UUID = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)

    fun probe(context: Context): DeviceCapabilities {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val lowRam = am.isLowRamDevice || am.memoryClass <= 128
        return DeviceCapabilities(
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidApi = Build.VERSION.SDK_INT,
            isLowRam = lowRam,
            memoryClassMb = am.memoryClass,
            maxLiveTabs = maxLiveTabs(lowRam, am.memoryClass),
            widevineLevel = widevineLevel(),
            hdrTypes = hdrTypes(context),
            videoDecoders = decoders(),
            displayModes = displayModes(context),
            supportsSystemBlur = Build.VERSION.SDK_INT >= 31 && !lowRam,
        )
    }

    fun maxLiveTabs(lowRam: Boolean, memoryClassMb: Int): Int = when {
        lowRam || memoryClassMb <= 192 -> 2
        memoryClassMb <= 256 -> 3
        else -> 4
    }

    private fun widevineLevel(): String = runCatching {
        if (!MediaDrm.isCryptoSchemeSupported(WIDEVINE_UUID)) return "none"
        val drm = MediaDrm(WIDEVINE_UUID)
        try { drm.getPropertyString("securityLevel") } finally { drm.release() }
    }.getOrDefault("none")

    @Suppress("DEPRECATION")
    private fun display(context: Context): Display? = runCatching {
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
    }.getOrNull()

    private fun hdrTypes(context: Context): List<String> {
        val caps = runCatching { display(context)?.hdrCapabilities?.supportedHdrTypes }.getOrNull()
            ?: return emptyList()
        return caps.toList().mapNotNull {
            when (it) {
                Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                else -> null
            }
        }
    }

    private fun decoders(): List<String> {
        val wanted = linkedMapOf(
            "video/avc" to "H.264", "video/hevc" to "HEVC", "video/x-vnd.on2.vp9" to "VP9",
            "video/av01" to "AV1", "video/dolby-vision" to "Dolby Vision",
        )
        val infos = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }.getOrNull()
            ?: return emptyList()
        return wanted.filter { (mime, _) ->
            infos.any { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
        }.values.toList()
    }

    private fun displayModes(context: Context): List<String> {
        val modes = runCatching { display(context)?.supportedModes }.getOrNull() ?: return emptyList()
        return modes.map { "${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate.toInt()}" }.distinct()
    }
}
