package com.makemore.agentfrontend.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A conversation containing messages.
 * Mirrors the iOS Conversation struct.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class Conversation(
    val id: String,
    val title: String? = null,
    val messages: List<APIMessage>? = null,
    @JsonNames("has_more") val hasMore: Boolean? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    /**
     * Server-persisted conversation metadata. The runtime stamps the
     * latest `context.usage` snapshot here at the end of a
     * successful run so a reloaded conversation can show the
     * freshest known token count + active model + `context_window`
     * without re-running an LLM call.
     */
    val metadata: JsonObject? = null,
    @JsonNames("next_before_seq") val nextBeforeSeq: Int? = null,
)

/** API message format (for decoding from backend) */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class APIMessage(
    val role: String,
    val content: String? = null,
    val timestamp: String? = null,
    @JsonNames("tool_calls") val toolCalls: List<ToolCall>? = null,
    @JsonNames("tool_call_id") val toolCallId: String? = null,
    val metadata: APIMessageMetadata? = null,
    val id: String? = null,
    val seq: Long? = null,
)

/**
 * Metadata carried on an API message. The backend persists rich UI data
 * (e.g. contentBlocks from tool results) here so conversations can be
 * re-rendered faithfully on reload without replaying the SSE stream.
 *
 * `contentBlocks` is kept as a raw `List<JsonObject>` so the existing
 * `ContentBlock.parse(List<Map<String, Any?>>)` helper can handle the
 * same shape used for live `content.blocks` SSE payloads.
 */
@Serializable
data class APIMessageMetadata(
    val contentBlocks: List<JsonObject>? = null,
    val toolName: String? = null
)

/** Tool call from API */
@Serializable
data class ToolCall(
    val id: String? = null,
    val name: String? = null,
    val function: ToolFunction? = null,
    val arguments: String? = null
)

/** Tool function */
@Serializable
data class ToolFunction(
    val name: String? = null,
    val arguments: String? = null
)

/** Agent run response */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class AgentRun(
    val id: String,
    @JsonNames("conversation_id") val conversationId: String? = null,
    val status: String? = null,
    val output: JsonObject? = null,
    val error: JsonElement? = null,
)

/** Conversation list response */
@Serializable
data class ConversationListResponse(
    val results: List<Conversation>? = null,
    val count: Int? = null,
    val next: String? = null,
    val previous: String? = null
)

