package com.makemore.agentfrontend.networking

import com.makemore.agentfrontend.models.AttachmentUploadError
import com.makemore.agentfrontend.models.UploadedAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private val attachmentJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** True when the host gave an attachments URL, so files upload before sending and the message carries `attachment_ids`. */
val APIClient.attachmentsEnabled: Boolean
    get() = config.apiPaths.attachmentsEnabled

private fun APIClient.attachmentsRequest(suffix: String, token: String?): Request.Builder {
    val path = config.apiPaths.attachmentsUrl(suffix) ?: throw AttachmentUploadError.NotConfigured
    val url = if (path.startsWith("https://", true) || path.startsWith("http://", true)) path else "${config.backendUrl}$path"
    val builder = Request.Builder().url(url)
    authHeaders(token).forEach { (k, v) -> builder.header(k, v) }
    builder.header("X-Api-Format", "camel")
    return builder
}

/** Reports 0..1 as the body is written to the socket. */
private class ProgressRequestBody(
    private val data: ByteArray,
    private val type: MediaType?,
    private val onProgress: ((Double) -> Unit)?,
) : RequestBody() {
    override fun contentType() = type
    override fun contentLength() = data.size.toLong()
    override fun writeTo(sink: BufferedSink) {
        val chunk = 64 * 1024
        var sent = 0
        while (sent < data.size) {
            val n = minOf(chunk, data.size - sent)
            sink.write(data, sent, n)
            sent += n
            onProgress?.invoke(if (data.isEmpty()) 1.0 else minOf(1.0, sent.toDouble() / data.size))
        }
        sink.flush()
    }
}

/**
 * Upload one file (multipart, field `file`) with this client's auth and return its id.
 * `progress` is called with 0..1 as the body is sent. Photos should already be prepared
 * (see the photo helpers in [com.makemore.agentfrontend.models.AttachmentLimits]).
 * Throws [AttachmentUploadError] for every failure the person should be told about.
 */
suspend fun APIClient.uploadAttachment(
    data: ByteArray,
    filename: String,
    contentType: String,
    progress: ((Double) -> Unit)? = null,
): UploadedAttachment = withContext(Dispatchers.IO) {
    if (!attachmentsEnabled) throw AttachmentUploadError.NotConfigured
    validateTransport()
    val epoch = sessionGeneration
    val token = getOrCreateSession()
    check(sessionGeneration == epoch) { "Account changed" }

    // Quotes and line breaks can't appear in the part header's file name.
    val safeName = filename.replace("\"", "_").replace("\r", "").replace("\n", "").ifEmpty { "file" }
    val type = contentType.toMediaTypeOrNull() ?: "application/octet-stream".toMediaTypeOrNull()
    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("file", safeName, ProgressRequestBody(data, type, progress))
        .build()
    val request = attachmentsRequest("", token).post(body).build()
    // A 25 MB body on a slow link outlives the chat client's 90 s call timeout.
    val client = httpClient.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(120, TimeUnit.SECONDS).build()

    val response = try {
        client.newCall(request).await()
    } catch (e: IOException) {
        throw AttachmentUploadError.Network
    }
    response.use {
        check(sessionGeneration == epoch) { "Account changed" }
        val text = try { it.body?.string().orEmpty() } catch (e: IOException) { "" }
        val obj = runCatching { JSONObject(text) }.getOrNull()
        val code = obj?.optString("code")
        when (it.code) {
            in 200..299 -> {
                val uploaded = runCatching { attachmentJson.decodeFromString<UploadedAttachment>(text) }.getOrNull()
                if (uploaded == null || uploaded.id.isEmpty()) throw AttachmentUploadError.Http(it.code)
                progress?.invoke(1.0)
                uploaded
            }
            413 -> throw AttachmentUploadError.TooLarge
            503 -> throw if (code == "attachments_unavailable") AttachmentUploadError.Unavailable
                else AttachmentUploadError.Http(503)
            400 -> {
                val file = obj?.opt("file")
                val reason = when (file) {
                    is org.json.JSONArray -> file.optString(0).takeIf { s -> s.isNotEmpty() }
                    is String -> file.takeIf { s -> s.isNotEmpty() }
                    else -> null
                } ?: obj?.optString("detail")?.takeIf { s -> s.isNotEmpty() }
                throw AttachmentUploadError.Rejected(reason ?: "That file couldn't be read.")
            }
            401, 403, 404 -> throw AttachmentUploadError.NotAllowed
            else -> throw if (code == "attachments_unavailable") AttachmentUploadError.Unavailable
                else AttachmentUploadError.Http(it.code)
        }
    }
}

/** The thread's attachments, to turn a message's `attachment_ids` into names. Empty when none is configured. */
suspend fun APIClient.listAttachments(): List<UploadedAttachment> = withContext(Dispatchers.IO) {
    if (!attachmentsEnabled) return@withContext emptyList()
    validateTransport()
    val epoch = sessionGeneration
    val token = getOrCreateSession()
    check(sessionGeneration == epoch) { "Account changed" }
    httpClient.newCall(attachmentsRequest("", token).get().build()).await().use { response ->
        check(sessionGeneration == epoch) { "Account changed" }
        when (response.code) {
            401, 403 -> throw Unauthorized
            404 -> throw NotFound
            200 -> {
                val text = response.body?.string() ?: throw InvalidResponse
                val array = runCatching { JSONObject(text).optJSONArray("results") }.getOrNull()
                if (array != null) attachmentJson.decodeFromString<List<UploadedAttachment>>(array.toString())
                else attachmentJson.decodeFromString<List<UploadedAttachment>>(text)
            }
            else -> throw HttpError(response.code)
        }
    }
}

/** Read one attachment's bytes from `{attachments}{id}/` with this client's auth. */
suspend fun APIClient.fetchAttachment(id: String): ByteArray = withContext(Dispatchers.IO) {
    validateTransport()
    val epoch = sessionGeneration
    val token = getOrCreateSession()
    check(sessionGeneration == epoch) { "Account changed" }
    val encoded = java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")
    val request = try { attachmentsRequest("$encoded/", token).get().build() } catch (e: AttachmentUploadError) { throw NotFound }
    httpClient.newCall(request).await().use { response ->
        check(sessionGeneration == epoch) { "Account changed" }
        when (response.code) {
            401, 403 -> throw Unauthorized
            404 -> throw NotFound
            200 -> response.body?.bytes() ?: throw InvalidResponse
            else -> throw HttpError(response.code)
        }
    }
}
