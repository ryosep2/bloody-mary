package com.bloodymary.app

import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import kotlin.math.sqrt
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import java.util.concurrent.Executors

/**
 * Watches the camera and reports who it sees, a few times per second.
 * Also captures faces for live guest enrollment.
 */
class FaceWatcher(
    private val activity: ComponentActivity,
    private val previewView: PreviewView,
    private val recognizer: FaceRecognizer,
    private val guests: GuestStore,
    private val onSighting: (Sighting) -> Unit,
) {
    /** One look at the camera. [name] is null for an unknown face; no face means no sighting. */
    data class Sighting(val name: String?, val score: Float)

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var lastAnalysis = 0L
    private val recent = ArrayDeque<Pair<Long, FloatArray>>()  // recent embeddings of the face in view

    // Live enrollment: collects embeddings until enough are captured
    private var captureTarget = 0
    private val captured = mutableListOf<FloatArray>()
    private var captureDone: ((List<FloatArray>) -> Unit)? = null

    /** Starts (or restarts) the camera. Face analysis always runs; the on-screen preview only if [showPreview]. */
    @OptIn(ExperimentalCamera2Interop::class)
    fun start(useFrontCamera: Boolean, showPreview: Boolean) {
        val providerFuture = ProcessCameraProvider.getInstance(activity)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysisBuilder = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                // More pixels per face than the default 640x480, so faces a few steps away still have detail
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                        )
                        .build()
                )
            // Allow the slowest frame rate the camera offers, so each frame can be exposed
            // longer: much brighter, cleaner pictures in a dim room
            slowestFrameRate(provider, selector)?.let {
                Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
            }
            val analysis = analysisBuilder.build()
            analysis.setAnalyzer(executor, ::analyze)

            try {
                provider.unbindAll()
                val camera = if (showPreview) provider.bindToLifecycle(activity, selector, preview, analysis)
                else provider.bindToLifecycle(activity, selector, analysis)
                // Expose brighter than the camera's normal target
                val exposure = camera.cameraInfo.exposureState
                if (exposure.isExposureCompensationSupported) {
                    camera.cameraControl.setExposureCompensationIndex(exposure.exposureCompensationRange.upper / 2)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Camera failed to start", e)
            }
        }, activity.mainExecutor)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun slowestFrameRate(provider: ProcessCameraProvider, selector: CameraSelector): Range<Int>? = try {
        val info = selector.filter(provider.availableCameraInfos).firstOrNull()
        info?.let { Camera2CameraInfo.from(it).getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) }
            ?.minWithOrNull(compareBy<Range<Int>>({ it.lower }, { it.upper }))
    } catch (e: Exception) {
        null
    }

    /** Captures [count] face samples of whoever is in front of the camera, then calls [done] on the main thread. */
    fun captureFaces(count: Int, done: (List<FloatArray>) -> Unit) {
        executor.execute {
            captured.clear()
            captureTarget = count
            captureDone = done
        }
    }

    fun cancelCapture() {
        executor.execute {
            captureTarget = 0
            captureDone = null
        }
    }

    private fun analyze(image: ImageProxy) {
        try {
            val capturing = captureTarget > 0
            val now = SystemClock.elapsedRealtime()
            if (!capturing && now - lastAnalysis < ANALYSIS_INTERVAL_MS) return
            lastAnalysis = now

            val embedding = recognizer.embedLargestFace(recognizer.brightenIfDark(upright(image))) ?: return

            if (capturing) {
                captured.add(embedding)
                if (captured.size >= captureTarget) {
                    captureTarget = 0
                    val faces = captured.toList()
                    val done = captureDone
                    captureDone = null
                    main.post { done?.invoke(faces) }
                }
                return
            }

            // One dim, grainy frame is unreliable, so match the average of the
            // last few seconds of the same face instead
            while (recent.isNotEmpty() && now - recent.first().first > TRACK_WINDOW_MS) recent.removeFirst()
            if (recent.isNotEmpty() && FaceRecognizer.similarity(embedding, averageRecent()) < SAME_PERSON) recent.clear()
            recent.addLast(now to embedding)
            val (name, score) = guests.match(averageRecent())
            main.post { onSighting(Sighting(name, score)) }
        } catch (e: Exception) {
            Log.w(TAG, "Face analysis failed", e)
        } finally {
            image.close()
        }
    }

    private fun averageRecent(): FloatArray {
        val avg = FloatArray(recent.first().second.size)
        for ((_, e) in recent) for (i in avg.indices) avg[i] += e[i]
        val norm = sqrt(avg.sumOf { (it * it).toDouble() }).toFloat()
        for (i in avg.indices) avg[i] /= norm
        return avg
    }

    private fun upright(image: ImageProxy): Bitmap {
        val bitmap = image.toBitmap()
        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    companion object {
        private const val TAG = "FaceWatcher"
        private const val ANALYSIS_INTERVAL_MS = 300L
        private const val TRACK_WINDOW_MS = 3_000L  // average the face over this long
        private const val SAME_PERSON = 0.4f        // below this, a new frame is someone else
    }
}
