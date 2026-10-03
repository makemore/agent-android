package com.makemore.agentfrontend.voice.kokoro

import com.makemore.agentfrontend.voice.VoiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

/** End to end with the real [VoiceController] sentence chunking. */
class KokoroVoiceControllerTest {
    // Stands in for the main thread: VoiceController is driven from one thread.
    private val executor = Executors.newSingleThreadExecutor()
    private val main = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + main)

    @After
    fun tearDown() {
        scope.cancel()
        executor.shutdown()
    }

    private fun <T> onMain(block: () -> T): T = runBlocking(main) { block() }

    private fun controller(h: ProviderHarness) = VoiceController(
        provider = h.provider,
        enabled = true,
        minChars = 10,
        maxChars = 200,
        scope = scope,
    ).also { vc -> onMain { vc.autoSpeakReplies.value = true } }

    @Test
    fun `streamed reply is spoken chunk by chunk in order`() {
        val h = ProviderHarness()
        val vc = controller(h)

        onMain {
            vc.pushDelta("The first sentence is here. ")
            vc.pushDelta("Then a second sentence follows. And a")
            vc.finishTurn()
        }
        eventually { h.audio.events.count { it == "finish" } == 3 }

        val chunks = listOf("The first sentence is here.", "Then a second sentence follows.", "And a")
        assertEquals(chunks, h.synth.calls.map { it.text }.distinct())
        assertEquals(chunks.flatMap { listOf("$it#0", "$it#1") }, h.audio.writes())
        assertEquals(0, h.fallback.spoken.size)
        eventually { !vc.isSpeaking.value }
    }

    @Test
    fun `stop interrupts kokoro playback`() {
        val h = ProviderHarness(synth = FakeSynth(pieces = 2_000, pieceDelayMs = 2))
        val vc = controller(h)
        onMain { vc.pushDelta("A long sentence that keeps going. Another one waiting here.") }
        eventually { h.audio.writes().isNotEmpty() }

        onMain { vc.stop() }

        eventually { h.synth.stoppedEarly.isNotEmpty() }
        eventually { !vc.isSpeaking.value }
        Thread.sleep(50)
        assertTrue(h.audio.writes().none { it.startsWith("Another one") })
    }

    @Test
    fun `a new turn after an engine error returns to kokoro`() {
        val h = ProviderHarness(synth = FakeSynth(failOn = { it.startsWith("Broken") }))
        val vc = controller(h)
        onMain {
            vc.pushDelta("Broken sentence here. ")
            vc.finishTurn()
        }
        eventually { h.fallback.spoken == listOf("Broken sentence here.") }

        onMain {
            vc.reset() // new assistant turn
            vc.pushDelta("Healthy sentence now. ")
            vc.finishTurn()
        }
        eventually { h.audio.writes() == listOf("Healthy sentence now.#0", "Healthy sentence now.#1") }
        assertTrue(vc.isEnabled.value)
    }
}
