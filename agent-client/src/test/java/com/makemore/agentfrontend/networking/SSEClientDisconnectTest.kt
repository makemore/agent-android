package com.makemore.agentfrontend.networking

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Tests for the `onDisconnect` callback fired by `SSEClient` exactly
 * once per run, with a classified `DisconnectReason`. These exercise
 * the SSE owner in isolation — no `ChatViewModel`, no `APIClient` —
 * using `MockWebServer` to drive the network conditions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SSEClientDisconnectTest {

    private lateinit var server: MockWebServer
    private val mainDispatcher = UnconfinedTestDispatcher()
    private val recorded = mutableListOf<Pair<String, DisconnectReason>>()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        server = MockWebServer()
        server.start()
        recorded.clear()
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun newClient(): SSEClient {
        val c = SSEClient()
        c.onDisconnect = { runId, reason ->
            // The callback fires on the main dispatcher; capture
            // synchronously from the test thread.
            recorded.add(runId to reason)
        }
        return c
    }

    @Test
    fun disconnectWithExplicitReasonFiresOnDisconnectOnce() = runTest {
        // Server stays open but never sends a body. We connect, then
        // explicitly disconnect with reason EXPLICIT. The callback
        // must fire once with the supplied runId.
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.KEEP_OPEN))
        val url = server.url("/stream").toString()
        val client = newClient()
        client.connect(url, emptyMap(), runId = "run-explicit")

        // Give the OkHttp enqueue a moment to register.
        Thread.sleep(50)
        client.disconnect(DisconnectReason.EXPLICIT)
        Thread.sleep(100)

        val explicit = recorded.firstOrNull { it.second == DisconnectReason.EXPLICIT }
        assertNotNull("expected an EXPLICIT disconnect, got $recorded", explicit)
        assertEquals("run-explicit", explicit!!.first)
    }

    @Test
    fun disconnectWithoutRunIdIsNoOp() = runTest {
        // A client that was never given a runId must not fire
        // onDisconnect (we have no runId to report). Mirrors the
        // production contract: connect(url, headers, runId) is
        // required for the callback to be meaningful.
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.KEEP_OPEN))
        val url = server.url("/stream").toString()
        val client = newClient()
        client.connect(url, emptyMap(), runId = null)

        Thread.sleep(50)
        client.disconnect(DisconnectReason.EXPLICIT)
        Thread.sleep(100)

        assertTrue("no onDisconnect should fire when no runId was set; got $recorded", recorded.isEmpty())
    }

    @Test
    fun networkErrorPropagatesViaOnError() = runTest {
        // The OkHttp client sees a connection failure when the
        // server is shut down before connect. onError should fire
        // and (when followed by a network-reason disconnect) the
        // callback should record NETWORK.
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
        val url = server.url("/stream").toString()
        var errorSeen: Throwable? = null
        val client = newClient()
        client.onError = { e -> errorSeen = e }
        client.connect(url, emptyMap(), runId = "run-network")

        // Wait for OkHttp's failure callback.
        val deadline = System.currentTimeMillis() + 2000
        while (errorSeen == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertNotNull("expected onError to fire on connection failure", errorSeen)

        // Now disconnect with NETWORK — this is the path the VM
        // would take after receiving an onError from a transport
        // failure.
        client.disconnect(DisconnectReason.NETWORK)
        Thread.sleep(100)

        val network = recorded.firstOrNull { it.second == DisconnectReason.NETWORK }
        assertNotNull("expected a NETWORK disconnect, got $recorded", network)
        assertEquals("run-network", network!!.first)
        // Exactly one disconnect for run-network.
        assertEquals(1, recorded.count { it.first == "run-network" })
    }

    @Test
    fun repeatedDisconnectFiresOnDisconnectOnlyOnce() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.KEEP_OPEN))
        val url = server.url("/stream").toString()
        val client = newClient()
        client.connect(url, emptyMap(), runId = "run-once")

        Thread.sleep(50)
        client.disconnect(DisconnectReason.EXPLICIT)
        client.disconnect(DisconnectReason.LIFECYCLE) // should be a no-op
        client.disconnect(DisconnectReason.NETWORK)   // should be a no-op
        Thread.sleep(100)

        val count = recorded.count { it.first == "run-once" }
        assertEquals("expected exactly 1 disconnect for run-once, got $recorded", 1, count)
        assertEquals(DisconnectReason.EXPLICIT, recorded.first { it.first == "run-once" }.second)
    }
}
