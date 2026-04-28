package com.makemore.agentfrontend.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A conversation containing messages.
 * Mirrors the iOS Conversation struct.
 */
@Serializable
data class Conversation(
    val id: String,
    val title: String? = null,
    val messages: List<APIMessage>? = null,
    val hasMore: Boolean? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
)

/** API message format (for decoding from backend) */
@Serializable
data class APIMessage(
    val role: String,
    val content: String? = null,
    val timestamp: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
    val metadata: APIMessageMetadata? = null
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
@Serializable
data class AgentRun(
    val id: String,
    val conversationId: String? = null
)

/** Conversation list response */
@Serializable
data class ConversationListResponse(
    val results: List<Conversation>? = null,
    val count: Int? = null,
    val next: String? = null,
    val previous: String? = null
)

