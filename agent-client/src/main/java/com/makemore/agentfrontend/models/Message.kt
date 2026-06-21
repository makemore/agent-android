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
    val files: List<FileAttachment>? = null,
    /** True while tokens are still streaming into this message. Views use
     *  this to render a cheaper text renderer during the stream and swap
     *  to full Markdown once the stream completes — avoids visible
     *  reflow/jitter from per-token re-parsing. */
    val isStreaming: Boolean = false,
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
    REQUIRED_ACTION("required_action"),
    SUB_AGENT_START("sub_agent_start"),
    SUB_AGENT_END("sub_agent_end"),
    AGENT_CONTEXT("agent_context"),
    CONTENT_BLOCKS("content_blocks");

    companion object {
        fun fromValue(value: String): MessageType =
            entries.firstOrNull { it.value == value } ?: MESSAGE
    }
}

/** Generic lifecycle state for a single run. */
enum class RunState(val value: String) {
    IDLE("idle"),
    SENDING("sending"),
    STREAMING("streaming"),
    WAITING("waiting"),
    CANCELLING("cancelling"),
    CANCELLED("cancelled"),
    FAILED("failed"),
    SUCCEEDED("succeeded");

    fun apply(eventType: String): RunState = when (eventType) {
        "run.started", "assistant.delta", "assistant.message", "tool.call", "tool.result", "content.blocks" -> STREAMING
        "run.suspended", "client.action.required" -> WAITING
        "run.cancelled" -> CANCELLED
        "run.failed", "run.timed_out" -> FAILED
        "run.succeeded" -> SUCCEEDED
        else -> this
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
    /** Wall-clock duration of a completed sub-agent bracket, in seconds.
     *  Set on the collapsed `SUB_AGENT_END` "Consulted X · Ns" row emitted
     *  in pill mode. Null for bubble-mode sub-agent rows. */
    val subAgentDurationSeconds: Double? = null,
    val contentBlocks: List<ContentBlock>? = null,
    val actionId: String? = null,
    val actionType: String? = null,
    val actionUrl: String? = null,
    val actionLabel: String? = null,
    val resumeHint: Any? = null
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

