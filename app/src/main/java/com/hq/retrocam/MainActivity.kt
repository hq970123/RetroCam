package com.hq.retrocam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import com.hq.retrocam.camera.CameraController
import com.hq.retrocam.camera.Mode
import com.hq.retrocam.databinding.ActivityMainBinding
import com.hq.retrocam.gl.CameraRenderer
import com.hq.retrocam.gl.LookParams
import com.hq.retrocam.gl.Styles
import com.hq.retrocam.record.VideoRecorder
import com.hq.retrocam.util.MediaSaver
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val params = LookParams()
    private lateinit var renderer: CameraRenderer
    private lateinit var cam: CameraController
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("retrocam", MODE_PRIVATE) }

    private var rendererReady = false
    private var providerReady = false
    private var orientation = 0

    private var grainOn = true
    private var vignetteOn = true
    private var leakOn = false
    private var intensityIdx = 0
    private val intensities = floatArrayOf(1f, 0.75f, 0.5f, 0.25f)
    private var flashIdx = 0   // 0 off, 1 on, 2 auto
    private var timerIdx = 0
    private val timers = intArrayOf(0, 3, 10)

    private var recorder: VideoRecorder? = null
    private var recStart = 0L
    private var busy = false
    private var lastUri: Uri? = null
    private var lastIsVideo = false
    private val styleViews = ArrayList<TextView>()

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.CAMERA] == true) maybeBind() else showToast("需要相机权限才能拍摄")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        loadPrefs()
        renderer = CameraRenderer(params)
        cam = CameraController(this, renderer)
        renderer.start { main.post { rendererReady = true; maybeBind() } }
        cam.init { providerReady = true; maybeBind() }

        b.preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                renderer.setDisplaySurface(holder.surface, width, height)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                val lock = Object()
                var done = false
                renderer.clearDisplaySurface { synchronized(lock) { done = true; lock.notifyAll() } }
                synchronized(lock) { val end = SystemClock.uptimeMillis() + 500; while (!done && SystemClock.uptimeMillis() < end) lock.wait(100) }
            }
        })

        buildStyleStrip()
        setupControls()
        setupGestures()
        refreshUi()

        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (needed.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            permLauncher.launch(needed)
        }
    }

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val snapped = when (o) { in 45..134 -> 90; in 135..224 -> 180; in 225..314 -> 270; else -> 0 }
                if (snapped != orientation) {
                    orientation = snapped
                    renderer.orientation = snapped
                    cam.imageCapture?.targetRotation = surfaceRotation(snapped)
                    rotateIcons(snapped)
                }
            }
        }
    }

    private fun surfaceRotation(o: Int) = when (o) { 90 -> Surface.ROTATION_270; 180 -> Surface.ROTATION_180; 270 -> Surface.ROTATION_90; else -> Surface.ROTATION_0 }

    private fun rotateIcons(o: Int) {
        val r = -o.toFloat()
        listOf<View>(b.thumb, b.btnFlip, b.countdown).forEach { it.animate().rotation(if (r < -180) r + 360 else r).setDuration(200).start() }
    }

    override fun onResume() {
        super.onResume()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        maybeBind()
    }

    override fun onPause() {
        if (recorder != null) stopRecording()
        orientationListener.disable()
        super.onPause()
    }

    override fun onDestroy() {
        renderer.release()
        io.shutdown()
        super.onDestroy()
    }

    private fun maybeBind() {
        if (!rendererReady || !providerReady) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (cam.lensFacing == CameraSelector.LENS_FACING_FRONT && !cam.hasFront()) cam.lensFacing = CameraSelector.LENS_FACING_BACK
        cam.bind(this, captureFlashMode()) {
            cam.imageCapture?.targetRotation = surfaceRotation(orientation)
            if (cam.mode == Mode.VIDEO) cam.setTorch(false)
        }
    }

    private fun captureFlashMode() = when (flashIdx) { 1 -> ImageCapture.FLASH_MODE_ON; 2 -> ImageCapture.FLASH_MODE_AUTO; else -> ImageCapture.FLASH_MODE_OFF }

    // ---------------- UI ----------------

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun buildStyleStrip() {
        for (s in Styles.all) {
            val tv = TextView(this).apply {
                text = s.name
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                setBackgroundResource(R.drawable.chip_bg)
                setPadding(dp(14f), 0, dp(14f), 0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34f)).apply { marginEnd = dp(8f) }
                setOnClickListener { selectStyle(s.id); it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
            }
            styleViews += tv
            b.styleStrip.addView(tv)
        }
    }

    private fun selectStyle(id: Int) {
        params.style = id
        val s = Styles.all.first { it.id == id }
        params.stampKind = s.stamp
        applyFx()
        refreshUi()
        savePrefs()
        showToast(s.name)
    }

    private fun applyFx() {
        val s = Styles.all.first { it.id == params.style }
        params.grain = if (grainOn) (if (s.grain > 0f) s.grain else 0.45f) else 0f
        params.vignette = if (vignetteOn) (if (s.vignette > 0f) s.vignette else 0.35f) else 0f
        params.leak = if (leakOn) (if (s.leak > 0f) s.leak else 0.6f) else 0f
        params.intensity = intensities[intensityIdx]
    }

    private fun setupControls() {
        b.fxGrain.setOnClickListener { grainOn = !grainOn; applyFx(); refreshUi(); savePrefs() }
        b.fxVignette.setOnClickListener { vignetteOn = !vignetteOn; applyFx(); refreshUi(); savePrefs() }
        b.fxLeak.setOnClickListener { leakOn = !leakOn; applyFx(); refreshUi(); savePrefs() }
        b.fxIntensity.setOnClickListener { intensityIdx = (intensityIdx + 1) % intensities.size; applyFx(); refreshUi(); savePrefs() }
        b.btnDate.setOnClickListener { params.dateStamp = !params.dateStamp; refreshUi(); savePrefs() }
        b.btnTimer.setOnClickListener { timerIdx = (timerIdx + 1) % timers.size; refreshUi() }
        b.btnFlash.setOnClickListener {
            flashIdx = if (cam.mode == Mode.VIDEO) (if (flashIdx == 0) 1 else 0) else (flashIdx + 1) % 3
            cam.imageCapture?.flashMode = captureFlashMode()
            if (cam.mode == Mode.VIDEO) cam.setTorch(flashIdx == 1)
            refreshUi()
        }
        b.modePhoto.setOnClickListener { switchMode(Mode.PHOTO) }
        b.modeVideo.setOnClickListener { switchMode(Mode.VIDEO) }
        b.btnFlip.setOnClickListener {
            if (recorder != null) return@setOnClickListener
            cam.lensFacing = if (cam.lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            maybeBind()
        }
        b.shutter.setOnClickListener { onShutter() }
        b.thumb.setOnClickListener { openLast() }
    }

    private fun switchMode(m: Mode) {
        if (recorder != null || cam.mode == m) return
        cam.mode = m
        if (m == Mode.VIDEO && flashIdx == 2) flashIdx = 0
        refreshUi()
        maybeBind()
    }

    private fun refreshUi() {
        for ((i, v) in styleViews.withIndex()) v.isSelected = Styles.all[i].id == params.style
        b.fxGrain.isSelected = grainOn
        b.fxVignette.isSelected = vignetteOn
        b.fxLeak.isSelected = leakOn
        b.fxIntensity.text = "强度 ${(intensities[intensityIdx] * 100).toInt()}%"
        b.btnDate.text = if (params.dateStamp) "日期 开" else "日期 关"
        b.btnTimer.text = if (timers[timerIdx] == 0) "定时 关" else "定时 ${timers[timerIdx]}s"
        b.btnFlash.text = when (flashIdx) { 1 -> "闪光 开"; 2 -> "闪光 自动"; else -> "闪光 关" }
        val photo = cam.mode == Mode.PHOTO
        b.btnRatio.text = if (photo) "4:3" else "16:9 · 1080p"
        b.modePhoto.setTextColor(if (photo) 0xFFFFB547.toInt() else 0x99FFFFFF.toInt())
        b.modeVideo.setTextColor(if (!photo) 0xFFFFB547.toInt() else 0x99FFFFFF.toInt())
        b.shutter.setImageResource(
            when {
                recorder != null -> R.drawable.shutter_recording
                photo -> R.drawable.shutter_photo
                else -> R.drawable.shutter_video
            }
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                cam.setZoom(cam.zoomRatio() * d.scaleFactor)
                return true
            }
        })
        b.preview.setOnTouchListener { _, e ->
            scale.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP && !scale.isInProgress && e.eventTime - e.downTime < 250) {
                val r = renderer.previewRect
                if (r.width() > 0 && r.contains(e.x, e.y)) {
                    cam.focusAt((e.x - r.left) / r.width(), (e.y - r.top) / r.height())
                    showFocusRing(e.x, e.y)
                }
            }
            true
        }
    }

    private fun showFocusRing(x: Float, y: Float) {
        val ring = TextView(this).apply {
            text = "◻"
            setTextColor(0xFFFFB547.toInt())
            textSize = 48f
        }
        b.root.addView(ring)
        ring.post {
            ring.x = x - ring.width / 2f
            ring.y = y - ring.height / 2f
            ring.animate().scaleX(0.8f).scaleY(0.8f).alpha(0f).setStartDelay(600).setDuration(400)
                .withEndAction { b.root.removeView(ring) }.start()
        }
    }

    private fun showToast(msg: String) {
        b.toast.text = msg
        b.toast.visibility = View.VISIBLE
        b.toast.alpha = 1f
        b.toast.animate().cancel()
        b.toast.animate().alpha(0f).setStartDelay(700).setDuration(300).withEndAction { b.toast.visibility = View.GONE }.start()
    }

    // ---------------- capture ----------------

    private fun onShutter() {
        if (cam.mode == Mode.VIDEO) {
            if (recorder != null) stopRecording() else withTimer { startRecording() }
            return
        }
        if (busy) return
        withTimer { takePhoto() }
    }

    private fun withTimer(action: () -> Unit) {
        val secs = timers[timerIdx]
        if (secs == 0) { action(); return }
        b.countdown.visibility = View.VISIBLE
        var left = secs
        val tick = object : Runnable {
            override fun run() {
                if (left == 0) { b.countdown.visibility = View.GONE; action(); return }
                b.countdown.text = left.toString()
                left--
                main.postDelayed(this, 1000)
            }
        }
        tick.run()
    }

    private fun takePhoto() {
        val ic = cam.imageCapture ?: return
        busy = true
        b.shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        b.flashOverlay.alpha = 0.8f
        b.flashOverlay.animate().alpha(0f).setDuration(250).start()
        val front = cam.lensFacing == CameraSelector.LENS_FACING_FRONT
        ic.takePicture(io, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rot = image.imageInfo.rotationDegrees
                val buf = image.planes[0].buffer
                val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                image.close()
                val bmp = try { decodeUpright(bytes, rot, front) } catch (t: Throwable) { null }
                if (bmp == null) { main.post { busy = false; showToast("照片解码失败") }; return }
                renderer.processStill(bmp) { out, err ->
                    bmp.recycle()
                    if (out == null) { main.post { busy = false; showToast("处理失败: ${err?.message}") }; return@processStill }
                    io.execute {
                        val uri = MediaSaver.saveJpeg(this@MainActivity, out)
                        val thumb = ThumbnailUtils.extractThumbnail(out, 200, 200)
                        out.recycle()
                        main.post {
                            busy = false
                            lastUri = uri; lastIsVideo = false
                            b.thumb.setImageBitmap(thumb)
                            showToast(if (uri != null) "已保存" else "保存失败")
                        }
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                main.post { busy = false; showToast("拍照失败: ${exception.message}") }
            }
        })
    }

    private fun decodeUpright(bytes: ByteArray, rotation: Int, mirror: Boolean): Bitmap {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / sample > 4096) sample *= 2
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        if (rotation == 0 && !mirror) return raw
        val m = Matrix()
        m.postRotate(rotation.toFloat())
        if (mirror) m.postScale(-1f, 1f)
        val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (out !== raw) raw.recycle()
        return out
    }

    private fun startRecording() {
        if (recorder != null) return
        val w = renderer.contentW / 16 * 16
        val h = renderer.contentH / 16 * 16
        val file = File(cacheDir, "rec_${System.currentTimeMillis()}.mp4")
        val withAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val bitrate = (w * h * 30 * 0.33).toInt().coerceIn(8_000_000, 40_000_000)
        val rec = try {
            VideoRecorder(file, w, h, bitrate, 30, orientation, withAudio) { f, err ->
                if (f == null) main.post { showToast("录像失败: ${err?.message}") } else onVideoFinished(f)
            }
        } catch (t: Throwable) {
            showToast("无法开始录像: ${t.message}")
            return
        }
        recorder = rec
        rec.start()
        renderer.startRecording(rec, w, h, orientation)
        if (flashIdx == 1) cam.setTorch(true)
        recStart = SystemClock.elapsedRealtime()
        b.recHud.visibility = View.VISIBLE
        b.shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        refreshUi()
        main.post(recTicker)
    }

    private fun onVideoFinished(f: File) {
                val thumb = try { ThumbnailUtils.createVideoThumbnail(f, Size(200, 200), null) } catch (_: Throwable) { null }
                val uri = try { MediaSaver.saveVideo(this, f) } catch (_: Throwable) { null }
                main.post {
                    lastUri = uri; lastIsVideo = true
                    thumb?.let { b.thumb.setImageBitmap(it) }
                    showToast(if (uri != null) "视频已保存" else "保存失败")
                }
    }

    private val recTicker = object : Runnable {
        override fun run() {
            if (recorder == null) return
            val s = (SystemClock.elapsedRealtime() - recStart) / 1000
            b.recTime.text = String.format("REC %02d:%02d", s / 60, s % 60)
            b.recDot.alpha = if (s % 2 == 0L) 1f else 0.25f
            main.postDelayed(this, 500)
        }
    }

    private fun stopRecording() {
        if (recorder == null) return
        renderer.stopRecording()
        recorder = null
        cam.setTorch(false)
        b.recHud.visibility = View.GONE
        refreshUi()
    }

    private fun openLast() {
        val uri = lastUri ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, if (lastIsVideo) "video/mp4" else "image/jpeg")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Throwable) { showToast("没有可打开的相册应用") }
    }

    // ---------------- prefs ----------------

    private fun loadPrefs() {
        params.style = prefs.getInt("style", 1)
        grainOn = prefs.getBoolean("grain", true)
        vignetteOn = prefs.getBoolean("vignette", true)
        leakOn = prefs.getBoolean("leak", false)
        intensityIdx = prefs.getInt("intensity", 0).coerceIn(0, intensities.size - 1)
        params.dateStamp = prefs.getBoolean("date", true)
        params.stampKind = Styles.all.first { it.id == params.style }.stamp
        applyFx()
    }

    private fun savePrefs() {
        prefs.edit()
            .putInt("style", params.style)
            .putBoolean("grain", grainOn)
            .putBoolean("vignette", vignetteOn)
            .putBoolean("leak", leakOn)
            .putInt("intensity", intensityIdx)
            .putBoolean("date", params.dateStamp)
            .apply()
    }
}
