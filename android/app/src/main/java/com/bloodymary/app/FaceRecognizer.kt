package com.bloodymary.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Finds faces with ML Kit and turns each one into a 512-number FaceNet
 * embedding. Two embeddings of the same person point in a similar direction,
 * so [similarity] near 1 means "same person". Blocking: call off the main thread.
 */
class FaceRecognizer(context: Context) : Closeable {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            // Slower but finds more faces in grainy, dim frames
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setMinFaceSize(0.08f)
            .build()
    )

    private val interpreter: Interpreter? = try {
        val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
        model.rewind()
        Interpreter(model, Interpreter.Options().setNumThreads(4))
    } catch (e: Exception) {
        Log.e(TAG, "Face model assets/$MODEL_ASSET missing or invalid", e)
        null
    }

    val ready: Boolean get() = interpreter != null

    private val input = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3).order(ByteOrder.nativeOrder())
    private val output = Array(1) { FloatArray(EMBEDDING_SIZE) }

    /** Bounding box of the largest face in [bitmap], or null if there is none. */
    fun largestFace(bitmap: Bitmap): Rect? {
        val faces = Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)))
        return faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }?.boundingBox
    }

    /** Embedding of the largest face in [bitmap], or null if there is no face (or no model). */
    fun embedLargestFace(bitmap: Bitmap): FloatArray? {
        val box = largestFace(bitmap) ?: return null
        return embed(bitmap, box)
    }

    @Synchronized
    fun embed(bitmap: Bitmap, box: Rect): FloatArray? {
        val tflite = interpreter ?: return null
        val crop = Rect(box).apply { intersect(0, 0, bitmap.width, bitmap.height) }
        if (crop.width() < 20 || crop.height() < 20) return null

        val face = Bitmap.createScaledBitmap(
            Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width(), crop.height()),
            INPUT_SIZE, INPUT_SIZE, true
        )
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        face.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        // FaceNet expects each image standardized to mean 0, standard deviation 1
        val values = FloatArray(pixels.size * 3)
        for (i in pixels.indices) {
            val p = pixels[i]
            values[3 * i] = ((p shr 16) and 0xFF).toFloat()
            values[3 * i + 1] = ((p shr 8) and 0xFF).toFloat()
            values[3 * i + 2] = (p and 0xFF).toFloat()
        }
        val mean = values.average().toFloat()
        val std = max(sqrt(values.sumOf { ((it - mean) * (it - mean)).toDouble() }.toFloat() / values.size),
            1f / sqrt(values.size.toFloat()))

        input.rewind()
        for (v in values) input.putFloat((v - mean) / std)
        input.rewind()
        tflite.run(input, output)

        // Unit length, so similarity is a plain dot product
        val e = output[0].copyOf()
        val norm = sqrt(e.sumOf { (it * it).toDouble() }).toFloat()
        for (i in e.indices) e[i] /= norm
        return e
    }

    /**
     * In a dim room the camera picture is dark and flat. Brightens it (a gamma
     * curve that lifts shadows most) so faces are easier to find and compare.
     * Normally lit pictures, like imported photos, are returned unchanged.
     */
    fun brightenIfDark(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var sum = 0L
        var n = 0
        for (i in pixels.indices step 16) {
            val p = pixels[i]
            sum += (((p shr 16) and 0xFF) * 3 + ((p shr 8) and 0xFF) * 6 + (p and 0xFF)) / 10
            n++
        }
        val mean = sum.toFloat() / n
        if (mean >= DARK_LEVEL) return bitmap

        // Pick the curve that lifts the average brightness to about mid-grey
        val gamma = (ln(0.45) / ln(maxOf(mean, 4f) / 255.0)).coerceIn(0.35, 1.0)
        val lut = IntArray(256) { (255.0 * (it / 255.0).pow(gamma)).toInt().coerceIn(0, 255) }
        for (i in pixels.indices) {
            val p = pixels[i]
            pixels[i] = (p and 0xFF000000.toInt()) or
                (lut[(p shr 16) and 0xFF] shl 16) or (lut[(p shr 8) and 0xFF] shl 8) or lut[p and 0xFF]
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    override fun close() {
        detector.close()
        interpreter?.close()
    }

    companion object {
        private const val TAG = "FaceRecognizer"
        const val MODEL_ASSET = "facenet_512.tflite"
        private const val INPUT_SIZE = 160
        private const val EMBEDDING_SIZE = 512
        private const val DARK_LEVEL = 100f  // average brightness (0-255) below which a frame is brightened

        /** Cosine similarity of two unit-length embeddings: 1 = identical. */
        fun similarity(a: FloatArray, b: FloatArray): Float {
            var dot = 0f
            for (i in a.indices) dot += a[i] * b[i]
            return dot
        }
    }
}
