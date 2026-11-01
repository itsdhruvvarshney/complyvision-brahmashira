package com.example.complyvision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.abs

/** Cheap per-frame measurements made on the phone: is it dark, is the picture moving */
data class FrameMetrics(val brightness: Int = 128, val motion: Float = 0f, val at: Long = 0L)

/**
 * Back camera for Accessibility Mode: preview, a stream of analysis frames (local light / motion checks and
 * the small upright frames sent for live guidance) and full-resolution captures for reading the label.
 */
class AssistCamera(private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor()
    private val selector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
        .build()
    private val preview = Preview.Builder().setResolutionSelector(selector).build()
    private val capture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setResolutionSelector(selector)
        .build()
    private val analysis = ImageAnalysis.Builder()
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                // 1080p: a live frame must be sharp enough to read a label when the still photo can't be used
                .setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build()
        )
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        .build()

    private var camera: Camera? = null
    private var provider: ProcessCameraProvider? = null
    @Volatile private var frameRequest: Pair<Int, CompletableDeferred<Bitmap?>>? = null
    private var lastGrid: IntArray? = null
    private var torchChangedAt = 0L
    private var frameCount = 0

    private val _metrics = MutableStateFlow(FrameMetrics())
    val metrics: StateFlow<FrameMetrics> = _metrics
    private val _torch = MutableStateFlow(false)
    val torch: StateFlow<Boolean> = _torch
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    val hasFlash: Boolean get() = camera?.cameraInfo?.hasFlashUnit() == true

    init {
        analysis.setAnalyzer(executor, ::analyze)
    }

    fun bind(owner: LifecycleOwner, view: PreviewView) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                preview.setSurfaceProvider(view.surfaceProvider)
                p.unbindAll()
                camera = try {
                    p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
                } catch (e: Exception) {
                    // Some cameras can't run three streams: frames for guidance matter more than full-size photos
                    Log.w("AssistCamera", "Falling back to preview + analysis", e)
                    p.unbindAll()
                    p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
                camera?.cameraControl?.enableTorch(_torch.value)
                _ready.value = true
            } catch (e: Exception) {
                Log.e("AssistCamera", "Camera unavailable", e)
                _ready.value = false
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun unbind() {
        provider?.unbindAll()
        camera = null
        _ready.value = false
    }

    fun setTorch(on: Boolean) {
        if ((on && hasFlash) != _torch.value) torchChangedAt = System.currentTimeMillis()
        _torch.value = on && hasFlash
        camera?.cameraControl?.enableTorch(_torch.value)
    }

    /** Autofocus and exposure on the centre, where the guidance put the object */
    fun focusCentre() {
        val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
        val action = FocusMeteringAction.Builder(factory.createPoint(0.5f, 0.5f))
            .setAutoCancelDuration(3, TimeUnit.SECONDS).build()
        try { camera?.cameraControl?.startFocusAndMetering(action) } catch (_: Exception) {}
    }

    /** The next camera frame, upright, scaled so its longer side is [maxSide] (null if the camera is not running) */
    suspend fun frame(maxSide: Int = 512): Bitmap? {
        if (!_ready.value) return null
        val request = CompletableDeferred<Bitmap?>()
        frameRequest = maxSide to request
        return withTimeoutOrNull(2_500) { request.await() }.also { frameRequest = null }
    }

    /**
     * A full-resolution photo. Falls back to a live frame when the still camera is unavailable, or when the still
     * came out much darker than the live view (some phones switch the torch off, or skip exposure, for a still).
     */
    suspend fun photo(maxSide: Int = 1600): Bitmap? {
        // Exposure needs a moment to settle after the torch is switched
        val sinceTorch = System.currentTimeMillis() - torchChangedAt
        if (sinceTorch < 1_200) kotlinx.coroutines.delay(1_200 - sinceTorch)
        val live = _metrics.value.brightness
        val still = if (camera != null && _ready.value) withTimeoutOrNull(6_000) { takeStill() } else null
        if (still != null) {
            val b = brightness(still)
            if (live < 45 || b >= live * 0.6f) return scale(still, maxSide)
            Log.w("AssistCamera", "Still photo too dark ($b vs live $live); using the live frame")
        }
        return frame(maxSide)
    }

    /** Mean luma of a bitmap, sampled on a 32 x 24 grid */
    private fun brightness(bmp: Bitmap): Int {
        var sum = 0L
        for (gy in 0 until 24) for (gx in 0 until 32) {
            val p = bmp.getPixel((gx * bmp.width / 32) + bmp.width / 64, (gy * bmp.height / 24) + bmp.height / 48)
            sum += (((p shr 16) and 0xff) * 299 + ((p shr 8) and 0xff) * 587 + (p and 0xff) * 114) / 1000
        }
        return (sum / (32 * 24)).toInt()
    }

    private suspend fun takeStill(): Bitmap? = suspendCancellableCoroutine { cont ->
        try {
            capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bmp = try { upright(image) } catch (e: Exception) { null } finally { image.close() }
                    if (cont.isActive) cont.resume(bmp)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.w("AssistCamera", "Capture failed", exception)
                    if (cont.isActive) cont.resume(null)
                }
            })
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }

    private fun analyze(image: ImageProxy) {
        try {
            frameRequest?.let { (side, deferred) ->
                if (!deferred.isCompleted) {
                    frameRequest = null
                    deferred.complete(try { scale(upright(image), side) } catch (e: Exception) { null })
                }
            }
            if (frameCount++ % 3 == 0) measure(image)
        } finally {
            image.close()
        }
    }

    /** Brightness and motion from a coarse 32 x 24 grid of the RGBA frame */
    private fun measure(image: ImageProxy) {
        val plane = image.planes[0]
        val buf = plane.buffer
        val w = image.width
        val h = image.height
        val gw = 32
        val gh = 24
        val grid = IntArray(gw * gh)
        var sum = 0L
        for (gy in 0 until gh) {
            val y = (gy * h / gh) + h / gh / 2
            for (gx in 0 until gw) {
                val x = (gx * w / gw) + w / gw / 2
                val i = y * plane.rowStride + x * plane.pixelStride
                if (i + 2 >= buf.limit()) continue
                val r = buf.get(i).toInt() and 0xff
                val g = buf.get(i + 1).toInt() and 0xff
                val b = buf.get(i + 2).toInt() and 0xff
                val luma = (r * 299 + g * 587 + b * 114) / 1000
                grid[gy * gw + gx] = luma
                sum += luma
            }
        }
        val prev = lastGrid
        val motion = if (prev == null) 0f else grid.indices.sumOf { abs(grid[it] - prev[it]) }.toFloat() / grid.size
        lastGrid = grid
        _metrics.value = FrameMetrics((sum / grid.size).toInt(), motion, System.currentTimeMillis())
    }

    private fun upright(image: ImageProxy): Bitmap {
        val bmp = if (image.format == ImageFormat.JPEG) {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } else {
            image.toBitmap()
        }
        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return bmp
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun scale(bmp: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= maxSide) return bmp
        val s = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
    }

    fun release() {
        unbind()
        executor.shutdown()
    }

    companion object {
        suspend fun jpeg(bmp: Bitmap, quality: Int): ByteArray = withContext(Dispatchers.Default) {
            ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
                out.toByteArray()
            }
        }
    }
}
