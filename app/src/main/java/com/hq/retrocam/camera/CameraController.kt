package com.hq.retrocam.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.hq.retrocam.gl.CameraRenderer
import java.util.concurrent.TimeUnit

enum class Mode { PHOTO, VIDEO }

/** A recording format: stream size (landscape) and frame rate. */
data class VideoFormat(val w: Int, val h: Int, val fps: Int, val fpsRange: Range<Int>) {
    val label: String get() = (if (h >= 2160) "4K" else "${h}p") + " $fps"
}

enum class Stab { OFF, PREVIEW, VIDEO_EIS, OIS_ONLY }

/** Manual overrides; null = auto. */
class ManualSettings {
    var iso: Int? = null
    var exposureNs: Long? = null
    var awbMode: Int = CaptureRequest.CONTROL_AWB_MODE_AUTO
    var focusDiopters: Float? = null
    var evIndex: Int = 0
    fun reset() { iso = null; exposureNs = null; awbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO; focusDiopters = null; evIndex = 0 }
}

class CameraCaps(
    val isoRange: Range<Int>?,
    val exposureRange: Range<Long>?,
    val minFocusDiopters: Float,
    val evRange: Range<Int>,
    val evStep: Float,
    val awbModes: IntArray,
    val rawSupported: Boolean,
    val tonemapCurve: Boolean,
    val maxCurvePoints: Int,
    val videoFormats: List<VideoFormat>,
    val previewStab: Boolean,
    val eis: Boolean,
    val ois: Boolean,
    /** 35mm-equivalent focal length, mm (0 if unknown). */
    val equivFocal: Int = 0,
    val aperture: Float = 0f,
)

class CameraController(private val context: Context, private val renderer: CameraRenderer) {
    private val tag = "RetroCam.Cam"
    private var provider: ProcessCameraProvider? = null
    var camera: Camera? = null
        private set
    var imageCapture: ImageCapture? = null
        private set
    var lensFacing = CameraSelector.LENS_FACING_BACK
    var mode = Mode.PHOTO
    var rawRequested = false
    /** Larger photo preview stream (used by slow shutter / light painting, which build the image from preview frames). */
    var hiResPreview = false
    /** True when the bound ImageCapture writes DNG. */
    var rawActive = false
        private set
    var caps: CameraCaps? = null
        private set
    val manual = ManualSettings()

    /** Requested video format (null = 1080p30 default). */
    var videoFormat: VideoFormat? = null
    var stabRequested = true
    /** Log profile requested (video only). */
    var logRequested = false
    /** Stabilisation actually applied at the last bind. */
    var stabActive = Stab.OFF
        private set
    /** True when the camera's tone curve is replaced by our log curve. */
    val logHardware: Boolean get() = mode == Mode.VIDEO && logRequested && caps?.tonemapCurve == true
    /** EV the log mode pulls exposure down by (actual, after rounding to the camera's step). */
    @Volatile var logEvOffset = 0f
        private set
    /** Frame-rate / size of the stream actually granted. */
    var boundFormat: VideoFormat? = null
        private set

    /** Latest values reported by the camera (auto or manual). */
    @Volatile var liveIso = 0
    @Volatile var liveExposureNs = 0L
    @Volatile var liveFocus = 0f

    var streamSize: Size = Size(1440, 1080)
        private set

    fun init(onReady: () -> Unit) {
        val f = ProcessCameraProvider.getInstance(context)
        f.addListener({
            provider = f.get()
            onReady()
        }, ContextCompat.getMainExecutor(context))
    }

    /** Front + back at once (picture in picture). */
    var dual = false
    /** Called when dual was requested but the phone can't run two cameras together. */
    var onDualUnsupported: (() -> Unit)? = null

    fun dualSupported(): Boolean = try {
        provider?.availableConcurrentCameraInfos?.any { l ->
            l.any { it.lensFacing == CameraSelector.LENS_FACING_BACK } && l.any { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
        } == true
    } catch (_: Throwable) { false }

    private fun bindDual(owner: LifecycleOwner, onBound: () -> Unit): Boolean {
        val p = provider ?: return false
        val pair = try {
            p.availableConcurrentCameraInfos.firstOrNull { l ->
                l.any { it.lensFacing == CameraSelector.LENS_FACING_BACK } && l.any { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
            }
        } catch (t: Throwable) { Log.w(tag, "concurrent infos", t); null } ?: return false
        val mainInfo = pair.firstOrNull { it.lensFacing == lensFacing } ?: return false
        val pipInfo = pair.firstOrNull { it.lensFacing != lensFacing } ?: return false
        caps = readCaps(mainInfo)
        val photo = mode == Mode.PHOTO
        val ratio = if (photo) AspectRatio.RATIO_4_3 else AspectRatio.RATIO_16_9
        val mainSel = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy(ratio, AspectRatioStrategy.FALLBACK_RULE_AUTO))
            .setResolutionStrategy(ResolutionStrategy(if (photo) Size(1440, 1080) else Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .build()
        val mainBuilder = Preview.Builder().setResolutionSelector(mainSel).setTargetRotation(Surface.ROTATION_0)
        Camera2Interop.Extender(mainBuilder).setSessionCaptureCallback(captureCallback)
        val main = mainBuilder.build()
        main.setSurfaceProvider(renderer.executor) { request ->
            val res = request.resolution
            streamSize = res
            val st = renderer.surfaceTexture
            st.setDefaultBufferSize(res.width, res.height)
            if (res.width >= res.height) renderer.setContentSize(res.height, res.width) else renderer.setContentSize(res.width, res.height)
            val surface = Surface(st)
            request.provideSurface(surface, renderer.executor) { surface.release() }
        }
        val pipSel = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()
        val pip = Preview.Builder().setResolutionSelector(pipSel).setTargetRotation(Surface.ROTATION_0).build()
        pip.setSurfaceProvider(renderer.executor) { request ->
            val res = request.resolution
            val st = renderer.pipSurfaceTexture
            st.setDefaultBufferSize(res.width, res.height)
            if (res.width >= res.height) { renderer.pipContentW = res.height; renderer.pipContentH = res.width }
            else { renderer.pipContentW = res.width; renderer.pipContentH = res.height }
            val surface = Surface(st)
            request.provideSurface(surface, renderer.executor) { surface.release() }
        }
        return try {
            p.unbindAll()
            val c1 = androidx.camera.core.ConcurrentCamera.SingleCameraConfig(mainInfo.cameraSelector,
                androidx.camera.core.UseCaseGroup.Builder().addUseCase(main).build(), owner)
            val c2 = androidx.camera.core.ConcurrentCamera.SingleCameraConfig(pipInfo.cameraSelector,
                androidx.camera.core.UseCaseGroup.Builder().addUseCase(pip).build(), owner)
            val cc = p.bindToLifecycle(listOf(c1, c2))
            camera = cc.cameras.firstOrNull()
            imageCapture = null
            rawActive = false
            boundFormat = if (photo) null else VideoFormat(1920, 1080, 30, Range(30, 30))
            stabActive = Stab.OFF
            renderer.pipEnabled = true
            applyManual()
            onBound()
            true
        } catch (t: Throwable) {
            Log.e(tag, "dual bind failed", t)
            renderer.pipEnabled = false
            false
        }
    }

    fun hasFront(): Boolean = provider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) == true

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)?.let { liveIso = it }
            result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)?.let { liveExposureNs = it }
            result.get(android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE)?.let { liveFocus = it }
        }
    }

    fun bind(owner: LifecycleOwner, flashMode: Int, onBound: () -> Unit = {}) {
        val p = provider ?: return
        if (dual) {
            if (bindDual(owner, onBound)) return
            dual = false
            onDualUnsupported?.invoke()
        }
        renderer.pipEnabled = false
        val photo = mode == Mode.PHOTO
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val info = try { p.getCameraInfo(selector) } catch (t: Throwable) { null }
        caps = info?.let { readCaps(it) }
        val c = caps
        val fmt = if (photo) null else (videoFormat?.let { v -> c?.videoFormats?.firstOrNull { it.w == v.w && it.h == v.h && it.fps == v.fps } }
            ?: c?.videoFormats?.firstOrNull { it.h == 1080 && it.fps == 30 })
        val ratio = if (photo) AspectRatio.RATIO_4_3 else AspectRatio.RATIO_16_9
        val previewTarget = if (photo) (if (hiResPreview) Size(2560, 1920) else Size(1440, 1080)) else Size(fmt?.w ?: 1920, fmt?.h ?: 1080)
        val previewSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy(ratio, AspectRatioStrategy.FALLBACK_RULE_AUTO))
            .setResolutionStrategy(ResolutionStrategy(previewTarget,
                if (photo && !hiResPreview) ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER else ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .build()
        val previewBuilder = Preview.Builder()
            .setResolutionSelector(previewSelector)
            .setTargetRotation(Surface.ROTATION_0)
        val ext = Camera2Interop.Extender(previewBuilder)
        ext.setSessionCaptureCallback(captureCallback)
        stabActive = Stab.OFF
        if (fmt != null) previewBuilder.setTargetFrameRate(fmt.fpsRange)
        if (!photo && stabRequested && c != null) {
            when {
                c.previewStab -> { previewBuilder.setPreviewStabilizationEnabled(true); stabActive = Stab.PREVIEW }
                c.eis -> {
                    ext.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                    stabActive = Stab.VIDEO_EIS
                }
                c.ois -> stabActive = Stab.OIS_ONLY
            }
        }
        if (c?.ois == true && (photo || stabRequested)) {
            ext.setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }
        boundFormat = fmt
        val preview = previewBuilder.build()
        preview.setSurfaceProvider(renderer.executor) { request ->
            val res = request.resolution
            streamSize = res
            val st = renderer.surfaceTexture
            st.setDefaultBufferSize(res.width, res.height)
            if (res.width >= res.height) renderer.setContentSize(res.height, res.width) else renderer.setContentSize(res.width, res.height)
            val surface = Surface(st)
            request.provideSurface(surface, renderer.executor) { surface.release() }
        }

        val useCases = mutableListOf<androidx.camera.core.UseCase>(preview)
        imageCapture = null
        rawActive = false
        if (photo) {
            val wantRaw = rawRequested && caps?.rawSupported == true
            val ic = if (wantRaw) {
                ImageCapture.Builder()
                    .setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setFlashMode(flashMode)
                    .build()
            } else {
                val capSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build()
                ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setResolutionSelector(capSelector)
                    .setFlashMode(flashMode)
                    .setJpegQuality(95)
                    .build()
            }
            rawActive = wantRaw
            imageCapture = ic
            useCases += ic
        }
        try {
            p.unbindAll()
            camera = p.bindToLifecycle(owner, selector, *useCases.toTypedArray())
            applyManual()
            onBound()
        } catch (t: Throwable) {
            Log.e(tag, "bind failed", t)
            if (!photo && (fmt != null && (fmt.fps != 30 || fmt.h != 1080) || stabActive != Stab.OFF)) {
                // fall back to plain 1080p30 without stabilisation
                onBindFallback?.invoke()
                videoFormat = c?.videoFormats?.firstOrNull { it.h == 1080 && it.fps == 30 }
                stabRequested = false
                bind(owner, flashMode, onBound)
                return
            }
            if (photo) {
                try {
                    p.unbindAll()
                    val ic = ImageCapture.Builder().setFlashMode(flashMode).build()
                    imageCapture = ic
                    rawActive = false
                    camera = p.bindToLifecycle(owner, selector, preview, ic)
                    applyManual()
                    onBound()
                } catch (t2: Throwable) { Log.e(tag, "bind retry failed", t2) }
            }
        }
    }

    /** Called when the requested video format could not be bound and 1080p30 is used instead. */
    var onBindFallback: (() -> Unit)? = null

    private fun videoFormats(c2: Camera2CameraInfo): List<VideoFormat> {
        val map = c2.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        val sizes = map.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.toList() ?: emptyList()
        val ranges = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: emptyList()
        val out = ArrayList<VideoFormat>()
        for ((w, h) in listOf(1920 to 1080, 3840 to 2160)) {
            val size = sizes.firstOrNull { it.width == w && it.height == h } ?: continue
            val minDur = try { map.getOutputMinFrameDuration(android.graphics.SurfaceTexture::class.java, size) } catch (_: Throwable) { 0L }
            for (fps in listOf(30, 60)) {
                if (minDur > 0 && minDur > 1_000_000_000L / fps + 500_000L) continue
                val range = ranges.firstOrNull { it.lower == fps && it.upper == fps }
                    ?: ranges.filter { it.upper == fps }.maxByOrNull { it.lower }
                    ?: if (fps == 30) Range(30, 30) else continue
                if (com.hq.retrocam.record.Encoders.choose(w, h, fps) == null) continue
                out += VideoFormat(w, h, fps, range)
            }
        }
        if (out.none { it.h == 1080 && it.fps == 30 }) out.add(0, VideoFormat(1920, 1080, 30, Range(30, 30)))
        return out
    }

    private fun readCaps(info: androidx.camera.core.CameraInfo): CameraCaps {
        val c2 = Camera2CameraInfo.from(info)
        val iso = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exp = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val hwCaps = c2.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val manualSensor = hwCaps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        val minFocus = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val awb = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf(CaptureRequest.CONTROL_AWB_MODE_AUTO)
        val ev = info.exposureState
        val raw = try {
            ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats.contains(ImageCapture.OUTPUT_FORMAT_RAW)
        } catch (t: Throwable) { false }
        val tmModes = c2.getCameraCharacteristic(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: IntArray(0)
        val maxPts = c2.getCameraCharacteristic(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 0
        val stabModes = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0)
        val oisModes = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0)
        val previewStab = try { Preview.getPreviewCapabilities(info).isStabilizationSupported } catch (_: Throwable) { false }
        return CameraCaps(
            tonemapCurve = tmModes.contains(CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE) && maxPts >= 16,
            maxCurvePoints = maxPts,
            videoFormats = try { videoFormats(c2) } catch (t: Throwable) { Log.w(tag, "formats", t); listOf(VideoFormat(1920, 1080, 30, Range(30, 30))) },
            previewStab = previewStab,
            eis = stabModes.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON),
            ois = oisModes.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON),
            isoRange = if (manualSensor) iso else null,
            exposureRange = if (manualSensor) exp else null,
            minFocusDiopters = if (manualSensor) minFocus else 0f,
            evRange = ev.exposureCompensationRange,
            evStep = ev.exposureCompensationStep.toFloat(),
            awbModes = awb,
            rawSupported = raw,
            equivFocal = try { equivFocal(c2) } catch (_: Throwable) { 0 },
            aperture = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull() ?: 0f,
        )
    }

    private fun equivFocal(c2: Camera2CameraInfo): Int {
        val f = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return 0
        val sz = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return 0
        val diag = Math.hypot(sz.width.toDouble(), sz.height.toDouble())
        if (diag <= 0.0) return 0
        return Math.round(f * 43.27 / diag).toInt()
    }

    /** Pushes the manual overrides to the running camera. */
    fun applyManual() {
        val cam = camera ?: return
        val m = manual
        val b = CaptureRequestOptions.Builder()
        if (m.iso != null || m.exposureNs != null) {
            val iso = m.iso ?: liveIso.takeIf { it > 0 } ?: 100
            val exp = m.exposureNs ?: liveExposureNs.takeIf { it > 0 } ?: 10_000_000L
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
            val frame = 1_000_000_000L / (boundFormat?.fps ?: 30)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(exp, frame))
        }
        if (m.awbMode != CaptureRequest.CONTROL_AWB_MODE_AUTO) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, m.awbMode)
        }
        m.focusDiopters?.let {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, it)
        }
        val autoExposure = m.iso == null && m.exposureNs == null
        var logIdx = 0
        if (logHardware) {
            val pts = minOf(caps?.maxCurvePoints ?: 32, 64).coerceAtLeast(16)
            val curve = FloatArray(pts * 2)
            val k = 64.0
            for (i in 0 until pts) {
                val x = (i.toDouble() / (pts - 1)).let { it * it }
                curve[i * 2] = x.toFloat()
                curve[i * 2 + 1] = (Math.log(x * k + 1.0) / Math.log(k + 1.0)).toFloat()
            }
            b.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
            b.setCaptureRequestOption(CaptureRequest.TONEMAP_CURVE, android.hardware.camera2.params.TonemapCurve(curve, curve, curve))
            val step = caps?.evStep ?: 0f
            if (autoExposure && step > 0f) logIdx = -Math.round(1.5f / step)
        }
        try {
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build())
            val r = caps?.evRange
            val ev = if (r != null) (m.evIndex + logIdx).coerceIn(r.lower, r.upper) else 0
            logEvOffset = if (logHardware && autoExposure) (ev - m.evIndex.coerceIn(r?.lower ?: 0, r?.upper ?: 0)) * (caps?.evStep ?: 0f) else 0f
            cam.cameraControl.setExposureCompensationIndex(ev)
        } catch (t: Throwable) {
            Log.w(tag, "applyManual", t)
        }
    }

    fun unbind() { provider?.unbindAll() }

    /** Focus/meter at a point in upright full-content coords (0..1, y down). */
    fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        if (manual.focusDiopters != null) return
        val sensorRot = cam.cameraInfo.sensorRotationDegrees
        val front = lensFacing == CameraSelector.LENS_FACING_FRONT
        val ux = if (front) 1f - x else x
        val uy = y
        val (bx, by) = when (sensorRot) {
            90 -> Pair(uy, 1f - ux)
            180 -> Pair(1f - ux, 1f - uy)
            270 -> Pair(1f - uy, ux)
            else -> Pair(ux, uy)
        }
        val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
        val point = factory.createPoint(bx, by)
        val flags = if (manual.iso != null || manual.exposureNs != null) FocusMeteringAction.FLAG_AF
        else FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        val action = FocusMeteringAction.Builder(point, flags)
            .setAutoCancelDuration(4, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    fun setZoom(ratio: Float) {
        val cam = camera ?: return
        val z = cam.cameraInfo.zoomState.value ?: return
        cam.cameraControl.setZoomRatio(ratio.coerceIn(z.minZoomRatio, z.maxZoomRatio))
    }

    fun zoomRange(): Pair<Float, Float>? = camera?.cameraInfo?.zoomState?.value?.let { Pair(it.minZoomRatio, it.maxZoomRatio) }

    fun zoomRatio(): Float = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f

    fun setTorch(on: Boolean) { camera?.cameraControl?.enableTorch(on) }
}
