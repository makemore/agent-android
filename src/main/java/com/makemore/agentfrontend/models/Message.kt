package com.makemore.agentfrontend.models

import java.util.Date
import java.util.UUID

/**
 * A chat message.
 * Mirrors the iOS Message struct.
 */
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    var content: String,
    val timestamp: Date = Date(),
    val type: MessageType = MessageType.MESSAGE,
    val metadata: MessageMetadata? = null,
    val files: List<FileAttachment>? = null
)

/** Message role */
enum class MessageRole(val value: String) {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system"),
    TOOL("tool");

    companion object {
        fun fromValue(value: String): MessageRole =
            entries.firstOrNull { it.value == value } ?: USER
    }
}

/** Message type */
enum class MessageType(val value: String) {
    MESSAGE("message"),
    TOOL_CALL("tool_call"),
    TOOL_RESULT("tool_result"),
    ERROR("error"),
    CANCELLED("cancelled"),
    SUB_AGENT_START("sub_agent_start"),
    SUB_AGENT_END("sub_agent_end"),
    AGENT_CONTEXT("agent_context"),
    CONTENT_BLOCKS("content_blocks");

    companion object {
        fun fromValue(value: String): MessageType =
            entries.firstOrNull { it.value == value } ?: MESSAGE
    }
}

/** Message metadata */
data class MessageMetadata(
    val toolName: String? = null,
    val toolCallId: String? = null,
    val arguments: String? = null,
    val result: Any? = null,
    val subAgentKey: String? = null,
    val agentName: String? = null,
    val invocationMode: String? = null,
    val contentBlocks: List<ContentBlock>? = null
)

/** File attachment */
data class FileAttachment(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val size: Int,
    val type: String,
    val url: String? = null,
    val data: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FileAttachment) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

