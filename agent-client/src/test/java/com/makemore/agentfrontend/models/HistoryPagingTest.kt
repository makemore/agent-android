package com.makemore.agentfrontend.models

import com.makemore.agentfrontend.configuration.APIPaths
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class HistoryPagingTest {
    @Test fun pagesAreBoundedAndCursorExcludesOffset() {
        val paths = APIPaths()
        assertTrue(paths.conversationPageUrl("c").endsWith("?limit=50&offset=0"))
        assertTrue(paths.conversationPageUrl("c", offset = 50, beforeSeq = 123).endsWith("?limit=50&before_seq=123"))
        assertTrue(paths.conversationPageUrl("c", offset = 50).endsWith("?limit=50&offset=50"))
    }

    @Test fun camelAndSnakeCursorsAndStableIdentitiesDecode() {
        val json = Json { ignoreUnknownKeys = true }
        for (cursorKey in listOf("next_before_seq", "nextBeforeSeq")) {
            val conversation = json.decodeFromString<Conversation>(
                """{"id":"c","has_more":true,"$cursorKey":123,"messages":[{"id":"m","seq":124,"role":"assistant","content":"Hello"}]}"""
            )
            assertEquals(123, conversation.nextBeforeSeq)
            assertEquals(true, conversation.hasMore)
            assertEquals("m", conversation.messages!!.single().id)
            assertEquals(124L, conversation.messages!!.single().seq)
        }
        assertNull(json.decodeFromString<Conversation>("""{"id":"c","next_before_seq":null}""").nextBeforeSeq)
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedPageIsRejected() { APIPaths().conversationPageUrl("c", limit = 51) }
}