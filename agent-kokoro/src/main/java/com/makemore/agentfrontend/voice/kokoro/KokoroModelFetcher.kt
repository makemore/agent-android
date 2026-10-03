package com.makemore.agentfrontend.voice.kokoro

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Opens the model archive. The only network access agent-kokoro performs:
 * a plain GET of the configured URL — no assistant text, no identifiers.
 */
fun interface KokoroModelFetcher {
    /** Blocking; called on an IO thread. Throws [KokoroDownloadException] or [IOException]. */
    fun open(url: String): KokoroModelResponse
}

class KokoroModelResponse(
    val body: InputStream,
    /** Byte length if the server sent one. */
    val contentLength: Long?,
    private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() {
        runCatching { body.close() }
        onClose()
    }
}

/** A download failure with a value-free [reason] code (see [KokoroModelState.Failed]). */
class KokoroDownloadException(val reason: String, cause: Throwable? = null) : IOException(reason, cause)

/** Default fetcher: HTTPS only, follows redirects (GitHub release assets redirect to a CDN). */
class HttpKokoroModelFetcher(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : KokoroModelFetcher {
    override fun open(url: String): KokoroModelResponse {
        if (!url.startsWith("https://", ignoreCase = true)) {
            throw KokoroDownloadException("insecure_url")
        }
        var current = URL(url)
        repeat(MAX_REDIRECTS) {
            val conn = current.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/octet-stream")
            val code = conn.responseCode
            when (code) {
                in 200..299 -> {
                    val length = conn.contentLengthLong.takeIf { it > 0 }
                    return KokoroModelResponse(conn.inputStream, length) { conn.disconnect() }
                }
                301, 302, 303, 307, 308 -> {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    val next = location?.let { URL(current, it) }
                        ?: throw KokoroDownloadException("http_$code")
                    // Never downgrade to cleartext on a redirect.
                    if (!next.protocol.equals("https", ignoreCase = true)) {
                        throw KokoroDownloadException("insecure_url")
                    }
                    current = next
                }
                else -> {
                    conn.disconnect()
                    throw KokoroDownloadException("http_$code")
                }
            }
        }
        throw KokoroDownloadException("too_many_redirects")
    }

    private companion object {
        const val MAX_REDIRECTS = 5
    }
}
