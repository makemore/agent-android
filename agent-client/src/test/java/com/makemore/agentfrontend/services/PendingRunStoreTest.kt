package com.makemore.agentfrontend.services

import org.junit.Assert.*
import org.junit.Test

class PendingRunStoreTest {
    @Test fun exactRequestSurvivesColdRestoreAndMatchingClear() {
        val memory = InMemoryStorage()
        val body = "{\"idempotency_key\":\"send-1\",\"messages\":[{\"content\":\"hello\"}]}"
        val pending = PendingRun("scope", "send-1", body, emptyList(), 1)
        PendingRunStore(memory, "scope").save(pending)
        val restored = PendingRunStore(memory, "scope")
        assertEquals(pending, restored.load())
        restored.save(pending.copy(runId = "run-1"))
        assertEquals(body, restored.load()?.requestBody)
        restored.clear("another-send")
        assertNotNull(restored.load())
        restored.clear("send-1")
        assertNull(restored.load())
    }

    @Test fun scopeSeparatesAccountBackendAndAgentAndLogoutClearsEveryOwnedAgent() {
        val scopes = listOf(
            PendingRunStore.scope("https://one", "account-a", "agent"),
            PendingRunStore.scope("https://two", "account-a", "agent"),
            PendingRunStore.scope("https://one", "account-b", "agent"),
            PendingRunStore.scope("https://one", "account-a", "other-agent"),
        )
        assertEquals(4, scopes.toSet().size)
        val memory = InMemoryStorage()
        val a = PendingRunStore(memory, scopes[0], "account-a")
        val otherAgent = PendingRunStore(memory, scopes[3], "account-a")
        val b = PendingRunStore(memory, scopes[2], "account-b")
        listOf(a, otherAgent, b).forEach { it.save(PendingRun(it.scope, "key", "{}", emptyList(), 1)) }
        a.clearAccount()
        assertNull(a.load())
        assertNull(otherAgent.load())
        assertNotNull(b.load())
    }

    @Test fun pendingPayloadsAndIndexRouteOnlyToSecureDurableStorage() {
        val secure = InMemoryStorage()
        val plain = InMemoryStorage()
        val store = PendingRunStore(SecureStorageService(secure, plain), "scope")
        store.save(PendingRun("scope", "key", "{}", emptyList(), 1))
        assertNotNull(secure.get("pending_run_scope"))
        assertNull(plain.get("pending_run_scope"))
        assertNull(plain.get("pending_run_index_scope"))
    }

    @Test fun coldRestoreKeepsCursorAndTheDurableCreationBudget() {
        val memory = InMemoryStorage()
        val store = PendingRunStore(memory, "scope")
        val baseline = listOf(PendingMessage("m", "user", "Hello", "message", 1, seq = 51))
        val pending = PendingRun("scope", "key", "{}", baseline, 1,
            messagesOffset = 50, nextBeforeSeq = 51, hasMoreMessages = true, creationAttempts = 3)
        store.save(pending)
        val restored = PendingRunStore(memory, "scope").load()!!
        assertEquals(pending, restored)
        assertEquals(51L, restored.baseline.single().message().seq)
        assertTrue(restored.mayRetryCreation(2))
        assertFalse(restored.copy(creationAttempts = 4).mayRetryCreation(2))
        assertFalse(restored.mayRetryCreation(86_400_001L))
        assertFalse(restored.mayRetryCreation(0))
    }

    @Test(expected = IllegalStateException::class)
    fun nonDurableStorageFailsClosed() {
        val store = object : StorageService {
            override fun get(key: String): String? = null
            override fun set(key: String, value: String?) = Unit
        }
        PendingRunStore(store, "scope").save(PendingRun("scope", "key", "{}", emptyList(), 1))
    }
}