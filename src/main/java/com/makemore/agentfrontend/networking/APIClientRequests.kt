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

/** Create a new agent run */
suspend fun APIClient.createRun(
    conversationId: String?,
    messages: List<Map<String, Any>>,
    model: String? = null,
    thinking: Boolean = false,
    supersedeFromMessageIndex: Int? = null,
    agentKeyOverride: String? = null,
    systemVersionId: String? = null
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
    if (response.code != 200) throw CancelFailed
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

