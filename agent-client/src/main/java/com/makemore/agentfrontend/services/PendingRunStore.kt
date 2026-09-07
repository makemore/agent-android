package com.makemore.agentfrontend.services

import com.makemore.agentfrontend.models.Message
import com.makemore.agentfrontend.models.MessageRole
import com.makemore.agentfrontend.models.MessageType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Date

@Serializable
data class PendingMessage(
    val id: String, val role: String, val content: String, val type: String, val timestamp: Long,
    val seq: Long? = null,
) {
    constructor(message: Message) : this(message.id, message.role.value, message.content, message.type.value, message.timestamp.time, message.seq)
    fun message() = Message(id, MessageRole.fromValue(role), content, Date(timestamp), MessageType.fromValue(type), seq = seq)
}

/** Exact original body is retained: a lost acknowledgement never creates a new logical send. */
@Serializable
data class PendingRun(
    val scope: String,
    val key: String,
    val requestBody: String,
    val baseline: List<PendingMessage>,
    val createdAt: Long,
    val runId: String? = null,
    val conversationId: String? = null,
    val messagesOffset: Int = 0,
    val nextBeforeSeq: Int? = null,
    val hasMoreMessages: Boolean = false,
    // Count POST attempts durably, including the initial send. GET recovery is always allowed.
    val creationAttempts: Int = 1,
) {
    fun mayRetryCreation(now: Long): Boolean =
        now >= createdAt && now - createdAt < 86_400_000L && creationAttempts < 4
}

class PendingRunStore(private val storage: StorageService, val scope: String, accountScope: String = scope) {
    private val storageKey = "pending_run_$scope"
    private val indexKey = "pending_run_index_$accountScope"
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): PendingRun? = storage.get(storageKey)?.let {
        json.decodeFromString<PendingRun>(it).also { pending -> check(pending.scope == scope) }
    }

    fun save(pending: PendingRun) {
        require(pending.scope == scope)
        // Register first, so a crash between writes cannot strand content outside logout cleanup.
        val scopes = storage.get(indexKey)?.let { json.decodeFromString<List<String>>(it) }.orEmpty()
        if (scope !in scopes) storage.setDurably(indexKey, json.encodeToString(scopes + scope))
        storage.setDurably(storageKey, json.encodeToString(pending))
    }

    fun clear(key: String? = null) {
        if (key == null || load()?.key == key) storage.setDurably(storageKey, null)
    }

    /** Also clears sends left behind by previously selected agents/surfaces for this account. */
    fun clearAccount() {
        val scopes = storage.get(indexKey)?.let { json.decodeFromString<List<String>>(it) }.orEmpty()
        (scopes + scope).distinct().forEach { storage.setDurably("pending_run_$it", null) }
        storage.setDurably(indexKey, null)
    }

    companion object {
        /** Only the digest is used in storage names; never persist a credential as an identifier. */
        fun scope(backend: String, account: String, agent: String, surface: String = ""): String =
            MessageDigest.getInstance("SHA-256")
                .digest(listOf(backend.trimEnd('/'), account, agent, surface).joinToString("\u0000").toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}