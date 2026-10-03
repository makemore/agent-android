package com.makemore.agentfrontend.voice.kokoro

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class KokoroModelManagerTest {
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun manager(dir: File = tempDir(), fetcher: FakeFetcher = FakeFetcher(), sha: String? = null, now: () -> Long = { 42L }) =
        KokoroModelManager(dir, "https://models.example/kokoro.tar.bz2", sha, fetcher, Dispatchers.IO, now)

    @Test
    fun `starts not downloaded and installs with progress`() = runBlocking {
        val fetcher = FakeFetcher(archive = tarBz2(MODEL_ENTRIES + ("blob.bin" to ByteArray(3_000_000).also { java.util.Random(1).nextBytes(it) })))
        val m = manager(fetcher = fetcher)
        assertEquals(KokoroModelState.NotDownloaded, m.state.value)
        assertFalse(m.isAvailable)
        assertNull(m.modelFiles())

        val seen = mutableListOf<KokoroModelState>()
        val collector = launch(Dispatchers.Unconfined) { m.state.collect { seen += it } }
        val result = withTimeout(10_000) { m.download() }
        collector.cancel()

        assertEquals(KokoroModelState.Ready, result)
        assertTrue(m.isAvailable)
        val files = m.modelFiles()!!
        assertEquals("model.int8.onnx", files.model.name)
        assertTrue(files.dataDir.resolve("phontab").isFile)
        assertEquals(listOf("lexicon-us-en.txt"), files.lexicons.map { it.name })
        assertEquals(listOf("https://models.example/kokoro.tar.bz2"), fetcher.urls)

        val progress = seen.filterIsInstance<KokoroModelState.Downloading>()
        assertTrue("expected intermediate progress, saw $seen", progress.size >= 2)
        assertEquals(progress.map { it.bytesDownloaded }.sorted(), progress.map { it.bytesDownloaded })
        assertEquals(1f, progress.last().fraction!!, 0.0001f)
        assertTrue(seen.contains(KokoroModelState.Installing))
        assertEquals(KokoroModelState.Ready, seen.last())
        // No temporary files left behind.
        assertFalse(File(m.directory, "download.part").exists())
        assertFalse(File(m.directory, "staging").exists())
    }

    @Test
    fun `installed model is reused from the cache without network`() = runBlocking {
        val dir = tempDir()
        val first = FakeFetcher()
        assertEquals(KokoroModelState.Ready, manager(dir, first).download())

        val second = FakeFetcher()
        val reopened = manager(dir, second)
        assertEquals(KokoroModelState.Ready, reopened.state.value)
        assertEquals(KokoroModelState.Ready, reopened.download())
        assertTrue(second.urls.isEmpty())
    }

    @Test
    fun `concurrent downloads share one transfer`() = runBlocking {
        val fetcher = FakeFetcher().apply { gate = CompletableDeferred() }
        val m = manager(fetcher = fetcher)
        val a = async(Dispatchers.IO) { m.download() }
        val b = async(Dispatchers.IO) { m.download() }
        m.startDownload()
        eventually { fetcher.urls.isNotEmpty() }
        fetcher.gate!!.complete(Unit)
        assertEquals(KokoroModelState.Ready, a.await())
        assertEquals(KokoroModelState.Ready, b.await())
        assertEquals(1, fetcher.urls.size)
    }

    @Test
    fun `checksum is verified when configured`() = runBlocking {
        val archive = tarBz2()
        assertEquals(KokoroModelState.Ready, manager(fetcher = FakeFetcher(archive), sha = sha256(archive).uppercase()).download())

        val bad = manager(fetcher = FakeFetcher(archive), sha = "0".repeat(64))
        assertEquals(KokoroModelState.Failed("checksum_mismatch"), bad.download())
        assertFalse(bad.isAvailable)
        assertFalse(File(bad.directory, KokoroModel.INSTALL_DIR).exists())
        assertEquals(42L, bad.lastFailureAtMillis)
    }

    @Test
    fun `http and network failures report value-free reasons`() = runBlocking {
        val http = manager(fetcher = FakeFetcher(error = KokoroDownloadException("http_404")))
        assertEquals(KokoroModelState.Failed("http_404"), http.download())

        val reset = manager(fetcher = FakeFetcher(failAfterBytes = 1))
        assertEquals(KokoroModelState.Failed("network"), reset.download())
        assertFalse(File(reset.directory, "download.part").exists())

        val short = manager(fetcher = FakeFetcher(declaredLength = 10_000_000))
        assertEquals(KokoroModelState.Failed("network"), short.download())
    }

    @Test
    fun `failed download can be retried`() = runBlocking {
        val fetcher = FakeFetcher(error = KokoroDownloadException("http_503"))
        val m = manager(fetcher = fetcher)
        assertEquals(KokoroModelState.Failed("http_503"), m.download())
        fetcher.error = null
        assertEquals(KokoroModelState.Ready, m.download())
        assertEquals(2, fetcher.urls.size)
    }

    @Test
    fun `archive entries cannot escape the model directory`() = runBlocking {
        val dir = tempDir()
        val evil = tarBz2(MODEL_ENTRIES + ("../../escaped.txt" to "x".toByteArray()))
        val m = manager(dir, FakeFetcher(evil))
        assertEquals(KokoroModelState.Failed("invalid_archive"), m.download())
        assertFalse(File(dir.parentFile, "escaped.txt").exists())
        assertFalse(File(dir, "escaped.txt").exists())
        assertFalse(m.isAvailable)
    }

    @Test
    fun `archive without the model files is rejected`() = runBlocking {
        val m = manager(fetcher = FakeFetcher(tarBz2(listOf("README.md" to "hi".toByteArray()))))
        assertEquals(KokoroModelState.Failed("invalid_archive"), m.download())

        val garbage = manager(fetcher = FakeFetcher("not an archive".toByteArray()))
        assertEquals(KokoroModelState.Failed("invalid_archive"), garbage.download())
    }

    @Test
    fun `archive top-level directory name does not matter`() = runBlocking {
        val m = manager(fetcher = FakeFetcher(tarBz2(prefix = "some-mirror-name/")))
        assertEquals(KokoroModelState.Ready, m.download())
        val flat = manager(fetcher = FakeFetcher(tarBz2(prefix = "")))
        assertEquals(KokoroModelState.Ready, flat.download())
    }

    @Test
    fun `delete removes the cached model`() = runBlocking {
        val m = manager()
        assertEquals(KokoroModelState.Ready, m.download())
        m.delete()
        assertEquals(KokoroModelState.NotDownloaded, m.state.value)
        assertFalse(File(m.directory, KokoroModel.INSTALL_DIR).exists())
        assertEquals(KokoroModelState.NotDownloaded, manager(m.directory).state.value)
    }

    @Test
    fun `cancelling a download returns to not downloaded`() = runBlocking {
        val fetcher = FakeFetcher().apply { gate = CompletableDeferred() }
        val m = manager(fetcher = fetcher)
        val job = m.startDownload()
        eventually { m.state.value is KokoroModelState.Downloading }
        m.cancelDownload()
        fetcher.gate!!.complete(Unit)
        runCatching { job.await() }
        eventually { m.state.value == KokoroModelState.NotDownloaded }
        assertFalse(m.isAvailable)
        assertFalse(File(m.directory, "download.part").exists())
    }

    @Test
    fun `default fetcher refuses cleartext urls without touching the network`() {
        try {
            HttpKokoroModelFetcher().open("http://example.com/kokoro.tar.bz2")
            fail("expected insecure_url")
        } catch (e: KokoroDownloadException) {
            assertEquals("insecure_url", e.reason)
        }
    }

    @Test
    fun `default url is sherpa-onnx's int8 multi-lang v1_0 release asset`() {
        assertEquals(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2",
            KokoroModel.DEFAULT_MODEL_URL,
        )
        assertEquals(KokoroModel.DEFAULT_MODEL_URL, KokoroOptions().modelUrl)
        assertNull(KokoroOptions().expectedSha256)
        assertTrue(KokoroOptions().autoDownload)
    }
}
