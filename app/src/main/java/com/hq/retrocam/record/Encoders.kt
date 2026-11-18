package com.hq.retrocam.record

import android.media.MediaCodecList
import android.media.MediaFormat

/** Picks a hardware video encoder for a size / frame rate. */
object Encoders {
    data class Choice(val mime: String, val bitrate: Int) {
        val label: String get() = if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) "HEVC" else "H.264"
    }

    private val list by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder } }

    fun supports(mime: String, w: Int, h: Int, fps: Int): Boolean = list.any { info ->
        info.supportedTypes.any { it.equals(mime, true) } && try {
            val vc = info.getCapabilitiesForType(mime).videoCapabilities
            vc != null && (vc.areSizeAndRateSupported(w, h, fps.toDouble()) || vc.areSizeAndRateSupported(h, w, fps.toDouble()))
        } catch (_: Throwable) { false }
    }

    private fun maxBitrate(mime: String): Int = list.filter { i -> i.supportedTypes.any { it.equals(mime, true) } }
        .maxOfOrNull { i -> try { i.getCapabilitiesForType(mime).videoCapabilities.bitrateRange.upper } catch (_: Throwable) { 0 } } ?: 0

    /** HEVC for 4K when available (smaller files), H.264 otherwise. Null if nothing can encode it. */
    fun choose(w: Int, h: Int, fps: Int, preferHevc: Boolean = w.toLong() * h >= 3840L * 2160): Choice? {
        val order = if (preferHevc) listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)
        else listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)
        val mime = order.firstOrNull { supports(it, w, h, fps) } ?: return null
        val bpp = if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) 0.14 else 0.2
        var br = (w.toDouble() * h * fps * bpp).toInt().coerceIn(16_000_000, 120_000_000)
        val cap = maxBitrate(mime)
        if (cap > 0) br = minOf(br, cap)
        return Choice(mime, br)
    }
}
