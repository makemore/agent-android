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
    val agents: String = "/api/agent-runtime/agents/",

    /**
     * Voice token mint endpoint (django_agent_runtime.voice).
     * Set to `null` to disable ElevenLabs and fall back to native TTS.
     */
    val voiceToken: String? = "/api/agent-runtime/voice/token/",

    /**
     * Voice TTS streaming endpoint. Returned by the token mint response,
     * but kept here as a default so callers can pre-resolve it.
     */
    val voiceTts: String? = "/api/agent-runtime/voice/tts/",

    /** Voice catalogue endpoint. */
    val voiceVoices: String? = "/api/agent-runtime/voice/voices/",

    /**
     * The thread's attachments URL (Studio: `/api/.../threads/<thread id>/attachments/`).
     * `POST` uploads one file (multipart field `file`), `GET` lists, `GET <id>/` downloads.
     * `null` (the default) means this host keeps no attachments: the attach button is hidden
     * and the message carries no `attachment_ids`.
     */
    val attachments: String? = null
) {
    /** True when the host gave an attachments URL. */
    val attachmentsEnabled: Boolean get() = !attachments.isNullOrEmpty()

    /** The attachments URL (or `<id>/` under it), or null when none is configured. */
    fun attachmentsUrl(suffix: String = ""): String? {
        val base = attachments?.takeIf { it.isNotEmpty() } ?: return null
        return (if (base.endsWith("/")) base else "$base/") + suffix
    }

    /** Get the run events URL with the run ID substituted */
    fun runEventsUrl(runId: String): String =
        runEvents.replace("{runId}", runId)

    fun runDetailUrl(runId: String): String = "$runs$runId/"

    fun runByIdempotencyKeyUrl(key: String): String =
        "${runs}by-idempotency-key/?idempotency_key=${java.net.URLEncoder.encode(key, "UTF-8")}"

    fun conversationPageUrl(id: String, limit: Int = 50, offset: Int = 0, beforeSeq: Int? = null): String {
        require(limit in 1..50 && offset >= 0 && (beforeSeq == null || beforeSeq >= 0))
        return "$conversations$id/?limit=$limit" +
            (beforeSeq?.let { "&before_seq=$it" } ?: "&offset=$offset")
    }

    /** Get the cancel run URL with the run ID substituted */
    fun cancelRunUrl(runId: String): String =
        cancelRun?.replace("{runId}", runId) ?: "${runs}${runId}/cancel/"
}

