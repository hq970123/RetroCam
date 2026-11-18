package com.hq.retrocam.camera

import android.content.Context
import android.util.Log
import android.util.Size
import android.view.Surface
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

class CameraController(private val context: Context, private val renderer: CameraRenderer) {
    private val tag = "RetroCam.Cam"
    private var provider: ProcessCameraProvider? = null
    var camera: Camera? = null
        private set
    var imageCapture: ImageCapture? = null
        private set
    var lensFacing = CameraSelector.LENS_FACING_BACK
    var mode = Mode.PHOTO
    /** Buffer size of the current preview stream (sensor orientation, landscape). */
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

    fun bind(owner: LifecycleOwner, flashMode: Int, onBound: () -> Unit = {}) {
        val p = provider ?: return
        val photo = mode == Mode.PHOTO
        val ratio = if (photo) AspectRatio.RATIO_4_3 else AspectRatio.RATIO_16_9
        val previewTarget = if (photo) Size(1440, 1080) else Size(1920, 1080)
        val previewSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy(ratio, AspectRatioStrategy.FALLBACK_RULE_AUTO))
            .setResolutionStrategy(ResolutionStrategy(previewTarget, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()
        val preview = Preview.Builder()
            .setResolutionSelector(previewSelector)
            .setTargetRotation(Surface.ROTATION_0)
            .build()
        preview.setSurfaceProvider(renderer.executor) { request ->
            val res = request.resolution
            streamSize = res
            val st = renderer.surfaceTexture
            st.setDefaultBufferSize(res.width, res.height)
            // Phone sensors are mounted landscape; the SurfaceTexture transform rotates to portrait.
            if (res.width >= res.height) renderer.setContentSize(res.height, res.width) else renderer.setContentSize(res.width, res.height)
            val surface = Surface(st)
            request.provideSurface(surface, renderer.executor) { surface.release() }
        }

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val useCases = mutableListOf<androidx.camera.core.UseCase>(preview)
        imageCapture = null
        if (photo) {
            val capSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                .build()
            val ic = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(capSelector)
                .setFlashMode(flashMode)
                .setJpegQuality(95)
                .build()
            imageCapture = ic
            useCases += ic
        }
        try {
            p.unbindAll()
            camera = p.bindToLifecycle(owner, selector, *useCases.toTypedArray())
            onBound()
        } catch (t: Throwable) {
            Log.e(tag, "bind failed", t)
            if (photo && useCases.size > 1) {
                // retry with a smaller capture size
                try {
                    p.unbindAll()
                    val ic = ImageCapture.Builder().setFlashMode(flashMode).build()
                    imageCapture = ic
                    camera = p.bindToLifecycle(owner, selector, preview, ic)
                    onBound()
                } catch (t2: Throwable) { Log.e(tag, "bind retry failed", t2) }
            }
        }
    }

    fun unbind() { provider?.unbindAll() }

    /**
     * Focus/meter at a point given in upright content coords (0..1, y down).
     */
    fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        // Convert upright portrait coords to sensor (buffer) coords.
        val sensorRot = cam.cameraInfo.sensorRotationDegrees
        val front = lensFacing == CameraSelector.LENS_FACING_FRONT
        var ux = if (front) 1f - x else x
        val uy = y
        val (bx, by) = when (sensorRot) {
            90 -> Pair(uy, 1f - ux)
            180 -> Pair(1f - ux, 1f - uy)
            270 -> Pair(1f - uy, ux)
            else -> Pair(ux, uy)
        }
        val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
        val point = factory.createPoint(bx, by)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
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
