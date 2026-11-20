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
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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

    // Phase 2
    private data class Ratio(val label: String, val r: Float)
    private val photoRatios = listOf(Ratio("4:3", 4f / 3f), Ratio("1:1", 1f), Ratio("16:9", 16f / 9f), Ratio("XPan", 65f / 24f))
    private val videoRatios = listOf(Ratio("16:9", 16f / 9f), Ratio("2.39:1", 2.39f))
    private var photoRatioIdx = 0
    private var videoRatioIdx = 0
    private var stabPref = true
    private var fmtPref: VideoFormat? = null
    private var gridOn = false
    private var levelOn = false
    private var histOn = false
    private var peakOn = false
    private var zebraOn = false

    // Phase 3
    private val lutLib by lazy { LutLibrary(this) }
    private var lutId: String? = null
    private var lutStrength = 100
    private var profileLog = false
    private var convertOn = true
    private var burnIn = true
    private val importLut = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        showToast("正在导入 LUT…")
        io.execute {
            try {
                val e = lutLib.import(uri)
                main.post { filterCat = LUT_CAT; selectLut(e.id); if (sheet == Sheet.FILTER) buildFilter(); showToast("已导入 ${e.name}") }
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
    private var tlIdx = 2
    private var border = Border.NONE
    private var lastBorder = Border.WHITE
    private var wmSample: Bitmap? = null
    private val wmCache = HashMap<Border, Bitmap>()
    private var hudHidden = false
    private lateinit var vidBar: LinearLayout
    private val vidCells = HashMap<String, TextView>()
    private lateinit var vidTc: TextView
    private lateinit var vidSub: TextView
    private var liveRec: VideoRecorder? = null
    private var accumSecs = 0f
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) openEditor(uri) }

    // UI v2 (极影-style)
    private enum class UiMode(val label: String, val icon: Int) {
        PHOTO("拍照", R.drawable.ic_camera), VIDEO("录像", R.drawable.ic_video), TIMELAPSE("延时摄影", R.drawable.ic_timelapse),
        SLOW("慢快门", R.drawable.ic_slow), BLUR("运动模糊", R.drawable.ic_blur), LIGHT("光绘", R.drawable.ic_light),
        MULTI("多重曝光", R.drawable.ic_multi), LIVE("动态照片", R.drawable.ic_live),
    }
    private var uiMode = UiMode.PHOTO
    private enum class Sheet { NONE, QUICK, MODE, FILTER, WATERMARK }
    private var sheet = Sheet.NONE
    private var filterCat = 0
    private var settingsTab = 0
    private var mirrorFront = true
    private var volShutter = true
    private var shutterBlink = true
    private var vfPlaced = false
    private val ACCENT = 0xFFF04A2C.toInt()
    private val WHITE = 0xFFFFFFFF.toInt()
    private val GREY = 0xFF8C8C8C.toInt()

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
    private var editTab = 0
    private val backCb = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                b.editor.visibility == View.VISIBLE -> closeEditor()
                b.settings.visibility == View.VISIBLE -> closeSettings()
                sheet != Sheet.NONE -> closeSheet()
                activeParam != null -> { activeParam = null; refreshUi(); syncBack() }
            }
        }
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        loadPrefs()
        renderer = CameraRenderer(params)
        cam = CameraController(this, renderer)
        cam.stabRequested = stabPref
        cam.videoFormat = fmtPref
        cam.logRequested = profileLog
        renderer.sideInset = dp(12f)
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

        b.overlay.corner = dp(22f).toFloat()
        b.vfControls.visibility = View.INVISIBLE
        b.thumb.clipToOutline = true
        setupInsets()
        renderer.onHistogram = { bins -> main.post { b.overlay.setHistogram(bins) } }
        setupVidBar()
        setupControls()
        setupPro()
        setupFilter()
        setupSettings()
        setupEditor()
        onBackPressedDispatcher.addCallback(this, backCb)
        cam.dual = prefs.getBoolean("dual", false)
        cam.onDualUnsupported = { main.post { prefs.edit().putBoolean("dual", false).apply(); showToast("这台手机不支持前后摄同时开启，已关闭双景"); if (sheet == Sheet.QUICK) buildQuick() } }
        cam.onBindFallback = { main.post { showToast("这个格式/防抖组合不支持，已回退 1080p 30") } }
        lutId?.let { id -> if (lutLib.entries().none { it.id == id }) lutId = null else loadLut(id) }
        applyRatio()
        setUiMode(UiMode.values().getOrElse(prefs.getInt("uimode", 0)) { UiMode.PHOTO }, quiet = true)
        main.post(uiTicker)
        setupGestures()
        refreshUi()

        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (needed.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            permLauncher.launch(needed)
        }
    }

    private fun setupInsets() {
        b.topBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateTopInset() }
        // reserve the zoom row (44dp) plus gaps above the pro row so the viewfinder never sits under controls
        b.bottomPanel.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val h = (v.parent as View).height
            if (h > 0) renderer.bottomInset = h - v.top + dp(44f) + dp(14f)
        }
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            b.topBar.setPadding(b.topBar.paddingLeft, bars.top + dp(12f), b.topBar.paddingRight, b.topBar.paddingBottom)
            b.bottomPanel.setPadding(0, 0, 0, bars.bottom + dp(10f))
            b.settingsHeader.setPadding(0, bars.top + dp(6f), 0, 0)
            b.editHeader.setPadding(0, bars.top + dp(6f), 0, 0)
            b.editBottom.setPadding(0, dp(8f), 0, bars.bottom + dp(12f))
            b.filterSheet.setPadding(0, dp(14f), 0, bars.bottom + dp(18f))
            (b.sheet.layoutParams as FrameLayout.LayoutParams).bottomMargin = bars.bottom + dp(28f)
            b.sheet.requestLayout()
            insets
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
        val target = if (r < -180) r + 360 else r
        listOf<View>(b.thumb, b.btnMode, b.btnFlash, b.btnSettings, b.countdown, b.btnQuick, b.btnAuto, b.filterIcon).forEach { it.animate().rotation(target).setDuration(200).start() }
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

    private fun idle() = recorder == null && !renderer.accumulating && liveRec == null && !busy

    private fun syncBack() {
        backCb.isEnabled = b.editor.visibility == View.VISIBLE || b.settings.visibility == View.VISIBLE || sheet != Sheet.NONE || activeParam != null
    }

    private fun setThumb(bmp: Bitmap) {
        try {
            val sc = 420f / maxOf(bmp.width, bmp.height)
            wmSample = if (sc < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt(), (bmp.height * sc).toInt(), true) else bmp.copy(Bitmap.Config.ARGB_8888, false)
            wmCache.clear()
        } catch (_: Throwable) {}
        b.thumb.setPadding(0, 0, 0, 0)
        b.thumb.setImageBitmap(bmp)
    }

    private fun selectStyle(id: Int) {
        params.style = id
        val s = Styles.all.first { it.id == id }
        params.stampKind = s.stamp
        applyFx()
        refreshUi()
        savePrefs()
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
        b.btnFlash.setOnClickListener {
            flashIdx = if (cam.mode == Mode.VIDEO) (if (flashIdx == 0) 1 else 0) else (flashIdx + 1) % 3
            cam.imageCapture?.flashMode = captureFlashMode()
            if (cam.mode == Mode.VIDEO) cam.setTorch(flashIdx == 1)
            refreshUi()
            showToast(when (flashIdx) { 1 -> if (cam.mode == Mode.VIDEO) "补光灯 开" else "闪光灯 开"; 2 -> "闪光灯 自动"; else -> "闪光灯 关" })
        }
        b.btnFormat.setOnClickListener {
            if (!idle()) return@setOnClickListener
            if (cam.mode == Mode.VIDEO) { cycleFormat(); return@setOnClickListener }
            if (cam.dual) { showToast("双景模式下只能拍 JPG"); return@setOnClickListener }
            if (cam.caps?.rawSupported != true) { showToast("这颗镜头不支持 RAW"); return@setOnClickListener }
            cam.rawRequested = !cam.rawRequested
            showToast(if (cam.rawRequested) "RAW：保存 DNG 原片，不套滤镜" else "JPG：带滤镜和水印")
            refreshUi()
            maybeBind()
        }
        b.btnRatio.setOnClickListener {
            if (!idle()) return@setOnClickListener
            if (cam.mode == Mode.PHOTO) photoRatioIdx = (photoRatioIdx + 1) % photoRatios.size
            else videoRatioIdx = (videoRatioIdx + 1) % videoRatios.size
            applyRatio(); refreshUi(); savePrefs()
        }
        b.btnFlip.setOnClickListener {
            if (!idle()) return@setOnClickListener
            cam.manual.reset()
            activeParam = null
            cam.lensFacing = if (cam.lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            b.btnFlip.animate().rotationBy(180f).setDuration(250).start()
            zoomKey = ""
            maybeBind()
        }
        b.btnMode.setOnClickListener { if (idle()) openSheet(Sheet.MODE) }
        b.btnQuick.setOnClickListener { openSheet(Sheet.QUICK) }
        b.btnFilter.setOnClickListener { openSheet(Sheet.FILTER, catOfStyle(params.style)) }
        b.scrim.setOnClickListener { closeSheet() }
        b.shutter.setOnClickListener { onShutter() }
        b.thumb.setOnClickListener { openLast() }
        b.thumb.setOnLongClickListener {
            val u = lastUri
            if (u != null && !lastIsVideo) openEditor(u) else pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            true
        }
    }

    private fun cycleFormat() {
        if (recorder != null || cam.mode != Mode.VIDEO) return
        val list = cam.caps?.videoFormats ?: return
        if (list.size <= 1) { showToast("这颗镜头只支持 1080p 30"); return }
        val cur = cam.boundFormat ?: cam.videoFormat
        val i = list.indexOfFirst { it.w == cur?.w && it.h == cur?.h && it.fps == cur?.fps }
        val next = list[(i + 1) % list.size]
        cam.videoFormat = next
        val codec = Encoders.choose(next.w, next.h, next.fps)
        showToast(next.label + (codec?.let { " · " + it.label + " " + it.bitrate / 1_000_000 + " Mbps" } ?: ""))
        savePrefs()
        maybeBind()
        refreshUi()
    }

    private fun toggleStab() {
        if (cam.mode != Mode.VIDEO) { showToast("防抖用于录像"); return }
        if (recorder != null) return
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
            refreshUi()
        }, 400)
    }

    private fun setLevel(on: Boolean) {
        levelOn = on
        if (on) startLevel() else sensorManager.unregisterListener(levelListener)
        savePrefs()
    }

    // ---------------- video HUD (Blackmagic Camera style) ----------------

    private fun setupVidBar() {
        vidBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10f), dp(4f), dp(10f), dp(6f))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP)
            visibility = View.GONE
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun cell(key: String, label: String, click: () -> Unit): View {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(4f), 0, dp(4f))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                background = GradientDrawable().apply { cornerRadius = dp(6f).toFloat(); setColor(0) }
            }
            col.addView(TextView(this).apply { text = label; setTextColor(0x99FFFFFF.toInt()); textSize = 10f })
            val v = TextView(this).apply { text = "--"; setTextColor(WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1 }
            col.addView(v)
            vidCells[key] = v
            col.setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); click() }
            return col
        }
        fun pro(p: ProParam) { closeSheet(); if (activeParam == p) activeParam = null else openSlider(p); refreshUi() }
        row.addView(cell("fps", "帧率") { if (idle()) cycleFormat() })
        row.addView(cell("sh", "快门") { pro(ProParam.SHUTTER) })
        vidTc = TextView(this).apply {
            text = "00:00:00:00"; setTextColor(WHITE); textSize = 19f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4f); marginEnd = dp(4f) }
        }
        row.addView(vidTc)
        row.addView(cell("iso", "ISO") { pro(ProParam.ISO) })
        row.addView(cell("wb", "白平衡") { pro(ProParam.WB) })
        vidBar.addView(row)
        vidSub = TextView(this).apply {
            setTextColor(0x99FFFFFF.toInt()); textSize = 11f; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2f) }
        }
        vidBar.addView(vidSub)
        b.root.addView(vidBar, b.root.indexOfChild(b.topBar) + 1)
        vidBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateTopInset() }
    }

    private fun updateTopInset() {
        val tb = b.topBar.bottom
        if (::vidBar.isInitialized && vidBar.translationY != tb.toFloat()) vidBar.translationY = tb.toFloat()
        val show = ::vidBar.isInitialized && vidBar.visibility == View.VISIBLE && vidBar.height > 0
        val inset = if (show) tb + vidBar.height + dp(2f) else tb + dp(6f)
        if (renderer.topInset != inset) renderer.topInset = inset
    }

    private var codecKey = ""
    private var codecChoice: Encoders.Choice? = null
    private var freeBytes = 0L
    private var freeAt = 0L

    private fun updateVidBar() {
        if (!::vidBar.isInitialized || vidBar.visibility != View.VISIBLE) return
        val fmt = cam.boundFormat ?: cam.videoFormat
        val fps = fmt?.fps ?: 30
        val m = cam.manual
        vidCells["fps"]?.text = fps.toString()
        val ns = m.exposureNs ?: cam.liveExposureNs
        vidCells["sh"]?.apply { text = if (ns > 0) "1/" + Math.round(1e9 / ns) else "自动"; setTextColor(if (m.exposureNs != null) ACCENT else WHITE) }
        val iso = m.iso ?: cam.liveIso
        vidCells["iso"]?.apply { text = if (iso > 0) iso.toString() else "自动"; setTextColor(if (m.iso != null) ACCENT else WHITE) }
        val wbAuto = m.awbMode == android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_AUTO
        vidCells["wb"]?.apply {
            text = if (wbAuto) "自动" else (cam.caps?.let { c -> ManualOptions.wb(c).firstOrNull { it.value == m.awbMode }?.label } ?: "手动")
            setTextColor(if (wbAuto) WHITE else ACCENT)
        }
        val rec = recorder != null
        if (rec) {
            val ms = SystemClock.elapsedRealtime() - recStart
            val sec = ms / 1000
            val fr = (ms % 1000) * fps / 1000
            vidTc.text = String.format(java.util.Locale.US, "%02d:%02d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60, fr)
        } else vidTc.text = "00:00:00:00"
        vidTc.setTextColor(if (rec) 0xFFFF3B30.toInt() else WHITE)
        val key = "${fmt?.w}x${fmt?.h}@$fps"
        if (key != codecKey) { codecKey = key; codecChoice = try { Encoders.choose(fmt?.w ?: 1920, fmt?.h ?: 1080, fps) } catch (_: Throwable) { null } }
        val now = SystemClock.elapsedRealtime()
        if (now - freeAt > 5000) {
            freeAt = now
            freeBytes = try { android.os.StatFs(filesDir.absolutePath).availableBytes } catch (_: Throwable) { 0L }
        }
        val parts = ArrayList<String>()
        parts += fmt?.label ?: "1080p 30"
        codecChoice?.let { parts += it.label }
        if (profileLog) parts += "Log"
        if (lutId != null) parts += "LUT"
        val br = codecChoice?.bitrate ?: 0
        if (br > 0 && freeBytes > 0) {
            val secs = freeBytes / (br / 8L + 24_000L)
            parts += "剩余 " + (if (secs >= 3600) "${secs / 3600}h${secs / 60 % 60}m" else "${secs / 60}m")
        }
        vidSub.text = parts.joinToString(" · ")
    }

    private fun refreshUi() {
        val photo = cam.mode == Mode.PHOTO
        val hideHud = hudHidden && !photo
        val showVid = !photo && !hideHud
        if (::vidBar.isInitialized && (vidBar.visibility == View.VISIBLE) != showVid) {
            vidBar.visibility = if (showVid) View.VISIBLE else View.GONE
            vidBar.post { updateTopInset() }
            updateTopInset()
        }
        b.proRow.visibility = if (hideHud) View.INVISIBLE else View.VISIBLE
        if (vfPlaced) b.vfControls.visibility = if (hideHud) View.INVISIBLE else View.VISIBLE
        b.recTime.visibility = if (showVid && renderer.timelapseIntervalNs <= 0) View.GONE else View.VISIBLE
        updateVidBar()
        b.btnFormat.text = if (photo) (if (cam.rawRequested) "RAW" else "JPG") else ((cam.boundFormat ?: cam.videoFormat)?.label ?: "1080p 30")
        b.btnFormat.setTextColor(if (photo && cam.rawRequested) ACCENT else WHITE)
        b.btnRatio.text = if (photo) photoRatios[photoRatioIdx].label else videoRatios[videoRatioIdx].label
        b.btnFlash.setImageResource(when (flashIdx) { 1 -> R.drawable.ic_flash_on; 2 -> R.drawable.ic_flash_auto; else -> R.drawable.ic_flash_off })
        b.btnFlash.setColorFilter(if (flashIdx == 1) ACCENT else WHITE)
        b.btnMode.setImageResource(uiMode.icon)
        b.btnMode.setColorFilter(if (uiMode == UiMode.PHOTO || uiMode == UiMode.VIDEO) WHITE else ACCENT)
        val st = Styles.all.first { it.id == params.style }
        val lutName = lutId?.let { id -> lutLib.entries().firstOrNull { it.id == id }?.name }
        b.filterLabel.text = when {
            params.style == 0 && lutName == null -> "原图"
            params.style == 0 -> lutName
            lutName != null -> st.name + "+LUT"
            else -> st.name
        }
        b.filterIcon.setColorFilter(if (params.style != 0 || lutId != null) ACCENT else WHITE)
        b.overlay.grid = gridOn
        b.overlay.level = levelOn
        b.overlay.showHistogram = histOn
        renderer.peaking = peakOn
        renderer.zebra = zebraOn
        renderer.histogram = histOn
        val running = recorder != null || renderer.accumulating || liveRec != null
        b.sliderRow.visibility = if (activeParam != null) View.VISIBLE else View.GONE
        b.optScroll.visibility = if (b.optStrip.childCount > 0 && !running) View.VISIBLE else View.GONE
        for (v in listOf<View>(b.thumb, b.btnMode, b.btnFlip, b.btnFilter, b.btnFormat, b.btnRatio, b.btnSettings)) v.alpha = if (running) 0.3f else 1f
        b.shutter.setImageResource(
            when {
                recorder != null || renderer.accumulating -> R.drawable.shutter_recording
                photo -> R.drawable.shutter_photo
                else -> R.drawable.shutter_video
            }
        )
        updateHud()
        refreshPro()
        if (sheet == Sheet.QUICK) buildQuick()
        if (sheet == Sheet.FILTER) refreshFilterFx()
        syncBack()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                cam.setZoom(cam.zoomRatio() * d.scaleFactor)
                return true
            }
        })
        var downX = 0f; var downY = 0f; var scaled = false
        b.preview.setOnTouchListener { _, e ->
            scale.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; scaled = false }
                MotionEvent.ACTION_POINTER_DOWN -> scaled = true
                MotionEvent.ACTION_UP -> {
                    val dx = e.x - downX; val dy = e.y - downY
                    if (!scaled && !scale.isInProgress && kotlin.math.abs(dx) > dp(70f) && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f) {
                        // swipe left / right → next / previous filter
                        stepFilter(if (dx < 0) 1 else -1)
                    } else if (!scaled && !scale.isInProgress && kotlin.math.abs(dy) > dp(90f) && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.5f && cam.mode == Mode.VIDEO) {
                        // swipe up / down in video → hide / show the HUD for a clean frame
                        hudHidden = dy < 0
                        refreshUi()
                        showToast(if (hudHidden) "已隐藏参数，下滑恢复" else "显示参数")
                    } else if (!scaled && !scale.isInProgress && e.eventTime - e.downTime < 300 && kotlin.math.abs(dx) < dp(12f) && kotlin.math.abs(dy) < dp(12f)) {
                        if (activeParam != null) { activeParam = null; refreshUi() }
                        val r = renderer.previewRect
                        if (cam.dual && r.width() > 0) {
                            val f = renderer.pipFrac(r.width(), r.height())
                            val pl = r.left + f[0] * r.width(); val pt = r.top + f[1] * r.height()
                            if (e.x >= pl && e.x <= pl + f[2] * r.width() && e.y >= pt && e.y <= pt + f[3] * r.height()) { swapDual(); return@setOnTouchListener true }
                        }
                        if (r.width() > 0 && r.contains(e.x, e.y)) {
                            val (fx, fy) = renderer.toFullContent((e.x - r.left) / r.width(), (e.y - r.top) / r.height())
                            cam.focusAt(fx, fy)
                            showFocusRing(e.x, e.y)
                        }
                    }
                }
            }
            true
        }
    }

    private val FILTER_ORDER = listOf(0, 1, 2, 8, 3, 4, 5, 6, 9, 7)

    private fun stepFilter(dir: Int) {
        if (!idle()) return
        val i = FILTER_ORDER.indexOf(params.style).coerceAtLeast(0)
        val next = FILTER_ORDER[(i + dir + FILTER_ORDER.size) % FILTER_ORDER.size]
        b.preview.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        selectStyle(next)
        if (sheet == Sheet.FILTER) buildFilter()
        showBigLabel(Styles.all.first { it.id == next }.name)
    }

    private var bigLabel: TextView? = null
    private fun showBigLabel(text: String) {
        val tv = bigLabel ?: TextView(this).apply {
            setTextColor(WHITE); textSize = 26f; typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(14f, 0f, 2f, 0xCC000000.toInt())
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            b.root.addView(this, b.root.indexOfChild(b.hudCol))
            bigLabel = this
        }
        tv.text = text
        val r = renderer.previewRect
        tv.y = (if (r.height() > 0) r.centerY() else b.root.height / 2f) - dp(20f)
        tv.visibility = View.VISIBLE
        tv.alpha = 1f
        tv.animate().cancel()
        tv.animate().alpha(0f).setStartDelay(700).setDuration(300).withEndAction { tv.visibility = View.GONE }.start()
    }

    private fun showFocusRing(x: Float, y: Float) {
        val size = dp(64f)
        val ring = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(6f).toFloat(); setStroke(dp(1.5f), 0xFFFFD54F.toInt()) }
            layoutParams = FrameLayout.LayoutParams(size, size)
        }
        b.root.addView(ring, b.root.indexOfChild(b.hudCol))
        ring.x = x - size / 2f
        ring.y = y - size / 2f
        ring.scaleX = 1.4f; ring.scaleY = 1.4f
        ring.animate().scaleX(1f).scaleY(1f).setDuration(180).withEndAction {
            ring.animate().alpha(0f).setStartDelay(700).setDuration(300).withEndAction { b.root.removeView(ring) }.start()
        }.start()
    }

    private fun showToast(msg: String) {
        b.toast.text = msg
        b.toast.visibility = View.VISIBLE
        b.toast.alpha = 1f
        b.toast.animate().cancel()
        b.toast.animate().alpha(0f).setStartDelay(1100).setDuration(300).withEndAction { b.toast.visibility = View.GONE }.start()
    }

    // ---------------- modes ----------------

    private fun setUiMode(m: UiMode, quiet: Boolean = false) {
        if (recorder != null || busy || renderer.accumulating || liveRec != null) return
        val changed = m != uiMode
        uiMode = m
        val newCreative = when (m) {
            UiMode.SLOW -> Creative.SLOW; UiMode.BLUR -> Creative.BLUR; UiMode.LIGHT -> Creative.LIGHT
            UiMode.MULTI -> Creative.MULTI; UiMode.LIVE -> Creative.LIVE; else -> Creative.NORMAL
        }
        if (newCreative != Creative.MULTI) clearMulti()
        creative = newCreative
        val camMode = if (m == UiMode.VIDEO || m == UiMode.TIMELAPSE) Mode.VIDEO else Mode.PHOTO
        var rebind = false
        if (cam.mode != camMode) {
            cam.mode = camMode
            if (camMode == Mode.VIDEO && flashIdx == 2) flashIdx = 0
            if (camMode == Mode.VIDEO && (cam.manual.exposureNs ?: 0L) > 33_333_333L) cam.manual.exposureNs = null
            activeParam = null
            applyRatio(); applyColor(); rebind = true
        }
        val hi = camMode == Mode.PHOTO && creative in ACCUM_MODES
        if (cam.hiResPreview != hi) { cam.hiResPreview = hi; rebind = true }
        if (tlIdx == 0) tlIdx = 2
        buildOptions()
        refreshUi()
        savePrefs()
        if (rebind) maybeBind()
        if (!quiet && changed) showToast(when (m) {
            UiMode.PHOTO -> "拍照"
            UiMode.VIDEO -> "录像"
            UiMode.TIMELAPSE -> "延时摄影：按间隔拍一帧，不录声音"
            UiMode.SLOW -> "慢快门：手机放稳，移动的东西拖出轨迹"
            UiMode.BLUR -> "运动模糊：适合追拍和车流"
            UiMode.LIGHT -> "光绘：只留下最亮的光迹，暗处效果最好"
            UiMode.MULTI -> "多重曝光：拍两张，自动叠在一起"
            UiMode.LIVE -> "动态照片：记录按快门前后约 1 秒"
        })
    }

    private fun vfChip(label: String, selected: Boolean, onClick: () -> Unit) {
        val tv = TextView(this).apply {
            text = label
            setTextColor(WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.vf_chip_bg)
            setPadding(dp(14f), 0, dp(14f), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32f)).apply { marginEnd = dp(8f) }
            isSelected = selected
            setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onClick() }
        }
        b.optStrip.addView(tv)
    }

    private fun buildOptions() {
        b.optStrip.removeAllViews()
        when (uiMode) {
            UiMode.SLOW -> slowSecs.forEachIndexed { i, v -> vfChip(secsLabel(v), i == slowIdx) { slowIdx = i; savePrefs(); buildOptions(); updateHud() } }
            UiMode.BLUR -> blurSecs.forEachIndexed { i, v -> vfChip(secsLabel(v), i == blurIdx) { blurIdx = i; savePrefs(); buildOptions(); updateHud() } }
            UiMode.LIGHT -> lightSecs.forEachIndexed { i, v -> vfChip(secsLabel(v), i == lightIdx) { lightIdx = i; savePrefs(); buildOptions(); updateHud() } }
            UiMode.MULTI -> {
                vfChip("变亮叠加", multiScreen) { multiScreen = true; buildOptions() }
                vfChip("平均", !multiScreen) { multiScreen = false; buildOptions() }
                if (multiFirst != null) vfChip("重拍第一张", false) { clearMulti(); buildOptions(); updateHud() }
            }
            UiMode.TIMELAPSE -> tlIntervals.forEachIndexed { i, v ->
                if (i > 0) vfChip("每 " + (if (v < 1f) "0.5" else v.toInt().toString()) + " 秒", i == tlIdx) {
                    if (recorder == null) { tlIdx = i; savePrefs(); buildOptions(); updateHud() }
                }
            }
            else -> {}
        }
        b.optScroll.visibility = if (b.optStrip.childCount > 0) View.VISIBLE else View.GONE
    }

    // ---------------- sheets ----------------

    private fun openSheet(s: Sheet, cat: Int = -1) {
        if (s == sheet) { closeSheet(); return }
        sheet = s
        activeParam = null
        val v: View = if (s == Sheet.FILTER) b.filterSheet else b.sheet
        b.sheet.visibility = if (v === b.sheet) View.VISIBLE else View.GONE
        b.filterSheet.visibility = if (v === b.filterSheet) View.VISIBLE else View.GONE
        b.scrim.visibility = View.VISIBLE
        when (s) {
            Sheet.QUICK -> buildQuick()
            Sheet.MODE -> buildModes()
            Sheet.FILTER -> { if (cat >= 0) filterCat = cat; buildFilter() }
            Sheet.WATERMARK -> buildWatermark()
            Sheet.NONE -> {}
        }
        v.alpha = 0f
        v.translationY = dp(40f).toFloat()
        v.animate().alpha(1f).translationY(0f).setDuration(180).start()
        refreshUi()
    }

    private fun closeSheet() {
        if (sheet == Sheet.NONE) return
        sheet = Sheet.NONE
        b.sheet.visibility = View.GONE
        b.filterSheet.visibility = View.GONE
        b.scrim.visibility = View.GONE
        syncBack()
    }

    private class GItem(val label: String, val icon: Int, val value: String?, val on: Boolean, val click: () -> Unit)

    private fun buildGrid(title: String?, items: List<GItem>, cols: Int = 4) {
        b.sheetTitle.text = title ?: ""
        b.sheetTitle.visibility = if (title == null) View.GONE else View.VISIBLE
        b.sheetGrid.removeAllViews()
        var row: LinearLayout? = null
        items.forEachIndexed { i, g ->
            if (i % cols == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = if (i == 0) 0 else dp(16f) }
                }
                b.sheetGrid.addView(row)
            }
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val circle = FrameLayout(this).apply {
                setBackgroundResource(R.drawable.qring)
                isSelected = g.on
                layoutParams = LinearLayout.LayoutParams(dp(56f), dp(56f))
            }
            if (g.value != null) circle.addView(TextView(this).apply {
                text = g.value; gravity = Gravity.CENTER; setTextColor(WHITE); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            }) else circle.addView(ImageView(this).apply {
                setImageResource(g.icon)
                layoutParams = FrameLayout.LayoutParams(dp(22f), dp(22f), Gravity.CENTER)
            })
            val lab = TextView(this).apply {
                text = g.label; setTextColor(WHITE); textSize = 13f; gravity = Gravity.CENTER; maxLines = 1
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) }
            }
            cell.addView(circle); cell.addView(lab)
            cell.setOnClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); g.click() }
            row?.addView(cell)
        }
        val rem = items.size % cols
        if (rem != 0) repeat(cols - rem) { row?.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }) }
    }

    private fun buildQuick() {
        val photo = cam.mode == Mode.PHOTO
        val items = ArrayList<GItem>()
        if (photo) items += GItem(if (border == Border.NONE) "水印" else border.label, R.drawable.ic_border, null, border != Border.NONE) {
            sheet = Sheet.NONE; openSheet(Sheet.WATERMARK)
        }
        items += GItem("双景", R.drawable.ic_dual, null, cam.dual) { toggleDual() }
        items += GItem("倒计时", R.drawable.ic_timer, if (timers[timerIdx] == 0) null else "${timers[timerIdx]}s", timers[timerIdx] > 0) {
            timerIdx = (timerIdx + 1) % timers.size; buildQuick()
        }
        items += GItem("参考线", R.drawable.ic_grid, null, gridOn) { gridOn = !gridOn; savePrefs(); refreshUi() }
        items += GItem("直方图", R.drawable.ic_hist, null, histOn) { histOn = !histOn; savePrefs(); refreshUi() }
        items += GItem("峰值", R.drawable.ic_peak, null, peakOn) { peakOn = !peakOn; savePrefs(); refreshUi() }
        items += GItem("水平仪", R.drawable.ic_level, null, levelOn) { setLevel(!levelOn); refreshUi() }
        items += GItem("斑马纹", R.drawable.ic_zebra, null, zebraOn) { zebraOn = !zebraOn; savePrefs(); refreshUi() }
        items += GItem("日期", R.drawable.ic_date, null, params.dateStamp) { params.dateStamp = !params.dateStamp; savePrefs(); refreshUi() }
        if (!photo) items += GItem("防抖", R.drawable.ic_stab, null, cam.stabRequested) { toggleStab(); main.postDelayed({ if (sheet == Sheet.QUICK) buildQuick() }, 450) }
        buildGrid(null, items)
    }

    private fun wmSampleBitmap(): Bitmap {
        wmSample?.let { return it }
        // generated stand-in until the first photo is taken: sky, sun, hills
        val w = 300; val h = 400
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF7FB2E5.toInt(), 0xFFF3C892.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null; p.color = 0xFFFFF1C9.toInt(); c.drawCircle(w * 0.7f, h * 0.38f, w * 0.09f, p)
        p.color = 0xFF5E7A55.toInt(); c.drawOval(-w * 0.3f, h * 0.62f, w * 0.9f, h * 1.3f, p)
        p.color = 0xFF3F5A3E.toInt(); c.drawOval(w * 0.3f, h * 0.7f, w * 1.4f, h * 1.4f, p)
        wmSample = bmp
        return bmp
    }

    private fun buildWatermark() {
        b.sheetTitle.text = "水印"
        b.sheetTitle.visibility = View.VISIBLE
        b.sheetGrid.removeAllViews()
        val on = border != Border.NONE
        // 关闭 / 开启 segmented switch
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { cornerRadius = dp(24f).toFloat(); setColor(0xFF161618.toInt()) }
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48f)).apply { marginStart = dp(20f); marginEnd = dp(20f) }
        }
        for ((label, want) in listOf("关闭" to false, "开启" to true)) {
            seg.addView(TextView(this).apply {
                text = label; gravity = Gravity.CENTER; textSize = 15f
                setTextColor(if (want == on) 0xFF111111.toInt() else WHITE)
                if (want == on) background = GradientDrawable().apply { cornerRadius = dp(20f).toFloat(); setColor(ACCENT) }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    border = if (want) lastBorder else Border.NONE
                    savePrefs(); buildWatermark()
                }
            })
        }
        b.sheetGrid.addView(seg)
        val scroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18f) }
        }
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14f), 0, dp(14f), 0) }
        scroll.addView(strip)
        val sample = wmSampleBitmap()
        val info = shotInfo()
        val cardH = dp(150f)
        var selectedView: View? = null
        for (kind in Border.values()) {
            if (kind == Border.NONE) continue
            val sel = kind == border
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(112f), cardH)
                background = GradientDrawable().apply {
                    cornerRadius = dp(12f).toFloat(); setColor(0xFF1A1A1C.toInt())
                    if (sel) setStroke(dp(2.5f), WHITE)
                }
                setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            }
            val cached = wmCache[kind]
            if (cached != null) img.setImageBitmap(cached) else io.execute {
                val out = try { Borders.apply(sample.copy(Bitmap.Config.ARGB_8888, true), kind, info) } catch (_: Throwable) { null }
                if (out != null) main.post { wmCache[kind] = out; img.setImageBitmap(out) }
            }
            val lab = TextView(this).apply {
                text = kind.label; textSize = 13f; gravity = Gravity.CENTER
                setTextColor(if (sel) ACCENT else WHITE)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) }
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(dp(112f), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6f); marginEnd = dp(6f) }
                addView(img); addView(lab)
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    border = kind; lastBorder = kind; savePrefs(); buildWatermark()
                }
            }
            if (sel) selectedView = col
            strip.addView(col)
        }
        b.sheetGrid.addView(scroll)
        selectedView?.let { v -> scroll.post { scroll.scrollTo(maxOf(0, v.left - dp(40f)), 0) } }
        b.sheetGrid.addView(TextView(this).apply {
            text = "水印只加在 JPG 照片上，RAW 和视频不加"
            setTextColor(0x80FFFFFF.toInt()); textSize = 12f; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14f) }
        })
    }

    private fun toggleDual() {
        if (!idle()) return
        if (!cam.dual && !cam.dualSupported()) { showToast("这台手机不支持前后摄同时开启"); return }
        cam.dual = !cam.dual
        if (cam.dual && cam.rawRequested) cam.rawRequested = false
        cam.manual.reset(); activeParam = null; zoomKey = ""
        showToast(if (cam.dual) "双景：点小窗交换前后摄" else "双景 关")
        prefs.edit().putBoolean("dual", cam.dual).apply()
        maybeBind()
        if (sheet == Sheet.QUICK) buildQuick()
    }

    private fun swapDual() {
        if (!idle()) return
        cam.manual.reset(); activeParam = null; zoomKey = ""
        cam.lensFacing = if (cam.lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        b.btnFlip.animate().rotationBy(180f).setDuration(250).start()
        maybeBind()
    }

    private fun buildModes() {
        val items = UiMode.values().map { m -> GItem(m.label, m.icon, null, m == uiMode) { closeSheet(); setUiMode(m) } } +
            GItem("编辑照片", R.drawable.ic_edit, null, false) {
                closeSheet(); pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        buildGrid("拍摄模式", items)
    }

    // ---------------- filter sheet ----------------

    private val CATS = listOf("胶片", "复古", "黑白", "LUT")
    private val catStyles = listOf(listOf(3, 4, 5, 6, 9), listOf(1, 2, 8), listOf(7), emptyList())
    private val LUT_CAT = 3
    private class Tile(val c1: Int, val c2: Int, val badge: String)
    private val styleTiles = mapOf(
        0 to Tile(0xFF4A4A4A.toInt(), 0xFF262626.toInt(), "原"),
        1 to Tile(0xFF3B6FE0.toInt(), 0xFF14306E.toInt(), "DV"),
        2 to Tile(0xFF9B59B6.toInt(), 0xFF4A235A.toInt(), "CCD"),
        3 to Tile(0xFFF5B78E.toInt(), 0xFFB0603A.toInt(), "160"),
        4 to Tile(0xFFF6C744.toInt(), 0xFFC0392B.toInt(), "200"),
        5 to Tile(0xFFE67E22.toInt(), 0xFF7E3A0E.toInt(), "400"),
        6 to Tile(0xFF3498DB.toInt(), 0xFF1B4F72.toInt(), "E6"),
        7 to Tile(0xFF9A9A9A.toInt(), 0xFF1E1E1E.toInt(), "BW"),
        8 to Tile(0xFF1ABC9C.toInt(), 0xFF0E5E4E.toInt(), "VHS"),
        9 to Tile(0xFFE74C3C.toInt(), 0xFF6E1B12.toInt(), "S8"),
    )
    private val lutPalette = intArrayOf(0xFF16A085.toInt(), 0xFF8E44AD.toInt(), 0xFF2C3E50.toInt(), 0xFFD35400.toInt(), 0xFF27AE60.toInt(), 0xFF2980B9.toInt(), 0xFFC0392B.toInt())

    private fun lutTile(name: String): Tile {
        val c = lutPalette[(name.hashCode() and 0x7fffffff) % lutPalette.size]
        val badge = name.filter { it.isLetterOrDigit() }.take(3).uppercase().ifEmpty { "LUT" }
        return Tile(c, 0xFF141414.toInt(), badge)
    }

    private fun catOfStyle(id: Int): Int {
        if (id == 0 && lutId != null) return LUT_CAT
        val i = catStyles.indexOfFirst { id in it }
        return if (i >= 0) i else 0
    }

    private fun setupFilter() {
        b.fxGrain.setOnClickListener { grainOn = !grainOn; applyFx(); refreshUi(); savePrefs() }
        b.fxVignette.setOnClickListener { vignetteOn = !vignetteOn; applyFx(); refreshUi(); savePrefs() }
        b.fxLeak.setOnClickListener { leakOn = !leakOn; applyFx(); refreshUi(); savePrefs() }
        b.fxIntensity.setOnClickListener { intensityIdx = (intensityIdx + 1) % intensities.size; applyFx(); refreshUi(); savePrefs() }
        b.lutSeek.progress = lutStrength
        b.lutSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                lutStrength = progress
                applyColor()
                b.lutLabel.text = "强度 ${lutStrength}%"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) { savePrefs() }
        })
    }

    private fun tile(parent: LinearLayout, name: String, t: Tile, selected: Boolean, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(dp(78f), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val can = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(t.c1, t.c2)).apply {
                cornerRadius = dp(12f).toFloat()
                if (selected) setStroke(dp(2.5f), ACCENT)
            }
            layoutParams = LinearLayout.LayoutParams(dp(60f), dp(74f))
        }
        can.addView(View(this).apply {
            setBackgroundColor(0x33000000)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(12f), Gravity.TOP).apply { topMargin = dp(8f) }
        })
        can.addView(TextView(this).apply {
            text = t.badge; setTextColor(WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setShadowLayer(4f, 0f, 1f, 0x80000000.toInt())
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        })
        val lab = TextView(this).apply {
            text = name; setTextColor(if (selected) ACCENT else WHITE); textSize = 12f; gravity = Gravity.CENTER; maxLines = 2
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6f) }
        }
        col.addView(can); col.addView(lab)
        col.setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onClick() }
        if (onLong != null) col.setOnLongClickListener { onLong(); true }
        parent.addView(col)
    }

    private fun buildFilter() {
        b.catStrip.removeAllViews()
        val none = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.cat_pill)
            isSelected = params.style == 0 && lutId == null
            layoutParams = LinearLayout.LayoutParams(dp(48f), dp(42f)).apply { marginEnd = dp(8f) }
            addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_none); layoutParams = FrameLayout.LayoutParams(dp(22f), dp(22f), Gravity.CENTER) })
            setOnClickListener { selectStyle(0); selectLut(null); buildFilter() }
        }
        b.catStrip.addView(none)
        CATS.forEachIndexed { i, name ->
            b.catStrip.addView(TextView(this).apply {
                text = name; setTextColor(WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.cat_pill)
                isSelected = filterCat == i
                setPadding(dp(24f), 0, dp(24f), 0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(42f)).apply { marginEnd = dp(8f) }
                setOnClickListener { filterCat = i; buildFilter() }
            })
        }
        b.tileStrip.removeAllViews()
        if (filterCat == LUT_CAT) {
            for (e in lutLib.entries()) {
                val del: (() -> Unit)? = if (e.builtin) null else ({
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("删除 LUT")
                        .setMessage("删除「${e.name}」？")
                        .setPositiveButton("删除") { _, _ ->
                            lutLib.delete(e.id)
                            if (lutId == e.id) selectLut(null)
                            buildFilter(); refreshUi()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                })
                tile(b.tileStrip, e.name, lutTile(e.name), lutId == e.id, del) { selectLut(if (lutId == e.id) null else e.id); buildFilter() }
            }
            tile(b.tileStrip, "导入 .cube", Tile(0xFF2E2E2E.toInt(), 0xFF1A1A1A.toInt(), "＋"), false) { importLut.launch(arrayOf("*/*")) }
        } else {
            for (id in catStyles[filterCat]) {
                val s = Styles.all.first { it.id == id }
                tile(b.tileStrip, s.name, styleTiles[id]!!, params.style == id) { selectStyle(id); buildFilter() }
            }
        }
        b.fxScroll.visibility = if (filterCat == LUT_CAT) View.GONE else View.VISIBLE
        b.lutRow.visibility = if (filterCat == LUT_CAT) View.VISIBLE else View.GONE
        refreshFilterFx()
    }

    private fun refreshFilterFx() {
        b.fxGrain.isSelected = grainOn
        b.fxVignette.isSelected = vignetteOn
        b.fxLeak.isSelected = leakOn
        b.fxIntensity.text = "强度 ${(intensities[intensityIdx] * 100).toInt()}%"
        b.lutLabel.text = "强度 ${lutStrength}%"
        b.lutSeek.progress = lutStrength
        b.lutSeek.isEnabled = lutId != null
        b.lutSeek.alpha = if (lutId != null) 1f else 0.4f
    }

    // ---------------- settings page ----------------

    private val settingsTabs = listOf("通用", "视频", "辅助", "关于")

    private fun setupSettings() {
        b.btnSettings.setOnClickListener { if (idle()) openSettings() }
        b.settingsBack.setOnClickListener { closeSettings() }
        settingsTabs.forEachIndexed { i, t ->
            b.segBar.addView(TextView(this).apply {
                text = t; gravity = Gravity.CENTER; setTextColor(WHITE); textSize = 15f
                setBackgroundResource(R.drawable.seg_item)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener { settingsTab = i; buildSettings() }
            })
        }
    }

    private fun openSettings() {
        closeSheet()
        b.settings.visibility = View.VISIBLE
        buildSettings()
        syncBack()
    }

    private fun closeSettings() {
        b.settings.visibility = View.GONE
        syncBack()
        refreshUi()
    }

    private fun section(title: String) {
        b.settingsList.addView(TextView(this).apply {
            text = title; setTextColor(GREY); textSize = 14f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(22f); bottomMargin = dp(10f); marginStart = dp(6f)
            }
        })
    }

    private fun card(build: (LinearLayout) -> Unit) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        build(c)
        b.settingsList.addView(c)
    }

    private fun rowBase(card: LinearLayout, icon: Int, title: String, sub: String?): LinearLayout {
        if (card.childCount > 0) card.addView(View(this).apply {
            setBackgroundColor(0xFF2A2A2A.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { marginStart = dp(58f) }
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18f), dp(16f), dp(16f), dp(16f))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        row.addView(ImageView(this).apply { setImageResource(icon); layoutParams = LinearLayout.LayoutParams(dp(24f), dp(24f)) })
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16f); marginEnd = dp(12f) }
        }
        texts.addView(TextView(this).apply { text = title; setTextColor(WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD })
        if (!sub.isNullOrEmpty()) texts.addView(TextView(this).apply {
            text = sub; setTextColor(GREY); textSize = 12f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4f) }
        })
        row.addView(texts)
        card.addView(row)
        return row
    }

    private fun switchView(on: Boolean): FrameLayout {
        val track = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(50f), dp(30f))
        }
        val knob = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF000000.toInt()) }
            layoutParams = FrameLayout.LayoutParams(dp(24f), dp(24f), Gravity.CENTER_VERTICAL)
        }
        track.addView(knob)
        setSwitch(track, on)
        return track
    }

    private fun setSwitch(track: FrameLayout, on: Boolean) {
        track.background = GradientDrawable().apply { cornerRadius = dp(15f).toFloat(); setColor(if (on) ACCENT else 0xFF4A4A4A.toInt()) }
        val knob = track.getChildAt(0)
        (knob.layoutParams as FrameLayout.LayoutParams).apply { gravity = Gravity.CENTER_VERTICAL or (if (on) Gravity.END else Gravity.START); marginStart = dp(3f); marginEnd = dp(3f) }
        knob.requestLayout()
    }

    private fun toggleRow(card: LinearLayout, icon: Int, title: String, sub: String?, on: Boolean, onChange: (Boolean) -> Unit) {
        val row = rowBase(card, icon, title, sub)
        val sw = switchView(on)
        row.addView(sw)
        var state = on
        row.setOnClickListener {
            state = !state
            setSwitch(sw, state)
            onChange(state)
        }
    }

    private fun valueRow(card: LinearLayout, icon: Int, title: String, value: String, sub: String? = null, onClick: (() -> Unit)?) {
        val row = rowBase(card, icon, title, sub)
        row.addView(TextView(this).apply { text = value; setTextColor(GREY); textSize = 14f })
        if (onClick != null) {
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_chevron_right); setColorFilter(GREY)
                layoutParams = LinearLayout.LayoutParams(dp(18f), dp(18f)).apply { marginStart = dp(6f) }
            })
            row.setOnClickListener { onClick() }
        }
    }

    private fun appVersion(): String = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Throwable) { "" }

    private fun buildSettings() {
        for (i in 0 until b.segBar.childCount) b.segBar.getChildAt(i).isSelected = i == settingsTab
        b.settingsList.removeAllViews()
        when (settingsTab) {
            0 -> {
                section("拍摄")
                card { c ->
                    toggleRow(c, R.drawable.ic_date, "日期水印", "照片和视频角落印上拍摄日期", params.dateStamp) { params.dateStamp = it; savePrefs() }
                    valueRow(c, R.drawable.ic_border, "参数水印边框", border.label, "照片加上相机参数边框") {
                        border = Border.values()[(border.ordinal + 1) % Border.values().size]; savePrefs(); buildSettings()
                    }
                    valueRow(c, R.drawable.ic_timer, "倒计时", if (timers[timerIdx] == 0) "关" else "${timers[timerIdx]} 秒") {
                        timerIdx = (timerIdx + 1) % timers.size; buildSettings()
                    }
                    toggleRow(c, R.drawable.ic_mirror, "镜像前置摄像头", "前置拍照保存成和预览一样的镜像", mirrorFront) { mirrorFront = it; savePrefs() }
                }
                section("偏好")
                card { c ->
                    toggleRow(c, R.drawable.ic_volume, "音量键快门", "按音量键拍照或开始/停止录像", volShutter) { volShutter = it; savePrefs() }
                    toggleRow(c, R.drawable.ic_camera, "快门闪黑", "按快门时画面闪一下", shutterBlink) { shutterBlink = it; savePrefs() }
                }
            }
            1 -> {
                section("视频格式")
                card { c ->
                    val fmt = (cam.boundFormat ?: cam.videoFormat)?.label ?: "1080p 30"
                    valueRow(c, R.drawable.ic_video, "分辨率与帧率", fmt, if (cam.mode == Mode.VIDEO) null else "切到录像模式后可改") {
                        if (cam.mode == Mode.VIDEO) { cycleFormat(); main.postDelayed({ if (b.settings.visibility == View.VISIBLE) buildSettings() }, 500) } else showToast("先切到录像模式")
                    }
                    toggleRow(c, R.drawable.ic_stab, "防抖", "录像时启用电子/光学防抖", cam.stabRequested) {
                        if (cam.mode == Mode.VIDEO) toggleStab() else { cam.stabRequested = it; savePrefs() }
                    }
                    valueRow(c, R.drawable.ic_timelapse, "延时摄影间隔", "每 " + (if (tlIntervals[tlIdx] < 1f) "0.5" else tlIntervals[tlIdx].toInt().toString()) + " 秒") {
                        if (recorder == null) { tlIdx = if (tlIdx + 1 >= tlIntervals.size) 1 else tlIdx + 1; savePrefs(); buildOptions(); buildSettings() }
                    }
                }
                section("色彩")
                card { c ->
                    toggleRow(c, R.drawable.ic_curve, "Log 模式", if (cam.logHardware) "相机输出平坦曲线，曝光自动降 1.5 档" else "录像用；镜头不支持时用软件模拟", profileLog) {
                        if (recorder == null) {
                            profileLog = it; cam.logRequested = profileLog; cam.applyManual(); applyColor(); savePrefs()
                        }
                    }
                    toggleRow(c, R.drawable.ic_filter, "预览套用转换", "关掉可直接看 Log 原始画面", convertOn) { convertOn = it; applyColor(); savePrefs() }
                    toggleRow(c, R.drawable.ic_drop, "LUT 烧录进视频", "关掉则只录原始画面，方便后期调色", burnIn) {
                        if (recorder == null) { burnIn = it; applyColor(); savePrefs() }
                    }
                }
            }
            2 -> {
                section("构图与曝光辅助")
                card { c ->
                    toggleRow(c, R.drawable.ic_grid, "参考线", "三分法九宫格", gridOn) { gridOn = it; savePrefs() }
                    toggleRow(c, R.drawable.ic_level, "水平仪", null, levelOn) { setLevel(it) }
                    toggleRow(c, R.drawable.ic_hist, "直方图", null, histOn) { histOn = it; savePrefs() }
                    toggleRow(c, R.drawable.ic_peak, "峰值对焦", "手动对焦时标出合焦边缘", peakOn) { peakOn = it; savePrefs() }
                    toggleRow(c, R.drawable.ic_zebra, "斑马纹", "标出过曝区域", zebraOn) { zebraOn = it; savePrefs() }
                }
            }
            else -> {
                section("关于")
                card { c ->
                    valueRow(c, R.drawable.ic_info, "RetroCam", "v" + appVersion(), "复古 DV / CCD / 胶片相机", null)
                    valueRow(c, R.drawable.ic_filter, "导入 LUT", ".cube") { importLut.launch(arrayOf("*/*")) }
                    valueRow(c, R.drawable.ic_save, "项目主页", "GitHub") {
                        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/hq970123/RetroCam"))) } catch (_: Throwable) {}
                    }
                }
            }
        }
        b.settingsScroll.scrollTo(0, 0)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (volShutter && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) &&
            b.editor.visibility != View.VISIBLE && b.settings.visibility != View.VISIBLE) {
            if (event.repeatCount == 0) { closeSheet(); onShutter() }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------- zoom ----------------

    private val zoomViews = ArrayList<Pair<Float, TextView>>()
    private var zoomKey = ""

    private fun zoomLabel(z: Float) = if (z < 9.95f) String.format(java.util.Locale.US, "%.1fx", z) else "${Math.round(z)}x"

    private fun buildZoom() {
        val range = cam.zoomRange() ?: return
        val stops = ArrayList<Float>()
        if (range.first < 0.95f) stops += Math.round(range.first * 10f) / 10f
        stops += 1f
        for (z in floatArrayOf(2f, 5f, 10f)) if (range.second >= z) stops += z
        val key = stops.joinToString()
        if (key == zoomKey) return
        zoomKey = key
        b.zoomStrip.removeAllViews(); zoomViews.clear()
        for (z in stops) {
            val tv = TextView(this).apply {
                gravity = Gravity.CENTER; textSize = 12f; setTextColor(WHITE)
                setBackgroundResource(R.drawable.vf_circ)
                layoutParams = LinearLayout.LayoutParams(dp(40f), dp(40f)).apply { marginStart = dp(5f); marginEnd = dp(5f) }
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    cam.setZoom(if (z == stops.first() && z < 1f) range.first else z)
                    main.postDelayed({ refreshZoom() }, 120)
                }
            }
            zoomViews += Pair(z, tv)
            b.zoomStrip.addView(tv)
        }
        refreshZoom()
    }

    private fun refreshZoom() {
        if (zoomViews.isEmpty()) return
        val cur = cam.zoomRatio()
        val active = zoomViews.lastOrNull { it.first <= cur + 0.05f }?.first ?: zoomViews.first().first
        for ((z, v) in zoomViews) {
            val on = z == active
            v.isSelected = on
            v.text = zoomLabel(if (on) cur else z)
        }
    }

    private fun placeVf() {
        val r = renderer.previewRect
        if (r.width() <= 0f) return
        val lp = b.vfControls.layoutParams
        val w = r.width().toInt()
        if (lp.width != w) { lp.width = w; b.vfControls.layoutParams = lp }
        b.vfControls.x = r.left
        // Sit just below the viewfinder so the zoom row never covers the burned-in date stamp;
        // only slide up over the image when there is no room (e.g. slider open, tall ratios).
        val maxTop = b.bottomPanel.top.toFloat() - dp(4f) - b.vfControls.height
        val below = r.bottom + dp(6f)
        b.vfControls.y = minOf(below, maxTop)
        b.hudCol.y = r.top + dp(14f)
        if (!vfPlaced && b.vfControls.height > 0) { vfPlaced = true; b.vfControls.visibility = View.VISIBLE }
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
        val ic = cam.imageCapture
        if (ic == null && !cam.dual) return
        if (cam.rawActive && ic != null) { takeRaw(ic); return }
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
        if (shutterBlink) {
            b.flashOverlay.alpha = 0.85f
            b.flashOverlay.animate().alpha(0f).setDuration(220).start()
        }
    }

    /** Takes a still and returns it upright and cropped to the photo ratio (unprocessed). Callback on [io]. */
    private fun captureUpright(onResult: (Bitmap?) -> Unit) {
        if (cam.dual) { renderer.grabComposite { bmp -> io.execute { onResult(bmp) } }; return }
        val ic = cam.imageCapture ?: run { onResult(null); return }
        val front = cam.lensFacing == CameraSelector.LENS_FACING_FRONT && mirrorFront
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
                    thumb?.let { setThumb(it) }
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
        if (shutterBlink) {
            b.flashOverlay.alpha = 0.85f
            b.flashOverlay.animate().alpha(0f).setDuration(220).start()
        }
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
        val timelapse = uiMode == UiMode.TIMELAPSE
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
                    thumb?.let { setThumb(it) }
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

    private class ProCell(val p: ProParam?, val box: FrameLayout, val icon: ImageView, val text: TextView)
    private val proCells = ArrayList<ProCell>()

    private fun isAllAuto(): Boolean {
        val m = cam.manual
        return m.iso == null && m.exposureNs == null && m.evIndex == 0 && m.focusDiopters == null &&
            m.awbMode == android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_AUTO
    }

    private fun resetParam(p: ProParam) {
        val m = cam.manual
        when (p) {
            ProParam.ISO -> m.iso = null
            ProParam.SHUTTER -> m.exposureNs = null
            ProParam.EV -> m.evIndex = 0
            ProParam.WB -> m.awbMode = android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_AUTO
            ProParam.FOCUS -> m.focusDiopters = null
        }
        cam.applyManual()
        if (activeParam == p) openSlider(p)
        showToast(proName(p) + " 自动")
        refreshUi()
    }

    private fun proName(p: ProParam) = when (p) { ProParam.ISO -> "ISO"; ProParam.SHUTTER -> "快门"; ProParam.EV -> "曝光补偿"; ProParam.WB -> "白平衡"; ProParam.FOCUS -> "对焦" }

    private fun setupPro() {
        val defs = listOf<Pair<ProParam?, Int>>(
            Pair(ProParam.SHUTTER, R.drawable.ic_aperture), Pair(ProParam.ISO, 0), Pair(ProParam.EV, R.drawable.ic_ev),
            Pair(ProParam.FOCUS, R.drawable.ic_focus), Pair(ProParam.WB, R.drawable.ic_wb), Pair(null, R.drawable.ic_drop),
        )
        b.proRow.removeAllViews()
        for ((p, ic) in defs) {
            val cell = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(0, dp(54f), 1f) }
            val box = FrameLayout(this).apply {
                setBackgroundResource(R.drawable.circ_btn)
                layoutParams = FrameLayout.LayoutParams(dp(50f), dp(50f), Gravity.CENTER)
            }
            val icon = ImageView(this).apply {
                if (ic != 0) setImageResource(ic)
                layoutParams = FrameLayout.LayoutParams(dp(24f), dp(24f), Gravity.CENTER)
            }
            val text = TextView(this).apply {
                gravity = Gravity.CENTER; setTextColor(WHITE); textSize = 12f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            }
            box.addView(icon); box.addView(text); cell.addView(box); b.proRow.addView(cell)
            proCells += ProCell(p, box, icon, text)
            box.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                if (p == null) { openSheet(Sheet.FILTER, LUT_CAT); return@setOnClickListener }
                closeSheet()
                if (activeParam == p) activeParam = null else openSlider(p)
                refreshUi()
            }
            if (p != null) box.setOnLongClickListener { resetParam(p); true }
        }
        b.btnAuto.setOnClickListener {
            if (isAllAuto()) showToast("点下方参数手动调节，长按单项恢复自动")
            else { cam.manual.reset(); cam.applyManual(); activeParam = null; showToast("已恢复全自动"); refreshUi() }
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
        if (proCells.isEmpty()) return
        val m = cam.manual
        val step = cam.caps?.evStep ?: 0f
        for (c in proCells) {
            val (txt, manual) = when (c.p) {
                ProParam.ISO -> Pair(m.iso?.toString() ?: "ISO", m.iso != null)
                ProParam.SHUTTER -> Pair(m.exposureNs?.let { ManualOptions.shutterLabel(it) } ?: "", m.exposureNs != null)
                ProParam.EV -> Pair(if (m.evIndex == 0) "" else String.format(java.util.Locale.US, "%+.1f", m.evIndex * step), m.evIndex != 0)
                ProParam.FOCUS -> Pair(m.focusDiopters?.let { ManualOptions.focusLabel(it) } ?: "", m.focusDiopters != null)
                ProParam.WB -> {
                    val on = m.awbMode != android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_AUTO
                    Pair(if (!on) "" else (cam.caps?.let { cc -> ManualOptions.wb(cc).firstOrNull { it.value == m.awbMode }?.label } ?: "WB"), on)
                }
                null -> Pair("", lutId != null)
            }
            val active = c.p != null && c.p == activeParam
            c.box.isSelected = active
            c.text.text = txt
            c.icon.visibility = if (txt.isEmpty()) View.VISIBLE else View.GONE
            val col = if (active) WHITE else if (manual) ACCENT else WHITE
            c.text.setTextColor(col)
            c.icon.setColorFilter(col)
        }
        val auto = isAllAuto()
        b.btnAuto.text = if (auto) "A" else "M"
        b.btnAuto.isSelected = !auto
    }

    private val uiTicker = object : Runnable {
        override fun run() {
            b.overlay.rect = renderer.previewRect
            placeVf()
            buildZoom()
            refreshZoom()
            updateVidBar()
            main.postDelayed(this, if (recorder != null) 100 else 200)
        }
    }


    // ---------------- Phase 3: colour / LUT ----------------

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
            setTextColor(if (accent) 0xFFF04A2C.toInt() else 0xFFFFFFFF.toInt())
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

    private fun updateHud() {
        val photo = cam.mode == Mode.PHOTO
        val text = when {
            renderer.accumulating -> null // progress updates own it
            photo && creative == Creative.SLOW -> "慢快门 " + secsLabel(slowSecs[slowIdx])
            photo && creative == Creative.BLUR -> "运动模糊 " + secsLabel(blurSecs[blurIdx])
            photo && creative == Creative.LIGHT -> "光绘 " + secsLabel(lightSecs[lightIdx])
            photo && creative == Creative.MULTI -> if (multiFirst != null) "多重曝光 · 拍第二张" else "多重曝光 · 拍第一张"
            photo && creative == Creative.LIVE -> "动态照片"
            uiMode == UiMode.TIMELAPSE && recorder == null -> "延时 每 " + (if (tlIntervals[tlIdx] < 1f) "0.5" else tlIntervals[tlIdx].toInt().toString()) + " 秒一帧"
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
                    buildOptions(); updateHud()
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
                            buildOptions(); updateHud()
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
        b.editIntensity.setOnClickListener { editIntensityIdx = (editIntensityIdx + 1) % intensities.size; applyEditFx(); refreshEditor(); renderEdit() }
        b.editGrain.setOnClickListener { editGrain = !editGrain; applyEditFx(); refreshEditor(); renderEdit() }
        b.editVignette.setOnClickListener { editVignette = !editVignette; applyEditFx(); refreshEditor(); renderEdit() }
        b.editLeak.setOnClickListener { editLeak = !editLeak; applyEditFx(); refreshEditor(); renderEdit() }
        b.editDate.setOnClickListener { editParams.dateStamp = !editParams.dateStamp; refreshEditor(); renderEdit() }
        listOf(b.editTabStyle, b.editTabLut, b.editTabFx, b.editTabBorder).forEachIndexed { i, v -> v.setOnClickListener { editTab = i; refreshEditor() } }
        b.editCancel.setOnClickListener { closeEditor() }
        b.editSave.setOnClickListener { saveEdit() }
    }

    private fun applyEditFx() = fxInto(editParams, editGrain, editVignette, editLeak, intensities[editIntensityIdx])

    private fun buildEditStrips() {
        b.editStyleStrip.removeAllViews()
        for (st in Styles.all) tile(b.editStyleStrip, if (st.id == 0) "原图" else st.name, styleTiles[st.id]!!, editParams.style == st.id) {
            editParams.style = st.id; editParams.stampKind = st.stamp; applyEditFx(); buildEditStrips(); renderEdit()
        }
        b.editLutStrip.removeAllViews()
        tile(b.editLutStrip, "无 LUT", Tile(0xFF4A4A4A.toInt(), 0xFF262626.toInt(), "⊘"), lutId == null) { selectLut(null); buildEditStrips(); renderEdit() }
        for (e in lutLib.entries()) tile(b.editLutStrip, e.name, lutTile(e.name), lutId == e.id) { selectLut(e.id); buildEditStrips() }
        b.editBorderStrip.removeAllViews()
        for (bd in Border.values()) chip(b.editBorderStrip, bd.label, editBorder == bd) { editBorder = bd; buildEditStrips(); renderEdit() }
    }

    private fun refreshEditor() {
        val tabs = listOf(b.editTabStyle, b.editTabLut, b.editTabFx, b.editTabBorder)
        val panes = listOf<View>(b.editPaneStyle, b.editPaneLut, b.editPaneFx, b.editPaneBorder)
        tabs.forEachIndexed { i, v -> v.isSelected = i == editTab }
        panes.forEachIndexed { i, v -> v.visibility = if (i == editTab) View.VISIBLE else View.GONE }
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
        buildEditStrips()
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
                        thumb?.let { setThumb(it) }
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
        syncBack()
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
        lastBorder = Border.values().getOrElse(prefs.getInt("lastBorder", 1)) { Border.WHITE }.let { if (it == Border.NONE) Border.WHITE else it }
        tlIdx = prefs.getInt("tl2", 2).coerceIn(1, tlIntervals.size - 1)
        mirrorFront = prefs.getBoolean("mirror", true)
        volShutter = prefs.getBoolean("volShutter", true)
        shutterBlink = prefs.getBoolean("blink", true)
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
            .putInt("lastBorder", lastBorder.ordinal)
            .putInt("tl2", tlIdx)
            .putInt("uimode", uiMode.ordinal)
            .putBoolean("mirror", mirrorFront)
            .putBoolean("volShutter", volShutter)
            .putBoolean("blink", shutterBlink)
            .putInt("slow", slowIdx)
            .putInt("blur", blurIdx)
            .putInt("light", lightIdx)
            .putString("vfmt", cam.videoFormat?.let { "${it.w},${it.h},${it.fps}" })
            .apply()
    }
}