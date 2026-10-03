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
import org.junit.Assert.assertTrue
import org.junit.Test

class KokoroTTSProviderTest {
    private val secret = "My account number is 12345"

    @Test
    fun `speaks each chunk on the synthesis thread and plays chunks in order`() = runBlocking {
        val h = ProviderHarness(core = FakeCore(pieces = 3))
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
        assertEquals(listOf("First sentence.", "Second sentence."), h.core.calls.map { it.text })
        // (coroutine debug mode suffixes thread names with " @coroutine#N")
        assertTrue(h.core.calls.all { it.thread.startsWith("agent-kokoro-tts") && it.thread != testThread })
        assertEquals(1, h.e.loads.get())
        assertEquals(0, h.fallbackCreated.get())
        assertTrue("no network when the voice is installed", h.fetcher.requests.isEmpty())
    }

    @Test
    fun `reports time to first audio and synthesis metrics`() = runBlocking {
        val h = ProviderHarness(core = FakeCore(pieces = 3))
        h.provider.speak("Measure me.")
        val m = h.metrics.single()
        assertEquals(3, m.chunkCount)
        assertTrue(m.firstAudioMs >= 0)
        assertEquals(0.03, m.audioSeconds, 1e-9)
        assertTrue(h.logs.any { it.startsWith("kokoro: first audio") && it.contains("3 chunks") })
        assertTrue(h.logs.none { it.contains("Measure me") })
    }

    @Test
    fun `blank text is ignored`() = runBlocking {
        val h = ProviderHarness()
        h.provider.speak("   ")
        assertTrue(h.core.calls.isEmpty())
        assertEquals(0, h.audio.opened.get())
        assertEquals(0, h.e.loads.get())
    }

    @Test
    fun `prefetch renders the next chunk while the current one plays`() = runBlocking {
        val h = ProviderHarness()
        val gate = CompletableDeferred<Unit>().also { h.audio.finishGate = it }

        val first = async(Dispatchers.IO) { h.provider.speak("Chunk A.") }
        eventually { h.audio.writes().size == 2 } // A fully written, held in finish()
        h.provider.prefetch("Chunk B.")
        eventually { h.core.calls.any { it.text == "Chunk B." } }
        assertTrue("B must not play before A finishes", h.audio.writes().none { it.startsWith("Chunk B.") })

        gate.complete(Unit)
        first.await()
        withTimeout(5_000) { h.provider.speak("Chunk B.") }

        assertEquals(1, h.core.calls.count { it.text == "Chunk B." })
        assertEquals(listOf("Chunk A.#0", "Chunk A.#1", "Chunk B.#0", "Chunk B.#1"), h.audio.writes())
    }

    @Test
    fun `chunk being spoken is synthesised before earlier prefetches`() = runBlocking {
        val h = ProviderHarness()
        assertTrue(h.provider.prepare())
        h.provider.prefetch("Later chunk.")
        assertTrue("nothing renders until a chunk is spoken", h.core.calls.isEmpty())

        withTimeout(5_000) {
            h.provider.speak("Now chunk.")
            h.provider.speak("Later chunk.")
        }

        assertEquals(listOf("Now chunk.", "Later chunk."), h.core.calls.map { it.text })
        assertEquals(listOf("Now chunk.#0", "Now chunk.#1", "Later chunk.#0", "Later chunk.#1"), h.audio.writes())
    }

    @Test
    fun `prefetch with different options is re-rendered`() = runBlocking {
        val h = ProviderHarness(installedVoices = listOf("af_heart", "am_michael"))
        h.engine.synthesizerFor(KokoroVoices.find("am_michael")!!)
        val gate = CompletableDeferred<Unit>().also { h.audio.finishGate = it }
        val first = async(Dispatchers.IO) { h.provider.speak("One.") }
        eventually { h.audio.writes().size == 2 }
        h.provider.prefetch("Two.", TTSSpeakOptions(voiceId = "am_michael"))
        gate.complete(Unit)
        first.await()

        withTimeout(5_000) { h.provider.speak("Two.", TTSSpeakOptions(voiceId = "af_heart")) }

        val two = h.core.calls.filter { it.text == "Two." }
        assertEquals("af_heart", two.last().voice)
        assertEquals(listOf("Two.#0", "Two.#1"), h.audio.writes().filter { it.startsWith("Two.") })
    }

    @Test
    fun `cancel stops playback and synthesis promptly`() = runBlocking {
        val h = ProviderHarness(core = FakeCore(pieces = 2_000, pieceDelayMs = 2))
        val speaking = async(Dispatchers.IO) { runCatching { h.provider.speak("A very long chunk.") } }
        eventually { h.audio.writes().isNotEmpty() }
        h.provider.prefetch("Queued chunk.")

        val started = System.currentTimeMillis()
        h.provider.cancel()
        val result = withTimeout(2_000) { speaking.await() }

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertTrue("speak() returned within 1s of cancel", System.currentTimeMillis() - started < 1_000)
        eventually { h.core.stoppedEarly.contains("A very long chunk.") }
        assertTrue(h.audio.events.contains("stop"))
        assertFalse(h.audio.events.contains("finish"))
        Thread.sleep(50)
        assertTrue("prefetched chunk dropped", h.core.calls.none { it.text == "Queued chunk." })
        assertEquals(0, h.fallback.spoken.size)
    }

    @Test
    fun `missing voice falls back to the system voice and starts the download`() = runBlocking {
        val h = ProviderHarness(installed = false)

        withTimeout(5_000) { h.provider.speak(secret) }

        assertEquals(listOf(secret), h.fallback.spoken)
        assertTrue(h.core.calls.isEmpty())
        eventually { h.engine.state.value == KokoroState.Ready }
        // Only asset files are fetched; text never leaves the device.
        val paths = h.fetcher.paths()
        assertEquals("manifest.json", paths.first())
        assertTrue(paths.all { it == "manifest.json" || it.startsWith("model/") || it.startsWith("voices/") || it.startsWith("g2p/en-us/") })
        assertTrue(h.logs.none { it.contains(secret) || it.contains("12345") })
    }

    @Test
    fun `stays on the system voice for the rest of the turn once it fell back`() = runBlocking {
        val h = ProviderHarness(installed = false)
        h.provider.speak("Before download.")
        eventually { h.engine.state.value == KokoroState.Ready }

        h.provider.speak("Same turn, after download.")
        assertEquals(listOf("Before download.", "Same turn, after download."), h.fallback.spoken)
        assertTrue(h.core.calls.isEmpty())

        h.provider.onTurnStart()
        withTimeout(5_000) { h.provider.speak("Next turn.") }
        assertEquals(listOf("Next turn."), h.core.calls.map { it.text })
        assertEquals(1, h.fetcher.requests.count { it.path == "model/kokoro-v1.0-q8.onnx" })
    }

    @Test
    fun `turn start loads a downloaded voice in the background`() = runBlocking {
        val h = ProviderHarness()
        h.provider.onTurnStart()
        eventually { h.engine.loadedSynthesizer(h.engine.voice) != null }
        assertEquals(1, h.e.loads.get())
        h.provider.speak("Ready already.")
        assertEquals(1, h.e.loads.get())
        assertEquals(0L, h.metrics.single().loadMs)
    }

    @Test
    fun `turn start begins the download when auto download is on`() {
        val h = ProviderHarness(installed = false)
        h.provider.onTurnStart()
        eventually { h.engine.state.value == KokoroState.Ready }
        assertTrue(h.fallback.spoken.isEmpty())
    }

    @Test
    fun `auto download can be turned off - no network at all`() = runBlocking {
        val h = ProviderHarness(installed = false, options = KokoroOptions(baseUrl = FakeFetcher.BASE, autoDownload = false))
        h.provider.speak("Hello there.")
        h.provider.onTurnStart()
        h.provider.speak("Hello again.")
        Thread.sleep(50)
        assertEquals(listOf("Hello there.", "Hello again."), h.fallback.spoken)
        assertTrue(h.fetcher.requests.isEmpty())
        assertEquals(KokoroState.NotDownloaded, h.engine.state.value)
    }

    @Test
    fun `failed downloads are retried only after a back-off`() = runBlocking {
        val h = ProviderHarness(installed = false)
        h.fetcher.error = KokoroAssetException("http_503")
        h.e.now = 1_000
        h.provider.speak("One.")
        eventually { h.engine.state.value == KokoroState.Failed("http_503") }

        h.provider.onTurnStart()
        h.e.now = 1_000 + 60_000
        h.provider.speak("Two.")
        Thread.sleep(50)
        assertEquals(1, h.fetcher.requests.size)

        h.provider.onTurnStart()
        h.e.now = 1_000 + KokoroTTSProvider.DOWNLOAD_RETRY_AFTER_MS
        h.provider.speak("Three.")
        eventually { h.fetcher.requests.size >= 2 }
    }

    @Test
    fun `engine error falls back for the turn with a value-free log`() = runBlocking {
        val h = ProviderHarness(core = FakeCore(failOn = { it == secret }))

        withTimeout(5_000) {
            h.provider.speak(secret)
            h.provider.speak("Rest of the turn.")
        }

        assertEquals(listOf(secret, "Rest of the turn."), h.fallback.spoken)
        assertEquals(listOf(secret), h.core.calls.map { it.text })
        assertTrue(h.logs.any { it.contains("synthesis failed (IllegalStateException)") })
        assertTrue(h.logs.none { it.contains(secret) || it.contains("12345") })

        h.provider.onTurnStart()
        withTimeout(5_000) { h.provider.speak("New turn.") }
        assertEquals(listOf(secret, "New turn."), h.core.calls.map { it.text })
        assertEquals(listOf("New turn.#0", "New turn.#1"), h.audio.writes())
    }

    @Test
    fun `engine that cannot load falls back for the turn`() = runBlocking {
        val h = ProviderHarness(loadError = UnsatisfiedLinkError("no native library for this ABI"))
        withTimeout(5_000) {
            h.provider.speak("One.")
            h.provider.speak("Two.")
        }
        assertEquals(listOf("One.", "Two."), h.fallback.spoken)
        assertEquals(1, h.e.loads.get())
        assertTrue(h.logs.any { it.contains("engine unavailable (load_failed)") })
        assertTrue(h.fetcher.requests.isEmpty())
    }

    @Test
    fun `voice selection uses kokoro ids and the language follows the voice`() = runBlocking {
        val george = ProviderHarness(installedVoices = listOf("bm_george"), voiceId = "bm_george")
        george.provider.speak("Cheerio.")
        assertEquals("bm_george", george.core.calls.single().voice)
        assertEquals("en-gb", george.core.calls.single().language)

        val unknown = ProviderHarness(voiceId = "21m00Tcm4TlvDq8ikWAM")
        unknown.provider.speak("Hi.")
        assertEquals("af_heart", unknown.core.calls.single().voice)
        assertTrue(unknown.logs.any { it.contains("not a Kokoro voice") })

        val optionsDefault = ProviderHarness(
            installedVoices = listOf("am_michael"),
            options = KokoroOptions(baseUrl = FakeFetcher.BASE, voice = "am_michael"),
        )
        optionsDefault.provider.speak("Hey.")
        assertEquals("am_michael", optionsDefault.core.calls.single().voice)
    }

    @Test
    fun `a per-call voice that is not downloaded falls back and fetches it`() = runBlocking {
        val h = ProviderHarness()
        h.provider.speak("Hello.", TTSSpeakOptions(voiceId = "bf_emma"))
        assertEquals(listOf("Hello."), h.fallback.spoken)
        eventually { h.engine.isDownloaded(KokoroVoices.find("bf_emma")!!) }
        assertTrue(h.fetcher.paths().contains("g2p/en-gb/g2p.onnx"))
    }

    @Test
    fun `emotion nudges speaking rate`() = runBlocking {
        val h = ProviderHarness(options = KokoroOptions(baseUrl = FakeFetcher.BASE, speed = 1.0f))
        h.provider.speak("Great news.", TTSSpeakOptions(emotion = Emotion("happy", 1.0)))
        h.provider.speak("Sorry.", TTSSpeakOptions(emotion = Emotion("sad", 1.0)))
        h.provider.speak("Plain.")
        val speeds = h.core.calls.map { it.speed }
        assertTrue(speeds[0] > 1f && speeds[1] < 1f)
        assertEquals(1f, speeds[2], 0.0001f)
    }

    @Test
    fun `lists the English kokoro voices`() = runBlocking {
        val voices = ProviderHarness().provider.listVoices()
        assertEquals(28, voices.size)
        assertEquals("af_heart", voices[0].id)
        assertTrue(voices.all { it.labels?.get("engine") == "kokoro" })
        assertEquals("kokoro", ProviderHarness().provider.name)
    }

    @Test
    fun `cancel and turn start reach the fallback`() = runBlocking {
        val h = ProviderHarness(installed = false, options = KokoroOptions(baseUrl = FakeFetcher.BASE, autoDownload = false))
        h.provider.speak("x.")
        h.provider.cancel()
        h.provider.onTurnStart()
        assertEquals(1, h.fallback.cancels.get())
        assertEquals(1, h.fallback.turnStarts.get())
    }

    @Test
    fun `deleted voice falls back`() = runBlocking {
        val h = ProviderHarness(options = KokoroOptions(baseUrl = FakeFetcher.BASE, autoDownload = false))
        h.provider.speak("Before.")
        h.engine.deleteDownloadedModel()
        h.provider.onTurnStart()
        h.provider.speak("After.")
        assertEquals(listOf("After."), h.fallback.spoken)
        assertEquals(1, h.core.closed.get())
        assertTrue(h.audio.opened.get() > 0)
    }
}
