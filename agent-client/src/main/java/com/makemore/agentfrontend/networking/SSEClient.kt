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
class SSEClient {
    var onEvent: ((SSEEvent) -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null
    var onComplete: (() -> Unit)? = null
    /// Fired exactly once when the SSE stream is torn down. The first
    /// argument is the runId of the stream that just closed; the
    /// second classifies the teardown. The library does NOT do any
    /// networking in response — it just signals.
    var onDisconnect: ((String, DisconnectReason) -> Unit)? = null

    private var call: Call? = null
    private var scope: CoroutineScope? = null
    /// Run ID of the active stream, set inside `connect(url, headers, runId)`.
    /// Captured here so `disconnect(reason:)` can pass it to the
    /// `onDisconnect` callback without callers having to remember to
    /// supply it. Cleared on disconnect.
    private var lastRunId: String? = null
    /// Set true after the onDisconnect callback has fired for the
    /// current run. Prevents double-firing if both an explicit
    /// disconnect and a late error callback race on the same run.
    private var hasFiredDisconnect: Boolean = false

    /// Set by `disconnect()` so the stream coroutine and OkHttp callback can
    /// distinguish a deliberate teardown after a terminal SSE event (surface
    /// as `onComplete`) from a genuine network failure (surface as `onError`).
    /// Without this, calling `disconnect()` after `run.succeeded` cancels the
    /// scope before the `finally` block can fire `onComplete`, leaving any
    /// `CompletableDeferred`-based awaiter (see ChatViewModel.subscribeToEvents)
    /// suspended forever. Mirrors the iOS `expectingDisconnect` flag.
    private var expectingDisconnect = false

    private val client = OkHttpClient.Builder()
        .readTimeout(java.time.Duration.ofMinutes(5))
        .build()

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
        disconnect()
        expectingDisconnect = false
        lastRunId = runId
        hasFiredDisconnect = false

        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")

        headers.forEach { (key, value) ->
            requestBuilder.header(key, value)
        }

        val request = requestBuilder.build()
        val newCall = client.newCall(request)
        call = newCall

        val job = SupervisorJob()
        scope = CoroutineScope(Dispatchers.IO + job)

        newCall.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Deliberate teardown after a terminal event — surface as a
                // clean completion so the awaiter resumes. Otherwise route
                // through the normal error path.
                if (call.isCanceled() || expectingDisconnect) {
                    onComplete?.invoke()
                } else {
                    onError?.invoke(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    onError?.invoke(HttpError(response.code))
                    response.close()
                    return
                }

                val source = response.body?.source()
                if (source == null) {
                    onError?.invoke(InvalidResponse)
                    response.close()
                    return
                }

                scope?.launch {
                    try {
                        processStream(source)
                    } catch (e: Exception) {
                        if (!call.isCanceled() && !expectingDisconnect) {
                            withContext(NonCancellable + Dispatchers.Main) {
                                onError?.invoke(e)
                            }
                        }
                    } finally {
                        // Run cleanup under NonCancellable so `onComplete`
                        // still fires when `disconnect()` cancelled the
                        // scope in response to a terminal SSE event.
                        withContext(NonCancellable) {
                            response.close()
                            withContext(Dispatchers.Main) { onComplete?.invoke() }
                        }
                    }
                }
            }
        })
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
        val runId = lastRunId
        expectingDisconnect = true
        call?.cancel()
        call = null
        scope?.cancel()
        scope = null
        if (runId != null && !hasFiredDisconnect) {
            hasFiredDisconnect = true
            val captured = runId
            // Fire on the main thread so hosts don't have to dispatch.
            // Use a fresh, NonCancellable scope so a host cancelling its
            // own scope in response to the callback doesn't prevent the
            // signal from being delivered.
            CoroutineScope(NonCancellable + Dispatchers.Main).launch {
                onDisconnect?.invoke(captured, reason)
            }
        }
        lastRunId = null
    }

    private suspend fun processStream(source: BufferedSource) {
        var buffer = StringBuilder()

        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break

            if (line.isEmpty()) {
                // Empty line = end of event
                val eventText = buffer.toString()
                buffer = StringBuilder()

                if (eventText.isNotBlank()) {
                    parseEvent(eventText)?.let { event ->
                        withContext(Dispatchers.Main) { onEvent?.invoke(event) }
                    }
                }
            } else {
                buffer.appendLine(line)
            }
        }
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
                    val dataLine = line.removePrefix("data:").trim()
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

