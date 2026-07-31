package com.selenite.scanner.link

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.selenite.scanner.util.AndroidContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

internal const val MIROR_LINK_PHOTO_CACHE_DIR = "miror_link_photos"

/**
 * Downscales on the way out, caches on the way in.
 *
 * The outgoing image is deliberately re-encoded rather than sent as-is: a scan straight
 * from the camera can be several megabytes, and the receiver only ever displays it in a
 * card-detail view, so the full-resolution original buys nothing and costs the whole
 * point of fetching photos one at a time.
 */
internal class AndroidMirorLinkPhotoCodec(context: Context) : MirorLinkPhotoCodec {

    private val appContext = context.applicationContext

    private val cacheDir: File
        get() = File(appContext.cacheDir, MIROR_LINK_PHOTO_CACHE_DIR)
            .also { if (!it.exists()) it.mkdirs() }

    override suspend fun readDownscaled(path: String, maxBytes: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            val file = File(path)
            if (!file.isFile || file.length() <= 0) return@withContext null
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

                val longestEdge = maxOf(bounds.outWidth, bounds.outHeight)
                var sample = 1
                while (longestEdge / sample > TARGET_LONGEST_EDGE * 2) sample *= 2

                val bitmap = BitmapFactory.decodeFile(
                    path,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                ) ?: return@withContext null

                val scaled = scaleToLongestEdge(bitmap, TARGET_LONGEST_EDGE)
                if (scaled !== bitmap) bitmap.recycle()

                // Step quality down until it fits the frame budget. A card photo reaches
                // the target in one or two passes; the loop is a guarantee, not the norm.
                var quality = INITIAL_QUALITY
                var encoded: ByteArray
                do {
                    val stream = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                    encoded = stream.toByteArray()
                    quality -= QUALITY_STEP
                } while (encoded.size > maxBytes && quality >= MIN_QUALITY)
                scaled.recycle()

                if (encoded.size > maxBytes) null else encoded
            } catch (_: Throwable) {
                // Including OutOfMemoryError: a photo that cannot be prepared simply is not
                // sent, and the viewer keeps showing the catalog image.
                // Do not put the local photo path from decoder exceptions in release logs.
                Log.w(TAG, "Could not prepare a photo for Link")
                null
            }
        }

    private fun scaleToLongestEdge(source: Bitmap, longestEdge: Int): Bitmap {
        val current = maxOf(source.width, source.height)
        if (current <= longestEdge) return source
        val ratio = longestEdge.toFloat() / current
        val width = (source.width * ratio).toInt().coerceAtLeast(1)
        val height = (source.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    override suspend fun writeSessionPhoto(cardId: String, bytes: ByteArray): String? =
        withContext(Dispatchers.IO) {
            try {
                if (!MirorLinkImageGate.looksLikeJpeg(bytes)) {
                    Log.w(TAG, "Refusing a received photo that is not a JPEG")
                    return@withContext null
                }
                // Bounds only -- inJustDecodeBounds allocates no pixel buffer. A peer
                // declaring an enormous image would otherwise drive a multi-gigabyte
                // allocation the moment the viewer rendered it.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (!MirorLinkImageGate.hasPlausibleDimensions(bounds.outWidth, bounds.outHeight)) {
                    Log.w(
                        TAG,
                        "Refusing a received photo of ${bounds.outWidth}x${bounds.outHeight}",
                    )
                    return@withContext null
                }
                // The name is derived from a hash, never from peer-supplied text, so a
                // hostile card id cannot steer the write anywhere.
                val safeName = MirorSha256.hash(cardId.encodeToByteArray())
                    .toHexLowercase()
                    .take(32)
                val target = File(cacheDir, "$safeName.jpg")
                target.writeBytes(bytes)
                target.absolutePath
            } catch (_: Exception) {
                Log.w(TAG, "Could not cache a received Link photo")
                null
            }
        }

    override fun clearSessionPhotos() {
        runCatching { cacheDir.deleteRecursively() }
            .onFailure { Log.w(TAG, "Could not clear Link photo cache") }
    }

    companion object {
        private const val TAG = "MirorLinkPhoto"
        private const val TARGET_LONGEST_EDGE = 900
        private const val INITIAL_QUALITY = 85
        private const val QUALITY_STEP = 10
        private const val MIN_QUALITY = 35
    }
}

internal actual fun createMirorLinkPhotoCodec(): MirorLinkPhotoCodec? =
    AndroidContext?.applicationContext?.let { AndroidMirorLinkPhotoCodec(it) }
