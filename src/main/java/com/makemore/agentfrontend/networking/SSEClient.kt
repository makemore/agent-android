package com.makemore.agentfrontend.networking

import kotlinx.coroutines.*
import okhttp3.*
import okio.BufferedSource
import java.io.IOException

/**
 * Server-Sent Events client for streaming responses.
 * Uses OkHttp for HTTP streaming — mirrors the iOS SSEClient.
 */
class SSEClient {
    var onEvent: ((SSEEvent) -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null
    var onComplete: (() -> Unit)? = null

    private var call: Call? = null
    private var scope: CoroutineScope? = null

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

    /** Connect to an SSE endpoint */
    fun connect(url: String, headers: Map<String, String> = emptyMap()) {
        disconnect()
        expectingDisconnect = false

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

    /** Disconnect from the SSE endpoint */
    fun disconnect() {
        expectingDisconnect = true
        call?.cancel()
        call = null
        scope?.cancel()
        scope = null
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

