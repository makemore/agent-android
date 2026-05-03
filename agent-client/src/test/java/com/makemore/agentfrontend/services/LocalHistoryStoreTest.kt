package com.makemore.agentfrontend.services

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalHistoryStoreTest {

    private fun makeStore(agentKey: String = "test-agent", max: Int = 50): LocalHistoryStore {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return LocalHistoryStore(context, agentKey, max)
    }

    private fun makeConversation(
        id: String = "conv-1",
        title: String = "Hello",
        messageCount: Int = 2
    ): LocalConversation {
        val now = System.currentTimeMillis()
        val msgs = (0 until messageCount).map { i ->
            LocalMessage(
                id = "m$i",
                role = if (i % 2 == 0) "user" else "assistant",
                content = "msg $i",
                timestamp = now,
                type = "message"
            )
        }
        return LocalConversation(id, title, now, now, msgs)
    }

    // -- Index --

    @Test
    fun `empty store returns empty index`() {
        val store = makeStore()
        assertEquals(emptyList<LocalConversationSummary>(), store.loadIndex())
    }

    @Test
    fun `upsert creates index entry`() {
        val store = makeStore()
        store.upsert(makeConversation())
        val index = store.loadIndex()
        assertEquals(1, index.size)
        assertEquals("conv-1", index[0].id)
        assertEquals("Hello", index[0].title)
        assertEquals(2, index[0].messageCount)
    }

    @Test
    fun `upsert updates existing entry`() {
        val store = makeStore()
        store.upsert(makeConversation(title = "First"))
        store.upsert(makeConversation(title = "Updated", messageCount = 5))
        val index = store.loadIndex()
        assertEquals(1, index.size)
        assertEquals("Updated", index[0].title)
        assertEquals(5, index[0].messageCount)
    }

    @Test
    fun `index ordered newest first`() {
        val store = makeStore()
        store.upsert(makeConversation(id = "old", title = "Old"))
        Thread.sleep(10)
        store.upsert(makeConversation(id = "new", title = "New"))
        val index = store.loadIndex()
        assertEquals("new", index[0].id)
        assertEquals("old", index[1].id)
    }

    // -- Load / Delete --

    @Test
    fun `load conversation`() {
        val store = makeStore()
        store.upsert(makeConversation())
        val loaded = store.load("conv-1")
        assertNotNull(loaded)
        assertEquals("conv-1", loaded!!.id)
        assertEquals(2, loaded.messages.size)
        assertEquals("user", loaded.messages[0].role)
    }

    @Test
    fun `load missing returns null`() {
        val store = makeStore()
        assertNull(store.load("nonexistent"))
    }

    @Test
    fun `delete conversation`() {
        val store = makeStore()
        store.upsert(makeConversation(id = "a"))
        store.upsert(makeConversation(id = "b"))
        store.delete("a")
        assertEquals(1, store.loadIndex().size)
        assertNull(store.load("a"))
        assertNotNull(store.load("b"))
    }

    // -- Purge --

    @Test
    fun `purge all`() {
        val store = makeStore()
        store.upsert(makeConversation(id = "a"))
        store.upsert(makeConversation(id = "b"))
        store.purgeAll()
        assertEquals(0, store.loadIndex().size)
        assertNull(store.load("a"))
        assertNull(store.load("b"))
    }

    // -- Eviction --

    @Test
    fun `eviction drops oldest`() {
        val store = makeStore(max = 3)
        for (i in 0 until 5) {
            Thread.sleep(10)
            store.upsert(makeConversation(id = "conv-$i", title = "Conv $i"))
        }
        val index = store.loadIndex()
        assertEquals(3, index.size)
        val ids = index.map { it.id }.toSet()
        assertFalse(ids.contains("conv-0"))
        assertFalse(ids.contains("conv-1"))
        assertTrue(ids.contains("conv-2"))
        assertTrue(ids.contains("conv-3"))
        assertTrue(ids.contains("conv-4"))
        assertNull(store.load("conv-0"))
        assertNull(store.load("conv-1"))
    }

    // -- Scope isolation --

    @Test
    fun `agent key isolation`() {
        // Both stores share the same Robolectric context (same DB file),
        // but agent_key column scopes data.
        val storeA = makeStore(agentKey = "agent-a")
        val storeB = makeStore(agentKey = "agent-b")
        storeA.upsert(makeConversation(id = "conv-a"))
        storeB.upsert(makeConversation(id = "conv-b"))
        assertEquals(1, storeA.loadIndex().size)
        assertEquals("conv-a", storeA.loadIndex()[0].id)
        assertEquals(1, storeB.loadIndex().size)
        assertEquals("conv-b", storeB.loadIndex()[0].id)
    }
}
