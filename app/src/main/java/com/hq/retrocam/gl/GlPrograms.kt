package com.hq.retrocam.gl

import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object Gl {
    fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(s)
            GLES30.glDeleteShader(s)
            throw RuntimeException("shader compile failed: $log")
        }
        return s
    }

    fun program(vs: String, fs: String): Int {
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glBindAttribLocation(p, 0, "aPos")
        GLES30.glBindAttribLocation(p, 1, "aUv")
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("link failed: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    fun floatBuffer(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

    fun createOesTexture(): Int {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0])
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    fun createTexture2D(bmp: Bitmap?): Int {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        if (bmp != null) GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        return t[0]
    }

    fun deleteTexture(id: Int) {
        if (id != 0) GLES30.glDeleteTextures(1, intArrayOf(id), 0)
    }

    fun checkError(tag: String) {
        val e = GLES30.glGetError()
        if (e != GLES30.GL_NO_ERROR) Log.w("RetroCam", "GL error 0x${Integer.toHexString(e)} at $tag")
    }
}

/** Full-screen quad: pos(x,y) + uv. */
object Quad {
    val full: FloatBuffer = Gl.floatBuffer(
        floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )
    )

    fun draw(buf: FloatBuffer) {
        buf.position(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, buf)
        GLES30.glEnableVertexAttribArray(0)
        buf.position(2)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, buf)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        buf.position(0)
    }
}

class LookProgram(oes: Boolean) {
    private val target = if (oes) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES30.GL_TEXTURE_2D
    val id = Gl.program(Shaders.VERTEX, if (oes) Shaders.FRAGMENT_OES else Shaders.FRAGMENT_2D)
    private val uTex = GLES30.glGetUniformLocation(id, "uTex")
    private val uTexMatrix = GLES30.glGetUniformLocation(id, "uTexMatrix")
    private val uStyle = GLES30.glGetUniformLocation(id, "uStyle")
    private val uIntensity = GLES30.glGetUniformLocation(id, "uIntensity")
    private val uGrain = GLES30.glGetUniformLocation(id, "uGrain")
    private val uVignette = GLES30.glGetUniformLocation(id, "uVignette")
    private val uLeak = GLES30.glGetUniformLocation(id, "uLeak")
    private val uTime = GLES30.glGetUniformLocation(id, "uTime")
    private val uAspect = GLES30.glGetUniformLocation(id, "uAspect")
    private val uFlipY = GLES30.glGetUniformLocation(id, "uFlipY")
    private val uCrop = GLES30.glGetUniformLocation(id, "uCrop")
    private val uPeaking = GLES30.glGetUniformLocation(id, "uPeaking")
    private val uZebra = GLES30.glGetUniformLocation(id, "uZebra")
    private val uTexel = GLES30.glGetUniformLocation(id, "uTexel")

    fun draw(
        tex: Int, texMatrix: FloatArray, p: LookParams, time: Float, aspect: Float, flipY: Boolean = false,
        crop: FloatArray = NO_CROP, peaking: Boolean = false, zebra: Boolean = false, texelX: Float = 0.001f, texelY: Float = 0.001f,
    ) {
        GLES30.glUseProgram(id)
        GLES30.glUniform4f(uCrop, crop[0], crop[1], crop[2], crop[3])
        GLES30.glUniform1f(uPeaking, if (peaking) 1f else 0f)
        GLES30.glUniform1f(uZebra, if (zebra) 1f else 0f)
        GLES30.glUniform2f(uTexel, texelX, texelY)
        GLES30.glUseProgram(id)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(target, tex)
        GLES30.glUniform1i(uTex, 0)
        GLES30.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES30.glUniform1i(uStyle, p.style)
        GLES30.glUniform1f(uIntensity, p.intensity)
        GLES30.glUniform1f(uGrain, p.grain)
        GLES30.glUniform1f(uVignette, p.vignette)
        GLES30.glUniform1f(uLeak, p.leak)
        GLES30.glUniform1f(uTime, time)
        GLES30.glUniform1f(uAspect, aspect)
        GLES30.glUniform1f(uFlipY, if (flipY) -1f else 1f)
        Quad.draw(Quad.full)
    }
}

val NO_CROP = floatArrayOf(0f, 0f, 1f, 1f)

class OverlayProgram {
    val id = Gl.program(Shaders.VERTEX, Shaders.OVERLAY_FRAGMENT)
    private val uTex = GLES30.glGetUniformLocation(id, "uTex")
    private val uFlipY = GLES30.glGetUniformLocation(id, "uFlipY")
    private val buf: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    /**
     * Draws [tex] (a premultiplied bitmap texture of size bw x bh) so that, once the
     * frame is rotated by [rotation] degrees clockwise for viewing, it sits upright in
     * the bottom-right corner, [widthFrac] of the viewer's frame width wide.
     * Frame (content) aspect = contentW / contentH before rotation.
     */
    fun drawStamp(tex: Int, bw: Int, bh: Int, contentW: Int, contentH: Int, rotation: Int, widthFrac: Float, flipY: Boolean = false) {
        val rotated = rotation == 90 || rotation == 270
        val viewW = if (rotated) contentH else contentW
        val viewH = if (rotated) contentW else contentH
        val w = widthFrac
        val h = w * viewW / viewH * bh / bw
        val margin = 0.045f
        // viewer space, y down: rect
        val x1 = 1f - margin; val x0 = x1 - w
        val yb = 1f - margin * viewW / viewH; val yt = yb - h
        // corners: (viewer x, viewer y, tex u, tex v) with v=0 at bottom of bitmap
        val corners = arrayOf(
            floatArrayOf(x0, yb, 0f, 0f),
            floatArrayOf(x1, yb, 1f, 0f),
            floatArrayOf(x0, yt, 0f, 1f),
            floatArrayOf(x1, yt, 1f, 1f),
        )
        buf.clear()
        for (c in corners) {
            val vx = c[0]; val vy = c[1]
            // viewer -> content (y down)
            val (cx, cy) = when (rotation) {
                90 -> Pair(vy, 1f - vx)
                180 -> Pair(1f - vx, 1f - vy)
                270 -> Pair(1f - vy, vx)
                else -> Pair(vx, vy)
            }
            buf.put(cx * 2f - 1f)
            buf.put(1f - cy * 2f)
            buf.put(c[2]); buf.put(c[3])
        }
        buf.position(0)
        GLES30.glUseProgram(id)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glUniform1i(uTex, 0)
        GLES30.glUniform1f(uFlipY, if (flipY) -1f else 1f)
        Quad.draw(buf)
        GLES30.glDisable(GLES30.GL_BLEND)
    }
}
