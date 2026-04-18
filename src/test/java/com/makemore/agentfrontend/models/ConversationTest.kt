package com.makemore.agentfrontend.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.*
import org.junit.Test

class ConversationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun `APIMessage decodes contentBlocks in metadata`() {
        // Backend persists rich UI content on tool messages so conversations
        // can be rendered faithfully on reload. Mirrors the iOS decoder test.
        val raw = """
            {
              "role": "tool",
              "content": "{\"ok\":true}",
              "tool_call_id": "call_123",
              "metadata": {
                "tool_name": "get_exercise_detail",
                "contentBlocks": [
                  {
                    "type": "video",
                    "url": "https://example.com/hand-squeeze.mp4",
                    "title": "Hand Squeeze + Release",
                    "caption": "A regulating exercise"
                  }
                ]
              }
            }
        """.trimIndent()

        val msg = json.decodeFromString(APIMessage.serializer(), raw)
        assertEquals("tool", msg.role)
        assertEquals("call_123", msg.toolCallId)
        assertNotNull(msg.metadata)
        assertEquals("get_exercise_detail", msg.metadata?.toolName)
        assertEquals(1, msg.metadata?.contentBlocks?.size)

        // Convert the raw JsonObject into the ContentBlock-parseable shape.
        val rawBlocks = msg.metadata?.contentBlocks!!.map { it.toTestAnyMap() }
        val parsed = ContentBlock.parse(rawBlocks)
        assertEquals(1, parsed.size)
        val video = parsed[0] as VideoBlock
        assertEquals("https://example.com/hand-squeeze.mp4", video.url)
        assertEquals("Hand Squeeze + Release", video.title)
        assertEquals("A regulating exercise", video.caption)
    }

    @Test
    fun `APIMessage without metadata decodes cleanly`() {
        val raw = """
            {
              "role": "assistant",
              "content": "Hello there."
            }
        """.trimIndent()

        val msg = json.decodeFromString(APIMessage.serializer(), raw)
        assertEquals("assistant", msg.role)
        assertEquals("Hello there.", msg.content)
        assertNull(msg.metadata)
    }

    @Test
    fun `APIMessage with empty contentBlocks array decodes`() {
        val raw = """
            {
              "role": "tool",
              "content": "done",
              "tool_call_id": "call_99",
              "metadata": { "contentBlocks": [] }
            }
        """.trimIndent()

        val msg = json.decodeFromString(APIMessage.serializer(), raw)
        assertNotNull(msg.metadata)
        assertEquals(0, msg.metadata?.contentBlocks?.size)
    }
}

/** Test-local helper to convert JsonObject → Map<String, Any?> for ContentBlock.parse. */
private fun JsonObject.toTestAnyMap(): Map<String, Any?> =
    mapValues { (_, v) -> v.toTestAny() }

private fun JsonElement.toTestAny(): Any? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
    is JsonObject -> toTestAnyMap()
    is JsonArray -> map { it.toTestAny() }
}
