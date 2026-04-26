package com.makemore.agentfrontend.streaming

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import com.makemore.agentfrontend.configuration.ChatWidgetConfig
import com.makemore.agentfrontend.networking.APIClient
import com.makemore.agentfrontend.services.InMemoryStorage
import com.makemore.agentfrontend.ui.ChatWidgetView
import com.makemore.agentfrontend.viewmodels.ChatViewModel
import org.junit.Rule
import org.junit.Test

/**
 * Level C streaming UI tests for Android.
 *
 * Mirrors `ChatStreamingUITests.swift` on iOS: drives the real
 * `ChatWidgetView` composable via `ChatViewModel` + `APIClient` against the
 * local Python stub server (`clients/test-stub-server/`). The stub must be
 * running before these tests are executed — see `clients/STREAMING_TESTS.md`
 * for the runner script. The stub URL defaults to the standard emulator
 * loopback (`http://10.0.2.2:8765`) and can be overridden by passing
 * `-PandroidTestStubUrl=...` (which becomes the `STUB_SERVER_URL`
 * instrumentation argument).
 */
class ChatStreamingUITests {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun stubServerUrl(): String {
        val args = InstrumentationRegistry.getArguments()
        return args.getString("STUB_SERVER_URL") ?: "http://10.0.2.2:8765"
    }

    /** Launch the widget and auto-send [prompt]. Returns the VM so
     *  multi-turn tests can drive subsequent prompts via [sendNext]
     *  (assertion-gated) once the previous turn has finished rendering. */
    private fun launch(fixture: String, prompt: String = "Hello agent"): ChatViewModel {
        val cfg = ChatWidgetConfig(
            backendUrl = stubServerUrl(),
            agentKey = "test-agent",
            showSystemPicker = false,
            showTasksTab = false,
            showTTSButton = false,
            enableTTS = false,
            enableVoice = false,
            enableFiles = false,
            metadata = mapOf("test_fixture" to fixture)
        )
        val storage = InMemoryStorage()
        val api = APIClient(cfg, storage)
        val vm = ChatViewModel(cfg, api, storage)

        composeRule.setContent {
            ChatWidgetView(viewModel = vm, config = cfg)
        }

        composeRule.runOnIdle { vm.sendMessage(prompt) }
        return vm
    }

    /** Send the next prompt on the main thread once the previous turn
     *  has visibly settled. Mirrors a real host-app pattern: wait for the
     *  in-flight reply to render, then dispatch the next user message. */
    private fun sendNext(vm: ChatViewModel, prompt: String) {
        composeRule.runOnIdle { vm.sendMessage(prompt) }
    }

    /** Poll the Compose tree for at least one node whose text contains
     *  [substring]. We deliberately do not call `assertIsDisplayed` because
     *  rendered markdown can split a phrase across multiple `Text` nodes
     *  (paragraph + inline span) and the substring then matches both —
     *  presence-in-tree is the meaningful check for streaming output.
     *
     *  The message list is a `LazyColumn`, so off-screen rows are *not*
     *  composed and won't appear in the semantics tree at all. When a
     *  poll finds nothing, we ask the column to scroll to a node matching
     *  the substring; if the row is in the underlying state list it'll
     *  compose, if not the scroll quietly fails and the next poll retries
     *  once more text has streamed in. */
    private fun assertText(substring: String, timeoutMs: Long = 20_000) {
        val matcher = hasText(substring, substring = true, ignoreCase = true)
        composeRule.waitUntil(timeoutMs) {
            if (composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()) {
                return@waitUntil true
            }
            try {
                composeRule.onAllNodes(hasScrollAction()).onFirst()
                    .performScrollToNode(matcher)
                true
            } catch (_: AssertionError) {
                false
            }
        }
    }

    /** Assert that [substring] appears inside a bubble whose `testTag` is
     *  `chat.message.user` (when [isUser] is true) or
     *  `chat.message.assistant`. Lets the multi-turn test verify that
     *  user prompts and assistant replies land on the correct side of the
     *  bubble layout, not just that the strings appear somewhere.
     *  Mirrors the iOS `assertTextInBubble` helper. Scrolls the
     *  `LazyColumn` between polls so virtualised off-screen bubbles
     *  still compose and land in the semantics tree (see [assertText]). */
    private fun assertTextInBubble(
        substring: String,
        isUser: Boolean,
        timeoutMs: Long = 30_000
    ) {
        val tag = if (isUser) "chat.message.user" else "chat.message.assistant"
        val textMatcher = hasText(substring, substring = true, ignoreCase = true)
        val bubbleMatcher = hasTestTag(tag).and(hasAnyDescendant(textMatcher))
        composeRule.waitUntil(timeoutMs) {
            if (composeRule.onAllNodes(bubbleMatcher).fetchSemanticsNodes().isNotEmpty()) {
                return@waitUntil true
            }
            try {
                composeRule.onAllNodes(hasScrollAction()).onFirst()
                    .performScrollToNode(bubbleMatcher)
                true
            } catch (_: AssertionError) {
                false
            }
        }
    }

    // -- Scenarios -----------------------------------------------------------

    @Test
    fun simpleStreamingRendersFinalMessage() {
        launch(fixture = "simple_streaming")
        assertText("Hello agent")
        assertText("Hello there! How can I help you today?")
    }

    @Test
    fun toolCallContentBlocksRender() {
        launch(fixture = "tool_call_with_content_blocks", prompt = "Find me a place to stay")
        assertText("Find me a place to stay")
        assertText("Beach House")
        assertText("Mountain Cabin")
        assertText("I found 2 options that match.")
    }

    @Test
    fun multiAgentHandoffSuppressesParentEcho() {
        launch(fixture = "sai_multi_agent_handoff", prompt = "I'm feeling overwhelmed")
        assertText("I'm feeling overwhelmed")
        assertText("let me bring in our therapist")
        assertText("Could you tell me a little about what's on your mind?")
    }

    @Test
    fun multiAgentWithBlocks() {
        launch(fixture = "sai_multi_agent_with_blocks", prompt = "Show me my account")
        assertText("Show me my account")
        assertText("Account in good standing")
        assertText("Pro Monthly")
    }

    @Test
    fun runFailedSurfacesError() {
        launch(fixture = "run_failed", prompt = "Crash on purpose")
        assertText("Crash on purpose")
        assertText("Upstream provider unavailable")
    }

    /** Long, slow demo run for hand-watching. Not for CI — the fixture
     *  deliberately paces itself over ~25–30 s and exercises three sub-agent
     *  handoffs plus most content-block renderers (callout, cardList, table,
     *  status, card, actionButtons, collapsible, code, divider). After the
     *  opener, two scripted user follow-ups are auto-sent (chained via the
     *  stub server's `next_fixture` to demo_followup_1 / demo_followup_2)
     *  so we also verify that user prompts render in `chat.message.user`
     *  bubbles and assistant replies in `chat.message.assistant` bubbles. */
    @Test
    fun demoBigConversation() {
        val prompt1 = "Plan me a 3-day trip to Tokyo"
        val prompt2 = "Yes, please book it."
        val prompt3 = "Add the trip to my calendar."
        val vm = launch(fixture = "demo_big_conversation", prompt = prompt1)

        // --- Turn 1: opener ---
        assertTextInBubble(prompt1, isUser = true, timeoutMs = 10_000)
        // Parent intro
        assertText("let me put my team on this", timeoutMs = 30_000)
        // Researcher sub-agent: callout + cardList + table cells
        assertText("Did you know?", timeoutMs = 60_000)
        assertText("Shibuya", timeoutMs = 60_000)
        assertText("Senso-ji Temple", timeoutMs = 60_000)
        // Booking sub-agent: hotel card + status
        assertText("Shibuya Sky Hotel", timeoutMs = 90_000)
        assertText("Live availability", timeoutMs = 90_000)
        // Itinerary writer sub-agent: streamed markdown
        assertText("Day 1", timeoutMs = 120_000)
        assertText("teamLab Planets in the morning", timeoutMs = 120_000)
        // Wrap-up callout + action buttons render via ContentBlockRenderer,
        // which now also carries the chat.message.assistant testTag so we
        // can verify they're on the assistant side of the bubble layout.
        assertTextInBubble("Trip ready", isUser = false, timeoutMs = 150_000)
        assertTextInBubble("Confirm everything", isUser = false, timeoutMs = 150_000)
        // The streamed assistant wrap-up message lands in a real bubble.
        assertTextInBubble("lock in the hotel", isUser = false, timeoutMs = 150_000)

        // --- Turn 2: confirm booking (chained via demo_followup_1) ---
        // sendMessage is gated by isLoading; dispatch from runOnIdle once
        // the wrap-up text has rendered guarantees the prior stream's
        // onComplete has flipped isLoading back to false.
        sendNext(vm, prompt2)
        assertTextInBubble(prompt2, isUser = true, timeoutMs = 30_000)
        assertTextInBubble("Hotel booked", isUser = false, timeoutMs = 30_000)
        assertTextInBubble("add the trip to your calendar", isUser = false, timeoutMs = 30_000)

        // --- Turn 3: add to calendar (chained via demo_followup_2) ---
        sendNext(vm, prompt3)
        assertTextInBubble(prompt3, isUser = true, timeoutMs = 30_000)
        assertTextInBubble("Calendar updated", isUser = false, timeoutMs = 30_000)
        assertTextInBubble("have a great trip", isUser = false, timeoutMs = 30_000)
    }
}
