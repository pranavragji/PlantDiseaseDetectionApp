package com.pranavragji.leafcheck

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import org.json.JSONArray
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

const val INPUT = 224
const val NO_LEAF = "none__no_leaf"

// A label is "crop__condition", e.g. "tomato__early_blight".
fun cropOf(label: String) = label.substringBefore("__")
fun conditionOf(label: String) = label.substringAfter("__")

data class Guess(val label: String, val confidence: Float)

// Sorts the model's probabilities. If the user said which plant it is, only that
// plant's classes (plus "no leaf") compete, and their scores are rescaled to sum to 1.
fun rank(probs: FloatArray, labels: List<String>, crop: String?): List<Guess> {
    val keep = labels.indices.filter { crop == null || cropOf(labels[it]) == crop || labels[it] == NO_LEAF }
    val total = keep.sumOf { probs[it].toDouble() }.toFloat().takeIf { it > 0f } ?: 1f
    return keep.map { Guess(labels[it], probs[it] / total) }.sortedByDescending { it.confidence }
}

class Classifier(context: Context) {
    val labels: List<String> = JSONArray(context.assets.open("labels.json").reader().readText())
        .let { a -> List(a.length()) { a.getString(it) } }
    private val interpreter = context.assets.openFd("model.tflite").use { fd ->
        val map = FileInputStream(fd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        Interpreter(map, Interpreter.Options().setNumThreads(4))
    }

    // The model takes raw 0..255 RGB and does its own normalisation.
    fun classify(square: Bitmap, crop: String?): List<Guess> {
        val img = Bitmap.createScaledBitmap(square, INPUT, INPUT, true)
        val pixels = IntArray(INPUT * INPUT).also { img.getPixels(it, 0, INPUT, 0, 0, INPUT, INPUT) }
        val input = ByteBuffer.allocateDirect(INPUT * INPUT * 3 * 4).order(ByteOrder.nativeOrder())
        for (p in pixels) {
            input.putFloat((p shr 16 and 0xFF).toFloat())
            input.putFloat((p shr 8 and 0xFF).toFloat())
            input.putFloat((p and 0xFF).toFloat())
        }
        val out = Array(1) { FloatArray(labels.size) }
        synchronized(this) { interpreter.run(input, out) }
        return rank(out[0], labels, crop)
    }

    companion object {
        @Volatile private var instance: Classifier? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: Classifier(context.applicationContext).also { instance = it }
        }
    }
}

// Opens a photo the right way up, center-cropped to a square of at most `size` px.
fun loadSquare(context: Context, uri: Uri, size: Int): Bitmap? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    // bounds-only decode always returns null; the size lands in `bounds`
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
    val raw = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    val degrees = runCatching {
        resolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }
    }.getOrNull() ?: 0f
    val side = minOf(raw.width, raw.height)
    val scale = minOf(1f, size.toFloat() / side)
    val m = Matrix().apply { postRotate(degrees); postScale(scale, scale) }
    return Bitmap.createBitmap(raw, (raw.width - side) / 2, (raw.height - side) / 2, side, side, m, true)
}
