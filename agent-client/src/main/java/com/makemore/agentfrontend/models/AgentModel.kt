package com.makemore.agentfrontend.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Available LLM model, as advertised by `GET /api/agent-runtime/models/`.
 * Mirrors the iOS `AgentModel` struct: the runtime emits snake_case keys
 * (`supports_thinking`, `supports_tools`, `supports_vision`) so the
 * mapping is spelt out explicitly via `@SerialName`.
 */
@Serializable
data class AgentModel(
    val id: String,
    val name: String,
    val provider: String,
    val description: String? = null,
    @SerialName("supports_thinking") val supportsThinking: Boolean = false,
    @SerialName("supports_tools") val supportsTools: Boolean = true,
    @SerialName("supports_vision") val supportsVision: Boolean = false,
)

/**
 * Models list response from `/api/agent-runtime/models/`. `default` is
 * the runtime's configured fallback (`DEFAULT_MODEL`) — used by the
 * picker to pre-select something sensible when the user hasn't chosen
 * yet.
 */
@Serializable
data class ModelsResponse(
    val models: List<AgentModel>,
    val default: String? = null,
)

/** Task item */
@Serializable
data class TaskItem(
    val id: String,
    val name: String,
    val description: String? = null,
    val state: String = "not_started",
    val parentId: String? = null
) {
    val taskState: TaskState
        get() = TaskState.fromValue(state)
}

/** Task state */
enum class TaskState(val value: String, val icon: String, val label: String) {
    NOT_STARTED("not_started", "○", "Not Started"),
    IN_PROGRESS("in_progress", "◐", "In Progress"),
    COMPLETE("complete", "●", "Complete"),
    CANCELLED("cancelled", "⊘", "Cancelled");

    /** Next state in the cycle */
    val next: TaskState
        get() = when (this) {
            NOT_STARTED -> IN_PROGRESS
            IN_PROGRESS -> COMPLETE
            COMPLETE -> NOT_STARTED
            CANCELLED -> NOT_STARTED
        }

    companion object {
        fun fromValue(value: String): TaskState =
            entries.firstOrNull { it.value == value } ?: NOT_STARTED
    }
}

/** Task list with progress */
@Serializable
data class TaskList(
    val id: String,
    val tasks: List<TaskItem>,
    val progress: TaskProgress
)

/** Task progress */
@Serializable
data class TaskProgress(
    val total: Int = 0,
    val completed: Int = 0,
    val percentComplete: Double = 0.0
)

