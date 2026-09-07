package com.makemore.agentfrontend.networking

import com.makemore.agentfrontend.configuration.AuthStrategy
import com.makemore.agentfrontend.configuration.ChatWidgetConfig
import com.makemore.agentfrontend.services.InMemoryStorage
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RunRequestsTest {
    @Test fun rejectedCreateIsClosedBeforeIdenticalRetryAndFinalResponseIsClosed() = runBlocking {
        val closed = AtomicInteger()
        val bodies = mutableListOf<String>()
        var savedBody: String? = null
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val body = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            assertEquals("beforePost must finish before HTTP starts", savedBody, body)
            bodies.add(body)
            if (bodies.size == 2) assertEquals("401 must close before retry", 1, closed.get())
            val text = if (bodies.size == 1) "{}" else "{\"id\":\"run-1\"}"
            val source = object : ForwardingSource(Buffer().writeUtf8(text)) {
                override fun close() { closed.incrementAndGet(); super.close() }
            }.buffer()
            val responseBody = object : ResponseBody() {
                override fun contentType() = "application/json".toMediaType()
                override fun contentLength() = -1L
                override fun source() = source
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (bodies.size == 1) 401 else 201).message("fixture").body(responseBody).build()
        }.build()
        try {
            val config = ChatWidgetConfig(backendUrl = "https://fixture.invalid", authStrategy = AuthStrategy.NONE)
            val api = APIClient(config, InMemoryStorage(), http)
            val run = api.createRun(null, listOf(mapOf("role" to "user", "content" to "Hello")),
                idempotencyKey = "send-1", beforePost = { savedBody = it })
            assertEquals("run-1", run.id)
            assertEquals(2, bodies.size)
            assertEquals(bodies[0], bodies[1])
            assertEquals(2, closed.get())
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test fun failedPersistenceNeverPosts() = runBlocking {
        val calls = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { calls.incrementAndGet(); error("Must not POST") }.build()
        try {
            val api = APIClient(ChatWidgetConfig(authStrategy = AuthStrategy.NONE), InMemoryStorage(), http)
            try {
                api.createRun(null, emptyList(), beforePost = { throw IllegalStateException("storage unavailable") })
                fail("Expected storage failure")
            } catch (_: IllegalStateException) { }
            assertEquals(0, calls.get())
        } finally { http.dispatcher.executorService.shutdown() }
    }

    @Test fun clearingSessionDoesNotFallBackToTheOriginalConfiguredToken() = runBlocking {
        val config = ChatWidgetConfig(authStrategy = AuthStrategy.TOKEN, authToken = "fixture-only")
        val api = APIClient(config, InMemoryStorage())
        api.clearSession()
        assertNull(api.getOrCreateSession())
        assertTrue(api.authHeaders().isEmpty())
    }
}