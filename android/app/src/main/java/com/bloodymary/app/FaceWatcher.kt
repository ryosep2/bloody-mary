package com.bloodymary.app

import android.graphics.Bitmap
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

    // Live enrollment: collects embeddings until enough are captured
    private var captureTarget = 0
    private val captured = mutableListOf<FloatArray>()
    private var captureDone: ((List<FloatArray>) -> Unit)? = null

    fun start(useFrontCamera: Boolean) {
        val providerFuture = ProcessCameraProvider.getInstance(activity)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(executor, ::analyze)

            val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            try {
                provider.unbindAll()
                provider.bindToLifecycle(activity, selector, preview, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "Camera failed to start", e)
            }
        }, activity.mainExecutor)
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

            val embedding = recognizer.embedLargestFace(upright(image)) ?: return

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

            val (name, score) = guests.match(embedding)
            main.post { onSighting(Sighting(name, score)) }
        } catch (e: Exception) {
            Log.w(TAG, "Face analysis failed", e)
        } finally {
            image.close()
        }
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
    }
}
