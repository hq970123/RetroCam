package com.hq.retrocam.camera

import android.hardware.camera2.CaptureRequest
import java.util.Locale
import kotlin.math.roundToInt

enum class ProParam { ISO, SHUTTER, EV, WB, FOCUS }

/** One slider position: its label and the value it sets (null = auto). */
data class Opt<T>(val label: String, val value: T?)

object ManualOptions {
    private val isoStops = intArrayOf(25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800)

    // shutter denominators (fractions) then whole seconds
    private val shutterFractions = intArrayOf(8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400, 320, 250, 200, 160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4, 3)
    private val shutterSeconds = floatArrayOf(0.5f, 0.6f, 0.8f, 1f, 1.3f, 1.6f, 2f, 2.5f, 3.2f, 4f, 5f, 6f, 8f, 10f, 15f, 20f, 30f)

    fun iso(caps: CameraCaps): List<Opt<Int>> {
        val r = caps.isoRange ?: return listOf(Opt("A", null))
        return listOf(Opt<Int>("A", null)) + isoStops.filter { it >= r.lower && it <= r.upper }.map { Opt(it.toString(), it) }
    }

    fun shutter(caps: CameraCaps, video: Boolean): List<Opt<Long>> {
        val r = caps.exposureRange ?: return listOf(Opt("A", null))
        val list = ArrayList<Opt<Long>>()
        list += Opt("A", null)
        for (d in shutterFractions) {
            val ns = 1_000_000_000L / d
            if (ns in r.lower..r.upper && (!video || ns <= 33_333_333L)) list += Opt("1/$d", ns)
        }
        if (!video) for (s in shutterSeconds) {
            val ns = (s * 1_000_000_000L).toLong()
            if (ns in r.lower..r.upper) list += Opt(if (s < 1f) String.format(Locale.US, "%.1fs", s) else "${trim(s)}s", ns)
        }
        return list
    }

    fun ev(caps: CameraCaps): List<Opt<Int>> {
        val r = caps.evRange
        if (r.lower == 0 && r.upper == 0) return listOf(Opt("0", 0))
        return (r.lower..r.upper).map { i ->
            val v = i * caps.evStep
            Opt(if (i == 0) "0" else String.format(Locale.US, "%+.1f", v), i)
        }
    }

    private val wbNames = linkedMapOf(
        CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT to "白炽灯",
        CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT to "暖荧光",
        CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT to "荧光灯",
        CaptureRequest.CONTROL_AWB_MODE_TWILIGHT to "黄昏",
        CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT to "日光",
        CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "阴天",
        CaptureRequest.CONTROL_AWB_MODE_SHADE to "阴影",
    )

    fun wb(caps: CameraCaps): List<Opt<Int>> {
        val list = arrayListOf(Opt("自动", CaptureRequest.CONTROL_AWB_MODE_AUTO))
        for ((mode, name) in wbNames) if (caps.awbModes.contains(mode)) list += Opt(name, mode)
        return list
    }

    fun focus(caps: CameraCaps): List<Opt<Float>> {
        val max = caps.minFocusDiopters
        if (max <= 0f) return listOf(Opt("A", null))
        val steps = 60
        val list = arrayListOf<Opt<Float>>(Opt("A", null))
        for (i in 0..steps) {
            val d = max * i / steps
            list += Opt(focusLabel(d), d)
        }
        return list
    }

    fun focusLabel(d: Float): String = when {
        d <= 0.01f -> "∞"
        1f / d >= 10f -> "${(1f / d).roundToInt()}m"
        1f / d >= 1f -> String.format(Locale.US, "%.1fm", 1f / d)
        else -> "${(100f / d).roundToInt()}cm"
    }

    fun shutterLabel(ns: Long): String {
        if (ns <= 0) return "--"
        return if (ns >= 500_000_000L) String.format(Locale.US, "%.1fs", ns / 1e9) else "1/${(1e9 / ns).roundToInt()}"
    }

    private fun trim(f: Float) = if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()
}
