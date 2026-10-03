package com.makemore.agentfrontend.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executors

/** The controller → provider hooks used by slow on-device engines (prefetch, turn start). */
class VoiceControllerProviderHooksTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + main)

    @After
    fun tearDown() {
        scope.cancel()
        executor.shutdown()
    }

    private fun <T> onMain(block: () -> T): T = runBlocking(main) { block() }

    private class RecordingProvider : TTSProvider {
        override val name = "recording"
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val release = CompletableDeferred<Unit>()

        override suspend fun speak(text: String, options: TTSSpeakOptions) {
            events += "speak:$text"
            release.await()
        }

        override fun cancel() { events += "cancel" }
        override fun prefetch(text: String, options: TTSSpeakOptions) { events += "prefetch:$text" }
        override fun onTurnStart() { events += "turnStart" }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(5)
        }
    }

    @Test
    fun `chunks queued behind playing audio are offered for prefetch`() {
        val provider = RecordingProvider()
        val vc = VoiceController(provider, enabled = true, minChars = 10, scope = scope)
        onMain {
            vc.autoSpeakReplies.value = true
            vc.pushDelta("First sentence goes here. ")
        }
        waitFor { provider.events.contains("speak:First sentence goes here.") }
        onMain { vc.pushDelta("Second sentence goes here. ") }

        provider.release.complete(Unit)
        waitFor { provider.events.contains("speak:Second sentence goes here.") }

        assertEquals(
            listOf(
                "speak:First sentence goes here.",
                "prefetch:Second sentence goes here.",
                "speak:Second sentence goes here.",
            ),
            provider.events.toList(),
        )
    }

    @Test
    fun `reset signals a new turn to the provider`() {
        val provider = RecordingProvider()
        val vc = VoiceController(provider, enabled = true, scope = scope)
        onMain { vc.reset() }
        assertTrue(provider.events.contains("turnStart"))
    }
}
