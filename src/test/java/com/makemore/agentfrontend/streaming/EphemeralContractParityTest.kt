package com.makemore.agentfrontend.streaming

import com.makemore.agentfrontend.configuration.ChatWidgetConfig
import com.makemore.agentfrontend.models.MessageRole
import com.makemore.agentfrontend.models.MessageType
import com.makemore.agentfrontend.networking.APIClient
import com.makemore.agentfrontend.services.InMemoryStorage
import com.makemore.agentfrontend.viewmodels.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Layer B — cross-platform parity. Drives the shared
 * `clients/test-fixtures/ephemeral/contract.json` scenarios through the real
 * [ChatViewModel] + [APIClient] + SSEClient and asserts exactly what the
 * client puts on the wire in ephemeral mode. The iOS
 * `EphemeralContractParityTests` asserts the same contract — that shared
 * oracle is what keeps the two platforms consistent.
 *
 * See agent/docs/ephemeral-security-validation-plan.md (Layer B).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EphemeralContractParityTest {

    private lateinit var server: MockWebServer
    private val mainDispatcher = UnconfinedTestDispatcher()

    /** (method, path, body) for every request, in arrival order. */
    private val recorded = CopyOnWriteArrayList<Triple<String, String, String?>>()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    @Test
    fun ephemeralWireContract() = runTest {
        val contract = JSONObject(loadContractText())
        val scenarios = contract.getJSONArray("scenarios")
        for (i in 0 until scenarios.length()) {
            runScenario(scenarios.getJSONObject(i))
        }
    }

    private suspend fun runScenario(scenario: JSONObject) {
        recorded.clear()
        val name = scenario.getString("name")
        val fixture = SSEFixture.load(scenario.getString("fixture"))
        installDispatcher(fixture)

        val turns = scenario.getJSONArray("turns")
        val expect = scenario.getJSONObject("expect")

        val backendUrl = server.url("/").toString().trimEnd('/')
        val storage = InMemoryStorage()
        val privateOnly = scenario.optBoolean("privateOnly", false)
        val config = ChatWidgetConfig(
            backendUrl = backendUrl,
            agentKey = scenario.getString("agentKey"),
            ephemeral = true,
            privateOnly = privateOnly,
        )
        val apiClient = APIClient(config = config, storage = storage)
        val vm = ChatViewModel(config, apiClient, storage)

        for (t in 0 until turns.length()) {
            vm.sendMessage(turns.getString(t))
            // Each turn must add exactly one assistant reply before the next
            // turn fires, otherwise the re-sent history is short. Waiting on
            // the assistant COUNT (not content) is essential here because
            // every turn's reply text is identical.
            waitForAssistantCount(vm, expected = t + 1)
        }

        // Create-run bodies in order.
        val creates = recorded.filter { it.first == "POST" && it.second.trimEnd('/').endsWith("/runs") }
        assertEquals("[$name] expected one create per turn", turns.length(), creates.size)

        val expectedMsgs = expect.getJSONArray("expectedMessagesPerTurn")
        val counts = expect.getJSONArray("messageCountPerTurn")
        val convIds = expect.getJSONArray("conversationIdSentPerTurn")
        val everyEphemeral = expect.getBoolean("everyRequestEphemeralTrue")

        creates.forEachIndexed { i, (_, _, bodyStr) ->
            assertNotNull("[$name] turn $i had no body", bodyStr)
            val body = JSONObject(bodyStr!!)

            // (a) ephemeral flag present every turn.
            if (everyEphemeral) {
                assertTrue("[$name] turn $i missing ephemeral:true", body.optBoolean("ephemeral", false))
            }

            // (b) full history re-sent — exact roles + contents.
            val msgs = body.getJSONArray("messages")
            assertEquals("[$name] turn $i wrong message count", counts.getInt(i), msgs.length())
            val exp = expectedMsgs.getJSONArray(i)
            assertEquals(exp.length(), msgs.length())
            for (j in 0 until exp.length()) {
                val expMsg = exp.getJSONObject(j)
                val actMsg = msgs.getJSONObject(j)
                assertEquals("[$name] turn $i msg $j role", expMsg.getString("role"), actMsg.getString("role"))
                assertEquals("[$name] turn $i msg $j content", expMsg.getString("content"), actMsg.getString("content"))
            }

            // (c) conversationId: absent on turn 1, reused thereafter.
            val sent: String? = if (body.has("conversationId")) body.getString("conversationId") else null
            val expectedConv: String? = if (convIds.isNull(i)) null else convIds.getString(i)
            assertEquals("[$name] turn $i conversationId mismatch", expectedConv, sent)

            // (e) private_only egress flag forwarded exactly as configured.
            assertEquals(
                "[$name] turn $i private_only mismatch",
                privateOnly, body.optBoolean("private_only", false)
            )
        }

        // (d) the client never fetched history from the server.
        val needle = expect.getString("forbiddenPathSubstring")
        assertFalse(
            "[$name] client hit a forbidden history path ($needle)",
            recorded.any { it.second.contains(needle) }
        )
    }

    // -- Harness (mirrors SSEStreamingTests) --

    private fun installDispatcher(fixture: SSEFixture) {
        val runJson = """{"id":"${fixture.runId}","conversationId":"${fixture.conversationId}","status":"running"}"""
        val sseBody = fixture.sseBody()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val rawPath = request.path?.substringBefore('?').orEmpty()
                val path = rawPath.trimEnd('/')
                val method = request.method ?: "GET"
                val isCreate = method == "POST" && path.endsWith("/runs")
                // Only the create body matters; reading consumes the buffer.
                val body = if (isCreate) request.body.readUtf8() else null
                recorded.add(Triple(method, rawPath, body))

                return when {
                    method == "POST" && path == "/api/accounts/anonymous-session" ->
                        MockResponse().setResponseCode(200).setBody("""{"token":"stub-anon-token"}""")
                    isCreate ->
                        MockResponse().setResponseCode(200).setBody(runJson)
                    path.endsWith("/events") || path.endsWith("/stream") ->
                        MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "text/event-stream")
                            .setBody(sseBody)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    private suspend fun waitForAssistantCount(
        vm: ChatViewModel,
        expected: Int,
        timeoutMs: Long = 8_000,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val assistantCount = vm.messages.toList()
                .count { it.role == MessageRole.ASSISTANT && it.type == MessageType.MESSAGE }
            if (!vm.isLoading.value && assistantCount == expected) {
                delay(150)
                return
            }
            delay(20)
        }
        fail(
            "waitForAssistantCount timed out after ${timeoutMs}ms " +
                "(isLoading=${vm.isLoading.value}, want $expected assistant msgs)"
        )
    }

    // -- Contract loader (mirrors SSEFixture.locateFixturesDir) --

    private fun loadContractText(): String {
        var dir: File? = File("").absoluteFile
        repeat(10) {
            val cur = dir ?: return@repeat
            for (rel in listOf("clients/test-fixtures/ephemeral", "test-fixtures/ephemeral")) {
                val f = File(File(cur, rel), "contract.json")
                if (f.isFile) return f.readText()
            }
            dir = cur.parentFile
        }
        error("Could not locate test-fixtures/ephemeral/contract.json from ${File("").absolutePath}")
    }
}
