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
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/** Outcome of reading a picked file: the file to stage, or why it was refused. */
sealed class PickedFile {
    class Ready(val input: AttachmentInput) : PickedFile()
    class Refused(val message: String) : PickedFile()
}

/**
 * Copies picked content:// files into the app's cache, off the main thread, so no file is ever
 * held whole in memory: the upload streams from the copy, which is deleted after the upload
 * and when the file is removed. The count is checked before anything is copied; a file over
 * the limit is refused from its declared size, or while copying when that is unknown. Photos
 * are prepared like the web client's `preparePhoto` (JPEG, longest side <= 4096, EXIF
 * orientation applied) when too big or not png/jpeg/gif/webp, decoding from the file with a
 * sample size so the decode is bounded.
 */
object AttachmentReader {

    fun stagingDir(context: Context): File = File(context.cacheDir, "upload-staging").apply { mkdirs() }

    /** Removes every staged copy (sign-out; app start, when no upload can still be running). */
    fun clearStaging(context: Context) {
        File(context.cacheDir, "upload-staging").deleteRecursively()
    }

    /**
     * Copies up to [remaining] of the picked files; returns the files to stage and the last
     * refusal worth telling the person.
     */
    suspend fun readAll(context: Context, uris: List<Uri>, remaining: Int): Pair<List<AttachmentInput>, String?> {
        var notice: String? = null
        val take = uris.take(maxOf(0, remaining))
        if (take.size < uris.size) notice = "You can attach up to ${AttachmentLimits.MAX_PER_MESSAGE} files to one message."
        val ready = mutableListOf<AttachmentInput>()
        for (uri in take) {
            when (val r = read(context, uri)) {
                is PickedFile.Ready -> ready += r.input
                is PickedFile.Refused -> notice = r.message
            }
        }
        return ready to notice
    }

    /** Camera preview bitmap: written as a JPEG in the staging dir, never held as bytes. */
    suspend fun fromBitmap(context: Context, bitmap: Bitmap, name: String): PickedFile = withContext(Dispatchers.IO) {
        val out = File.createTempFile("camera-", ".jpg", stagingDir(context))
        val ok = FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (ok) PickedFile.Ready(AttachmentInput(name, "image/jpeg", out))
        else PickedFile.Refused("$name couldn't be read.").also { out.delete() }
    }

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
        // Photos may shrink on re-encode, so only the file limit applies to the raw copy.
        if ((declaredSize ?: 0L) > AttachmentLimits.MAX_FILE_BYTES) return@withContext PickedFile.Refused(tooLarge(name))
        val copy = File.createTempFile("pick-", ".bin", stagingDir(context))
        try {
            val within = resolver.openInputStream(uri)?.use { copyCapped(it, copy, AttachmentLimits.MAX_FILE_BYTES) }
                ?: return@withContext PickedFile.Refused("$name couldn't be read.").also { copy.delete() }
            if (!within) return@withContext PickedFile.Refused(tooLarge(name)).also { copy.delete() }
            prepare(context, name, mime, copy)
        } catch (e: Exception) {
            copy.delete()
            PickedFile.Refused("$name couldn't be read.")
        } catch (e: OutOfMemoryError) {
            copy.delete()
            PickedFile.Refused("$name is too large to prepare on this phone.")
        }
    }

    /** Takes ownership of [copy]: it is returned inside the result, or deleted. */
    internal fun prepare(context: Context, name: String, mime: String, copy: File): PickedFile {
        if (!AttachmentLimits.isImage(mime)) return PickedFile.Ready(AttachmentInput(name, mime, copy))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(copy.path, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        // Not decodable here (e.g. svg): leave it as it is.
        if (w <= 0 || h <= 0 || !AttachmentLimits.photoNeedsReencode(mime, copy.length(), w, h)) {
            return PickedFile.Ready(AttachmentInput(name, mime, copy))
        }
        val out = File.createTempFile("photo-", ".jpg", stagingDir(context))
        val ok = try { reencode(copy, out, w, h) } catch (e: Throwable) { false }
        copy.delete()
        if (!ok) {
            out.delete()
            return PickedFile.Refused("$name couldn't be read as a photo.")
        }
        if (out.length() > AttachmentLimits.MAX_PHOTO_BYTES) {
            out.delete()
            return PickedFile.Refused(tooLarge(name))
        }
        return PickedFile.Ready(AttachmentInput(name.substringBeforeLast('.', name) + ".jpg", "image/jpeg", out))
    }

    private fun reencode(src: File, dst: File, w: Int, h: Int): Boolean {
        val (tw, th) = AttachmentLimits.scaledPhotoSize(w, h)
        var sample = 1
        while (w / (sample * 2) >= tw && h / (sample * 2) >= th) sample *= 2
        val decoded = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
        val matrix = Matrix()
        orientationMatrix(src, matrix)
        val scale = minOf(1f, AttachmentLimits.MAX_PHOTO_SIDE.toFloat() / maxOf(decoded.width, decoded.height))
        if (scale < 1f) matrix.postScale(scale, scale)
        val final = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        var quality = 90
        while (true) {
            val done = FileOutputStream(dst).use { final.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            if (!done) return false
            if (dst.length() <= AttachmentLimits.MAX_PHOTO_BYTES || quality <= 50) return true
            quality -= 10
        }
    }

    private fun orientationMatrix(file: File, m: Matrix) {
        val orientation = runCatching {
            ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
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

    /** Copies at most [limit] bytes; false when the source is longer. */
    private fun copyCapped(input: InputStream, dst: File, limit: Long): Boolean {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        FileOutputStream(dst).use { out ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > limit) return false
                out.write(buf, 0, n)
            }
        }
        return true
    }

    private fun tooLarge(name: String) =
        "$name is too large (up to ${AttachmentLimits.MAX_FILE_BYTES / (1024 * 1024)} MB a file)."
}
