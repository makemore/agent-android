package com.makemore.agentfrontend.streaming

import com.makemore.agentfrontend.configuration.ChatWidgetConfig
import com.makemore.agentfrontend.models.*
import com.makemore.agentfrontend.networking.APIClient
import com.makemore.agentfrontend.services.InMemoryStorage
import com.makemore.agentfrontend.viewmodels.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Level-A streaming tests. Wires the *real* [ChatViewModel], [APIClient]
 * and [com.makemore.agentfrontend.networking.SSEClient] together but
 * routes every HTTP request through [MockWebServer] so each scenario
 * replays a JSON fixture from `clients/test-fixtures/sse/`. No real
 * `agent_studio` instance — but the same code paths the production app
 * exercises. Mirrors the iOS `SSEStreamingTests`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SSEStreamingTests {

    private lateinit var server: MockWebServer
    private lateinit var config: ChatWidgetConfig
    private lateinit var apiClient: APIClient
    private lateinit var storage: InMemoryStorage
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        server = MockWebServer().apply { start() }
        // Trim trailing slash so urlString concatenation matches MockWebServer's url() format.
        val backendUrl = server.url("/").toString().trimEnd('/')
        storage = InMemoryStorage()
        config = ChatWidgetConfig(
            backendUrl = backendUrl,
            agentKey = "test-agent",
        )
        apiClient = APIClient(config = config, storage = storage)
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    // -- Tests --

    @Test
    fun simpleStreamingFlowFinalisesAssistantMessage() = runTest {
        val fixture = SSEFixture.load("simple_streaming")
        installDispatcher(fixture)
        val expected = "Hello there! How can I help you today?"

        val vm = ChatViewModel(config, apiClient, storage)
        vm.sendMessage("Hi")
        waitForStreamSettled(vm, expected)

        assertEquals("expected user + assistant only, got ${dumpMessages(vm)}", 2, vm.messages.size)
        assertEquals(MessageRole.USER, vm.messages[0].role)
        assertEquals("Hi", vm.messages[0].content)
        assertEquals(MessageRole.ASSISTANT, vm.messages[1].role)
        assertEquals(MessageType.MESSAGE, vm.messages[1].type)
        assertEquals(expected, vm.messages[1].content)
        assertNull(vm.error.value)
        assertEquals(fixture.conversationId, vm.conversationId.value)
    }

    @Test
    fun toolCallEmitsContentBlocksMessage() = runTest {
        val fixture = SSEFixture.load("tool_call_with_content_blocks")
        installDispatcher(fixture)

        val vm = ChatViewModel(config, apiClient, storage)
        vm.sendMessage("Find me a beach house")
        waitForStreamSettled(vm, expected = "I found 2 options that match.")

        assertTrue(
            "missing tool.call: ${dumpMessages(vm)}",
            vm.messages.any { it.role == MessageRole.ASSISTANT && it.type == MessageType.TOOL_CALL }
        )
        assertTrue(
            "missing tool.result: ${dumpMessages(vm)}",
            vm.messages.any { it.role == MessageRole.SYSTEM && it.type == MessageType.TOOL_RESULT }
        )

        val blocksMsg = vm.messages.firstOrNull { it.type == MessageType.CONTENT_BLOCKS }
        assertNotNull("expected a content_blocks message", blocksMsg)
        val blocks = blocksMsg!!.metadata?.contentBlocks ?: emptyList()
        assertEquals("expected card + table", 2, blocks.size)

        val card = blocks[0] as? CardBlock
        assertNotNull("first block should be a CardBlock, got ${blocks[0]}", card)
        assertEquals("Beach House", card!!.title)
        assertEquals(2, card.metadata?.size)

        val table = blocks[1] as? TableBlock
        assertNotNull("second block should be a TableBlock, got ${blocks[1]}", table)
        assertEquals(listOf("Listing", "Price", "Sleeps"), table!!.headers)
        assertEquals(2, table.rows?.size)

        assertEquals(MessageRole.ASSISTANT, vm.messages.last().role)
        assertEquals("I found 2 options that match.", vm.messages.last().content)
    }

    @Test
    fun multiAgentHandoffSuppressesParentEcho() = runTest {
        val fixture = SSEFixture.load("sai_multi_agent_handoff")
        installDispatcher(fixture)

        val vm = ChatViewModel(config, apiClient, storage)
        vm.sendMessage("I'm anxious about something")
        val therapistReply =
            "Hi, I'm here to listen. Could you tell me a little about what's on your mind?"
        waitForStreamSettled(vm, expected = therapistReply)

        val assistantTexts = vm.messages
            .filter { it.role == MessageRole.ASSISTANT && it.type == MessageType.MESSAGE }
            .map { it.content }
        assertEquals(
            "sub-agent reply should appear exactly once, got: $assistantTexts",
            1,
            assistantTexts.count { it == therapistReply }
        )

        val starts = vm.messages.filter { it.type == MessageType.SUB_AGENT_START }
        val ends = vm.messages.filter { it.type == MessageType.SUB_AGENT_END }
        assertEquals(1, starts.size)
        assertEquals(1, ends.size)
        assertEquals("S'Ai Therapist", starts.first().metadata?.agentName)
    }

    @Test
    fun multiAgentWithBlocksRendersSubAgentBlocks() = runTest {
        val fixture = SSEFixture.load("sai_multi_agent_with_blocks")
        installDispatcher(fixture)

        val vm = ChatViewModel(config, apiClient, storage)
        vm.sendMessage("Show me my account")
        waitForStreamSettled(vm)

        val blocksMsg = vm.messages.firstOrNull { it.type == MessageType.CONTENT_BLOCKS }
        val blocks = blocksMsg?.metadata?.contentBlocks ?: emptyList()
        assertEquals("expected callout + cardList + actionButtons, got $blocks", 3, blocks.size)

        val callout = blocks[0] as? CalloutBlock
        assertNotNull("expected callout, got ${blocks[0]}", callout)
        assertEquals("info", callout!!.style)
        assertEquals("Account in good standing", callout.title)

        val cardList = blocks[1] as? CardListBlock
        assertNotNull("expected cardList, got ${blocks[1]}", cardList)
        assertEquals(2, cardList!!.items.size)
        assertEquals("Plan", cardList.items[0].title)

        val actionButtons = blocks[2] as? ActionButtonsBlock
        assertNotNull("expected actionButtons, got ${blocks[2]}", actionButtons)
        assertEquals(2, actionButtons!!.buttons.size)
        assertEquals("pay_invoice", actionButtons.buttons[0].callbackId)
    }

    @Test
    fun runFailedSurfacesError() = runTest {
        val fixture = SSEFixture.load("run_failed")
        installDispatcher(fixture)

        val vm = ChatViewModel(config, apiClient, storage)
        vm.sendMessage("Hello")
        waitForStreamSettled(vm)

        assertEquals("Upstream provider unavailable", vm.error.value)
        assertTrue(
            "expected an error message, got ${dumpMessages(vm)}",
            vm.messages.any { it.type == MessageType.ERROR && it.role == MessageRole.SYSTEM }
        )
    }

    @Test
    fun requiredActionRendersWaitingState() = runTest {
        val fixture = SSEFixture.load("required_action")
        installDispatcher(fixture)

        val vm = ChatViewModel(config, apiClient, storage)
        vm.sendMessage("Check my calendar")
        waitForStreamSettled(vm)

        assertEquals(RunState.WAITING, vm.runState.value)
        val action = vm.messages.firstOrNull { it.type == MessageType.REQUIRED_ACTION }
        assertNotNull("expected required action message: ${dumpMessages(vm)}", action)
        assertEquals("oauth", action!!.metadata?.actionType)
        assertEquals("Connect", action.metadata?.actionLabel)
    }

    // -- Helpers --

    /** Wire MockWebServer to satisfy the three endpoints this flow hits. */
    private fun installDispatcher(fixture: SSEFixture) {
        val runJson = """
            {"id":"${fixture.runId}","conversationId":"${fixture.conversationId}","status":"running"}
        """.trimIndent()
        val sseBody = fixture.sseBody()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path?.substringBefore('?').orEmpty().trimEnd('/')
                val method = request.method ?: "GET"
                return when {
                    method == "POST" && path == "/api/accounts/anonymous-session" ->
                        MockResponse().setResponseCode(200).setBody("""{"token":"stub-anon-token"}""")
                    method == "POST" && path == "/api/agent-runtime/runs" ->
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

    /**
     * Wait for the run to terminate (`isLoading == false`) **and** the
     * drain loop's typewriter buffer to fully reveal. Optionally wait
     * until the most recent assistant text bubble matches the expected
     * final string — this is the only reliable way to assert content
     * because `isLoading` flips on `run.succeeded` while the drain loop
     * continues to reveal characters for another ~100–500ms.
     */
    private suspend fun waitForStreamSettled(
        vm: ChatViewModel,
        expected: String? = null,
        timeoutMs: Long = 8_000,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val loaded = !vm.isLoading.value
            val matched = expected?.let { exp ->
                vm.messages.lastOrNull { it.role == MessageRole.ASSISTANT && it.type == MessageType.MESSAGE }
                    ?.content == exp
            } ?: true
            if (loaded && matched) {
                // Give the drain a couple of ticks to commit any tail chars.
                kotlinx.coroutines.delay(150)
                return
            }
            kotlinx.coroutines.delay(20)
        }
        fail(
            "waitForStreamSettled timed out after ${timeoutMs}ms — " +
                "isLoading=${vm.isLoading.value}, expected=$expected, " +
                "lastAssistant=${vm.messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.content}, " +
                "messages=${dumpMessages(vm)}"
        )
    }

    private fun dumpMessages(vm: ChatViewModel): String =
        vm.messages.joinToString(" | ") { "${it.role}/${it.type}: ${it.content.take(40)}" }
}
