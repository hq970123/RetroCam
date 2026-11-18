package com.hq.retrocam.gl

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.hq.retrocam.record.VideoRecorder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor

/**
 * Owns the GL thread: camera SurfaceTexture -> look shader -> screen (+ encoder).
 * Content coordinates are upright portrait (the SurfaceTexture matrix carries the
 * sensor rotation / front-camera mirroring).
 */
class CameraRenderer(private val params: LookParams) {
    private val tag = "RetroCam.GL"
    private val thread = HandlerThread("gl").apply { start() }
    val handler = Handler(thread.looper)
    val executor = Executor { handler.post(it) }

    private lateinit var egl: EglCore
    private lateinit var pbuffer: EGLSurface
    private lateinit var oesProgram: LookProgram
    private var stillProgram: LookProgram? = null
    private lateinit var overlay: OverlayProgram
    private var oesTex = 0
    lateinit var surfaceTexture: SurfaceTexture
        private set

    private var displaySurface: EGLSurface? = null
    private var displayW = 0
    private var displayH = 0

    @Volatile var contentW = 1080
    @Volatile var contentH = 1440
    @Volatile var orientation = 0

    private var encSurface: EGLSurface? = null
    private var recorder: VideoRecorder? = null
    private var encW = 0
    private var encH = 0
    private var recRotation = 0

    private var stampTex = 0
    private var stampKey = ""
    private var stampBw = 1
    private var stampBh = 1

    private val texMatrix = FloatArray(16)
    private val t0 = System.nanoTime()
    private var frameReady = false

    /** Content rect of the preview inside the view, in view pixels (for tap mapping). */
    @Volatile var previewRect = android.graphics.RectF()

    fun start(onReady: (SurfaceTexture) -> Unit) {
        handler.post {
            egl = EglCore()
            pbuffer = egl.createPbuffer(1, 1)
            egl.makeCurrent(pbuffer)
            oesProgram = LookProgram(true)
            overlay = OverlayProgram()
            oesTex = Gl.createOesTexture()
            surfaceTexture = SurfaceTexture(oesTex)
            surfaceTexture.setOnFrameAvailableListener({ drawFrame() }, handler)
            onReady(surfaceTexture)
        }
    }

    fun setDisplaySurface(surface: Surface, w: Int, h: Int) = handler.post {
        displaySurface?.let { egl.releaseSurface(it) }
        displaySurface = egl.createWindowSurface(surface)
        displayW = w; displayH = h
        if (frameReady) drawFrame(updateTex = false)
    }

    fun resizeDisplay(w: Int, h: Int) = handler.post { displayW = w; displayH = h }

    fun clearDisplaySurface(done: () -> Unit) = handler.post {
        displaySurface?.let {
            egl.makeCurrent(pbuffer)
            egl.releaseSurface(it)
        }
        displaySurface = null
        done()
    }

    fun setContentSize(w: Int, h: Int) { contentW = w; contentH = h }

    private fun updateStamp() {
        if (!params.dateStamp) return
        val kind = params.stampKind
        val key = kind.name + Stamp.text(kind).joinToString("|")
        if (key == stampKey && stampTex != 0) return
        val bmp = Stamp.render(kind, 128)
        if (stampTex == 0) stampTex = Gl.createTexture2D(bmp) else {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, stampTex)
            android.opengl.GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        }
        stampBw = bmp.width; stampBh = bmp.height
        bmp.recycle()
        stampKey = key
    }

    private fun drawFrame(updateTex: Boolean = true) {
        try {
            if (updateTex) {
                if (displaySurface == null && encSurface == null) {
                    // still need to consume the frame so the camera keeps streaming
                    egl.makeCurrent(pbuffer)
                    surfaceTexture.updateTexImage()
                    return
                }
                egl.makeCurrent(displaySurface ?: encSurface!!)
                surfaceTexture.updateTexImage()
                surfaceTexture.getTransformMatrix(texMatrix)
                frameReady = true
            }
            val time = ((System.nanoTime() - t0) / 1e9).toFloat() % 1000f
            val aspect = contentW.toFloat() / contentH
            updateStamp()

            displaySurface?.let { ds ->
                egl.makeCurrent(ds)
                GLES30.glViewport(0, 0, displayW, displayH)
                GLES30.glClearColor(0f, 0f, 0f, 1f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                // fit content (top-aligned under the status/top bar area, centered vertically if room)
                val viewAspect = displayW.toFloat() / displayH
                val vw: Int; val vh: Int
                if (viewAspect > aspect) { vh = displayH; vw = (displayH * aspect).toInt() } else { vw = displayW; vh = (displayW / aspect).toInt() }
                val vx = (displayW - vw) / 2
                val topOffset = (displayH * 0.11f).toInt()
                var vyTop = if (displayH - vh >= topOffset * 2) topOffset else (displayH - vh) / 2
                if (vyTop + vh > displayH) vyTop = (displayH - vh) / 2
                val vy = displayH - vyTop - vh
                previewRect = android.graphics.RectF(vx.toFloat(), vyTop.toFloat(), (vx + vw).toFloat(), (vyTop + vh).toFloat())
                GLES30.glViewport(vx, vy, vw, vh)
                oesProgram.draw(oesTex, texMatrix, params, time, aspect)
                if (params.dateStamp && stampTex != 0) {
                    overlay.drawStamp(stampTex, stampBw, stampBh, contentW, contentH, orientation, Stamp.widthFrac(params.stampKind))
                }
                egl.swap(ds)
            }

            encSurface?.let { es ->
                egl.makeCurrent(es)
                GLES30.glViewport(0, 0, encW, encH)
                oesProgram.draw(oesTex, texMatrix, params, time, encW.toFloat() / encH)
                if (params.dateStamp && stampTex != 0) {
                    overlay.drawStamp(stampTex, stampBw, stampBh, encW, encH, recRotation, Stamp.widthFrac(params.stampKind))
                }
                egl.setPresentationTime(es, System.nanoTime())
                egl.swap(es)
            }
        } catch (t: Throwable) {
            Log.e(tag, "drawFrame", t)
        }
    }

    fun startRecording(rec: VideoRecorder, w: Int, h: Int, rotation: Int) = handler.post {
        encSurface = egl.createWindowSurface(rec.inputSurface)
        recorder = rec
        encW = w; encH = h
        recRotation = rotation
    }

    fun stopRecording() = handler.post {
        encSurface?.let {
            egl.makeCurrent(pbuffer)
            egl.releaseSurface(it)
        }
        encSurface = null
        recorder?.stop()
        recorder = null
    }

    /** Applies the current look to an upright bitmap. Callback on the GL thread. */
    fun processStill(input: Bitmap, onDone: (Bitmap?, Throwable?) -> Unit) = handler.post {
        var fbo = 0; var outTex = 0; var inTex = 0; var stTex = 0
        try {
            egl.makeCurrent(pbuffer)
            val maxTex = IntArray(1).also { GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, it, 0) }[0]
            var src = input
            if (src.width > maxTex || src.height > maxTex) {
                val s = maxTex.toFloat() / maxOf(src.width, src.height)
                src = Bitmap.createScaledBitmap(input, (input.width * s).toInt(), (input.height * s).toInt(), true)
            }
            val w = src.width; val h = src.height
            val prog = stillProgram ?: LookProgram(false).also { stillProgram = it }
            inTex = Gl.createTexture2D(src)
            outTex = Gl.createTexture2D(null)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            val f = IntArray(1); GLES30.glGenFramebuffers(1, f, 0); fbo = f[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, outTex, 0)
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "fbo incomplete" }
            GLES30.glViewport(0, 0, w, h)
            val flipV = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)
            prog.draw(inTex, flipV, params, 37.5f, w.toFloat() / h, flipY = true)
            if (params.dateStamp) {
                val kind = params.stampKind
                val frac = Stamp.widthFrac(kind)
                val ratio = if (kind == StampKind.FILM) 4 else 3
                val bh = (frac * w / ratio).toInt().coerceIn(48, 1024)
                val bmp = Stamp.render(kind, bh)
                stTex = Gl.createTexture2D(bmp)
                overlay.drawStamp(stTex, bmp.width, bmp.height, w, h, 0, frac, flipY = true)
                bmp.recycle()
            }
            val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            buf.rewind()
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.copyPixelsFromBuffer(buf)
            if (src !== input) src.recycle()
            onDone(out, null)
        } catch (t: Throwable) {
            Log.e(tag, "processStill", t)
            onDone(null, t)
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            Gl.deleteTexture(inTex); Gl.deleteTexture(outTex); Gl.deleteTexture(stTex)
        }
    }

    fun release() = handler.post {
        try {
            encSurface?.let { egl.releaseSurface(it) }
            displaySurface?.let { egl.releaseSurface(it) }
            surfaceTexture.release()
            egl.releaseSurface(pbuffer)
            egl.release()
        } catch (_: Throwable) {}
        thread.quitSafely()
    }
}
