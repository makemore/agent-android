package com.makemore.agentfrontend.networking

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SSETransportTest {
    @Test fun framingPreservesWhitespaceAndSplitUtf8() = runBlocking {
        val wire = Buffer().writeUtf8(": keepalive\r\nevent: assistant.message\r\ndata:  hé🙂\r\ndata: second  \r\nid: 12\r\n\r\n")
        val split = object : ForwardingSource(wire) {
            override fun read(sink: Buffer, byteCount: Long): Long = super.read(sink, minOf(1L, byteCount))
        }.buffer()
        val events = mutableListOf<SSEEvent>()
        SSEClient().processStream(split) { events.add(it) }
        assertEquals(listOf(SSEEvent("assistant.message", " hé🙂\nsecond  ", "12")), events)
    }

    @Test fun truncatedAndOversizedFramesAreMalformed() = runBlocking {
        for (wire in listOf("data: unfinished", "data: " + "x".repeat(1_048_577) + "\n\n")) {
            try {
                SSEClient().processStream(Buffer().writeUtf8(wire)) { fail("Must not emit") }
                fail("Must reject malformed stream")
            } catch (error: SSEFailure) {
                assertEquals(SSEFailure.Malformed, error)
            }
        }
    }

    @Test fun httpValidationEofAndCleanupUseOneCallbackThread() {
        val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "sse-callback-test") }.asCoroutineDispatcher()
        try {
            runBlocking(dispatcher) {
                // Coroutine debugging decorates thread names; compare actual thread identity.
                val callbackThread = Thread.currentThread()
                for ((status, type, expected) in listOf(
                    Triple(401, "text/event-stream", SSEFailure.Authentication(401)),
                    Triple(403, "text/event-stream", SSEFailure.Authentication(403)),
                    Triple(408, "text/event-stream", SSEFailure.Http(408)),
                    Triple(503, "text/event-stream", SSEFailure.Http(503)),
                    Triple(200, "application/json", SSEFailure.ContentType),
                    Triple(200, "text/event-stream", SSEFailure.UnexpectedEof),
                )) {
                    val closed = AtomicBoolean()
                    val source = object : ForwardingSource(Buffer()) {
                        override fun close() { closed.set(true); super.close() }
                    }.buffer()
                    val body = object : ResponseBody() {
                        override fun contentType() = type.toMediaType()
                        override fun contentLength() = -1L
                        override fun source() = source
                    }
                    val http = OkHttpClient.Builder().addInterceptor { chain ->
                        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                            .code(status).message("fixture").body(body).build()
                    }.build()
                    val failure = CompletableDeferred<Throwable>()
                    val disconnects = mutableListOf<DisconnectReason>()
                    val callbackThreads = mutableListOf<Thread>()
                    val client = SSEClient(http, dispatcher)
                    client.onComplete = { fail("EOF or HTTP failure must never complete successfully") }
                    client.onDisconnect = { _, reason ->
                        callbackThreads.add(Thread.currentThread())
                        disconnects.add(reason)
                    }
                    client.onError = {
                        callbackThreads.add(Thread.currentThread())
                        failure.complete(it)
                    }
                    client.connect("https://fixture.invalid/stream", runId = "run")
                    assertEquals(expected, withTimeout(5_000) { failure.await() })
                    assertEquals(listOf(callbackThread, callbackThread), callbackThreads)
                    assertEquals(listOf(DisconnectReason.NETWORK), disconnects)
                    assertTrue("Response must be closed before failure callback", closed.get())
                    http.dispatcher.executorService.shutdown()
                    http.connectionPool.evictAll()
                }
            }
        } finally { dispatcher.close() }
    }

    @Test fun disconnectIsSingleShotAndReplacementCannotDeliverOldEvents() {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            runBlocking(dispatcher) {
                val http = OkHttpClient.Builder().addInterceptor { chain ->
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("fixture")
                        .body("data: old\n\n".toResponseBody("text/event-stream".toMediaType())).build()
                }.build()
                val seen = mutableListOf<String>()
                val disconnected = CompletableDeferred<Unit>()
                val client = SSEClient(http, dispatcher)
                client.onEvent = { seen.add(it.data) }
                client.onDisconnect = { _, _ -> disconnected.complete(Unit) }
                client.connect("https://fixture.invalid/stream", runId = "old")
                client.disconnect()
                client.disconnect()
                withTimeout(5_000) { disconnected.await() }
                assertTrue(seen.isEmpty())
                http.dispatcher.executorService.shutdown()
                http.connectionPool.evictAll()
            }
        } finally { dispatcher.close() }
    }

    @Test fun explicitDisconnectClosesAnAcceptedResponseWhileTheReaderIsBlocked() {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val release = java.util.concurrent.CountDownLatch(1)
        val closed = AtomicBoolean()
        val reading = CompletableDeferred<Unit>()
        val source = object : okio.Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                reading.complete(Unit)
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return -1
            }
            override fun timeout() = okio.Timeout.NONE
            override fun close() { closed.set(true); release.countDown() }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType() = "text/event-stream".toMediaType()
            override fun contentLength() = -1L
            override fun source() = source
        }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture").body(body).build()
        }.build()
        try {
            runBlocking(dispatcher) {
                val client = SSEClient(http, dispatcher)
                val done = CompletableDeferred<Unit>()
                var disconnects = 0
                client.onError = { done.completeExceptionally(it) }
                client.onDisconnect = { _, reason ->
                    assertEquals(DisconnectReason.EXPLICIT, reason)
                    disconnects++
                }
                client.onComplete = { done.complete(Unit) }
                client.connect("https://fixture.invalid/stream", runId = "run")
                withTimeout(5_000) { reading.await() }
                client.disconnect()
                withTimeout(5_000) { done.await() }
                assertTrue(closed.get())
                assertEquals(1, disconnects)
            }
        } finally {
            release.countDown()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
            dispatcher.close()
        }
    }
}