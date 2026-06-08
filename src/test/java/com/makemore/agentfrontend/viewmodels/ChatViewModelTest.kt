package com.makemore.agentfrontend.viewmodels

import com.makemore.agentfrontend.configuration.ChatWidgetConfig
import com.makemore.agentfrontend.networking.APIClient
import com.makemore.agentfrontend.services.InMemoryStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var server: MockWebServer

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
    fun `ephemeral mode does not rehydrate stale conversationId`() {
        val storage = InMemoryStorage()
        storage.set("chat_widget_conversation_id", "stale-server-id")

        val config = ChatWidgetConfig(
            backendUrl = server.url("/").toString().trimEnd('/'),
            agentKey = "test-agent",
            ephemeral = true
        )
        val apiClient = APIClient(config = config, storage = storage)
        val vm = ChatViewModel(config, apiClient, storage)

        assertNull(
            "Ephemeral VM should not rehydrate a stale server conversationId",
            vm.conversationId.value
        )
    }

    @Test
    fun `non-ephemeral mode rehydrates conversationId`() {
        val storage = InMemoryStorage()
        storage.set("chat_widget_conversation_id", "server-id-123")

        val config = ChatWidgetConfig(
            backendUrl = server.url("/").toString().trimEnd('/'),
            agentKey = "test-agent",
            ephemeral = false
        )
        val apiClient = APIClient(config = config, storage = storage)
        val vm = ChatViewModel(config, apiClient, storage)

        assertEquals(
            "Non-ephemeral VM should rehydrate the saved conversationId",
            "server-id-123",
            vm.conversationId.value
        )
    }

    @Test
    fun `model selector is opt-in and off by default`() {
        // The composer model pill (the only entry point to the model
        // selector) is gated on showModelSelector, which defaults to off.
        assertFalse(ChatWidgetConfig().showModelSelector)
        assertTrue(ChatWidgetConfig(showModelSelector = true).showModelSelector)
    }
}
