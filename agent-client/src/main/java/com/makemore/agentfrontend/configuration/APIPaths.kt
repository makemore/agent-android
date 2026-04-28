package com.makemore.agentfrontend.configuration

/**
 * API endpoint paths configuration.
 * Mirrors the iOS APIPaths struct.
 */
data class APIPaths(
    /** Anonymous session creation endpoint */
    val anonymousSession: String = "/api/accounts/anonymous-session/",

    /** Conversations list/detail endpoint */
    val conversations: String = "/api/agent-runtime/conversations/",

    /** Agent runs endpoint */
    val runs: String = "/api/agent-runtime/runs/",

    /** Run events SSE endpoint (use {runId} as placeholder) */
    val runEvents: String = "/api/agent-runtime/runs/{runId}/events/",

    /** Cancel run endpoint (use {runId} as placeholder) */
    val cancelRun: String? = null,

    /** Simulate customer endpoint (for demo flows) */
    val simulateCustomer: String = "/api/agent-runtime/simulate-customer/",

    /** TTS voices endpoint */
    val ttsVoices: String = "/api/tts/voices/",

    /** TTS set voice endpoint */
    val ttsSetVoice: String = "/api/tts/set-voice/",

    /** Available models endpoint */
    val models: String = "/api/agent-runtime/models/",

    /** Tasks endpoint */
    val tasks: String = "/api/agent/tasks/",

    /** Systems discovery endpoint */
    val systems: String = "/api/agent-runtime/systems/",

    /** Agents discovery endpoint */
    val agents: String = "/api/agent-runtime/agents/"
) {
    /** Get the run events URL with the run ID substituted */
    fun runEventsUrl(runId: String): String =
        runEvents.replace("{runId}", runId)

    /** Get the cancel run URL with the run ID substituted */
    fun cancelRunUrl(runId: String): String =
        cancelRun?.replace("{runId}", runId) ?: "${runs}${runId}/cancel/"
}

