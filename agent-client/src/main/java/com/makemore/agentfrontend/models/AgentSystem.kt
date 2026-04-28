package com.makemore.agentfrontend.models

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
    val isActive: Boolean = true,
    val entryAgent: AgentDefinitionSummary? = null,
    val members: List<AgentSystemMember>? = null,
    val versions: List<AgentSystemVersionSummary>? = null,
    val activeVersion: String? = null
)

/** Summary of an agent definition (used in system listings) */
@Serializable
data class AgentDefinitionSummary(
    val id: String,
    val slug: String,
    val name: String,
    val description: String? = null,
    val icon: String? = null,
    val isActive: Boolean = true,
    val activeVersion: String? = null,
    val versions: List<AgentVersionSummary>? = null
)

/** Summary of an agent version */
@Serializable
data class AgentVersionSummary(
    val id: String,
    val version: String,
    val isActive: Boolean = false,
    val isDraft: Boolean = false,
    val model: String? = null,
    val createdAt: String? = null
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
    val isActive: Boolean = false,
    val isDraft: Boolean = false,
    val releaseNotes: String? = null,
    val publishedAt: String? = null
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

