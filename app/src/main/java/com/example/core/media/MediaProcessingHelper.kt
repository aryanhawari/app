package com.example.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

data class TimestampedVideoFrame(
    val timestampMs: Long,
    val label: String,
    val bitmap: Bitmap
)

data class ValidatedVideoPayload(
    val durationMs: Long,
    val sizeBytes: Long,
    val mimeType: String,
    val frames: List<TimestampedVideoFrame>
)

object MediaProcessingHelper {

    private const val MAX_IMAGE_DIM = 1280
    private const val MAX_VIDEO_BYTES = 50L * 1024L * 1024L // 50 MB guard
    private const val MAX_VIDEO_DURATION_MS = 5L * 60L * 1000L // 5 minutes guard

    suspend fun decodeDownscaledBitmap(
        context: Context,
        uri: Uri,
        maxDimension: Int = MAX_IMAGE_DIM
    ): Result<Bitmap> = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            } ?: return@withContext Result.failure(IllegalArgumentException("Cannot open selected image."))

            val rawW = bounds.outWidth
            val rawH = bounds.outHeight
            if (rawW <= 0 || rawH <= 0) {
                return@withContext Result.failure(IllegalArgumentException("Invalid or corrupted image file."))
            }

            var inSampleSize = 1
            while (max(rawW / inSampleSize, rawH / inSampleSize) > maxDimension * 2) {
                inSampleSize *= 2
            }

            val opts = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
            val decoded = resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            } ?: return@withContext Result.failure(IllegalArgumentException("Unable to decode image."))

            Result.success(scaleBitmapMaxDim(decoded, maxDimension))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun scaleBitmapMaxDim(source: Bitmap, maxDimension: Int = MAX_IMAGE_DIM): Bitmap {
        val w = source.width
        val h = source.height
        val longest = max(w, h)
        if (longest <= maxDimension) return source
        val scale = maxDimension.toFloat() / longest.toFloat()
        val targetW = (w * scale).roundToInt().coerceAtLeast(1)
        val targetH = (h * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetW, targetH, true)
    }

    fun rotateBitmap90(source: Bitmap): Bitmap {
        val matrix = Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    fun cropNormalized(
        source: Bitmap,
        leftFrac: Float,
        topFrac: Float,
        rightFrac: Float,
        bottomFrac: Float
    ): Bitmap {
        val l = (leftFrac.coerceIn(0f, 0.9f) * source.width).roundToInt()
        val t = (topFrac.coerceIn(0f, 0.9f) * source.height).roundToInt()
        val r = (rightFrac.coerceIn(leftFrac + 0.05f, 1f) * source.width).roundToInt()
        val b = (bottomFrac.coerceIn(topFrac + 0.05f, 1f) * source.height).roundToInt()
        val w = (r - l).coerceAtLeast(16)
        val h = (b - t).coerceAtLeast(16)
        return Bitmap.createBitmap(source, l, t, w, h)
    }

    fun bitmapToJpegBytes(bitmap: Bitmap, quality: Int = 85): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }

    suspend fun extractVideoFrames(
        context: Context,
        uri: Uri,
        maxFrames: Int = 5
    ): Result<ValidatedVideoPayload> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri).orEmpty()
        if (mime.isNotBlank() && !mime.startsWith("video/")) {
            return@withContext Result.failure(IllegalArgumentException("Please select a valid video file."))
        }

        val sizeBytes = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0) cursor.getLong(idx) else 0L
            } else 0L
        } ?: 0L

        if (sizeBytes > MAX_VIDEO_BYTES) {
            return@withContext Result.failure(
                IllegalArgumentException("Video exceeds 50 MB limit. Please select a shorter clip.")
            )
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 0L
            if (durationMs <= 0L) {
                return@withContext Result.failure(IllegalArgumentException("Could not read video duration."))
            }
            if (durationMs > MAX_VIDEO_DURATION_MS) {
                return@withContext Result.failure(
                    IllegalArgumentException("Video is longer than 5 minutes. Please pick a shorter segment.")
                )
            }

            val count = maxFrames.coerceIn(1, 6)
            val frames = mutableListOf<TimestampedVideoFrame>()
            for (i in 0 until count) {
                val fraction = if (count == 1) 0.5f else (i.toFloat() / (count - 1).toFloat())
                val timeMs = (durationMs * fraction * 0.9f).toLong().coerceAtLeast(0L)
                val rawFrame = retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (rawFrame != null) {
                    val scaled = scaleBitmapMaxDim(rawFrame, 768)
                    val secs = timeMs / 1000
                    val mm = secs / 60
                    val ss = secs % 60
                    frames += TimestampedVideoFrame(
                        timestampMs = timeMs,
                        label = String.format("%d:%02d", mm, ss),
                        bitmap = scaled
                    )
                }
            }

            if (frames.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("No frames could be extracted from this video."))
            }

            Result.success(
                ValidatedVideoPayload(
                    durationMs = durationMs,
                    sizeBytes = sizeBytes,
                    mimeType = mime.ifBlank { "video/mp4" },
                    frames = frames
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }
}
