package com.hq.retrocam.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Border(val label: String) {
    NONE("无边框"),
    WHITE("经典水印"),
    BLACK("经典黑"),
    POLAROID("拍立得"),
    FILM("胶片边"),
    PUNCH("打卡水印"),
    CORNER("角标水印"),
    FROST("毛玻璃"),
}

/** What the watermark prints. Zero / blank fields are left out. */
data class ShotInfo(
    val model: String,
    val iso: Int = 0,
    val exposureNs: Long = 0,
    val focalMm: Int = 0,
    val aperture: Float = 0f,
    val look: String = "",
    val time: Date = Date(),
) {
    fun exposureLine(): String {
        val parts = ArrayList<String>()
        if (focalMm > 0) parts += "${focalMm}mm"
        if (aperture > 0f) parts += "f/" + String.format(Locale.US, "%.1f", aperture).removeSuffix(".0")
        if (exposureNs > 0) parts += shutter(exposureNs)
        if (iso > 0) parts += "ISO$iso"
        return parts.joinToString("  ")
    }

    private fun shutter(ns: Long): String {
        val s = ns / 1e9
        return if (s >= 0.5) String.format(Locale.US, "%.1fs", s).replace(".0s", "s") else "1/" + Math.round(1.0 / s) + "s"
    }
}

object Borders {
    fun apply(src: Bitmap, kind: Border, info: ShotInfo): Bitmap = when (kind) {
        Border.NONE -> src
        Border.WHITE -> bar(src, info, Color.WHITE, 0xFF111111.toInt(), 0xFF8A8A8A.toInt())
        Border.BLACK -> bar(src, info, 0xFF0E0E0E.toInt(), Color.WHITE, 0xFF9A9A9A.toInt())
        Border.POLAROID -> polaroid(src, info)
        Border.FILM -> film(src, info)
        Border.PUNCH -> punch(src, info)
        Border.CORNER -> corner(src, info)
        Border.FROST -> frost(src, info)
    }.also { if (it !== src) src.recycle() }

    private fun paint(color: Int, size: Float, bold: Boolean = false, tf: Typeface = Typeface.SANS_SERIF) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = Typeface.create(tf, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun date(d: Date, p: String) = SimpleDateFormat(p, Locale.US).format(d)

    /** Bottom info bar: model + date on the left, exposure + look on the right. */
    private fun bar(src: Bitmap, info: ShotInfo, bg: Int, fg: Int, sub: Int): Bitmap {
        val w = src.width; val h = src.height
        val s = minOf(w, h).toFloat()
        val barH = (s * 0.14f).toInt()
        val out = Bitmap.createBitmap(w, h + barH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(bg)
        c.drawBitmap(src, 0f, 0f, null)
        val pad = s * 0.045f
        val big = paint(fg, s * 0.034f, bold = true)
        val small = paint(sub, s * 0.024f)
        val y1 = h + barH * 0.47f
        val y2 = h + barH * 0.78f
        val sp = info.model.indexOf(' ')
        if (sp > 0) {
            val brand = info.model.substring(0, sp)
            val brandP = paint(ACCENT, s * 0.034f, bold = true)
            c.drawText(brand, pad, y1, brandP)
            c.drawText(info.model.substring(sp), pad + brandP.measureText(brand), y1, big)
        } else c.drawText(info.model, pad, y1, big)
        c.drawText(date(info.time, "yyyy.MM.dd  HH:mm"), pad, y2, small)
        val exp = info.exposureLine().ifBlank { "RetroCam" }
        val rx = w - pad
        val ew = big.measureText(exp)
        c.drawText(exp, rx - ew, y1, big)
        val look = if (info.look.isBlank()) "RetroCam" else "RetroCam · " + info.look
        c.drawText(look, rx - small.measureText(look), y2, small)
        // divider between the two blocks
        val divX = rx - maxOf(ew, small.measureText(look)) - pad * 0.6f
        if (divX > pad + big.measureText(info.model) + pad * 0.6f) {
            c.drawRect(divX, h + barH * 0.28f, divX + maxOf(2f, s * 0.002f), h + barH * 0.82f, paint(sub, 1f))
        }
        return out
    }

    private fun polaroid(src: Bitmap, info: ShotInfo): Bitmap {
        val w = src.width; val h = src.height
        val s = minOf(w, h).toFloat()
        val m = (s * 0.055f).toInt()
        val bottom = (s * 0.26f).toInt()
        val out = Bitmap.createBitmap(w + 2 * m, h + m + bottom, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFFF6F3EC.toInt())
        c.drawBitmap(src, m.toFloat(), m.toFloat(), null)
        val hand = paint(0xFF3A3A44.toInt(), s * 0.05f, tf = Typeface.SERIF).apply { textSkewX = -0.22f }
        val text = date(info.time, "MMM d, yyyy").lowercase(Locale.US)
        c.drawText(text, m + s * 0.04f, h + m + bottom * 0.58f, hand)
        return out
    }

    private fun film(src: Bitmap, info: ShotInfo): Bitmap {
        val w = src.width; val h = src.height
        val s = minOf(w, h).toFloat()
        val landscape = w >= h
        // rebate along the long edges, sprocket holes in the rebate
        val r = (s * 0.11f).toInt()
        val side = (s * 0.025f).toInt()
        val ow = if (landscape) w + 2 * side else w + 2 * r
        val oh = if (landscape) h + 2 * r else h + 2 * side
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFF121010.toInt())
        val ox = if (landscape) side else r
        val oy = if (landscape) r else side
        c.drawBitmap(src, ox.toFloat(), oy.toFloat(), null)
        val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2E2A28.toInt() }
        val hw = s * 0.032f; val hh = s * 0.045f
        val step = s * 0.085f
        val text = paint(0xFFE89A3C.toInt(), s * 0.026f, bold = true, tf = Typeface.MONOSPACE)
        val label = "RC 400  ▸ " + (1 + (info.time.time / 1000 % 36)) + "A   " + date(info.time, "yy.MM.dd")
        if (landscape) {
            var x = step / 2
            while (x < ow) {
                c.drawRoundRect(RectF(x, r * 0.18f, x + hw, r * 0.18f + hh), hw * 0.2f, hw * 0.2f, hole)
                c.drawRoundRect(RectF(x, oh - r * 0.18f - hh, x + hw, oh - r * 0.18f), hw * 0.2f, hw * 0.2f, hole)
                x += step
            }
            c.drawText(label, side + s * 0.03f, oh - r * 0.18f - hh - s * 0.012f, text)
        } else {
            var y = step / 2
            while (y < oh) {
                c.drawRoundRect(RectF(r * 0.18f, y, r * 0.18f + hh, y + hw), hw * 0.2f, hw * 0.2f, hole)
                c.drawRoundRect(RectF(ow - r * 0.18f - hh, y, ow - r * 0.18f, y + hw), hw * 0.2f, hw * 0.2f, hole)
                y += step
            }
            c.save()
            c.rotate(90f, ow - r * 0.5f, side + s * 0.03f)
            c.drawText(label, ow - r * 0.5f, side + s * 0.03f, text)
            c.restore()
        }
        return out
    }

    private const val ACCENT = 0xFFF04A2C.toInt()

    private fun shadowed(color: Int, size: Float, bold: Boolean = false) = paint(color, size, bold).apply {
        setShadowLayer(size * 0.18f, 0f, size * 0.05f, 0x99000000.toInt())
    }

    /** Check-in style: big time, divider, date + weekday, look line; drawn on the photo. */
    private fun punch(src: Bitmap, info: ShotInfo): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val s = minOf(out.width, out.height).toFloat()
        val pad = s * 0.06f
        val big = shadowed(Color.WHITE, s * 0.13f, bold = true)
        val mid = shadowed(Color.WHITE, s * 0.036f, bold = true)
        val small = shadowed(0xEEFFFFFF.toInt(), s * 0.03f)
        val baseY = out.height - pad - s * 0.06f
        val time = date(info.time, "HH:mm")
        c.drawText(time, pad, baseY, big)
        val tx = pad + big.measureText(time) + s * 0.03f
        c.drawRect(tx, baseY - s * 0.095f, tx + maxOf(2f, s * 0.004f), baseY - s * 0.005f, shadowed(Color.WHITE, 1f))
        c.drawText(date(info.time, "yyyy.MM.dd"), tx + s * 0.03f, baseY - s * 0.055f, mid)
        c.drawText(SimpleDateFormat("EEEE", Locale.CHINA).format(info.time), tx + s * 0.03f, baseY - s * 0.005f, small)
        val line = listOf(info.model, info.look).filter { it.isNotBlank() }.joinToString(" · ")
        c.drawText(line, pad, baseY + s * 0.055f, small)
        return out
    }

    /** Small date + model in the bottom-right corner, on the photo. */
    private fun corner(src: Bitmap, info: ShotInfo): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val s = minOf(out.width, out.height).toFloat()
        val pad = s * 0.04f
        val p1 = shadowed(Color.WHITE, s * 0.026f, bold = true)
        val p2 = shadowed(0xDDFFFFFF.toInt(), s * 0.02f)
        val l1 = info.model
        val l2 = date(info.time, "yyyy.MM.dd HH:mm") + (info.exposureLine().takeIf { it.isNotBlank() }?.let { "  $it" } ?: "")
        c.drawText(l1, out.width - pad - p1.measureText(l1), out.height - pad - s * 0.032f, p1)
        c.drawText(l2, out.width - pad - p2.measureText(l2), out.height - pad, p2)
        return out
    }

    /** Photo floating on a blurred, darkened copy of itself, with centred info below. */
    private fun frost(src: Bitmap, info: ShotInfo): Bitmap {
        val w = src.width; val h = src.height
        val s = minOf(w, h).toFloat()
        val m = (s * 0.08f).toInt()
        val bottom = (s * 0.2f).toInt()
        val ow = w + 2 * m; val oh = h + m + bottom
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        // cheap blur: shrink a lot, then scale back up with filtering (twice for smoothness)
        val tiny = Bitmap.createScaledBitmap(src, maxOf(4, w / 48), maxOf(4, h / 48), true)
        val mid = Bitmap.createScaledBitmap(tiny, maxOf(8, w / 12), maxOf(8, h / 12), true)
        c.drawBitmap(mid, null, RectF(-m.toFloat(), -m.toFloat(), ow + m.toFloat(), oh + m.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        tiny.recycle(); mid.recycle()
        c.drawColor(0x55000000)
        val dst = RectF(m.toFloat(), m.toFloat(), (m + w).toFloat(), (m + h).toFloat())
        val r = s * 0.025f
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); setShadowLayer(s * 0.03f, 0f, s * 0.01f, 0x88000000.toInt()) }
        c.drawRoundRect(dst, r, r, shadow)
        c.save()
        c.clipPath(android.graphics.Path().apply { addRoundRect(dst, r, r, android.graphics.Path.Direction.CW) })
        c.drawBitmap(src, m.toFloat(), m.toFloat(), null)
        c.restore()
        val t1 = paint(Color.WHITE, s * 0.04f, bold = true).apply { textAlign = Paint.Align.CENTER }
        val t2 = paint(0xCCFFFFFF.toInt(), s * 0.026f).apply { textAlign = Paint.Align.CENTER }
        c.drawText(info.model, ow / 2f, h + m + bottom * 0.45f, t1)
        val l2 = info.exposureLine().ifBlank { date(info.time, "yyyy.MM.dd HH:mm") }
        c.drawText(l2, ow / 2f, h + m + bottom * 0.72f, t2)
        return out
    }
}
