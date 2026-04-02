package com.makemore.agentfrontend.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An agent system — a named collection of agents that work together.
 * Mirrors the iOS AgentSystem struct.
 */
@Serializable
data class AgentSystem(
    val id: String,
    val slug: String,
    val name: String,
    val description: String? = null,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("entry_agent") val entryAgent: AgentDefinitionSummary? = null,
    val members: List<AgentSystemMember>? = null,
    val versions: List<AgentSystemVersionSummary>? = null,
    @SerialName("active_version") val activeVersion: String? = null
)

/** Summary of an agent definition (used in system listings) */
@Serializable
data class AgentDefinitionSummary(
    val id: String,
    val slug: String,
    val name: String,
    val description: String? = null,
    val icon: String? = null,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("active_version") val activeVersion: String? = null,
    val versions: List<AgentVersionSummary>? = null
)

/** Summary of an agent version */
@Serializable
data class AgentVersionSummary(
    val id: String,
    val version: String,
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("is_draft") val isDraft: Boolean = false,
    val model: String? = null,
    @SerialName("created_at") val createdAt: String? = null
)

/** A member agent within a system */
@Serializable
data class AgentSystemMember(
    val id: String,
    val agent: AgentDefinitionSummary,
    val role: String,
    val order: Int = 0,
    val notes: String? = null
)

/** Summary of a system version */
@Serializable
data class AgentSystemVersionSummary(
    val id: String,
    val version: String,
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("is_draft") val isDraft: Boolean = false,
    @SerialName("release_notes") val releaseNotes: String? = null,
    @SerialName("published_at") val publishedAt: String? = null
)

/** Response wrapper for paginated system lists */
@Serializable
data class SystemsListResponse(
    val results: List<AgentSystem>? = null,
    val count: Int? = null
)

/** Response wrapper for paginated agent definition lists */
@Serializable
data class AgentDefinitionsListResponse(
    val results: List<AgentDefinitionSummary>? = null,
    val count: Int? = null
)

