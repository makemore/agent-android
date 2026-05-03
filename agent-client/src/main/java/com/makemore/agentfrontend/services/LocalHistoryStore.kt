package com.makemore.agentfrontend.services

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.makemore.agentfrontend.models.Message
import com.makemore.agentfrontend.models.MessageRole
import com.makemore.agentfrontend.models.MessageType
import java.util.Date

// ---------------------------------------------------------------------------
// Data classes
// ---------------------------------------------------------------------------

/** Summary of a locally-persisted ephemeral conversation (index entry). */
data class LocalConversationSummary(
    val id: String,
    val title: String,
    val createdAt: Long,   // epoch millis
    val updatedAt: Long,
    val messageCount: Int
)

/** A full locally-persisted ephemeral conversation. */
data class LocalConversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<LocalMessage>
)

/** Lightweight message for local persistence (subset of Message fields). */
data class LocalMessage(
    val id: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val type: String
) {
    constructor(msg: Message) : this(
        id = msg.id,
        role = msg.role.value,
        content = msg.content,
        timestamp = msg.timestamp.time,
        type = msg.type.value
    )

    fun toMessage(): Message = Message(
        id = id,
        role = MessageRole.fromValue(role),
        content = content,
        timestamp = Date(timestamp),
        type = MessageType.fromValue(type)
    )
}

// ---------------------------------------------------------------------------
// SQLite helper
// ---------------------------------------------------------------------------

private class LocalHistoryDbHelper(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "agent_local_history.db"
        const val DB_VERSION = 1
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE conversations (
                id          TEXT NOT NULL,
                agent_key   TEXT NOT NULL,
                title       TEXT NOT NULL DEFAULT '',
                created_at  INTEGER NOT NULL,
                updated_at  INTEGER NOT NULL,
                message_count INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (id, agent_key)
            )
        """)
        db.execSQL("""
            CREATE TABLE messages (
                conversation_id TEXT NOT NULL,
                agent_key       TEXT NOT NULL,
                sort_order      INTEGER NOT NULL,
                msg_id          TEXT NOT NULL,
                role            TEXT NOT NULL,
                content         TEXT NOT NULL DEFAULT '',
                timestamp       INTEGER NOT NULL,
                type            TEXT NOT NULL DEFAULT 'message',
                PRIMARY KEY (conversation_id, agent_key, sort_order)
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future migrations go here
    }
}

// ---------------------------------------------------------------------------
// Store
// ---------------------------------------------------------------------------

/**
 * Manages on-device persistence of ephemeral conversations using SQLite.
 *
 * @param context Android context (used to open the database).
 * @param agentKey Scopes all data — different agents never collide.
 * @param maxConversations Eviction cap per agent (default 50).
 */
class LocalHistoryStore(
    context: Context,
    private val agentKey: String,
    private val maxConversations: Int = 50
) {
    private val db: SQLiteDatabase = LocalHistoryDbHelper(context).writableDatabase

    // -- Index --

    /** Load the conversation index, newest first. */
    fun loadIndex(): List<LocalConversationSummary> {
        val results = mutableListOf<LocalConversationSummary>()
        db.rawQuery(
            "SELECT id, title, created_at, updated_at, message_count FROM conversations WHERE agent_key = ? ORDER BY updated_at DESC",
            arrayOf(agentKey)
        ).use { c ->
            while (c.moveToNext()) {
                results.add(LocalConversationSummary(
                    id = c.getString(0),
                    title = c.getString(1),
                    createdAt = c.getLong(2),
                    updatedAt = c.getLong(3),
                    messageCount = c.getInt(4)
                ))
            }
        }
        return results
    }

    // -- CRUD --

    /** Upsert a conversation — creates/updates the row + replaces all messages. Evicts beyond cap. */
    fun upsert(conversation: LocalConversation) {
        db.beginTransaction()
        try {
            // Upsert conversation row
            db.execSQL(
                """INSERT INTO conversations (id, agent_key, title, created_at, updated_at, message_count)
                   VALUES (?, ?, ?, ?, ?, ?)
                   ON CONFLICT(id, agent_key) DO UPDATE SET
                       title = excluded.title,
                       updated_at = excluded.updated_at,
                       message_count = excluded.message_count""",
                arrayOf(
                    conversation.id, agentKey, conversation.title,
                    conversation.createdAt, conversation.updatedAt,
                    conversation.messages.size
                )
            )

            // Replace messages: delete + re-insert
            db.delete("messages", "conversation_id = ? AND agent_key = ?",
                arrayOf(conversation.id, agentKey))

            for ((i, m) in conversation.messages.withIndex()) {
                val cv = ContentValues().apply {
                    put("conversation_id", conversation.id)
                    put("agent_key", agentKey)
                    put("sort_order", i)
                    put("msg_id", m.id)
                    put("role", m.role)
                    put("content", m.content)
                    put("timestamp", m.timestamp)
                    put("type", m.type)
                }
                db.insert("messages", null, cv)
            }

            evictIfNeeded()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Load a full conversation by ID. */
    fun load(conversationId: String): LocalConversation? {
        // Conversation row
        val conv = db.rawQuery(
            "SELECT title, created_at, updated_at FROM conversations WHERE id = ? AND agent_key = ?",
            arrayOf(conversationId, agentKey)
        ).use { c ->
            if (!c.moveToFirst()) return null
            Triple(c.getString(0), c.getLong(1), c.getLong(2))
        }

        // Messages
        val messages = loadMessages(conversationId)
        return LocalConversation(conversationId, conv.first, conv.second, conv.third, messages)
    }

    /** Delete a single conversation and its messages. */
    fun delete(conversationId: String) {
        db.delete("messages", "conversation_id = ? AND agent_key = ?",
            arrayOf(conversationId, agentKey))
        db.delete("conversations", "id = ? AND agent_key = ?",
            arrayOf(conversationId, agentKey))
    }

    /** Purge all local conversations for this agent. */
    fun purgeAll() {
        db.delete("messages", "agent_key = ?", arrayOf(agentKey))
        db.delete("conversations", "agent_key = ?", arrayOf(agentKey))
    }

    // -- Private helpers --

    private fun loadMessages(conversationId: String): List<LocalMessage> {
        val msgs = mutableListOf<LocalMessage>()
        db.rawQuery(
            "SELECT msg_id, role, content, timestamp, type FROM messages WHERE conversation_id = ? AND agent_key = ? ORDER BY sort_order",
            arrayOf(conversationId, agentKey)
        ).use { c ->
            while (c.moveToNext()) {
                msgs.add(LocalMessage(
                    id = c.getString(0),
                    role = c.getString(1),
                    content = c.getString(2),
                    timestamp = c.getLong(3),
                    type = c.getString(4)
                ))
            }
        }
        return msgs
    }

    private fun evictIfNeeded() {
        val idsToDelete = mutableListOf<String>()
        db.rawQuery(
            "SELECT id FROM conversations WHERE agent_key = ? ORDER BY updated_at DESC LIMIT -1 OFFSET ?",
            arrayOf(agentKey, maxConversations.toString())
        ).use { c ->
            while (c.moveToNext()) idsToDelete.add(c.getString(0))
        }
        for (id in idsToDelete) {
            db.delete("messages", "conversation_id = ? AND agent_key = ?", arrayOf(id, agentKey))
            db.delete("conversations", "id = ? AND agent_key = ?", arrayOf(id, agentKey))
        }
    }
}
