package com.javon.healthdash.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class StoredPhoto(val path: String, val jpeg: ByteArray, val takenAtMs: Long?)

/** Meal photos are kept locally, compressed (SPEC §7). Long edge ≤ 1568 px, JPEG 85. */
class Photos(private val context: Context) {
    private val dir = File(context.filesDir, "photos").apply { mkdirs() }

    /** A temp file the camera app writes into. */
    fun newCaptureFile(): File = File(File(context.cacheDir, "capture").apply { mkdirs() }, "capture-${UUID.randomUUID()}.jpg")

    suspend fun store(uri: Uri): StoredPhoto = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val exif = resolver.openInputStream(uri)?.use { ExifInterface(it) }
        val rotation = when (exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val takenAt = (exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif?.getAttribute(ExifInterface.TAG_DATETIME))?.let {
            runCatching { SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(it)?.time }.getOrNull()
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1568) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: error("Could not read photo")
        val scale = minOf(1f, 1568f / maxOf(decoded.width, decoded.height))
        val m = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
        val bmp = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)

        val file = File(dir, "${UUID.randomUUID()}.jpg")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        StoredPhoto(file.absolutePath, file.readBytes(), takenAt)
    }
}
