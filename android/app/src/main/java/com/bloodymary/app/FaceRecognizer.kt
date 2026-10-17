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
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Finds faces with ML Kit and turns each one into a 512-number FaceNet
 * embedding. Two embeddings of the same person point in a similar direction,
 * so [similarity] near 1 means "same person". Blocking: call off the main thread.
 */
class FaceRecognizer(context: Context) : Closeable {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.1f)
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

    override fun close() {
        detector.close()
        interpreter?.close()
    }

    companion object {
        private const val TAG = "FaceRecognizer"
        const val MODEL_ASSET = "facenet_512.tflite"
        private const val INPUT_SIZE = 160
        private const val EMBEDDING_SIZE = 512

        /** Cosine similarity of two unit-length embeddings: 1 = identical. */
        fun similarity(a: FloatArray, b: FloatArray): Float {
            var dot = 0f
            for (i in a.indices) dot += a[i] * b[i]
            return dot
        }
    }
}
