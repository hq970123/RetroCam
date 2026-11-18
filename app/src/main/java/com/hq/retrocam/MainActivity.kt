package com.hq.retrocam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Environment
import android.provider.MediaStore
import android.widget.SeekBar
import com.hq.retrocam.camera.ManualOptions
import com.hq.retrocam.camera.Opt
import com.hq.retrocam.camera.ProParam
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
import com.hq.retrocam.gl.ColorState
import com.hq.retrocam.gl.LutLibrary
import com.hq.retrocam.camera.Stab
import com.hq.retrocam.camera.VideoFormat
import com.hq.retrocam.record.Encoders
import android.media.MediaFormat
import com.hq.retrocam.record.VideoRecorder
import com.hq.retrocam.util.MediaSaver
import com.hq.retrocam.util.Border
import com.hq.retrocam.util.Borders
import com.hq.retrocam.util.MotionPhoto
import com.hq.retrocam.util.ShotInfo
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.OnBackPressedCallback
import androidx.exifinterface.media.ExifInterface
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.ImageDecoder
import android.widget.FrameLayout
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

    // Phase 2
    private data class Ratio(val label: String, val r: Float)
    private val photoRatios = listOf(Ratio("4:3", 4f / 3f), Ratio("1:1", 1f), Ratio("16:9", 16f / 9f), Ratio("XPan", 65f / 24f))
    private val videoRatios = listOf(Ratio("16:9", 16f / 9f), Ratio("2.39:1", 2.39f))
    private var photoRatioIdx = 0
    private var videoRatioIdx = 0
    private var assistOpen = false
    private var stabPref = true
    private var fmtPref: VideoFormat? = null
    private var gridOn = false
    private var levelOn = false
    private var histOn = false
    private var peakOn = false
    private var zebraOn = false
    /** Bottom tab: 0 effects, 1 pro, 2 colour. */
    private var tab = 0
    private val proTab get() = tab == 1

    // Phase 3
    private val lutLib by lazy { LutLibrary(this) }
    private var lutId: String? = null
    private var lutStrength = 100
    private var profileLog = false
    private var convertOn = true
    private var burnIn = true
    private val lutViews = ArrayList<Pair<String?, TextView>>()
    private val importLut = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        showToast("正在导入 LUT…")
        io.execute {
            try {
                val e = lutLib.import(uri)
                main.post { buildLutStrip(); selectLut(e.id); showToast("已导入 ${e.name}") }
            } catch (t: Throwable) {
                main.post { showToast("导入失败: ${t.message}") }
            }
        }
    }
    // Phase 4
    private enum class Creative(val label: String) { NORMAL("普通"), SLOW("慢快门"), BLUR("运动模糊"), LIGHT("光绘"), MULTI("多重曝光"), LIVE("动态照片") }
    private var creative = Creative.NORMAL
    private val slowSecs = floatArrayOf(1f, 2f, 4f, 8f)
    private var slowIdx = 1
    private val blurSecs = floatArrayOf(0.25f, 0.5f, 1f)
    private var blurIdx = 1
    private val lightSecs = floatArrayOf(0f, 5f, 10f, 20f, 30f)
    private var lightIdx = 0
    private var multiScreen = true
    private var multiFirst: Bitmap? = null
    private val tlIntervals = floatArrayOf(0f, 0.5f, 1f, 2f, 5f)
    private var tlIdx = 0
    private var border = Border.NONE
    private var liveRec: VideoRecorder? = null
    private var accumSecs = 0f
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) openEditor(uri) }

    // editor
    private val editParams = LookParams()
    private var editSrc: Bitmap? = null
    private var editShown: Bitmap? = null
    private var editUri: Uri? = null
    private var editInfo = ShotInfo("RetroCam")
    private var editBorder = Border.NONE
    private var editGrain = true
    private var editVignette = true
    private var editLeak = false
    private var editIntensityIdx = 0
    private var editRendering = false
    private var editDirty = false
    private var editSaving = false
    private val editStyleViews = ArrayList<TextView>()
    private val editLutViews = ArrayList<Pair<String?, TextView>>()
    private val editBorderViews = ArrayList<Pair<Border, TextView>>()
    private val backCb = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { closeEditor() }
    }

    private var activeParam: ProParam? = null
    private var sliderOpts: List<Opt<*>> = emptyList()
    private var sliderBusy = false
    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val levelListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val x = e.values[0]; val y = e.values[1]
            val roll = Math.toDegrees(Math.atan2(x.toDouble(), y.toDouble())).toFloat()
            var d = roll - b.overlay.roll
            while (d > 180f) d -= 360f
            while (d < -180f) d += 360f
            var r = b.overlay.roll + d * 0.25f
            if (r > 180f) r -= 360f
            if (r < -180f) r += 360f
            b.overlay.roll = r
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

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
        cam.stabRequested = stabPref
        cam.videoFormat = fmtPref
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

        renderer.onHistogram = { bins -> main.post { b.overlay.setHistogram(bins) } }
        buildStyleStrip()
        setupControls()
        setupAssist()
        setupPro()
        setupColor()
        setupCreative()
        setupEditor()
        onBackPressedDispatcher.addCallback(this, backCb)
        cam.onBindFallback = { main.post { showToast("这个格式/防抖组合不支持，已回退 1080p 30") } }
        main.post(uiTicker)
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
        if (levelOn) startLevel()
        maybeBind()
    }

    override fun onPause() {
        if (recorder != null) stopRecording()
        if (renderer.accumulating) renderer.cancelAccumulation()
        liveRec?.let { renderer.stopRecording(); liveRec = null }
        orientationListener.disable()
        sensorManager.unregisterListener(levelListener)
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
            if (activeParam != null) openSlider(activeParam!!)
            applyColor()
            refreshPro()
            refreshUi()
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

    private fun applyFx() = fxInto(params, grainOn, vignetteOn, leakOn, intensities[intensityIdx])

    private fun fxInto(p: LookParams, grain: Boolean, vignette: Boolean, leak: Boolean, intensity: Float) {
        val s = Styles.all.first { it.id == p.style }
        p.grain = if (grain) (if (s.grain > 0f) s.grain else 0.45f) else 0f
        p.vignette = if (vignette) (if (s.vignette > 0f) s.vignette else 0.35f) else 0f
        p.leak = if (leak) (if (s.leak > 0f) s.leak else 0.6f) else 0f
        p.intensity = intensity
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
            cam.manual.reset()
            cam.lensFacing = if (cam.lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            maybeBind()
        }
        b.shutter.setOnClickListener { onShutter() }
        b.thumb.setOnClickListener { openLast() }
        b.thumb.setOnLongClickListener {
            val u = lastUri
            if (u != null && !lastIsVideo) openEditor(u) else pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            true
        }
    }

    private fun switchMode(m: Mode) {
        if (recorder != null || cam.mode == m) return
        cam.mode = m
        clearMulti()
        cam.hiResPreview = m == Mode.PHOTO && creative in ACCUM_MODES
        buildCreative()
        if (m == Mode.VIDEO && flashIdx == 2) flashIdx = 0
        if (m == Mode.VIDEO && (cam.manual.exposureNs ?: 0L) > 33_333_333L) cam.manual.exposureNs = null
        applyRatio()
        applyColor()
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
        b.btnRatio.text = if (photo) photoRatios[photoRatioIdx].label + (if (cam.rawActive) " RAW" else "") else videoRatios[videoRatioIdx].label
        b.btnFormat.visibility = if (photo) View.GONE else View.VISIBLE
        b.btnFormat.text = (cam.boundFormat ?: cam.videoFormat)?.label ?: "1080p 30"
        b.asStab.alpha = if (photo) 0.4f else 1f
        b.asStab.isSelected = !photo && cam.stabRequested
        b.asStab.text = if (cam.stabRequested) "防抖 开" else "防抖 关"
        b.colorPanel.visibility = if (tab == 2) View.VISIBLE else View.GONE
        b.creativePanel.visibility = if (tab == 3) View.VISIBLE else View.GONE
        b.tabCreative.setTextColor(if (tab == 3) 0xFFFFB547.toInt() else 0x99FFFFFF.toInt())
        b.borderScroll.visibility = if (photo) View.VISIBLE else View.GONE
        updateHud()
        b.tabColor.setTextColor(if (tab == 2) 0xFFFFB547.toInt() else 0x99FFFFFF.toInt())
        for (v in listOf(b.colLog, b.colConvert, b.colBurn)) v.visibility = if (photo) View.GONE else View.VISIBLE
        b.colConvert.visibility = if (!photo && profileLog) View.VISIBLE else View.GONE
        b.colLog.isSelected = profileLog
        b.colLog.text = if (!profileLog) "Log 关" else if (cam.logHardware) "Log 开" else "Log 开(模拟)"
        b.colConvert.isSelected = convertOn
        b.colConvert.text = if (convertOn) "内置转换 开" else "内置转换 关"
        b.colBurn.isSelected = burnIn
        b.colBurn.text = if (burnIn) "烧录 开" else "烧录 关(录原始)"
        for ((id, v) in lutViews) v.isSelected = id == lutId
        b.lutLabel.text = "LUT ${lutStrength}%"
        b.lutSeek.isEnabled = lutId != null
        b.lutSeek.alpha = if (lutId != null) 1f else 0.4f
        b.assistPanel.visibility = if (assistOpen) View.VISIBLE else View.GONE
        b.btnAssist.text = if (assistOpen) "辅助 ▴" else "辅助 ▾"
        b.asGrid.isSelected = gridOn
        b.asLevel.isSelected = levelOn
        b.asHist.isSelected = histOn
        b.asPeak.isSelected = peakOn
        b.asZebra.isSelected = zebraOn
        b.asRaw.isSelected = cam.rawRequested
        b.asRaw.alpha = if (photo) 1f else 0.4f
        b.overlay.grid = gridOn
        b.overlay.level = levelOn
        b.overlay.showHistogram = histOn
        renderer.peaking = peakOn
        renderer.zebra = zebraOn
        renderer.histogram = histOn
        b.tabFx.setTextColor(if (tab == 0) 0xFFFFB547.toInt() else 0x99FFFFFF.toInt())
        b.tabPro.setTextColor(if (proTab) 0xFFFFB547.toInt() else 0x99FFFFFF.toInt())
        b.fxRow.visibility = if (tab == 0) View.VISIBLE else View.GONE
        b.proRow.visibility = if (proTab) View.VISIBLE else View.GONE
        b.sliderRow.visibility = if (proTab && activeParam != null) View.VISIBLE else View.GONE
        b.modePhoto.setTextColor(if (photo) 0xFFFFB547.toInt() else 0x99FFFFFF.toInt())
        b.modeVideo.setTextColor(if (!photo) 0xFFFFB547.toInt() else 0x99FFFFFF.toInt())
        b.shutter.setImageResource(
            when {
                recorder != null || renderer.accumulating -> R.drawable.shutter_recording
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
                    val (fx, fy) = renderer.toFullContent((e.x - r.left) / r.width(), (e.y - r.top) / r.height())
                    cam.focusAt(fx, fy)
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
        if (renderer.accumulating) {
            if (creative == Creative.LIGHT) renderer.stopAccumulation()
            return
        }
        if (busy) return
        when (creative) {
            Creative.NORMAL -> withTimer { takePhoto() }
            Creative.SLOW, Creative.BLUR, Creative.LIGHT -> withTimer { startAccum() }
            Creative.MULTI -> withTimer { takeMulti() }
            Creative.LIVE -> withTimer { takeLive() }
        }
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
        if (cam.rawActive) { takeRaw(ic); return }
        busy = true
        shutterFeedback()
        val info = shotInfo()
        captureUpright { bmp ->
            if (bmp == null) { main.post { busy = false; showToast("拍照失败") }; return@captureUpright }
            main.post { finishStill(bmp, info) }
        }
    }

    private fun shutterFeedback() {
        b.shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        b.flashOverlay.alpha = 0.8f
        b.flashOverlay.animate().alpha(0f).setDuration(250).start()
    }

    /** Takes a still and returns it upright and cropped to the photo ratio (unprocessed). Callback on [io]. */
    private fun captureUpright(onResult: (Bitmap?) -> Unit) {
        val ic = cam.imageCapture ?: run { onResult(null); return }
        val front = cam.lensFacing == CameraSelector.LENS_FACING_FRONT
        val ratio = photoRatios[photoRatioIdx].r
        ic.takePicture(io, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rot = image.imageInfo.rotationDegrees
                val buf = image.planes[0].buffer
                val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                image.close()
                val bmp = try { cropToRatio(decodeUpright(bytes, rot, front), ratio) } catch (t: Throwable) { null }
                onResult(bmp)
            }
            override fun onError(exception: ImageCaptureException) { onResult(null) }
        })
    }

    private fun deviceName(): String {
        val market = try {
            val c = Class.forName("android.os.SystemProperties")
            c.getMethod("get", String::class.java).invoke(null, "ro.product.marketname") as? String
        } catch (_: Throwable) { null }
        return market?.takeIf { it.isNotBlank() } ?: (android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + android.os.Build.MODEL)
    }

    private fun shotInfo(exposureNs: Long = cam.manual.exposureNs ?: cam.liveExposureNs) = ShotInfo(
        model = deviceName(),
        iso = cam.manual.iso ?: cam.liveIso,
        exposureNs = exposureNs,
        focalMm = cam.caps?.equivFocal ?: 0,
        aperture = cam.caps?.aperture ?: 0f,
        look = Styles.all.first { it.id == params.style }.name,
    )

    /** Look + border + save. [motion] makes it a motion photo. Takes ownership of [bmp]. */
    private fun finishStill(bmp: Bitmap, info: ShotInfo, motion: File? = null) {
        val bd = border
        renderer.processStill(bmp) { out, err ->
            bmp.recycle()
            if (out == null) { motion?.delete(); main.post { busy = false; showToast("处理失败: ${err?.message}") }; return@processStill }
            io.execute {
                var uri: Uri? = null
                var thumb: Bitmap? = null
                try {
                    val framed = Borders.apply(out, bd, info)
                    thumb = ThumbnailUtils.extractThumbnail(framed, 200, 200)
                    uri = if (motion != null) {
                        val jpg = java.io.ByteArrayOutputStream().also { framed.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
                        val video = motion.readBytes()
                        motion.delete()
                        MediaSaver.saveJpegBytes(this@MainActivity, MotionPhoto.build(jpg, video, 1_000_000L), "MVIMG_")
                    } else MediaSaver.saveJpeg(this@MainActivity, framed)
                    framed.recycle()
                } catch (t: Throwable) {
                    android.util.Log.e("RetroCam", "save", t)
                }
                main.post {
                    busy = false
                    lastUri = uri; lastIsVideo = false
                    thumb?.let { b.thumb.setImageBitmap(it) }
                    showToast(if (uri == null) "保存失败" else if (motion != null) "动态照片已保存" else "已保存")
                    refreshUi()
                }
            }
        }
    }

    private fun cropToRatio(src: Bitmap, r: Float): Bitmap {
        val w = src.width; val h = src.height
        val target = if (w >= h) r else 1f / r
        val cur = w.toFloat() / h
        if (kotlin.math.abs(cur - target) < 0.01f) return src
        val (cw, ch) = if (cur > target) Pair((h * target).toInt(), h) else Pair(w, (w / target).toInt())
        val out = Bitmap.createBitmap(src, (w - cw) / 2, (h - ch) / 2, cw, ch)
        if (out !== src) src.recycle()
        return out
    }

    private fun takeRaw(ic: ImageCapture) {
        busy = true
        b.shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        b.flashOverlay.alpha = 0.8f
        b.flashOverlay.animate().alpha(0f).setDuration(250).start()
        val name = "RetroCam_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.dng")
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/RetroCam")
        }
        val opts = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
        ic.takePicture(opts, io, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(res: ImageCapture.OutputFileResults) {
                main.post {
                    busy = false
                    lastUri = res.savedUri; lastIsVideo = false
                    showToast("RAW 已保存（DNG，不含风格）")
                }
            }
            override fun onError(e: ImageCaptureException) {
                main.post { busy = false; showToast("RAW 失败: ${e.message}") }
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
        // landscape encoder frame; the renderer rotates the portrait content into it
        val w = maxOf(renderer.contentW, renderer.contentH) / 8 * 8
        val h = minOf(renderer.contentW, renderer.contentH) / 8 * 8
        val fps = cam.boundFormat?.fps ?: 30
        val file = File(cacheDir, "rec_${System.currentTimeMillis()}.mp4")
        val timelapse = tlIdx > 0
        val withAudio = !timelapse && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        renderer.timelapseIntervalNs = if (timelapse) (tlIntervals[tlIdx] * 1e9).toLong() else 0L
        val enc = Encoders.choose(w, h, fps) ?: Encoders.Choice(MediaFormat.MIMETYPE_VIDEO_AVC, (w * h * fps * 0.2).toInt().coerceIn(16_000_000, 100_000_000))
        val hint = (orientation + 270) % 360
        b.audioMeter.reset()
        val rec = try {
            VideoRecorder(file, w, h, enc.bitrate, fps, hint, withAudio, enc.mime,
                onLevel = { l, r -> main.post { b.audioMeter.setLevels(l, r) } }) { f, err ->
                if (f == null) main.post { showToast("录像失败: ${err?.message}") } else onVideoFinished(f)
            }
        } catch (t: Throwable) {
            showToast("无法开始录像: ${t.message}")
            return
        }
        recorder = rec
        rec.start()
        renderer.startRecording(rec, w, h, orientation, fps)
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
            b.recTime.text = if (renderer.timelapseIntervalNs > 0) {
                val o = renderer.tlFrames / (cam.boundFormat?.fps ?: 30)
                String.format("延时 %02d:%02d → %02d:%02d", s / 60, s % 60, o / 60, o % 60)
            } else String.format("REC %02d:%02d", s / 60, s % 60)
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

    // ---------------- Phase 2: assist, ratio, pro ----------------

    private fun applyRatio() {
        val r = if (cam.mode == Mode.PHOTO) photoRatios[photoRatioIdx].r else videoRatios[videoRatioIdx].r
        renderer.cropAspect = 1f / r
    }

    private fun startLevel() {
        val s = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensorManager.registerListener(levelListener, s, SensorManager.SENSOR_DELAY_UI)
    }

    private fun setupAssist() {
        applyRatio()
        b.btnAssist.setOnClickListener { assistOpen = !assistOpen; refreshUi() }
        b.btnRatio.setOnClickListener {
            if (recorder != null) return@setOnClickListener
            if (cam.mode == Mode.PHOTO) photoRatioIdx = (photoRatioIdx + 1) % photoRatios.size
            else videoRatioIdx = (videoRatioIdx + 1) % videoRatios.size
            applyRatio(); refreshUi(); savePrefs()
        }
        b.asGrid.setOnClickListener { gridOn = !gridOn; refreshUi(); savePrefs() }
        b.asLevel.setOnClickListener {
            levelOn = !levelOn
            if (levelOn) startLevel() else sensorManager.unregisterListener(levelListener)
            refreshUi(); savePrefs()
        }
        b.asHist.setOnClickListener { histOn = !histOn; refreshUi(); savePrefs() }
        b.asPeak.setOnClickListener { peakOn = !peakOn; refreshUi(); savePrefs() }
        b.asZebra.setOnClickListener { zebraOn = !zebraOn; refreshUi(); savePrefs() }
        b.btnFormat.setOnClickListener {
            if (recorder != null || cam.mode != Mode.VIDEO) return@setOnClickListener
            val list = cam.caps?.videoFormats ?: return@setOnClickListener
            if (list.size <= 1) { showToast("这颗镜头只支持 1080p 30"); return@setOnClickListener }
            val cur = cam.boundFormat ?: cam.videoFormat
            val i = list.indexOfFirst { it.w == cur?.w && it.h == cur?.h && it.fps == cur?.fps }
            val next = list[(i + 1) % list.size]
            cam.videoFormat = next
            val codec = Encoders.choose(next.w, next.h, next.fps)
            showToast(next.label + (codec?.let { " · " + it.label + " " + it.bitrate / 1_000_000 + " Mbps" } ?: ""))
            savePrefs()
            maybeBind()
        }
        b.asStab.setOnClickListener {
            if (cam.mode != Mode.VIDEO) { showToast("防抖用于录像模式"); return@setOnClickListener }
            if (recorder != null) return@setOnClickListener
            cam.stabRequested = !cam.stabRequested
            savePrefs()
            maybeBind()
            main.postDelayed({
                showToast(when (cam.stabActive) {
                    Stab.PREVIEW -> "防抖 开（预览级电子防抖）"
                    Stab.VIDEO_EIS -> "防抖 开（电子防抖）"
                    Stab.OIS_ONLY -> "仅光学防抖，这颗镜头没有电子防抖"
                    Stab.OFF -> if (cam.stabRequested) "这颗镜头不支持防抖" else "防抖 关"
                })
            }, 400)
        }
        b.asRaw.setOnClickListener {
            if (cam.mode != Mode.PHOTO) { showToast("RAW 仅拍照模式可用"); return@setOnClickListener }
            if (cam.caps?.rawSupported != true) { showToast("这颗镜头不支持 RAW"); return@setOnClickListener }
            cam.rawRequested = !cam.rawRequested
            showToast(if (cam.rawRequested) "RAW 开：保存 DNG 原片，不套风格" else "RAW 关")
            maybeBind()
        }
    }

    private fun setupPro() {
        b.tabFx.setOnClickListener { tab = 0; refreshUi() }
        b.tabPro.setOnClickListener { tab = 1; refreshPro(); refreshUi() }
        val map = mapOf(b.proIso to ProParam.ISO, b.proShutter to ProParam.SHUTTER, b.proEv to ProParam.EV, b.proWb to ProParam.WB, b.proFocus to ProParam.FOCUS)
        for ((v, p) in map) v.setOnClickListener {
            if (activeParam == p) { activeParam = null } else openSlider(p)
            refreshPro(); refreshUi()
        }
        b.proReset.setOnClickListener {
            cam.manual.reset(); cam.applyManual(); activeParam = null
            refreshPro(); refreshUi(); showToast("已恢复全自动")
        }
        b.slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser || sliderBusy) return
                setParamIndex(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        b.sliderAuto.setOnClickListener {
            val p = activeParam ?: return@setOnClickListener
            val idx = if (p == ProParam.EV) sliderOpts.indexOfFirst { it.value == 0 }.coerceAtLeast(0) else 0
            setParamIndex(idx)
            sliderBusy = true; b.slider.progress = idx; sliderBusy = false
        }
    }

    private fun optionsFor(p: ProParam): List<Opt<*>> {
        val caps = cam.caps ?: return emptyList()
        return when (p) {
            ProParam.ISO -> ManualOptions.iso(caps)
            ProParam.SHUTTER -> ManualOptions.shutter(caps, cam.mode == Mode.VIDEO)
            ProParam.EV -> ManualOptions.ev(caps)
            ProParam.WB -> ManualOptions.wb(caps)
            ProParam.FOCUS -> ManualOptions.focus(caps)
        }
    }

    private fun openSlider(p: ProParam) {
        val opts = optionsFor(p)
        if (opts.size <= 1) {
            activeParam = null
            showToast(if (p == ProParam.FOCUS) "这颗镜头不支持手动对焦" else "这颗镜头不支持手动调节")
            return
        }
        activeParam = p
        sliderOpts = opts
        val m = cam.manual
        val idx = when (p) {
            ProParam.ISO -> opts.indexOfFirst { it.value == m.iso }
            ProParam.SHUTTER -> opts.indexOfFirst { it.value == m.exposureNs }
            ProParam.EV -> opts.indexOfFirst { it.value == m.evIndex }
            ProParam.WB -> opts.indexOfFirst { it.value == m.awbMode }
            ProParam.FOCUS -> if (m.focusDiopters == null) 0 else opts.indices.minByOrNull { i -> kotlin.math.abs(((opts[i].value as? Float) ?: -99f) - m.focusDiopters!!) } ?: 0
        }.coerceAtLeast(0)
        sliderBusy = true
        b.slider.max = opts.size - 1
        b.slider.progress = idx
        sliderBusy = false
        b.sliderLabel.text = opts[idx].label
        b.sliderAuto.visibility = View.VISIBLE
    }

    private fun setParamIndex(i: Int) {
        val p = activeParam ?: return
        val o = sliderOpts.getOrNull(i) ?: return
        val m = cam.manual
        when (p) {
            ProParam.ISO -> m.iso = o.value as Int?
            ProParam.SHUTTER -> m.exposureNs = o.value as Long?
            ProParam.EV -> m.evIndex = (o.value as Int?) ?: 0
            ProParam.WB -> m.awbMode = (o.value as Int?) ?: android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_AUTO
            ProParam.FOCUS -> m.focusDiopters = o.value as Float?
        }
        cam.applyManual()
        b.sliderLabel.text = o.label
        refreshPro()
    }

    private fun refreshPro() {
        val m = cam.manual
        val isoAuto = m.iso == null
        val sAuto = m.exposureNs == null
        b.proIso.text = "ISO " + (if (isoAuto) "A·" + (cam.liveIso.takeIf { it > 0 }?.toString() ?: "--") else m.iso.toString())
        b.proShutter.text = "S " + (if (sAuto) "A·" + ManualOptions.shutterLabel(cam.liveExposureNs) else ManualOptions.shutterLabel(m.exposureNs!!))
        val step = cam.caps?.evStep ?: 0f
        b.proEv.text = "EV " + if (m.evIndex == 0) "0" else String.format(java.util.Locale.US, "%+.1f", m.evIndex * step)
        b.proWb.text = "WB " + (cam.caps?.let { c -> ManualOptions.wb(c).firstOrNull { it.value == m.awbMode }?.label } ?: "自动")
        b.proFocus.text = "MF " + (m.focusDiopters?.let { ManualOptions.focusLabel(it) } ?: "A")
        b.proIso.isSelected = activeParam == ProParam.ISO || !isoAuto
        b.proShutter.isSelected = activeParam == ProParam.SHUTTER || !sAuto
        b.proEv.isSelected = activeParam == ProParam.EV || m.evIndex != 0
        b.proWb.isSelected = activeParam == ProParam.WB || m.awbMode != android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_AUTO
        b.proFocus.isSelected = activeParam == ProParam.FOCUS || m.focusDiopters != null
    }

    private val uiTicker = object : Runnable {
        override fun run() {
            b.overlay.rect = renderer.previewRect
            if (proTab) refreshPro()
            main.postDelayed(this, 250)
        }
    }

    // ---------------- Phase 3: colour / LUT ----------------

    private fun setupColor() {
        b.tabColor.setOnClickListener { tab = 2; refreshUi() }
        b.colLog.setOnClickListener {
            if (recorder != null) return@setOnClickListener
            profileLog = !profileLog
            cam.logRequested = profileLog
            cam.applyManual()
            applyColor(); refreshUi(); savePrefs()
            if (profileLog) showToast(if (cam.logHardware) "Log 开：相机输出平坦曲线，曝光自动降 1.5 档" else "Log 开：这颗镜头不能改色调曲线，用软件模拟")
        }
        b.colConvert.setOnClickListener {
            convertOn = !convertOn
            applyColor(); refreshUi(); savePrefs()
            showToast(if (convertOn) "预览显示转换后的画面" else "显示原始 Log（选中的 LUT 直接套在 Log 上）")
        }
        b.colBurn.setOnClickListener {
            if (recorder != null) return@setOnClickListener
            burnIn = !burnIn
            applyColor(); refreshUi(); savePrefs()
            showToast(if (burnIn) "烧录开：视频带上 LUT 和风格" else "烧录关：视频只录原始画面（Log 不转换），预览照常")
        }
        b.lutSeek.progress = lutStrength
        b.lutSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                lutStrength = progress
                applyColor()
                b.lutLabel.text = "LUT ${lutStrength}%"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) { savePrefs() }
        })
        cam.logRequested = profileLog
        buildLutStrip()
        lutId?.let { id -> if (lutLib.entries().none { it.id == id }) lutId = null else loadLut(id) }
    }

    private fun lutChip(label: String, id: String?, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 13f
        gravity = android.view.Gravity.CENTER
        setBackgroundResource(R.drawable.chip_bg)
        setPadding(dp(14f), 0, dp(14f), 0)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30f)).apply { marginEnd = dp(8f) }
        setOnClickListener { onClick() }
        lutViews += Pair(id, this)
    }

    private fun buildLutStrip() {
        b.lutStrip.removeAllViews()
        lutViews.clear()
        b.lutStrip.addView(lutChip("无 LUT", null) { selectLut(null) })
        for (e in lutLib.entries()) {
            val v = lutChip(e.name, e.id) { selectLut(e.id) }
            if (!e.builtin) v.setOnLongClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("删除 LUT")
                    .setMessage("删除「${e.name}」？")
                    .setPositiveButton("删除") { _, _ ->
                        lutLib.delete(e.id)
                        if (lutId == e.id) selectLut(null)
                        buildLutStrip(); refreshUi()
                    }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
            b.lutStrip.addView(v)
        }
        val add = lutChip("＋ 导入 .cube", "__import__") { importLut.launch(arrayOf("*/*")) }
        add.setTextColor(0xFFFFB547.toInt())
        b.lutStrip.addView(add)
        refreshUi()
    }

    private fun selectLut(id: String?) {
        lutId = id
        if (id == null) { renderer.setLut(null); applyColor(); refreshUi(); savePrefs(); return }
        loadLut(id)
        refreshUi(); savePrefs()
    }

    private fun loadLut(id: String) {
        io.execute {
            val lut = try { lutLib.load(id) } catch (t: Throwable) { main.post { showToast("LUT 读取失败: ${t.message}") }; null }
            if (lut == null) { main.post { if (lutId == id) { lutId = null; applyColor(); refreshUi() } }; return@execute }
            renderer.setLut(lut)
            main.post { if (lutId == id) { applyColor(); if (b.editor.visibility == View.VISIBLE) { refreshEditor(); renderEdit() } else showToast("LUT：${lut.name}（${lut.size}³）") } }
        }
    }

    private fun applyColor() {
        val video = cam.mode == Mode.VIDEO
        val logIn = if (video && profileLog) (if (cam.logHardware) 1 else 2) else 0
        val gain = if (logIn == 1) Math.pow(2.0, -cam.logEvOffset.toDouble()).toFloat() else ColorState.LOG_GAIN
        val mix = if (lutId != null) lutStrength / 100f else 0f
        renderer.color = ColorState(logIn, convertOn, mix, gain)
        renderer.cleanColor = ColorState(logIn, false, 0f, gain)
        renderer.cleanRecord = video && !burnIn
    }

    // ---------------- Phase 4: creative modes ----------------

    private val ACCUM_MODES = setOf(Creative.SLOW, Creative.BLUR, Creative.LIGHT)

    private fun secsLabel(v: Float) = when {
        v <= 0f -> "手动结束"
        v < 1f -> "1/" + Math.round(1f / v) + "s"
        else -> "${v.toInt()}s"
    }

    private fun chip(parent: LinearLayout, label: String, selected: Boolean, accent: Boolean = false, onClick: () -> Unit): TextView {
        val tv = TextView(this).apply {
            text = label
            setTextColor(if (accent) 0xFFFFB547.toInt() else 0xFFFFFFFF.toInt())
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setBackgroundResource(R.drawable.chip_bg)
            setPadding(dp(14f), 0, dp(14f), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30f)).apply { marginEnd = dp(8f) }
            isSelected = selected
            setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onClick() }
        }
        parent.addView(tv)
        return tv
    }

    private fun setupCreative() {
        b.tabCreative.setOnClickListener { tab = 3; buildCreative(); refreshUi() }
        cam.hiResPreview = cam.mode == Mode.PHOTO && creative in ACCUM_MODES
        buildCreative()
    }

    private fun setCreative(c: Creative) {
        if (busy || renderer.accumulating) return
        if (c != Creative.MULTI) clearMulti()
        creative = c
        val hi = c in ACCUM_MODES
        if (cam.hiResPreview != hi) { cam.hiResPreview = hi; maybeBind() }
        buildCreative(); refreshUi()
        showToast(when (c) {
            Creative.NORMAL -> "普通拍照"
            Creative.SLOW -> "慢快门：按下后保持手机稳定，画面里移动的东西会拖出轨迹"
            Creative.BLUR -> "运动模糊：短时间叠加，适合追拍和车流"
            Creative.LIGHT -> "光绘：只留下最亮的光迹，暗环境效果最好"
            Creative.MULTI -> "多重曝光：拍两张，自动叠在一起"
            Creative.LIVE -> "动态照片：记录按快门前后约 1 秒"
        })
    }

    private fun buildCreative() {
        b.creativeStrip.removeAllViews(); b.optStrip.removeAllViews(); b.borderStrip.removeAllViews()
        val photo = cam.mode == Mode.PHOTO
        if (photo) {
            for (c in Creative.values()) chip(b.creativeStrip, c.label, creative == c) { setCreative(c) }
        } else {
            chip(b.creativeStrip, "普通录像", tlIdx == 0) { if (recorder == null) { tlIdx = 0; savePrefs(); buildCreative(); refreshUi() } }
            chip(b.creativeStrip, "延时摄影", tlIdx > 0) { if (recorder == null) { if (tlIdx == 0) tlIdx = 2; savePrefs(); buildCreative(); refreshUi(); showToast("延时摄影：按设定间隔拍一帧，不录声音") } }
        }
        chip(b.creativeStrip, "＋ 编辑照片", false, accent = true) { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

        if (photo) { when (creative) {
            Creative.SLOW -> slowSecs.forEachIndexed { i, v -> chip(b.optStrip, secsLabel(v), i == slowIdx) { slowIdx = i; savePrefs(); buildCreative(); updateHud() } }
            Creative.BLUR -> blurSecs.forEachIndexed { i, v -> chip(b.optStrip, secsLabel(v), i == blurIdx) { blurIdx = i; savePrefs(); buildCreative(); updateHud() } }
            Creative.LIGHT -> lightSecs.forEachIndexed { i, v -> chip(b.optStrip, secsLabel(v), i == lightIdx) { lightIdx = i; savePrefs(); buildCreative(); updateHud() } }
            Creative.MULTI -> {
                chip(b.optStrip, "叠加（变亮）", multiScreen) { multiScreen = true; buildCreative() }
                chip(b.optStrip, "平均", !multiScreen) { multiScreen = false; buildCreative() }
                if (multiFirst != null) chip(b.optStrip, "重拍第一张", false, accent = true) { clearMulti(); buildCreative(); updateHud() }
            }
            Creative.LIVE -> chip(b.optStrip, "保存为动态照片（MVIMG）", true) {}
            Creative.NORMAL -> chip(b.optStrip, "选一个创意模式", false) {}
        } } else {
            if (tlIdx > 0) tlIntervals.forEachIndexed { i, v ->
                if (i > 0) chip(b.optStrip, "每 " + (if (v < 1f) "0.5" else v.toInt().toString()) + " 秒一帧", i == tlIdx) { if (recorder == null) { tlIdx = i; savePrefs(); buildCreative(); updateHud() } }
            } else chip(b.optStrip, "防抖、格式在辅助和顶栏里", false) {}
        }

        for (bd in Border.values()) chip(b.borderStrip, bd.label, border == bd) { border = bd; savePrefs(); buildCreative() }
    }

    private fun updateHud() {
        val photo = cam.mode == Mode.PHOTO
        val text = when {
            renderer.accumulating -> null // progress updates own it
            photo && creative == Creative.SLOW -> "慢快门 " + secsLabel(slowSecs[slowIdx])
            photo && creative == Creative.BLUR -> "运动模糊 " + secsLabel(blurSecs[blurIdx])
            photo && creative == Creative.LIGHT -> "光绘 " + secsLabel(lightSecs[lightIdx])
            photo && creative == Creative.MULTI -> if (multiFirst != null) "多重曝光 · 拍第二张" else "多重曝光 · 拍第一张"
            photo && creative == Creative.LIVE -> "动态照片"
            !photo && tlIdx > 0 && recorder == null -> "延时 每 " + (if (tlIntervals[tlIdx] < 1f) "0.5" else tlIntervals[tlIdx].toInt().toString()) + " 秒一帧"
            else -> ""
        }
        if (text == null) return
        b.creativeHud.text = text
        b.creativeHud.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun startAccum() {
        if (renderer.accumulating || busy) return
        val c = creative
        val secs = when (c) { Creative.SLOW -> slowSecs[slowIdx]; Creative.BLUR -> blurSecs[blurIdx]; else -> lightSecs[lightIdx] }
        val mode = if (c == Creative.LIGHT) CameraRenderer.AccumMode.LIGHTEN else CameraRenderer.AccumMode.AVERAGE
        val o = orientation
        val ratio = photoRatios[photoRatioIdx].r
        val baseInfo = shotInfo()
        busy = true
        accumSecs = 0f
        b.shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        renderer.startAccumulation(mode, (secs * 1000).toLong(), onProgress = { frac, frames ->
            main.post {
                val t = if (secs > 0f) frac * secs else frac
                accumSecs = t
                b.creativeHud.visibility = View.VISIBLE
                b.creativeHud.text = if (secs > 0f) String.format(java.util.Locale.US, "曝光中 %.1f / %.1fs · %d 帧", t, secs, frames)
                else String.format(java.util.Locale.US, "光绘中 %.1fs · 再按快门结束", t)
            }
        }) { bmp, err ->
            if (bmp == null) {
                main.post { busy = false; refreshUi(); if (err != null) showToast("合成失败: ${err.message}") }
                return@startAccumulation
            }
            io.execute {
                val up = if (o == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(o.toFloat()) }, true).also { if (it !== bmp) bmp.recycle() }
                val out = cropToRatio(up, ratio)
                main.post {
                    val total = if (secs > 0f) secs else accumSecs
                    refreshUi()
                    showToast("正在合成…")
                    finishStill(out, baseInfo.copy(exposureNs = (total * 1e9).toLong()))
                }
            }
        }
        main.postDelayed({ refreshUi() }, 50)
    }

    private fun clearMulti() {
        multiFirst?.recycle()
        multiFirst = null
        b.ghost.setImageBitmap(null)
        b.ghost.visibility = View.GONE
    }

    private fun takeMulti() {
        busy = true
        shutterFeedback()
        val info = shotInfo()
        val o = orientation
        captureUpright { bmp ->
            main.post {
                if (bmp == null) { busy = false; showToast("拍照失败"); return@post }
                val first = multiFirst
                if (first == null) {
                    multiFirst = bmp
                    showGhost(bmp, o)
                    busy = false
                    buildCreative(); updateHud()
                    showToast("第一张已拍，对准第二个画面再按快门")
                } else {
                    multiFirst = null
                    b.ghost.visibility = View.GONE
                    b.ghost.setImageBitmap(null)
                    val screen = multiScreen
                    io.execute {
                        val blended = try { blend(first, bmp, screen) } catch (t: Throwable) { null }
                        first.recycle(); bmp.recycle()
                        main.post {
                            buildCreative(); updateHud()
                            if (blended == null) { busy = false; showToast("合成失败") } else finishStill(blended, info)
                        }
                    }
                }
            }
        }
    }

    /** Shows the first exposure over the preview, turned back into portrait screen space. */
    private fun showGhost(bmp: Bitmap, o: Int) {
        val r = renderer.previewRect
        if (r.width() <= 0f) return
        val s = 1080f / maxOf(bmp.width, bmp.height)
        val m = Matrix().apply { postScale(s, s); postRotate(-o.toFloat()) }
        val g = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        b.ghost.setImageBitmap(g)
        b.ghost.layoutParams = FrameLayout.LayoutParams(r.width().toInt(), r.height().toInt())
        b.ghost.x = r.left; b.ghost.y = r.top
        b.ghost.visibility = View.VISIBLE
    }

    private fun blend(a: Bitmap, bm: Bitmap, screen: Boolean): Bitmap {
        val out = a.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        // centre-crop the second shot to the first's shape
        val ta = out.width.toFloat() / out.height
        val sa = bm.width.toFloat() / bm.height
        val src = if (sa > ta) { val w = (bm.height * ta).toInt(); Rect((bm.width - w) / 2, 0, (bm.width + w) / 2, bm.height) }
        else { val h = (bm.width / ta).toInt(); Rect(0, (bm.height - h) / 2, bm.width, (bm.height + h) / 2) }
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        if (screen) p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) else p.alpha = 128
        c.drawBitmap(bm, src, Rect(0, 0, out.width, out.height), p)
        return out
    }

    private fun takeLive() {
        if (cam.imageCapture == null || liveRec != null) return
        busy = true
        val crop = renderer.crop()
        // landscape encoder frame for the (portrait) cropped content
        var lw = renderer.contentH * crop[3]
        var lh = renderer.contentW * crop[2]
        val sc = minOf(1f, 1920f / maxOf(lw, lh))
        lw *= sc; lh *= sc
        val w = (lw.toInt() / 16) * 16
        val h = (lh.toInt() / 16) * 16
        val file = File(cacheDir, "live_${System.currentTimeMillis()}.mp4")
        val info = shotInfo()
        var still: Bitmap? = null
        var stillDone = false
        var video: File? = null
        var videoDone = false
        fun check() {
            if (!stillDone || !videoDone) return
            val st = still
            if (st == null) { video?.delete(); busy = false; showToast("动态照片失败"); return }
            if (video == null) showToast("短视频没录上，只保存照片")
            finishStill(st, info, video)
        }
        val rec = try {
            VideoRecorder(file, w, h, 12_000_000, 30, (orientation + 270) % 360, false, MediaFormat.MIMETYPE_VIDEO_AVC, null) { f, _ ->
                main.post { video = f; videoDone = true; check() }
            }
        } catch (t: Throwable) {
            busy = false; showToast("无法录制动态部分: ${t.message}"); return
        }
        renderer.timelapseIntervalNs = 0L
        rec.start()
        renderer.startRecording(rec, w, h, orientation, 30)
        liveRec = rec
        b.creativeHud.visibility = View.VISIBLE
        b.creativeHud.text = "● 动态照片 录制中"
        main.postDelayed({
            shutterFeedback()
            captureUpright { bmp -> main.post { still = bmp; stillDone = true; check() } }
            main.postDelayed({
                if (liveRec === rec) { renderer.stopRecording(); liveRec = null }
                updateHud()
            }, 1200)
        }, 1000)
    }

    // ---------------- Phase 4: editor ----------------

    private fun setupEditor() {
        for (st in Styles.all) {
            editStyleViews += chip(b.editStyleStrip, st.name, false) {
                editParams.style = st.id; editParams.stampKind = st.stamp; applyEditFx(); refreshEditor(); renderEdit()
            }
        }
        for (bd in Border.values()) editBorderViews += Pair(bd, chip(b.editBorderStrip, bd.label, false) { editBorder = bd; refreshEditor(); renderEdit() })
        b.editIntensity.setOnClickListener { editIntensityIdx = (editIntensityIdx + 1) % intensities.size; applyEditFx(); refreshEditor(); renderEdit() }
        b.editGrain.setOnClickListener { editGrain = !editGrain; applyEditFx(); refreshEditor(); renderEdit() }
        b.editVignette.setOnClickListener { editVignette = !editVignette; applyEditFx(); refreshEditor(); renderEdit() }
        b.editLeak.setOnClickListener { editLeak = !editLeak; applyEditFx(); refreshEditor(); renderEdit() }
        b.editDate.setOnClickListener { editParams.dateStamp = !editParams.dateStamp; refreshEditor(); renderEdit() }
        b.editCancel.setOnClickListener { closeEditor() }
        b.editSave.setOnClickListener { saveEdit() }
    }

    private fun applyEditFx() = fxInto(editParams, editGrain, editVignette, editLeak, intensities[editIntensityIdx])

    private fun buildEditLuts() {
        b.editLutStrip.removeAllViews(); editLutViews.clear()
        editLutViews += Pair(null, chip(b.editLutStrip, "无 LUT", false) { selectLut(null); refreshEditor(); renderEdit() })
        for (e in lutLib.entries()) editLutViews += Pair(e.id, chip(b.editLutStrip, e.name, false) { selectLut(e.id); refreshEditor() })
    }

    private fun refreshEditor() {
        for ((i, v) in editStyleViews.withIndex()) v.isSelected = Styles.all[i].id == editParams.style
        for ((bd, v) in editBorderViews) v.isSelected = bd == editBorder
        for ((id, v) in editLutViews) v.isSelected = id == lutId
        b.editIntensity.text = "强度 ${(intensities[editIntensityIdx] * 100).toInt()}%"
        b.editGrain.isSelected = editGrain
        b.editVignette.isSelected = editVignette
        b.editLeak.isSelected = editLeak
        b.editDate.isSelected = editParams.dateStamp
        b.editDate.text = if (editParams.dateStamp) "日期 开" else "日期 关"
    }

    private fun decodeForEdit(uri: Uri, maxSide: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width; val h = info.size.height
            val sc = minOf(1f, maxSide.toFloat() / maxOf(w, h))
            if (sc < 1f) d.setTargetSize(maxOf(1, (w * sc).toInt()), maxOf(1, (h * sc).toInt()))
        }.let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false).also { _ -> } }

    private fun readExif(uri: Uri): ShotInfo {
        var info = ShotInfo(deviceName(), look = Styles.all.first { it.id == editParams.style }.name)
        try {
            contentResolver.openInputStream(uri)?.use { ins ->
                val e = ExifInterface(ins)
                val model = e.getAttribute(ExifInterface.TAG_MODEL)
                val iso = e.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)
                val exp = e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
                val fn = e.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
                val fl = e.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
                val dt = e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let {
                    try { java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).parse(it) } catch (_: Throwable) { null }
                }
                info = info.copy(
                    model = if (!model.isNullOrBlank() && model != "RetroCam") model else info.model,
                    iso = iso, exposureNs = (exp * 1e9).toLong(), aperture = fn.toFloat(), focalMm = fl,
                    time = dt ?: info.time,
                )
            }
        } catch (_: Throwable) {}
        return info
    }

    private fun openEditor(uri: Uri) {
        if (recorder != null || busy) { showToast("先停止拍摄"); return }
        editUri = uri
        editParams.style = params.style; editParams.stampKind = params.stampKind
        editGrain = grainOn; editVignette = vignetteOn; editLeak = leakOn; editIntensityIdx = intensityIdx
        editParams.dateStamp = false
        editBorder = border
        applyEditFx()
        buildEditLuts()
        refreshEditor()
        b.editor.visibility = View.VISIBLE
        b.editTitle.text = "编辑"
        b.editImage.setImageBitmap(null)
        b.editBusy.visibility = View.VISIBLE
        backCb.isEnabled = true
        io.execute {
            val bmp = try { decodeForEdit(uri, 1440) } catch (t: Throwable) { null }
            val info = readExif(uri)
            main.post {
                if (editUri != uri) { bmp?.recycle(); return@post }
                if (bmp == null) { showToast("打不开这张照片"); closeEditor(); return@post }
                editSrc = bmp
                editInfo = info
                renderEdit()
            }
        }
    }

    private fun renderEdit() {
        val src = editSrc ?: return
        if (editRendering) { editDirty = true; return }
        editRendering = true
        b.editBusy.visibility = View.VISIBLE
        val bd = editBorder
        val info = editInfo.copy(look = Styles.all.first { it.id == editParams.style }.name)
        renderer.processStill(src, editParams) { out, _ ->
            val framed = out?.let { try { Borders.apply(it, bd, info) } catch (_: Throwable) { it } }
            main.post {
                editRendering = false
                if (b.editor.visibility != View.VISIBLE) { framed?.recycle(); return@post }
                if (framed != null) {
                    val old = editShown
                    b.editImage.setImageBitmap(framed)
                    editShown = framed
                    old?.recycle()
                }
                if (editDirty) { editDirty = false; renderEdit() } else b.editBusy.visibility = View.GONE
            }
        }
    }

    private fun saveEdit() {
        val uri = editUri ?: return
        if (editSaving) return
        editSaving = true
        b.editTitle.text = "保存中…"
        b.editBusy.visibility = View.VISIBLE
        val bd = editBorder
        val info = editInfo.copy(look = Styles.all.first { it.id == editParams.style }.name)
        val p = LookParams().apply {
            style = editParams.style; intensity = editParams.intensity; grain = editParams.grain
            vignette = editParams.vignette; leak = editParams.leak; dateStamp = editParams.dateStamp; stampKind = editParams.stampKind
        }
        io.execute {
            val full = try { decodeForEdit(uri, 4096) } catch (t: Throwable) { null }
            if (full == null) { main.post { editSaving = false; b.editTitle.text = "读取失败" }; return@execute }
            renderer.processStill(full, p) { out, _ ->
                full.recycle()
                io.execute {
                    var saved: Uri? = null
                    var thumb: Bitmap? = null
                    if (out != null) try {
                        val framed = Borders.apply(out, bd, info)
                        thumb = ThumbnailUtils.extractThumbnail(framed, 200, 200)
                        saved = MediaSaver.saveJpeg(this@MainActivity, framed)
                        framed.recycle()
                    } catch (t: Throwable) { android.util.Log.e("RetroCam", "edit save", t) }
                    main.post {
                        editSaving = false
                        if (saved == null) { b.editTitle.text = "保存失败"; b.editBusy.visibility = View.GONE; return@post }
                        lastUri = saved; lastIsVideo = false
                        thumb?.let { b.thumb.setImageBitmap(it) }
                        closeEditor()
                        showToast("已另存一张新照片")
                    }
                }
            }
        }
    }

    private fun closeEditor() {
        if (editSaving) return
        b.editor.visibility = View.GONE
        backCb.isEnabled = false
        editUri = null
        b.editImage.setImageBitmap(null)
        editShown?.recycle(); editShown = null
        editSrc?.recycle(); editSrc = null
        editDirty = false
        refreshUi()
    }

    // ---------------- prefs ----------------

    private fun loadPrefs() {
        params.style = prefs.getInt("style", 1)
        grainOn = prefs.getBoolean("grain", true)
        vignetteOn = prefs.getBoolean("vignette", true)
        leakOn = prefs.getBoolean("leak", false)
        intensityIdx = prefs.getInt("intensity", 0).coerceIn(0, intensities.size - 1)
        params.dateStamp = prefs.getBoolean("date", true)
        photoRatioIdx = prefs.getInt("pratio", 0).coerceIn(0, photoRatios.size - 1)
        videoRatioIdx = prefs.getInt("vratio", 0).coerceIn(0, videoRatios.size - 1)
        gridOn = prefs.getBoolean("grid", false)
        levelOn = prefs.getBoolean("level", false)
        histOn = prefs.getBoolean("hist", false)
        peakOn = prefs.getBoolean("peak", false)
        zebraOn = prefs.getBoolean("zebra", false)
        params.stampKind = Styles.all.first { it.id == params.style }.stamp
        lutId = prefs.getString("lut", null)
        lutStrength = prefs.getInt("lutStrength", 100).coerceIn(0, 100)
        profileLog = prefs.getBoolean("log", false)
        convertOn = prefs.getBoolean("convert", true)
        burnIn = prefs.getBoolean("burn", true)
        stabPref = prefs.getBoolean("stab", true)
        border = Border.values().getOrElse(prefs.getInt("border", 0)) { Border.NONE }
        tlIdx = prefs.getInt("tl", 0).coerceIn(0, tlIntervals.size - 1)
        slowIdx = prefs.getInt("slow", 1).coerceIn(0, slowSecs.size - 1)
        blurIdx = prefs.getInt("blur", 1).coerceIn(0, blurSecs.size - 1)
        lightIdx = prefs.getInt("light", 0).coerceIn(0, lightSecs.size - 1)
        val vf = prefs.getString("vfmt", null)?.split(",")?.mapNotNull { it.toIntOrNull() }
        if (vf != null && vf.size == 3) fmtPref = VideoFormat(vf[0], vf[1], vf[2], android.util.Range(vf[2], vf[2]))
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
            .putInt("pratio", photoRatioIdx)
            .putInt("vratio", videoRatioIdx)
            .putBoolean("grid", gridOn)
            .putBoolean("level", levelOn)
            .putBoolean("hist", histOn)
            .putBoolean("peak", peakOn)
            .putBoolean("zebra", zebraOn)
            .putString("lut", lutId)
            .putInt("lutStrength", lutStrength)
            .putBoolean("log", profileLog)
            .putBoolean("convert", convertOn)
            .putBoolean("burn", burnIn)
            .putBoolean("stab", cam.stabRequested)
            .putInt("border", border.ordinal)
            .putInt("tl", tlIdx)
            .putInt("slow", slowIdx)
            .putInt("blur", blurIdx)
            .putInt("light", lightIdx)
            .putString("vfmt", cam.videoFormat?.let { "${it.w},${it.h},${it.fps}" })
            .apply()
    }
}
