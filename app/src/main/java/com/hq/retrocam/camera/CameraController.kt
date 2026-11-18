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
    /** True when the bound ImageCapture writes DNG. */
    var rawActive = false
        private set
    var caps: CameraCaps? = null
        private set
    val manual = ManualSettings()

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
        val photo = mode == Mode.PHOTO
        val ratio = if (photo) AspectRatio.RATIO_4_3 else AspectRatio.RATIO_16_9
        val previewTarget = if (photo) Size(1440, 1080) else Size(1920, 1080)
        val previewSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy(ratio, AspectRatioStrategy.FALLBACK_RULE_AUTO))
            .setResolutionStrategy(ResolutionStrategy(previewTarget, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()
        val previewBuilder = Preview.Builder()
            .setResolutionSelector(previewSelector)
            .setTargetRotation(Surface.ROTATION_0)
        Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(captureCallback)
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

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val info = try { p.getCameraInfo(selector) } catch (t: Throwable) { null }
        caps = info?.let { readCaps(it) }

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
        return CameraCaps(
            isoRange = if (manualSensor) iso else null,
            exposureRange = if (manualSensor) exp else null,
            minFocusDiopters = if (manualSensor) minFocus else 0f,
            evRange = ev.exposureCompensationRange,
            evStep = ev.exposureCompensationStep.toFloat(),
            awbModes = awb,
            rawSupported = raw,
        )
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
            b.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(exp, 33_333_333L))
        }
        if (m.awbMode != CaptureRequest.CONTROL_AWB_MODE_AUTO) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, m.awbMode)
        }
        m.focusDiopters?.let {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, it)
        }
        try {
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build())
            val r = caps?.evRange
            val ev = if (r != null) m.evIndex.coerceIn(r.lower, r.upper) else 0
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

    fun zoomRatio(): Float = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f

    fun setTorch(on: Boolean) { camera?.cameraControl?.enableTorch(on) }
}
