package com.makemore.agentfrontend.voice.kokoro

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class KokoroEngineTest {
    @Test
    fun `prepare downloads, verifies and loads with progress and states`() = runBlocking {
        val h = EngineHarness(installed = false)
        val reports = Collections.synchronizedList(mutableListOf<KokoroModelProgress>())
        h.engine.onModelProgress = { reports += it }

        withTimeout(10_000) { h.engine.prepare() }

        assertEquals(KokoroState.Ready, h.engine.state.value)
        val states = reports.map { it.state }.distinct()
        assertEquals(listOf(KokoroState.Downloading, KokoroState.Loading, KokoroState.Ready), states)
        val downloading = reports.filter { it.state == KokoroState.Downloading }
        assertTrue(downloading.zipWithNext().all { (a, b) -> b.fraction >= a.fraction })
        assertEquals(h.fetcher.assets.bytesFor("af_heart", "en-us"), reports.last().bytesTotal)
        assertEquals(1f, reports.last().fraction)
        assertEquals(listOf("en-us/af_heart"), h.core.ensured)
        assertEquals(1, h.loads.get())
        assertEquals(h.engine.modelProgress.value, reports.last())

        // Idempotent: already loaded, nothing fetched or loaded again.
        val requests = h.fetcher.requests.size
        h.engine.prepare()
        h.engine.prefetch()
        assertEquals(requests, h.fetcher.requests.size)
        assertEquals(1, h.loads.get())
    }

    @Test
    fun `concurrent prepare calls share one download`() = runBlocking {
        val h = EngineHarness(installed = false)
        val a = h.engine.startPrepare(h.engine.voice)
        val b = h.engine.startPrepare(h.engine.voice)
        withTimeout(10_000) { a.await(); b.await() }
        assertEquals(1, h.fetcher.requests.count { it.path == "model/kokoro-v1.0-q8.onnx" })
    }

    @Test
    fun `failures are reported with a value-free reason`() {
        val h = EngineHarness(installed = false)
        h.fetcher.error = KokoroAssetException("http_503")
        h.now = 42
        val e = runCatching { runBlocking { h.engine.prepare() } }.exceptionOrNull()
        assertEquals("http_503", (e as KokoroAssetException).reason)
        assertEquals(KokoroState.Failed("http_503"), h.engine.state.value)
        assertEquals(42, h.engine.lastFailureAtMillis)
    }

    @Test
    fun `engine that cannot load reports load_failed`() {
        val h = EngineHarness(loadError = UnsatisfiedLinkError("no native library for this ABI"))
        val e = runCatching { runBlocking { h.engine.prepare() } }.exceptionOrNull()
        assertEquals("load_failed", (e as KokoroAssetException).reason)
        assertEquals(KokoroState.Failed("load_failed"), h.engine.state.value)
    }

    @Test
    fun `already downloaded voice starts as ready without network`() {
        val h = EngineHarness(installed = true)
        eventually { h.engine.state.value == KokoroState.Ready }
        assertTrue(h.fetcher.requests.isEmpty())
        assertNull("not loaded until used", h.engine.loadedSynthesizer(h.engine.voice))
    }

    @Test
    fun `british voice uses the en-gb assets`() = runBlocking {
        val h = EngineHarness(installed = false, options = KokoroOptions(baseUrl = FakeFetcher.BASE, voice = "bf_emma"))
        assertEquals("en-gb", h.engine.voice.language)
        h.engine.prepare()
        assertEquals(listOf("en-gb/bf_emma"), h.core.ensured)
        assertTrue(h.fetcher.paths().none { it.contains("en-us") })
    }

    @Test
    fun `unknown configured voice uses af_heart`() {
        val h = EngineHarness(options = KokoroOptions(baseUrl = FakeFetcher.BASE, voice = "nope"))
        assertEquals("af_heart", h.engine.voice.id)
    }

    @Test
    fun `voices come from voices json`() = runBlocking {
        val h = EngineHarness(installed = false)
        val voices = h.engine.voices()
        assertEquals(28, voices.size)
        assertEquals(KokoroVoices.all, voices)
        assertEquals(listOf("af_heart", "bf_emma"), voices.filter { it.suggested }.map { it.id })
        assertEquals("en-gb", voices.first { it.id == "bm_george" }.language)
        assertEquals(listOf("manifest.json", "voices/voices.json"), h.fetcher.paths())
    }

    @Test
    fun `voices fall back to the built-in list offline`() = runBlocking {
        val h = EngineHarness(installed = false)
        h.fetcher.error = java.io.IOException("offline")
        assertEquals(KokoroVoices.all, h.engine.voices())
    }

    @Test
    fun `delete unloads and removes every file`() = runBlocking {
        val h = EngineHarness(installed = false)
        h.engine.prepare()
        assertTrue(h.engine.downloadedBytes > 0)
        h.engine.deleteDownloadedModel()
        assertEquals(0L, h.engine.downloadedBytes)
        assertEquals(KokoroState.NotDownloaded, h.engine.state.value)
        assertEquals(1, h.core.closed.get())
        assertNull(h.engine.loadedSynthesizer(h.engine.voice))
    }

    @Test
    fun `the last chat closing unloads the engine but keeps the files`() = runBlocking {
        val h = ProviderHarness()
        h.provider.speak("Hello.")
        assertNotNull(h.engine.loadedSynthesizer(h.engine.voice))
        h.provider.shutdown()
        eventually { h.core.closed.get() == 1 }
        assertNull(h.engine.loadedSynthesizer(h.engine.voice))
        assertTrue(h.engine.isDownloaded(h.engine.voice))
    }

    @Test
    fun `built-in catalogue matches voices json`() {
        val parsed = KokoroVoices.parse(KokoroGoldenTest.resource("voices.json"))
        assertEquals(KokoroVoices.all, parsed)
        assertEquals("af_heart", KokoroVoices.default.id)
        assertEquals("Heart (American English, female)", KokoroVoices.default.label)
        assertEquals("George (British English, male)", KokoroVoices.find(" BM_George ")!!.label)
        assertNull(KokoroVoices.find("21m00Tcm4TlvDq8ikWAM")) // an ElevenLabs id
        assertNull(KokoroVoices.find("ef_dora")) // not in the English asset set
        val d = KokoroVoices.find("bf_emma")!!.toDescriptor()
        assertEquals("kokoro", d.labels?.get("engine"))
        assertEquals("en-gb", d.labels?.get("language"))
        assertEquals("true", d.labels?.get("suggested"))
    }
}
