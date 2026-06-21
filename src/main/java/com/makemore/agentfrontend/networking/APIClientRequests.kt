package com.makemore.agentfrontend.networking

import com.makemore.agentfrontend.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

// -- Conversations --

/** Load conversations list */
suspend fun APIClient.loadConversations(): List<Conversation> = withContext(Dispatchers.IO) {
    val token = getOrCreateSession()
    val encodedKey = URLEncoder.encode(config.agentKey, "UTF-8")
    val path = "${config.apiPaths.conversations}?agent_key=$encodedKey"
    val request = buildRequest(path, "GET", token = token)

    val response = httpClient.newCall(request).await()

    if (response.code == 401) throw Unauthorized
    if (response.code != 200) throw HttpError(response.code)

    val body = response.body?.string() ?: throw InvalidResponse

    // Try paginated response first, then array
    try {
        val listResponse = json.decodeFromString<ConversationListResponse>(body)
        return@withContext listResponse.results ?: emptyList()
    } catch (_: Exception) {
        return@withContext json.decodeFromString<List<Conversation>>(body)
    }
}

/** Load a specific conversation */
suspend fun APIClient.loadConversation(id: String, limit: Int = 10, offset: Int = 0): Conversation =
    withContext(Dispatchers.IO) {
        val token = getOrCreateSession()
        val path = "${config.apiPaths.conversations}$id/?limit=$limit&offset=$offset"
        val request = buildRequest(path, "GET", token = token)

        val response = httpClient.newCall(request).await()

        if (response.code == 404) throw NotFound
        if (response.code != 200) throw HttpError(response.code)

        val body = response.body?.string() ?: throw InvalidResponse
        json.decodeFromString<Conversation>(body)
    }

// -- Runs --

/**
 * Create a new agent run.
 *
 * `params` is forwarded verbatim under the request body's `params` key.
 * The backend's `AgentRunCreateSerializer` accepts an arbitrary dict
 * here and folds `model` / `thinking` into it on arrival — this is how
 * the chat widget ships behaviour knobs (response_style, tool_access,
 * research, web_search) without breaking the wire format every time a
 * new toggle is added. Only `String`, `Boolean`, `Int`, `Long`, and
 * `Double` values are supported (matches `JSONObject.put`).
 */
suspend fun APIClient.createRun(
    conversationId: String?,
    messages: List<Map<String, Any>>,
    model: String? = null,
    thinking: Boolean = false,
    supersedeFromMessageIndex: Int? = null,
    agentKeyOverride: String? = null,
    systemVersionId: String? = null,
    ephemeral: Boolean = false,
    privateOnly: Boolean = false,
    memories: List<Map<String, String>>? = null,
    params: Map<String, Any>? = null
): AgentRun = withContext(Dispatchers.IO) {
    val token = getOrCreateSession()

    val body = JSONObject().apply {
        put("agentKey", agentKeyOverride ?: config.agentKey)
        put("messages", JSONArray().apply {
            messages.forEach { msg ->
                put(JSONObject().apply {
                    msg.forEach { (k, v) -> put(k, v) }
                })
            }
        })
        val meta = JSONObject()
        config.metadata.forEach { (k, v) -> meta.put(k, v) }
        meta.put("journeyType", config.defaultJourneyType)
        put("metadata", meta)

        conversationId?.let { put("conversationId", it) }
        model?.let { put("model", it) }
        if (thinking) put("thinking", true)
        supersedeFromMessageIndex?.let { put("supersedeFromMessageIndex", it) }
        systemVersionId?.let { put("systemVersionId", it) }
        if (ephemeral) put("ephemeral", true)
        if (privateOnly) put("private_only", true)
        if (!memories.isNullOrEmpty()) {
            put("memories", JSONArray().apply {
                memories.forEach { mem ->
                    put(JSONObject().apply {
                        mem.forEach { (k, v) -> put(k, v) }
                    })
                }
            })
        }
        if (!params.isNullOrEmpty()) {
            put("params", JSONObject().apply {
                params.forEach { (k, v) -> put(k, v) }
            })
        }
    }

    val requestBody = body.toString().toRequestBody("application/json".toMediaType())
    val request = buildRequest(config.apiPaths.runs, "POST", requestBody, token)

    var response = httpClient.newCall(request).await()

    if (response.code == 401) {
        // Try refreshing token
        clearSession()
        val newToken = getOrCreateSession(forceRefresh = true)
        val retryRequest = buildRequest(config.apiPaths.runs, "POST", requestBody, newToken)
        response = httpClient.newCall(retryRequest).await()
        if (response.code !in listOf(200, 201)) throw Unauthorized
    }

    if (response.code !in listOf(200, 201)) {
        val errorBody = response.body?.string()
        if (errorBody != null) {
            try {
                val errorJson = JSONObject(errorBody)
                val msg = errorJson.optString("error") ?: errorJson.optString("detail")
                if (msg.isNotEmpty()) throw ServerError(msg)
            } catch (_: Exception) { }
        }
        throw HttpError(response.code)
    }

    val responseBody = response.body?.string() ?: throw InvalidResponse
    json.decodeFromString<AgentRun>(responseBody)
}

/** Cancel a run */
suspend fun APIClient.cancelRun(id: String): Unit = withContext(Dispatchers.IO) {
    val token = getOrCreateSession()
    val path = config.apiPaths.cancelRunUrl(id)
    val request = buildRequest(path, "POST", token = token)

    val response = httpClient.newCall(request).await()
    if (response.code !in 200..204) throw CancelFailed
}

// -- Systems Discovery --

/** Load available agent systems */
suspend fun APIClient.loadSystems(): List<AgentSystem> = withContext(Dispatchers.IO) {
    val token = getOrCreateSession()
    val request = buildRequest(config.apiPaths.systems, "GET", token = token)

    val response = httpClient.newCall(request).await()
    if (response.code != 200) throw HttpError(response.code)

    val body = response.body?.string() ?: throw InvalidResponse

    try {
        val listResponse = json.decodeFromString<SystemsListResponse>(body)
        return@withContext listResponse.results ?: emptyList()
    } catch (_: Exception) {
        return@withContext json.decodeFromString<List<AgentSystem>>(body)
    }
}

// -- Models --

/**
 * Fetch the list of LLM models the runtime is willing to route to.
 * Hits `GET /api/agent-runtime/models/` (configurable via
 * `APIPaths.models`) — the same endpoint the web client and iOS app
 * use to populate the model picker.
 */
suspend fun APIClient.loadModels(): ModelsResponse = withContext(Dispatchers.IO) {
    val token = getOrCreateSession()
    val request = buildRequest(config.apiPaths.models, "GET", token = token)

    val response = httpClient.newCall(request).await()
    if (response.code == 401) throw Unauthorized
    if (response.code !in 200..299) throw HttpError(response.code)

    val body = response.body?.string() ?: throw InvalidResponse
    json.decodeFromString<ModelsResponse>(body)
}

