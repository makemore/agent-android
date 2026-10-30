package com.makemore.agentfrontend.models

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * A file uploaded to the host's attachments URL (`APIPaths.attachments`).
 * Mirrors the iOS `UploadedAttachment`.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UploadedAttachment(
    // POST answers `id`; the list answers `attachment_id`.
    @JsonNames("attachment_id") val id: String,
    val name: String = "Attachment",
    @JsonNames("content_type") val contentType: String = "application/octet-stream",
    val size: Long = 0,
)

/** Limits the server enforces; the client checks first so nothing big is read or sent for nothing. */
object AttachmentLimits {
    /** Most attachments one message may carry. */
    const val MAX_PER_MESSAGE = 20
    /** Largest file, in bytes. */
    const val MAX_FILE_BYTES = 25L * 1024 * 1024
    /** Largest photo, in bytes (after re-encoding). */
    const val MAX_PHOTO_BYTES = 20L * 1024 * 1024
    /** Longest side of a re-encoded photo, in pixels. */
    const val MAX_PHOTO_SIDE = 4096
    /** Uploads running at once. */
    const val MAX_IN_FLIGHT = 3

    val PHOTO_PASSTHROUGH_TYPES = setOf("image/png", "image/jpeg", "image/jpg", "image/gif", "image/webp")

    fun isImage(contentType: String): Boolean = contentType.lowercase().startsWith("image/")

    /**
     * True when a photo must be re-encoded as JPEG before upload: it is larger than the photo
     * limit, taller/wider than [MAX_PHOTO_SIDE], or not a png/jpeg/gif/webp. Mirrors the web
     * client's `preparePhoto`.
     */
    fun photoNeedsReencode(contentType: String, bytes: Long, width: Int, height: Int): Boolean =
        contentType.lowercase() !in PHOTO_PASSTHROUGH_TYPES ||
            bytes > MAX_PHOTO_BYTES ||
            maxOf(width, height) > MAX_PHOTO_SIDE

    /** Size to scale a photo to so its longest side fits [MAX_PHOTO_SIDE]. */
    fun scaledPhotoSize(width: Int, height: Int): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= MAX_PHOTO_SIDE || longest <= 0) return width to height
        val scale = MAX_PHOTO_SIDE.toDouble() / longest
        return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
    }
}

/** Why a file could not be uploaded, worded for the person. Mirrors the iOS `AttachmentUploadError`. */
sealed class AttachmentUploadError(message: String) : Exception(message) {
    /** 413 `too_large`. */
    object TooLarge : AttachmentUploadError("This file is too large.")
    /** 503 `attachments_unavailable`: this host keeps no attachments. Hide the attach button. */
    object Unavailable : AttachmentUploadError("Files can't be added here.")
    /** 400: the server's reason. */
    class Rejected(reason: String) : AttachmentUploadError(reason)
    /** 401/403/404: not allowed to add files to this conversation. */
    object NotAllowed : AttachmentUploadError("You can't add files to this conversation.")
    /** No connection, or the request did not complete. */
    object Network : AttachmentUploadError("Upload failed. Check the connection and try again.")
    class Http(val status: Int) : AttachmentUploadError("Upload failed. Try again.")
    /** No attachments URL is configured. */
    object NotConfigured : AttachmentUploadError("Files can't be added here.")
}
