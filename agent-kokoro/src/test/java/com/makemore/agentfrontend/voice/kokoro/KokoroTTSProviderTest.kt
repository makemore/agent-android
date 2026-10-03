package com.makemore.agentfrontend.voice.kokoro

import com.makemore.agentfrontend.voice.Emotion
import com.makemore.agentfrontend.voice.TTSSpeakOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KokoroTTSProviderTest {
    private val secret = "My account number is 12345"

    @Test
    fun `speaks each chunk on the synthesis thread and plays chunks in order`() = runBlocking {
        val h = ProviderHarness(synth = FakeSynth(pieces = 3))
        val testThread = Thread.currentThread().name

        withTimeout(5_000) {
            h.provider.speak("First sentence.")
            h.provider.speak("Second sentence.")
        }

        assertEquals(
            listOf("First sentence.#0", "First sentence.#1", "First sentence.#2",
                "Second sentence.#0", "Second sentence.#1", "Second sentence.#2"),
            h.audio.writes(),
        )
        assertEquals(2, h.audio.events.count { it == "finish" })
        assertEquals(listOf("First sentence.", "Second sentence."), h.synth.calls.map { it.text })
        // (coroutine debug mode suffixes thread names with " @coroutine#N")
        assertTrue(h.synth.calls.all { it.thread.startsWith("agent-kokoro-tts") && it.thread != testThread })
        assertEquals(1, h.loads.get())
        assertEquals(0, h.fallbackCreated.get())
        assertTrue("no network when the model is installed", h.fetcher.urls.isEmpty())
    }

    @Test
    fun `blank text is ignored`() = runBlocking {
        val h = ProviderHarness()
        h.provider.speak("   ")
        assertTrue(h.synth.calls.isEmpty())
        assertEquals(0, h.audio.opened.get())
        assertEquals(0, h.loads.get())
    }

    @Test
    fun `prefetch renders the next chunk while the current one plays`() = runBlocking {
        val h = ProviderHarness()
        val gate = CompletableDeferred<Unit>().also { h.audio.finishGate = it }

        val first = async(Dispatchers.IO) { h.provider.speak("Chunk A.") }
        eventually { h.audio.writes().size == 2 } // A fully written, held in finish()
        h.provider.prefetch("Chunk B.")
        eventually { h.synth.calls.any { it.text == "Chunk B." } }
        assertTrue("B must not play before A finishes", h.audio.writes().none { it.startsWith("Chunk B.") })

        gate.complete(Unit)
        first.await()
        withTimeout(5_000) { h.provider.speak("Chunk B.") }

        assertEquals(1, h.synth.calls.count { it.text == "Chunk B." })
        assertEquals(listOf("Chunk A.#0", "Chunk A.#1", "Chunk B.#0", "Chunk B.#1"), h.audio.writes())
    }

    @Test
    fun `chunk being spoken is synthesised before earlier prefetches`() = runBlocking {
        val h = ProviderHarness()
        assertTrue(h.provider.prepare())
        h.provider.prefetch("Later chunk.")
        assertTrue("nothing renders until a chunk is spoken", h.synth.calls.isEmpty())

        withTimeout(5_000) {
            h.provider.speak("Now chunk.")
            h.provider.speak("Later chunk.")
        }

        assertEquals(listOf("Now chunk.", "Later chunk."), h.synth.calls.map { it.text })
        assertEquals(listOf("Now chunk.#0", "Now chunk.#1", "Later chunk.#0", "Later chunk.#1"), h.audio.writes())
    }

    @Test
    fun `prefetch with different options is re-rendered`() = runBlocking {
        val h = ProviderHarness()
        val gate = CompletableDeferred<Unit>().also { h.audio.finishGate = it }
        val first = async(Dispatchers.IO) { h.provider.speak("One.") }
        eventually { h.audio.writes().size == 2 }
        h.provider.prefetch("Two.", TTSSpeakOptions(voiceId = "am_michael"))
        gate.complete(Unit)
        first.await()

        withTimeout(5_000) { h.provider.speak("Two.", TTSSpeakOptions(voiceId = "bf_emma")) }

        val two = h.synth.calls.filter { it.text == "Two." }
        assertEquals(21, two.last().speakerId)
        assertEquals(listOf("Two.#0", "Two.#1"), h.audio.writes().filter { it.startsWith("Two.") })
    }

    @Test
    fun `cancel stops playback and synthesis promptly`() = runBlocking {
        val h = ProviderHarness(synth = FakeSynth(pieces = 2_000, pieceDelayMs = 2))
        val speaking = async(Dispatchers.IO) { runCatching { h.provider.speak("A very long chunk.") } }
        eventually { h.audio.writes().isNotEmpty() }
        h.provider.prefetch("Queued chunk.")

        val started = System.currentTimeMillis()
        h.provider.cancel()
        val result = withTimeout(2_000) { speaking.await() }

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertTrue("speak() returned within 1s of cancel", System.currentTimeMillis() - started < 1_000)
        eventually { h.synth.stoppedEarly.contains("A very long chunk.") }
        assertTrue(h.audio.events.contains("stop"))
        assertFalse(h.audio.events.contains("finish"))
        Thread.sleep(50)
        assertTrue("prefetched chunk dropped", h.synth.calls.none { it.text == "Queued chunk." })
        assertEquals(0, h.fallback.spoken.size)
    }

    @Test
    fun `missing model falls back to the system voice and starts the download`() = runBlocking {
        val h = ProviderHarness(installed = false)

        withTimeout(5_000) { h.provider.speak(secret) }

        assertEquals(listOf(secret), h.fallback.spoken)
        assertTrue(h.synth.calls.isEmpty())
        // Only the model URL is fetched; text never leaves the device.
        assertEquals(KokoroModelState.Ready, withTimeout(5_000) { h.manager.startDownload().await() })
        assertEquals(listOf(KokoroModel.DEFAULT_MODEL_URL), h.fetcher.urls)
        assertTrue(h.logs.none { it.contains(secret) || it.contains("12345") })
    }

    @Test
    fun `stays on the system voice for the rest of the turn once it fell back`() = runBlocking {
        val h = ProviderHarness(installed = false)
        h.provider.speak("Before download.")
        withTimeout(5_000) { h.manager.startDownload().await() }
        assertTrue(h.manager.isAvailable)

        h.provider.speak("Same turn, after download.")
        assertEquals(listOf("Before download.", "Same turn, after download."), h.fallback.spoken)
        assertTrue(h.synth.calls.isEmpty())

        h.provider.onTurnStart()
        withTimeout(5_000) { h.provider.speak("Next turn.") }
        assertEquals(listOf("Next turn."), h.synth.calls.map { it.text })
        assertEquals(1, h.fetcher.urls.size)
    }

    @Test
    fun `auto download can be turned off - no network at all`() = runBlocking {
        val h = ProviderHarness(installed = false, options = KokoroOptions(autoDownload = false))
        h.provider.speak("Hello there.")
        h.provider.onTurnStart()
        h.provider.speak("Hello again.")
        Thread.sleep(50)
        assertEquals(listOf("Hello there.", "Hello again."), h.fallback.spoken)
        assertTrue(h.fetcher.urls.isEmpty())
        assertEquals(KokoroModelState.NotDownloaded, h.manager.state.value)
    }

    @Test
    fun `failed downloads are retried only after a back-off`() = runBlocking {
        val h = ProviderHarness(installed = false, fetcher = FakeFetcher(error = KokoroDownloadException("http_503")))
        h.now = 1_000
        h.provider.speak("One.")
        eventually { h.manager.state.value == KokoroModelState.Failed("http_503") }

        h.provider.onTurnStart()
        h.now = 1_000 + 60_000
        h.provider.speak("Two.")
        Thread.sleep(50)
        assertEquals(1, h.fetcher.urls.size)

        h.provider.onTurnStart()
        h.now = 1_000 + KokoroTTSProvider.DOWNLOAD_RETRY_AFTER_MS
        h.provider.speak("Three.")
        eventually { h.fetcher.urls.size == 2 }
    }

    @Test
    fun `engine error falls back for the turn with a value-free log`() = runBlocking {
        val h = ProviderHarness(synth = FakeSynth(failOn = { it == secret }))

        withTimeout(5_000) {
            h.provider.speak(secret)
            h.provider.speak("Rest of the turn.")
        }

        assertEquals(listOf(secret, "Rest of the turn."), h.fallback.spoken)
        assertEquals(listOf(secret), h.synth.calls.map { it.text })
        assertTrue(h.logs.any { it.contains("synthesis failed (IllegalStateException)") })
        assertTrue(h.logs.none { it.contains(secret) || it.contains("12345") })

        h.provider.onTurnStart()
        withTimeout(5_000) { h.provider.speak("New turn.") }
        assertEquals(listOf(secret, "New turn."), h.synth.calls.map { it.text })
        assertEquals(listOf("New turn.#0", "New turn.#1"), h.audio.writes())
    }

    @Test
    fun `engine that cannot load falls back without retrying every chunk`() = runBlocking {
        val h = ProviderHarness(loadError = UnsatisfiedLinkError("no libsherpa-onnx-jni for this ABI"))
        withTimeout(5_000) {
            h.provider.speak("One.")
            h.provider.onTurnStart()
            h.provider.speak("Two.")
        }
        assertEquals(listOf("One.", "Two."), h.fallback.spoken)
        assertEquals(1, h.loads.get())
        assertTrue(h.logs.any { it.contains("engine failed to load (UnsatisfiedLinkError)") })
        assertTrue(h.fetcher.urls.isEmpty())
    }

    @Test
    fun `voice selection uses kokoro ids with sensible fallbacks`() = runBlocking {
        val george = ProviderHarness(voiceId = "bm_george")
        george.provider.speak("Cheerio.")
        assertEquals(26, george.synth.calls.single().speakerId)
        assertEquals("en", george.synth.calls.single().language)

        val unknown = ProviderHarness(voiceId = "21m00Tcm4TlvDq8ikWAM")
        unknown.provider.speak("Hi.")
        assertEquals(3, unknown.synth.calls.single().speakerId) // af_heart
        assertTrue(unknown.logs.any { it.contains("not a Kokoro voice") })

        val perCall = ProviderHarness()
        perCall.provider.speak("Hola.", TTSSpeakOptions(voiceId = "ef_dora"))
        assertEquals(28, perCall.synth.calls.single().speakerId)
        assertEquals("es", perCall.synth.calls.single().language)

        val optionsDefault = ProviderHarness(options = KokoroOptions(defaultVoiceId = "am_michael"))
        optionsDefault.provider.speak("Hey.")
        assertEquals(16, optionsDefault.synth.calls.single().speakerId)

        // em_santa (53) is missing from older 53-voice archives.
        val older = ProviderHarness(synth = FakeSynth(numSpeakers = 53), voiceId = "em_santa")
        older.provider.speak("Ho ho.")
        assertEquals(3, older.synth.calls.single().speakerId)
    }

    @Test
    fun `emotion nudges speaking rate`() = runBlocking {
        val h = ProviderHarness(options = KokoroOptions(speed = 1.0f))
        h.provider.speak("Great news.", TTSSpeakOptions(emotion = Emotion("happy", 1.0)))
        h.provider.speak("Sorry.", TTSSpeakOptions(emotion = Emotion("sad", 1.0)))
        h.provider.speak("Plain.")
        val speeds = h.synth.calls.map { it.speed }
        assertTrue(speeds[0] > 1f && speeds[1] < 1f)
        assertEquals(1f, speeds[2], 0.0001f)
    }

    @Test
    fun `lists all kokoro voices`() = runBlocking {
        val voices = ProviderHarness().provider.listVoices()
        assertEquals(54, voices.size)
        assertEquals("af_heart", voices[3].id)
        assertTrue(voices.all { it.labels?.get("engine") == "kokoro" })
        assertEquals("kokoro", ProviderHarness().provider.name)
    }

    @Test
    fun `cancel and turn start reach the fallback`() = runBlocking {
        val h = ProviderHarness(installed = false, options = KokoroOptions(autoDownload = false))
        h.provider.speak("x.")
        h.provider.cancel()
        h.provider.onTurnStart()
        assertEquals(1, h.fallback.cancels.get())
        assertEquals(1, h.fallback.turnStarts.get())
    }

    @Test
    fun `shutdown releases the engine on the synthesis thread`() = runBlocking {
        val h = ProviderHarness()
        h.provider.speak("Bye.")
        h.provider.shutdown()
        eventually { h.synth.released.get() == 1 }
        assertEquals("agent-kokoro-tts", h.synth.releasedOnThread)
        assertNull(h.fallback.shutdowns.get().takeIf { it > 0 }) // fallback never created
    }

    @Test
    fun `deleted model releases the engine and falls back`() = runBlocking {
        val h = ProviderHarness(options = KokoroOptions(autoDownload = false))
        h.provider.speak("Before.")
        h.manager.delete()
        h.provider.onTurnStart()
        h.provider.speak("After.")
        assertEquals(listOf("After."), h.fallback.spoken)
        eventually { h.synth.released.get() == 1 }
        assertNotEquals(0, h.audio.opened.get())
    }
}
