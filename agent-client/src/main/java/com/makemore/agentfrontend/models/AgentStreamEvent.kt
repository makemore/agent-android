package com.makemore.agentfrontend.models

import org.json.JSONArray
import org.json.JSONObject

enum class AgentStreamConnectionStatus { IDLE, CONNECTING, OPEN, RECONNECTING, CLOSED, ERRORED }

private val AGENT_STREAM_KNOWN_TYPES = setOf(
    "assistant.message", "assistant.delta", "tool.call", "tool.result",
    "tool.progress", "content.blocks", "sub_agent.start", "sub_agent.end",
    "custom", "error", "run.started", "run.succeeded", "run.failed",
    "run.cancelled", "run.timed_out", "run.suspended", "run.resumed",
    "client.action.required", "run.heartbeat", "state.checkpoint",
    "step.started", "step.completed", "step.failed", "step.skipped",
    "step.retrying", "progress.update", "memory.update",
)

data class AgentStreamEvent(
    val type: String,
    val payload: Map<String, Any?> = emptyMap(),
    val runId: String? = null,
    val seq: Int? = null,
    val timestamp: String? = null,
    val known: Boolean = AGENT_STREAM_KNOWN_TYPES.contains(type),
    val parseError: String? = null,
) {
    val dedupeKey: String?
        get() {
            if (runId != null && seq != null) return "$runId:$seq"
            if (type == "assistant.delta") return null
            val stable = payload["id"] ?: payload["message_id"] ?: payload["tool_call_id"] ?: payload["action_id"]
            return (stable as? String)?.let { "$type:$it" }
        }

    companion object {
        val knownTypes: Set<String> = AGENT_STREAM_KNOWN_TYPES

        fun parse(data: String, eventTypeHint: String? = null): AgentStreamEvent = try {
            parse(JSONObject(data), eventTypeHint)
        } catch (e: Exception) {
            AgentStreamEvent(type = "unknown", known = false, parseError = e.message ?: "Malformed JSON")
        }

        fun parse(obj: JSONObject, eventTypeHint: String? = null): AgentStreamEvent {
            val type = obj.optString("type", eventTypeHint ?: "message")
            val payload = obj.optJSONObject("payload")?.toMap() ?: obj.toMap()
            return AgentStreamEvent(
                type = type,
                payload = payload,
                runId = obj.optStringOrNull("run_id") ?: obj.optStringOrNull("runId"),
                seq = if (obj.has("seq")) obj.optInt("seq") else null,
                timestamp = obj.optStringOrNull("ts"),
                known = AGENT_STREAM_KNOWN_TYPES.contains(type),
            )
        }
    }
}

enum class AgentRunLifecycleStatus { IDLE, RUNNING, WAITING, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT, ERRORED }

data class AgentToolCallState(
    val id: String,
    val name: String? = null,
    val status: String,
    val result: Any? = null,
    val error: Any? = null,
)

data class AgentRequiredActionState(
    val id: String,
    val actionType: String? = null,
    val status: String,
    val title: String? = null,
    val message: String? = null,
)

data class AgentRunReducerState(
    val runId: String? = null,
    val status: AgentRunLifecycleStatus = AgentRunLifecycleStatus.IDLE,
    val assistantText: String = "",
    val toolCalls: Map<String, AgentToolCallState> = emptyMap(),
    val requiredActions: Map<String, AgentRequiredActionState> = emptyMap(),
    val unknownEvents: List<AgentStreamEvent> = emptyList(),
    val seenEventKeys: Set<String> = emptySet(),
) {
    fun reduce(event: AgentStreamEvent): AgentRunReducerState {
        val key = event.dedupeKey
        if (key != null && seenEventKeys.contains(key)) return this
        var next = copy(
            runId = event.runId ?: runId,
            seenEventKeys = if (key == null) seenEventKeys else seenEventKeys + key,
        )
        if (!event.known) return next.applyUnknown(event)
        return when (event.type) {
            "run.started" -> next.copy(status = AgentRunLifecycleStatus.RUNNING)
            "assistant.delta" -> next.copy(
                status = if (next.status == AgentRunLifecycleStatus.IDLE) AgentRunLifecycleStatus.RUNNING else next.status,
                assistantText = next.assistantText + (event.payload["delta"] as? String).orEmpty(),
            )
            "assistant.message" -> next.copy(assistantText = event.payload["content"] as? String ?: next.assistantText)
            "tool.call" -> next.applyToolCall(event.payload)
            "tool.progress" -> next.applyToolProgress(event.payload)
            "tool.result" -> next.applyToolResult(event.payload)
            "client.action.required" -> next.applyRequiredAction(event.payload)
            "run.suspended" -> next.copy(status = AgentRunLifecycleStatus.WAITING)
            "run.succeeded" -> next.copy(status = AgentRunLifecycleStatus.SUCCEEDED)
            "run.failed" -> next.copy(status = AgentRunLifecycleStatus.FAILED)
            "run.cancelled" -> next.copy(status = AgentRunLifecycleStatus.CANCELLED)
            "run.timed_out" -> next.copy(status = AgentRunLifecycleStatus.TIMED_OUT)
            "error" -> next.copy(status = AgentRunLifecycleStatus.ERRORED)
            else -> next
        }
    }

    private fun applyToolCall(payload: Map<String, Any?>): AgentRunReducerState {
        val id = payload["id"] as? String ?: payload["tool_call_id"] as? String ?: "tool-${toolCalls.size}"
        return copy(status = AgentRunLifecycleStatus.RUNNING, toolCalls = toolCalls + (id to AgentToolCallState(id, payload["name"] as? String ?: payload["tool_name"] as? String, "running")))
    }

    private fun applyToolProgress(payload: Map<String, Any?>): AgentRunReducerState {
        val id = payload["tool_call_id"] as? String ?: payload["id"] as? String ?: return this
        val call = toolCalls[id] ?: AgentToolCallState(id = id, status = "running")
        return copy(toolCalls = toolCalls + (id to call.copy(status = "running")))
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyToolResult(payload: Map<String, Any?>): AgentRunReducerState {
        val id = payload["tool_call_id"] as? String ?: payload["id"] as? String ?: "tool-${toolCalls.size}"
        val result = payload["result"]
        val error = (result as? Map<String, Any?>)?.get("error")
        val name = payload["name"] as? String ?: payload["tool_name"] as? String ?: toolCalls[id]?.name
        return copy(toolCalls = toolCalls + (id to AgentToolCallState(id, name, if (error == null) "completed" else "failed", result, error)))
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyRequiredAction(payload: Map<String, Any?>): AgentRunReducerState {
        val action = payload["required_action"] as? Map<String, Any?> ?: payload
        val id = action["action_id"] as? String ?: "action-${requiredActions.size}"
        val state = AgentRequiredActionState(id, action["action_type"] as? String, "requested", action["title"] as? String, action["message"] as? String)
        return copy(status = AgentRunLifecycleStatus.WAITING, requiredActions = requiredActions + (id to state))
    }

    private fun applyUnknown(event: AgentStreamEvent): AgentRunReducerState {
        val actionId = event.payload["action_id"] as? String
        val action = actionId?.let { requiredActions[it] }
        val actions = if (actionId != null && action != null && (event.type == "client.action.submitted" || event.type == "client.action.resolved")) {
            requiredActions + (actionId to action.copy(status = if (event.type.endsWith("submitted")) "submitted" else "resolved"))
        } else requiredActions
        return copy(requiredActions = actions, unknownEvents = unknownEvents + event)
    }
}

private fun JSONObject.optStringOrNull(name: String): String? = if (has(name) && !isNull(name)) optString(name) else null

private fun JSONObject.toMap(): Map<String, Any?> = keys().asSequence().associateWith { key ->
    when (val value = get(key)) {
        is JSONObject -> value.toMap()
        is JSONArray -> value.toList()
        JSONObject.NULL -> null
        else -> value
    }
}

private fun JSONArray.toList(): List<Any?> = (0 until length()).map { idx ->
    when (val value = get(idx)) {
        is JSONObject -> value.toMap()
        is JSONArray -> value.toList()
        JSONObject.NULL -> null
        else -> value
    }
}
