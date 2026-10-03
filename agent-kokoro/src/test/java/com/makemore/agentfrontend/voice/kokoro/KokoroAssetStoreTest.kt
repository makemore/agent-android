package com.makemore.agentfrontend.voice.kokoro

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections

class KokoroAssetStoreTest {
    private fun store(fetcher: FakeFetcher = FakeFetcher(), dir: File = tempDir()) =
        KokoroAssetStore(dir, FakeFetcher.BASE, fetcher)

    private fun failure(block: suspend () -> Unit): KokoroAssetException =
        runCatching { runBlocking { block() } }.exceptionOrNull() as? KokoroAssetException
            ?: throw AssertionError("expected a KokoroAssetException")

    @Test
    fun `language follows the voice prefix and only that language is fetched`() = runBlocking {
        val f = FakeFetcher()
        val s = store(f)
        val m = s.manifest()
        val us = m.plan("af_heart", KokoroVoices.languageOf("af_heart")!!)
        val gb = m.plan("bf_emma", KokoroVoices.languageOf("bf_emma")!!)
        assertEquals("en-us", us.lang)
        assertEquals("en-gb", gb.lang)
        assertEquals("g2p/en-us/gold.json.gz", us.gold.path) // gzip copy preferred
        assertEquals("g2p/en-gb/silver.json.gz", gb.silver.path)
        assertEquals("voices/bf_emma.bin", gb.voice.path)

        val progress = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
        s.download(gb.all) { done, total -> progress += done to total }
        val fetched = f.paths().toSet()
        assertTrue(fetched.contains("g2p/en-gb/g2p.onnx"))
        assertTrue("no en-us files", fetched.none { it.contains("en-us") })
        assertTrue("no plain JSON dictionaries", fetched.none { it.endsWith("gold.json") || it.endsWith("silver.json") })
        assertTrue("no other voices", fetched.none { it.startsWith("voices/") && it != "voices/bf_emma.bin" && it != "voices/voices.json" })
        assertEquals(f.assets.bytesFor("bf_emma", "en-gb"), gb.totalBytes)
        assertEquals(gb.totalBytes to gb.totalBytes, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b.first >= a.first })
        assertTrue(s.isCached(gb))
        assertFalse(s.isCached(us))
        assertTrue(KokoroVoices.languageOf("zf_xiaoxiao") == null)
    }

    @Test
    fun `verified files are cached by sha256 and not fetched again`() = runBlocking {
        val f = FakeFetcher()
        val dir = tempDir()
        val s = store(f, dir)
        val plan = s.manifest().plan("af_heart", "en-us")
        s.download(plan.all) { _, _ -> }
        val first = f.requests.size

        // A new store over the same directory (e.g. next app launch): no network at all.
        val again = store(f, dir)
        val cachedPlan = again.cachedManifest()!!.plan("af_heart", "en-us")
        assertTrue(again.isCached(cachedPlan))
        again.download(cachedPlan.all) { _, _ -> }
        assertEquals(first, f.requests.size)
        val model = again.cached(cachedPlan.model)!!
        assertEquals(cachedPlan.model.sha256, model.name)
        assertArrayEquals(f.assets.files.getValue("model/kokoro-v1.0-q8.onnx"), model.readBytes())
        assertEquals(plan.totalBytes, again.downloadedBytes())
    }

    @Test
    fun `a file whose hash does not match the manifest is rejected`() {
        val f = FakeFetcher()
        val tampered = f.assets.files.getValue("voices/af_heart.bin").copyOf().also { it[100] = 99 }
        f.bodies["voices/af_heart.bin"] = tampered
        val s = store(f)
        val e = failure { s.download(s.manifest().plan("af_heart", "en-us").all) { _, _ -> } }
        assertEquals("checksum_mismatch", e.reason)
        val voice = s.cachedManifest()!!.file("voices/af_heart.bin")
        assertNull("not cached", s.cached(voice))
        assertFalse("partial removed", File(s.directory, "partial/${voice.sha256}.part").exists())
    }

    @Test
    fun `a manifest listing a different hash is enforced too`() {
        val f = FakeFetcher()
        f.manifest = f.assets.manifest(override = mapOf("model/vocab.json" to "0".repeat(64)))
        val s = store(f)
        val e = failure { s.download(s.manifest().plan("af_heart", "en-us").all) { _, _ -> } }
        assertEquals("checksum_mismatch", e.reason)
    }

    @Test
    fun `a file of the wrong size is rejected before it is stored`() {
        val f = FakeFetcher()
        f.bodies["g2p/en-us/g2p.onnx"] = ByteArray(25_000)
        val s = store(f)
        val e = failure { s.download(s.manifest().plan("af_heart", "en-us").all) { _, _ -> } }
        assertEquals("size_mismatch", e.reason)
    }

    @Test
    fun `an interrupted download resumes with a range request`() = runBlocking {
        val f = FakeFetcher()
        f.failAfter["model/kokoro-v1.0-q8.onnx"] = 100_000
        val s = store(f)
        val plan = s.manifest().plan("af_heart", "en-us")
        val e = runCatching { s.download(plan.all) { _, _ -> } }.exceptionOrNull()
        assertTrue(e is java.io.IOException)
        assertNull(s.cached(plan.model))

        s.download(plan.all) { _, _ -> }
        val modelRequests = f.requests.filter { it.path == plan.model.path }
        assertEquals(listOf(0L, 100_000L), modelRequests.map { it.offset })
        assertArrayEquals(f.assets.files.getValue(plan.model.path), s.cached(plan.model)!!.readBytes())
        assertEquals(1, f.requests.count { it.path == plan.vocab.path })
    }

    @Test
    fun `a server that ignores the range restarts the file`() = runBlocking {
        val f = FakeFetcher()
        f.failAfter["model/kokoro-v1.0-q8.onnx"] = 50_000
        val s = store(f)
        val plan = s.manifest().plan("af_heart", "en-us")
        runCatching { s.download(plan.all) { _, _ -> } }
        f.ignoreRange = true
        s.download(plan.all) { _, _ -> }
        assertEquals(listOf(0L, 50_000L), f.requests.filter { it.path == plan.model.path }.map { it.offset })
        assertArrayEquals(f.assets.files.getValue(plan.model.path), s.cached(plan.model)!!.readBytes())
    }

    @Test
    fun `clear deletes cached and partial files`() = runBlocking {
        val f = FakeFetcher()
        f.failAfter["model/kokoro-v1.0-q8.onnx"] = 10_000
        val s = store(f)
        runCatching { s.download(s.manifest().plan("af_heart", "en-us").all) { _, _ -> } }
        assertTrue(s.downloadedBytes() > 10_000)
        s.clear()
        assertEquals(0, s.downloadedBytes())
        assertNull(s.cachedManifest())
    }

    @Test
    fun `unknown voices and bad manifests are reported`() {
        val s = store()
        val e = failure { s.manifest().plan("zf_xiaoxiao", "en-us") }
        assertEquals("invalid_manifest", e.reason)

        val bad = FakeFetcher().apply { manifest = "{not json".toByteArray() }
        assertEquals("invalid_manifest", failure { store(bad).manifest() }.reason)

        val unsafe = FakeFetcher().apply {
            manifest = String(assets.manifest()).replace("\"model/vocab.json\",\"size\"", "\"../vocab.json\",\"size\"").toByteArray()
        }
        assertEquals("invalid_manifest", failure { store(unsafe).manifest() }.reason)
    }

    @Test
    fun `http fetcher refuses cleartext and reads file urls`() {
        val e = runCatching { HttpKokoroFetcher().open("http://example.test/manifest.json", 0) }.exceptionOrNull()
        assertEquals("insecure_url", (e as KokoroAssetException).reason)

        val file = File(tempDir(), "x.bin").apply { writeBytes(ByteArray(10) { it.toByte() }) }
        HttpKokoroFetcher().open(file.toURI().toString(), 4).use { r ->
            assertEquals(4, r.offset)
            assertEquals(6L, r.contentLength)
            assertArrayEquals(byteArrayOf(4, 5, 6, 7, 8, 9), r.body.readBytes())
        }
    }

    @Test
    fun `the real v1 manifest plans the documented download sizes`() {
        val root = KokoroGoldenTest.assetsDir
        val m = KokoroManifest.parse(
            root?.let { File(it, "manifest.json").readText() } ?: return, // optional: only with -PkokoroAssets
        )
        val us = m.plan("af_heart", "en-us")
        val gb = m.plan("bf_emma", "en-gb")
        println("download en-us (af_heart): ${us.totalBytes} bytes; en-gb (bf_emma): ${gb.totalBytes} bytes")
        assertTrue(us.gold.path.endsWith(".gz"))
        assertTrue(us.totalBytes in 97_000_000L..98_000_000L)
        assertTrue(gb.totalBytes in 97_000_000L..98_500_000L)
    }
}
