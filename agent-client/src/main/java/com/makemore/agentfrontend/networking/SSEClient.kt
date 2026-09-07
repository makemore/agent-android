package com.makemore.agentfrontend.networking

import kotlinx.coroutines.*
import okhttp3.*
import okio.BufferedSource
import java.io.IOException

/**
 * Reason the SSE stream was torn down. Mirrors the `DisconnectReason`
 * enum exposed by `iOS` and the `DisconnectReason` union exported by
 * `@makemore/agent-client` on web. Hosts can use this to distinguish
 * a clean user-driven cancel from a network failure or a lifecycle
 * teardown (e.g. Compose removal, VM cleared, OS backgrounding).
 */
enum class DisconnectReason {
    /** `cancelRun()` or an explicit client-side close. */
    EXPLICIT,
    /** Underlying socket / read error reported by OkHttp. */
    NETWORK,
    /** View disappeared, VM cleared, OS backgrounded — the run
     *  continues server-side; the client is just no longer watching. */
    LIFECYCLE,
    /** Unhandled / unknown teardown. */
    ERROR
}

/**
 * Server-Sent Events client for streaming responses.
 * Uses OkHttp for HTTP streaming — mirrors the iOS SSEClient.
 */
class SSEClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(java.time.Duration.ofSeconds(30))
        .readTimeout(java.time.Duration.ofSeconds(960))
        .callTimeout(java.time.Duration.ofSeconds(1020))
        .build(),
    private val callbackDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    var onEvent: ((SSEEvent) -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null
    var onComplete: (() -> Unit)? = null
    /// Fired exactly once when the SSE stream is torn down. The first
    /// argument is the runId of the stream that just closed; the
    /// second classifies the teardown. The library does NOT do any
    /// networking in response — it just signals.
    var onDisconnect: ((String, DisconnectReason) -> Unit)? = null

    // Connection ownership and every callback are confined to one dispatcher.
    // A late callback can never observe callbacks installed for its replacement.
    private val callbacks = CoroutineScope(SupervisorJob() + callbackDispatcher)
    private class Connection(
        val call: Call,
        val runId: String?,
        val event: ((SSEEvent) -> Unit)?,
        val error: ((Throwable) -> Unit)?,
        val complete: (() -> Unit)?,
        val disconnected: ((String, DisconnectReason) -> Unit)?,
        var job: Job? = null,
        var response: Response? = null,
    )
    private var connection: Connection? = null

    /**
     * Connect to an SSE endpoint.
     *
     * @param runId the run ID for the stream. Captured so the
     *   `onDisconnect` callback can report it without the caller
     *   having to thread it through `disconnect(reason:)`. Pass
     *   `null` if the client is being used outside of an
     *   agent-runtime run (e.g. ad-hoc SSE in tests); in that
     *   case `disconnect(reason:)` will not fire `onDisconnect`
     *   because there is no runId to report.
     */
    fun connect(url: String, headers: Map<String, String> = emptyMap(), runId: String? = null) {
        val request = Request.Builder().url(url)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .apply { headers.forEach { (key, value) -> header(key, value) } }.build()
        val next = Connection(client.newCall(request), runId, onEvent, onError, onComplete, onDisconnect)
        callbacks.launch {
            connection?.let { finish(it, DisconnectReason.LIFECYCLE) }
            connection = next
            next.job = launch(Dispatchers.IO) {
                var receivedHeaders = false
                val started = System.nanoTime()
                try {
                    next.call.await().use { response ->
                        withContext(callbackDispatcher) {
                            if (connection !== next) throw CancellationException()
                            next.response = response
                        }
                        receivedHeaders = true
                        if (response.code == 401 || response.code == 403) throw SSEFailure.Authentication(response.code)
                        if (response.code != 200) throw SSEFailure.Http(response.code)
                        if (response.body?.contentType()?.let { "${it.type}/${it.subtype}" } != "text/event-stream") {
                            throw SSEFailure.ContentType
                        }
                        val source = response.body?.source() ?: throw SSEFailure.Malformed
                        processStream(source) { event ->
                            withContext(callbackDispatcher) {
                                if (connection === next) next.event?.invoke(event)
                            }
                        }
                        throw SSEFailure.UnexpectedEof
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000
                    val failure = when {
                        e is SSEFailure -> e
                        client.callTimeoutMillis > 0 && elapsedMs >= client.callTimeoutMillis -> SSEFailure.OverallTimeout
                        e is java.net.SocketTimeoutException -> if (receivedHeaders) SSEFailure.IdleTimeout else SSEFailure.ConnectionTimeout
                        e is IOException -> SSEFailure.Network
                        else -> SSEFailure.Malformed
                    }
                    withContext(callbackDispatcher) {
                        // use {} has already closed it on the I/O path.
                        next.response = null
                        finish(next, DisconnectReason.NETWORK, failure)
                    }
                } finally {
                    next.call.cancel()
                }
            }
        }
    }

    /**
     * Disconnect from the SSE endpoint.
     *
     * @param reason why the stream is being torn down. The caller
     *   (the view model) is responsible for choosing the correct
     *   reason — the SSE owner no longer has enough context to
     *   distinguish "user cancelled" from "view disappeared".
     *   `disconnect(reason:)` only fires the `onDisconnect` callback
     *   when a run was associated with this client (i.e. `connect(...,
     *   runId:)` was called first); for clients created in tests
     *   without a runId the callback is a no-op.
     */
    fun disconnect(reason: DisconnectReason = DisconnectReason.EXPLICIT) {
        callbacks.launch { connection?.let { finish(it, reason) } }
    }

    private fun finish(target: Connection, reason: DisconnectReason, error: SSEFailure? = null) {
        if (connection !== target) return
        connection = null
        target.call.cancel()
        // Explicit teardown owns the accepted response too, including a blocked read.
        try { target.response?.close() } catch (_: IOException) { /* The call is already cancelled. */ }
        target.response = null
        target.job?.cancel()
        try {
            target.runId?.let { target.disconnected?.invoke(it, reason) }
        } finally {
            if (error != null) target.error?.invoke(error) else target.complete?.invoke()
        }
    }

    internal suspend fun processStream(source: BufferedSource, emit: suspend (SSEEvent) -> Unit) {
        val buffer = StringBuilder()
        while (!source.exhausted()) {
            val line = try { source.readUtf8LineStrict(1_048_576) } catch (_: java.io.EOFException) {
                throw SSEFailure.Malformed
            }
            if (buffer.length + line.length > 1_048_576) throw SSEFailure.Malformed
            if (line.isEmpty()) {
                parseEvent(buffer.toString())?.let { emit(it) }
                buffer.clear()
            } else if (!line.startsWith(":")) buffer.appendLine(line)
        }
        if (buffer.isNotEmpty()) throw SSEFailure.Malformed
    }

    private fun parseEvent(text: String): SSEEvent? {
        var eventType: String? = null
        var data: StringBuilder? = null
        var id: String? = null

        for (line in text.lines()) {
            when {
                line.startsWith("event:") -> {
                    eventType = line.removePrefix("event:").trim()
                }
                line.startsWith("data:") -> {
                    val dataLine = line.removePrefix("data:").removePrefix(" ")
                    if (data == null) data = StringBuilder(dataLine)
                    else data.append("\n").append(dataLine)
                }
                line.startsWith("id:") -> {
                    id = line.removePrefix("id:").trim()
                }
            }
        }

        val eventData = data?.toString() ?: return null

        return SSEEvent(
            type = eventType ?: "message",
            data = eventData,
            id = id
        )
    }
}

/** SSE Event */
data class SSEEvent(
    val type: String,
    val data: String,
    val id: String? = null
) {
    /** Parse the data as a JSON map */
    fun json(): Map<String, Any?>? {
        return try {
            @Suppress("UNCHECKED_CAST")
            org.json.JSONObject(data).toMap() as? Map<String, Any?>
        } catch (_: Exception) {
            null
        }
    }
}

/** Extension to convert JSONObject to a Map */
private fun org.json.JSONObject.toMap(): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    keys().forEach { key ->
        val value = get(key)
        map[key] = when (value) {
            is org.json.JSONObject -> value.toMap()
            is org.json.JSONArray -> value.toList()
            org.json.JSONObject.NULL -> null
            else -> value
        }
    }
    return map
}

private fun org.json.JSONArray.toList(): List<Any?> {
    return (0 until length()).map { i ->
        val value = get(i)
        when (value) {
            is org.json.JSONObject -> value.toMap()
            is org.json.JSONArray -> value.toList()
            org.json.JSONObject.NULL -> null
            else -> value
        }
    }
}

