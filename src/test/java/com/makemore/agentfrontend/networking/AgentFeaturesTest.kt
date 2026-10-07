package com.makemore.agentfrontend.networking

import com.makemore.agentfrontend.configuration.AuthStrategy
import com.makemore.agentfrontend.configuration.ChatWidgetConfig
import com.makemore.agentfrontend.services.InMemoryStorage
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** Web access (agent_runtime_core.web_access): the runtime says whether to offer the "Web" switch. */
class AgentFeaturesTest {
    private fun client(code: Int, body: String, seen: MutableList<Request>): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            seen.add(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

    @Test fun featuresComeFromTheRunsFeaturesEndpoint() = runBlocking {
        val seen = mutableListOf<Request>()
        val http = client(200, """{"agent_key":"help er","web_access":true}""", seen)
        val api = APIClient(ChatWidgetConfig(backendUrl = "https://fixture.invalid", authStrategy = AuthStrategy.NONE),
            InMemoryStorage(), http)
        val features = api.loadAgentFeatures("help er")
        assertTrue(features.webAccess)
        assertEquals("GET", seen.single().method)
        assertEquals("/api/agent-runtime/runs/features/", seen.single().url.encodedPath)
        assertEquals("help er", seen.single().url.queryParameter("agent_key"))
    }

    @Test fun missingFlagMeansOffAndErrorsThrow() = runBlocking {
        val api = APIClient(ChatWidgetConfig(backendUrl = "https://fixture.invalid", authStrategy = AuthStrategy.NONE),
            InMemoryStorage(), client(200, """{"agent_key":"a"}""", mutableListOf()))
        assertFalse(api.loadAgentFeatures("a").webAccess)
        val old = APIClient(ChatWidgetConfig(backendUrl = "https://fixture.invalid", authStrategy = AuthStrategy.NONE),
            InMemoryStorage(), client(404, "", mutableListOf()))
        try {
            old.loadAgentFeatures("a")
            fail("Expected an HTTP error")
        } catch (_: HttpError) {
        }
    }
}
