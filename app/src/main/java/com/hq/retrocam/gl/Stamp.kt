package com.hq.retrocam.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Renders the date stamp text into a premultiplied ARGB bitmap. */
object Stamp {
    fun text(kind: StampKind, now: Date = Date()): List<String> = when (kind) {
        StampKind.CAMCORDER -> listOf(
            SimpleDateFormat("a h:mm:ss", Locale.US).format(now),
            SimpleDateFormat("MMM. dd yyyy", Locale.US).format(now).uppercase(Locale.US),
        )
        StampKind.FILM -> listOf(SimpleDateFormat("''yy MM dd", Locale.US).format(now))
    }

    /** Bitmap with aspect ratio ~ 4:1 (film) or 3:1 (camcorder). Height in px. */
    fun render(kind: StampKind, height: Int, now: Date = Date()): Bitmap {
        val lines = text(kind, now)
        val width = if (kind == StampKind.FILM) height * 4 else height * 3
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        if (kind == StampKind.FILM) {
            p.textSize = height * 0.62f
            p.color = Color.rgb(255, 150, 40)
            p.setShadowLayer(height * 0.12f, 0f, 0f, Color.argb(200, 255, 90, 0))
            p.letterSpacing = 0.06f
            c.drawText(lines[0], width - height * 0.15f, height * 0.72f, p)
        } else {
            p.textSize = height * 0.36f
            val lineH = height * 0.44f
            for ((i, line) in lines.withIndex()) {
                val y = height * 0.40f + i * lineH
                // black outline then white fill, like a camcorder OSD
                p.style = Paint.Style.STROKE
                p.strokeWidth = height * 0.05f
                p.color = Color.argb(220, 0, 0, 0)
                c.drawText(line, width - height * 0.1f, y, p)
                p.style = Paint.Style.FILL
                p.color = Color.rgb(245, 245, 245)
                c.drawText(line, width - height * 0.1f, y, p)
            }
        }
        return bmp
    }

    /** Fraction of viewer frame width the stamp should span. */
    fun widthFrac(kind: StampKind) = if (kind == StampKind.FILM) 0.30f else 0.36f
}
