package com.makemore.agentfrontend.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import com.makemore.agentfrontend.models.AttachmentLimits
import com.makemore.agentfrontend.viewmodels.AttachmentInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Outcome of reading a picked file: the file to stage, or why it was refused. */
sealed class PickedFile {
    class Ready(val input: AttachmentInput) : PickedFile()
    class Refused(val message: String) : PickedFile()
}

/**
 * Reads picked content:// files for upload, always off the main thread. A file over the limit is
 * refused from its declared size before any bytes are read (and while reading when the size is
 * unknown), so a huge file never fills memory. Photos are prepared like the web client's
 * `preparePhoto`: re-encoded as JPEG, longest side <= 4096, EXIF orientation applied, when
 * too big or not png/jpeg/gif/webp.
 */
object AttachmentReader {

    suspend fun read(context: Context, uri: Uri): PickedFile = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        var displayName: String? = null
        var declaredSize: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0 && !c.isNull(n)) displayName = c.getString(n)
                    if (s >= 0 && !c.isNull(s)) declaredSize = c.getLong(s)
                }
            }
        }
        val name = displayName ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
        val limit = AttachmentLimits.MAX_FILE_BYTES
        // Photos may shrink on re-encode, so only the file limit applies to the raw bytes.
        if ((declaredSize ?: 0L) > limit) return@withContext PickedFile.Refused(tooLarge(name))
        val bytes = runCatching {
            resolver.openInputStream(uri)?.use { readCapped(it, limit) }
        }.getOrNull() ?: return@withContext PickedFile.Refused("$name couldn't be read.")
        if (bytes.size.toLong() > limit) return@withContext PickedFile.Refused(tooLarge(name))
        prepare(name, mime, bytes)
    }

    /** Camera / bitmap source: always a fresh JPEG. */
    suspend fun fromBitmap(bitmap: Bitmap, name: String): PickedFile = withContext(Dispatchers.IO) {
        val out = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) PickedFile.Refused("$name couldn't be read.")
        else PickedFile.Ready(AttachmentInput(name, "image/jpeg", out.toByteArray()))
    }

    internal fun prepare(name: String, mime: String, bytes: ByteArray): PickedFile {
        if (!AttachmentLimits.isImage(mime)) return PickedFile.Ready(AttachmentInput(name, mime, bytes))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        // Not decodable here (e.g. svg): leave it as it is.
        if (w <= 0 || h <= 0) return PickedFile.Ready(AttachmentInput(name, mime, bytes))
        if (!AttachmentLimits.photoNeedsReencode(mime, bytes.size.toLong(), w, h)) {
            return PickedFile.Ready(AttachmentInput(name, mime, bytes))
        }
        val reencoded = reencode(bytes, w, h) ?: return PickedFile.Refused("$name couldn't be read as a photo.")
        if (reencoded.size > AttachmentLimits.MAX_PHOTO_BYTES) return PickedFile.Refused(tooLarge(name))
        val jpegName = name.substringBeforeLast('.', name) + ".jpg"
        return PickedFile.Ready(AttachmentInput(jpegName, "image/jpeg", reencoded))
    }

    private fun reencode(bytes: ByteArray, w: Int, h: Int): ByteArray? {
        val (tw, th) = AttachmentLimits.scaledPhotoSize(w, h)
        var sample = 1
        while (w / (sample * 2) >= tw && h / (sample * 2) >= th) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val matrix = Matrix()
        orientationMatrix(bytes, matrix)
        val scale = minOf(1f, AttachmentLimits.MAX_PHOTO_SIDE.toFloat() / maxOf(decoded.width, decoded.height))
        if (scale < 1f) matrix.postScale(scale, scale)
        val final = if (matrix.isIdentity) decoded
        else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        var quality = 90
        while (true) {
            val out = ByteArrayOutputStream()
            if (!final.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
            if (out.size() <= AttachmentLimits.MAX_PHOTO_BYTES || quality <= 50) return out.toByteArray()
            quality -= 10
        }
    }

    private fun orientationMatrix(bytes: ByteArray, m: Matrix) {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
    }

    /** Reads at most [limit] + 1 bytes, so an unknown-size file over the limit stops early. */
    private fun readCapped(input: java.io.InputStream, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
            if (total > limit) break
        }
        return out.toByteArray()
    }

    private fun tooLarge(name: String) =
        "$name is too large (up to ${AttachmentLimits.MAX_FILE_BYTES / (1024 * 1024)} MB a file)."
}
