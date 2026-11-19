package com.hq.retrocam.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/** Draws grid, level and histogram over the preview rect. Never consumes touches. */
class OverlayView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var rect = RectF()
        set(v) { if (v != field) { field = RectF(v); invalidate() } }
    var grid = false
        set(v) { field = v; invalidate() }
    var level = false
        set(v) { field = v; invalidate() }
    var showHistogram = false
        set(v) { field = v; invalidate() }

    /** Device roll in degrees, 0 = upright portrait, positive = left side down. */
    var roll = 0f
        set(v) { field = v; if (level) invalidate() }

    private var hist: IntArray? = null
    fun setHistogram(bins: IntArray) { hist = bins; if (showHistogram) invalidate() }

    private val dp = resources.displayMetrics.density
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 255, 255, 255); strokeWidth = 1f * dp }
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f * dp; strokeCap = Paint.Cap.ROUND }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12f * dp; textAlign = Paint.Align.CENTER }
    private val histBg = Paint().apply { color = Color.argb(110, 0, 0, 0) }
    private val histPaints = arrayOf(
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 235, 235, 235) },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 255, 60, 60) },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 60, 255, 60) },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 80, 120, 255) },
    )
    private val path = Path()
    private val maskPath = Path()
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    /** Rounds the viewfinder corners by painting black outside a rounded rect. */
    var corner = 0f

    override fun onDraw(c: Canvas) {
        val r = rect
        if (r.width() <= 0f) return
        if (grid) {
            for (i in 1..2) {
                val x = r.left + r.width() * i / 3f
                val y = r.top + r.height() * i / 3f
                c.drawLine(x, r.top, x, r.bottom, gridPaint)
                c.drawLine(r.left, y, r.right, y, gridPaint)
            }
        }
        if (level) drawLevel(c, r)
        if (showHistogram) drawHistogram(c, r)
        if (corner > 0f) {
            maskPath.reset()
            maskPath.fillType = Path.FillType.EVEN_ODD
            maskPath.addRect(r, Path.Direction.CW)
            maskPath.addRoundRect(r, corner, corner, Path.Direction.CW)
            c.drawPath(maskPath, maskPaint)
        }
    }

    private fun drawLevel(c: Canvas, r: RectF) {
        val nearest = (roll / 90f).roundToInt() * 90f
        val dev = roll - nearest
        if (abs(dev) > 30f) return
        val ok = abs(dev) < 1f
        val cx = r.centerX(); val cy = r.centerY()
        val half = minOf(r.width(), r.height()) * 0.22f
        // reference ticks fixed to the frame
        levelPaint.color = Color.argb(160, 255, 255, 255)
        c.save()
        c.rotate(nearest, cx, cy)
        c.drawLine(cx - half - 18 * dp, cy, cx - half, cy, levelPaint)
        c.drawLine(cx + half, cy, cx + half + 18 * dp, cy, levelPaint)
        c.restore()
        // live horizon
        levelPaint.color = if (ok) Color.rgb(80, 230, 120) else Color.rgb(240, 74, 44)
        c.save()
        c.rotate(roll, cx, cy)
        c.drawLine(cx - half, cy, cx + half, cy, levelPaint)
        c.restore()
        c.save()
        c.rotate(nearest, cx, cy)
        c.drawText(String.format("%.1f°", dev), cx, cy - 10 * dp, textPaint)
        c.restore()
    }

    private fun drawHistogram(c: Canvas, r: RectF) {
        val bins = hist ?: return
        val w = 120f * dp; val h = 56f * dp
        val left = r.left + 10 * dp; val top = r.top + 10 * dp
        c.drawRect(left, top, left + w, top + h, histBg)
        for (ch in 3 downTo 0) {
            var max = 1
            for (i in 1 until 63) max = maxOf(max, bins[ch * 64 + i])
            path.reset()
            path.moveTo(left, top + h)
            for (i in 0 until 64) {
                val v = minOf(1f, bins[ch * 64 + i].toFloat() / max)
                path.lineTo(left + w * i / 63f, top + h - v * h)
            }
            path.lineTo(left + w, top + h)
            path.close()
            c.drawPath(path, histPaints[ch])
        }
    }
}
