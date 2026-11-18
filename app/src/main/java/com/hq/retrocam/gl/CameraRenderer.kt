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
    private lateinit var look2d: LookProgram
    private lateinit var colorProgram: ColorProgram
    private lateinit var overlay: OverlayProgram
    private val lutTex = LutTexture()
    private var preFbo = 0
    private var preTex = 0
    private var preW = 0
    private var preH = 0
    private val identity = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
    private val cleanParams = LookParams().apply { style = 0; intensity = 0f; grain = 0f; vignette = 0f; leak = 0f; dateStamp = false }

    /** Colour conversion for preview / burned-in output. */
    @Volatile var color: ColorState = ColorState.NONE
    /** When true the encoder gets the clean camera image (no LUT, look, grain or stamp). */
    @Volatile var cleanRecord = false
    /** Colour state for the clean recording (log kept, no LUT). */
    @Volatile var cleanColor: ColorState = ColorState.NONE
    /** Name of the LUT now on the GPU, for UI checks. */
    @Volatile var lutName: String? = null
        private set

    fun setLut(lut: CubeLut?) = handler.post {
        try {
            egl.makeCurrent(pbuffer)
            lutTex.upload(lut)
            lutName = lutTex.name
        } catch (t: Throwable) { Log.e(tag, "lut upload", t); lutName = null }
    }

    private fun prePass(cs: ColorState) {
        val w = contentW; val h = contentH
        if (preFbo == 0 || preW != w || preH != h) {
            if (preTex == 0) preTex = Gl.createTexture2D(null)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, preTex)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            if (preFbo == 0) { val f = IntArray(1); GLES30.glGenFramebuffers(1, f, 0); preFbo = f[0] }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, preFbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, preTex, 0)
            preW = w; preH = h
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, preFbo)
        GLES30.glViewport(0, 0, w, h)
        colorProgram.draw(oesTex, texMatrix, cs, lutTex)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private var usePre = false

    /** Draws the look for the current frame into the current viewport. */
    private fun drawLook(time: Float, aspect: Float, crop: FloatArray, peaking: Boolean = false, zebra: Boolean = false, texelX: Float = 0.001f, texelY: Float = 0.001f) {
        if (usePre) look2d.draw(preTex, identity, params, time, aspect, crop = crop, peaking = peaking, zebra = zebra, texelX = texelX, texelY = texelY)
        else oesProgram.draw(oesTex, texMatrix, params, time, aspect, crop = crop, peaking = peaking, zebra = zebra, texelX = texelX, texelY = texelY,
            colorState = color, lut = lutTex)
    }
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

    /** Target content aspect (w/h, portrait content space); 0 = full frame. */
    @Volatile var cropAspect = 0f
    @Volatile var peaking = false
    @Volatile var zebra = false
    @Volatile var histogram = false
    @Volatile var onHistogram: ((IntArray) -> Unit)? = null
    private var histFbo = 0
    private var histTex = 0
    private val histW = 96
    private val histH = 128
    private val histBuf: ByteBuffer = ByteBuffer.allocateDirect(96 * 128 * 4).order(ByteOrder.nativeOrder())
    private var frameCount = 0

    /** [offX, offY, scaleX, scaleY] for the current crop. */
    fun crop(): FloatArray {
        val a = cropAspect
        val ca = contentW.toFloat() / contentH
        if (a <= 0f || kotlin.math.abs(a - ca) < 0.01f) return NO_CROP
        return if (a < ca) { val sx = a / ca; floatArrayOf((1f - sx) / 2f, 0f, sx, 1f) }
        else { val sy = ca / a; floatArrayOf(0f, (1f - sy) / 2f, 1f, sy) }
    }

    /** Maps a point in the cropped preview (0..1, y down) to full content coords. */
    fun toFullContent(x: Float, y: Float): Pair<Float, Float> {
        val c = crop()
        return Pair(c[0] + x * c[2], c[1] + y * c[3])
    }

    private fun histogramPass(texMatrix: FloatArray, time: Float, crop: FloatArray, aspect: Float) {
        if (histFbo == 0) {
            histTex = Gl.createTexture2D(null)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, histW, histH, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            val f = IntArray(1); GLES30.glGenFramebuffers(1, f, 0); histFbo = f[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, histFbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, histTex, 0)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, histFbo)
        GLES30.glViewport(0, 0, histW, histH)
        drawLook(time, aspect, crop)
        histBuf.clear()
        GLES30.glReadPixels(0, 0, histW, histH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, histBuf)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        val bins = IntArray(256) // 64 bins each: luma, r, g, b
        val n = histW * histH
        for (i in 0 until n) {
            val r = histBuf.get(i * 4).toInt() and 0xFF
            val g = histBuf.get(i * 4 + 1).toInt() and 0xFF
            val b = histBuf.get(i * 4 + 2).toInt() and 0xFF
            val l = (r * 77 + g * 150 + b * 29) shr 8
            bins[l shr 2]++
            bins[64 + (r shr 2)]++
            bins[128 + (g shr 2)]++
            bins[192 + (b shr 2)]++
        }
        onHistogram?.invoke(bins)
    }

    /** Content rect of the preview inside the view, in view pixels (for tap mapping). */
    @Volatile var previewRect = android.graphics.RectF()

    fun start(onReady: (SurfaceTexture) -> Unit) {
        handler.post {
            egl = EglCore()
            pbuffer = egl.createPbuffer(1, 1)
            egl.makeCurrent(pbuffer)
            oesProgram = LookProgram(true)
            look2d = LookProgram(false)
            colorProgram = ColorProgram()
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
            val crop = crop()
            val cw = contentW * crop[2]
            val ch = contentH * crop[3]
            val aspect = cw / ch
            updateStamp()
            frameCount++
            Gl.posRot = Gl.IDENTITY_ROT
            val cs = color
            usePre = cs.active
            if (usePre) {
                egl.makeCurrent(displaySurface ?: encSurface!!)
                prePass(cs)
            }

            displaySurface?.let { ds ->
                egl.makeCurrent(ds)
                if (histogram && frameCount % 4 == 0 && updateTex) histogramPass(texMatrix, time, crop, aspect)
                GLES30.glViewport(0, 0, displayW, displayH)
                GLES30.glClearColor(0f, 0f, 0f, 1f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                // full-frame placement is fixed by the uncropped frame so switching ratio doesn't jump
                val viewAspect = displayW.toFloat() / displayH
                val full = contentW.toFloat() / contentH
                val fw: Int; val fh: Int
                if (viewAspect > full) { fh = displayH; fw = (displayH * full).toInt() } else { fw = displayW; fh = (displayW / full).toInt() }
                val topOffset = (displayH * 0.11f).toInt()
                var fTop = if (displayH - fh >= topOffset * 2) topOffset else (displayH - fh) / 2
                if (fTop + fh > displayH) fTop = (displayH - fh) / 2
                val fLeft = (displayW - fw) / 2
                val vw = (fw * crop[2]).toInt()
                val vh = (fh * crop[3]).toInt()
                val vx = fLeft + (fw - vw) / 2
                val vyTop = fTop + (fh - vh) / 2
                val vy = displayH - vyTop - vh
                previewRect = android.graphics.RectF(vx.toFloat(), vyTop.toFloat(), (vx + vw).toFloat(), (vyTop + vh).toFloat())
                GLES30.glViewport(vx, vy, vw, vh)
                drawLook(time, aspect, crop, peaking = peaking, zebra = zebra,
                    texelX = 1f / maxOf(1f, cw), texelY = 1f / maxOf(1f, ch))
                if (params.dateStamp && stampTex != 0) {
                    overlay.drawStamp(stampTex, stampBw, stampBh, cw.toInt(), ch.toInt(), orientation, Stamp.widthFrac(params.stampKind))
                }
                egl.swap(ds)
            }

            encSurface?.let { es ->
                egl.makeCurrent(es)
                GLES30.glViewport(0, 0, encW, encH)
                GLES30.glClearColor(0f, 0f, 0f, 1f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                // The encoder frame is landscape; upright portrait content is rotated 90 degrees
                // clockwise into it and the container rotation hint turns it back.
                val pw = encH; val ph = encW
                val ea = pw.toFloat() / ph
                val sw: Int; val sh: Int
                if (aspect < ea) { sh = ph; sw = (ph * aspect).toInt() } else { sw = pw; sh = (pw / aspect).toInt() }
                GLES30.glViewport((encW - sh) / 2, (encH - sw) / 2, sh, sw)
                Gl.posRot = Gl.ROT_CW
                if (cleanRecord) {
                    oesProgram.draw(oesTex, texMatrix, cleanParams, time, aspect, crop = crop, colorState = cleanColor, lut = null)
                } else {
                    drawLook(time, aspect, crop)
                    if (params.dateStamp && stampTex != 0) {
                        overlay.drawStamp(stampTex, stampBw, stampBh, sw, sh, recRotation, Stamp.widthFrac(params.stampKind))
                    }
                }
                Gl.posRot = Gl.IDENTITY_ROT
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
            Gl.posRot = Gl.IDENTITY_ROT
            prog.draw(inTex, flipV, params, 37.5f, w.toFloat() / h, flipY = true, colorState = color.copy(logIn = 0), lut = lutTex)
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
