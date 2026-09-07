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
suspend fun APIClient.loadConversation(id: String, limit: Int = 50, offset: Int = 0, beforeSeq: Int? = null): Conversation =
    withContext(Dispatchers.IO) {
        val epoch = sessionGeneration
        val token = getOrCreateSession()
        check(sessionGeneration == epoch) { "Account changed" }
        val path = config.apiPaths.conversationPageUrl(id, limit, offset, beforeSeq)
        val request = buildRequest(path, "GET", token = token)

        httpClient.newCall(request).await().use { response ->
            check(sessionGeneration == epoch) { "Account changed" }
            if (response.code == 401 || response.code == 403) throw Unauthorized
            if (response.code == 404) throw NotFound
            if (response.code == 410) throw RunExpired
            if (response.code != 200) throw HttpError(response.code)
            json.decodeFromString<Conversation>(response.body?.string() ?: throw InvalidResponse)
        }
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
    params: Map<String, Any>? = null,
    idempotencyKey: String = java.util.UUID.randomUUID().toString(),
    beforePost: suspend (String) -> Unit = {},
): AgentRun = withContext(Dispatchers.IO) {
    val body = JSONObject().apply {
        put("idempotency_key", idempotencyKey)
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

    val originalBody = body.toString()
    beforePost(originalBody)
    createRunFromBody(originalBody)
}

/** Retries must use the original persisted bytes, not rebuilt preferences/history. */
suspend fun APIClient.createRunFromBody(originalBody: String): AgentRun = withContext(Dispatchers.IO) {
    require(JSONObject(originalBody).optString("idempotency_key").isNotBlank())
    val requestBody = originalBody.toRequestBody("application/json".toMediaType())
    val epoch = sessionGeneration
    for (attempt in 0..1) {
        val token = getOrCreateSession(forceRefresh = attempt == 1)
        check(sessionGeneration == epoch) { "Account changed" }
        val request = buildRequest(config.apiPaths.runs, "POST", requestBody, token)
        httpClient.newCall(request).await().use { response ->
            check(sessionGeneration == epoch) { "Account changed" }
            if (response.code == 401 && attempt == 0) return@use
            if (response.code == 401 || response.code == 403) throw Unauthorized
            if (response.code == 410) throw RunExpired
            if (response.code !in listOf(200, 201)) throw HttpError(response.code)
            return@withContext json.decodeFromString<AgentRun>(response.body?.string() ?: throw InvalidResponse)
        } // The rejected response is CLOSED before refreshing or issuing another call.
    }
    throw Unauthorized
}

suspend fun APIClient.loadRun(id: String): AgentRun = loadRunPath(config.apiPaths.runDetailUrl(id))

suspend fun APIClient.loadRunByIdempotencyKey(key: String): AgentRun =
    loadRunPath(config.apiPaths.runByIdempotencyKeyUrl(key))

private suspend fun APIClient.loadRunPath(path: String): AgentRun = withContext(Dispatchers.IO) {
    val epoch = sessionGeneration
    val token = getOrCreateSession()
    check(sessionGeneration == epoch) { "Account changed" }
    httpClient.newCall(buildRequest(path, token = token)).await().use { response ->
        check(sessionGeneration == epoch) { "Account changed" }
        when (response.code) {
            401, 403 -> throw Unauthorized
            404 -> throw NotFound
            410 -> throw RunExpired
            200 -> json.decodeFromString<AgentRun>(response.body?.string() ?: throw InvalidResponse)
            else -> throw HttpError(response.code)
        }
    }
}

/** Cancel a run */
suspend fun APIClient.cancelRun(id: String): Unit = withContext(Dispatchers.IO) {
    val epoch = sessionGeneration
    val token = getOrCreateSession()
    check(sessionGeneration == epoch) { "Account changed" }
    val path = config.apiPaths.cancelRunUrl(id)
    val request = buildRequest(path, "POST", token = token)

    httpClient.newCall(request).await().use { response ->
        if (response.code !in 200..204) throw CancelFailed
    }
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

