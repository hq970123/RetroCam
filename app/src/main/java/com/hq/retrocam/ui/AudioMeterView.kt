package com.hq.retrocam.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.log10
import kotlin.math.max

/** Two-channel peak meter, -48 dBFS .. 0 dBFS, with a slow-falling peak hold. */
class AudioMeterView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val level = FloatArray(2)
    private val hold = FloatArray(2)
    private val bg = Paint().apply { color = Color.argb(70, 255, 255, 255) }
    private val green = Paint().apply { color = Color.rgb(90, 220, 110) }
    private val yellow = Paint().apply { color = Color.rgb(255, 200, 70) }
    private val red = Paint().apply { color = Color.rgb(255, 70, 60) }
    private val holdPaint = Paint().apply { color = Color.WHITE }

    private fun norm(peak: Float): Float {
        if (peak <= 0.00001f) return 0f
        val db = 20f * log10(peak)
        return ((db + 48f) / 48f).coerceIn(0f, 1f)
    }

    fun setLevels(l: Float, r: Float) {
        val v = floatArrayOf(norm(l), norm(r))
        for (i in 0..1) {
            level[i] = max(v[i], level[i] * 0.8f)
            hold[i] = max(v[i], hold[i] - 0.015f)
        }
        invalidate()
    }

    fun reset() { level.fill(0f); hold.fill(0f); invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val gap = height * 0.2f
        val bh = (height - gap) / 2f
        for (i in 0..1) {
            val top = i * (bh + gap)
            c.drawRect(0f, top, w, top + bh, bg)
            val x = level[i] * w
            // -12 dB and -3 dB boundaries
            val y12 = 36f / 48f * w
            val y3 = 45f / 48f * w
            c.drawRect(0f, top, minOf(x, y12), top + bh, green)
            if (x > y12) c.drawRect(y12, top, minOf(x, y3), top + bh, yellow)
            if (x > y3) c.drawRect(y3, top, x, top + bh, red)
            val hx = hold[i] * w
            if (hx > 1f) c.drawRect(hx - 2f, top, hx, top + bh, holdPaint)
        }
    }
}
