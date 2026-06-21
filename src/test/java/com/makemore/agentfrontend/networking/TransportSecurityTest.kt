package com.makemore.agentfrontend.networking

import com.makemore.agentfrontend.configuration.ChatWidgetConfig
import com.makemore.agentfrontend.services.InMemoryStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Layer C — HTTPS transport enforcement (fail-closed). Mirrors the iOS
 * ClientSecurityTests transport cases. The client refuses cleartext to a real
 * backend; loopback / emulator / `.local` dev hosts and an explicit
 * allowInsecureHTTP escape hatch are permitted.
 */
class TransportSecurityTest {

    private fun client(url: String, allowInsecure: Boolean = false) =
        APIClient(
            ChatWidgetConfig(
                backendUrl = url,
                agentKey = "a",
                allowInsecureHTTP = allowInsecure,
            ),
            InMemoryStorage(),
        )

    @Test
    fun isDevHostClassification() {
        assertTrue(APIClient.isDevHost("localhost"))
        assertTrue(APIClient.isDevHost("127.0.0.1"))
        assertTrue(APIClient.isDevHost("10.0.2.2"))
        assertTrue(APIClient.isDevHost("stub.local"))
        assertFalse(APIClient.isDevHost("api.example.com"))
    }

    @Test
    fun cleartextProductionBackendIsRefused() {
        val ex = assertThrows(InsecureTransport::class.java) {
            client("http://api.example.com").validateTransport()
        }
        assertEquals("api.example.com", ex.host)
    }

    @Test
    fun httpsBackendPassesGate() {
        client("https://api.example.com").validateTransport() // no throw
    }

    @Test
    fun loopbackDevBackendIsAllowed() {
        client("http://127.0.0.1:8080").validateTransport() // no throw
    }

    @Test
    fun allowInsecureHTTPEscapeHatch() {
        client("http://api.example.com", allowInsecure = true).validateTransport() // no throw
    }
}
