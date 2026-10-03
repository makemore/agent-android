package com.makemore.agentfrontend.voice.kokoro

import com.makemore.agentfrontend.voice.TTSProvider
import com.makemore.agentfrontend.voice.TTSSpeakOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

internal fun tempDir(): File = Files.createTempDirectory("agent-kokoro-test").toFile().apply { deleteOnExit() }

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
    GZIPOutputStream(out).use { it.write(bytes) }
}.toByteArray()

/** A small fake asset folder (same layout and manifest format as kokoro/v1). */
internal class FakeAssets(voiceIds: List<String> = listOf("af_heart", "am_michael", "bf_emma", "bm_george")) {
    val files = LinkedHashMap<String, ByteArray>()

    init {
        files["model/kokoro-v1.0-q8.onnx"] = ByteArray(300_000) { (it * 7).toByte() }
        files["model/vocab.json"] = """{"format":"kokoro-vocab/1","vocab":{" ":16,"a":43}}""".toByteArray()
        files["voices/voices.json"] = KokoroGoldenTest.resource("voices.json").toByteArray()
        for (id in voiceIds) files["voices/$id.bin"] = ByteArray(KokoroVoicePack.ROWS * KokoroVoicePack.STYLE_DIM * 4) { (it + id.hashCode()).toByte() }
        for (lang in listOf("en-us", "en-gb")) {
            val gold = """{"hello":"h${lang}"}""".toByteArray()
            val silver = """{"world":"w${lang}"}""".toByteArray()
            files["g2p/$lang/gold.json"] = gold
            files["g2p/$lang/gold.json.gz"] = gzip(gold)
            files["g2p/$lang/silver.json"] = silver
            files["g2p/$lang/silver.json.gz"] = gzip(silver)
            files["g2p/$lang/g2p.onnx"] = ByteArray(20_000) { (it + lang.hashCode()).toByte() }
            files["g2p/$lang/g2p-vocab.json"] = """{"format":"bart-g2p-vocab/1","lang":"$lang"}""".toByteArray()
        }
    }

    /** Manifest bytes; [override] replaces the listed sha256 of a path (to simulate corruption). */
    fun manifest(override: Map<String, String> = emptyMap()): ByteArray {
        val entries = files.entries.joinToString(",") { (path, bytes) ->
            """{"path":"$path","size":${bytes.size},"sha256":"${override[path] ?: sha256(bytes)}","role":"x","license":"Apache-2.0"}"""
        }
        val g2p = listOf("en-us", "en-gb").joinToString(",") { lang ->
            """"$lang":{"gold":"g2p/$lang/gold.json","silver":"g2p/$lang/silver.json","model":"g2p/$lang/g2p.onnx","vocab":"g2p/$lang/g2p-vocab.json"}"""
        }
        return """{"format":"kokoro-asset-manifest/1","name":"kokoro-en","version":"v1",
            "entry":{"model":"model/kokoro-v1.0-q8.onnx","vocab":"model/vocab.json","voices":"voices/voices.json","g2p":{$g2p}},
            "files":[$entries]}""".toByteArray()
    }

    fun bytesFor(voiceId: String, lang: String): Long =
        listOf(
            "model/kokoro-v1.0-q8.onnx", "model/vocab.json", "voices/voices.json", "voices/$voiceId.bin",
            "g2p/$lang/gold.json.gz", "g2p/$lang/silver.json.gz", "g2p/$lang/g2p.onnx", "g2p/$lang/g2p-vocab.json",
        ).sumOf { files.getValue(it).size.toLong() }
}

/** Serves [assets] under [BASE]; records every (path, offset) request. */
internal class FakeFetcher(
    val assets: FakeAssets = FakeAssets(),
    var manifest: ByteArray = assets.manifest(),
) : KokoroFetcher {
    data class Request(val path: String, val offset: Long)

    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())

    /** Throw this from open(). */
    @Volatile var error: Exception? = null

    /** Paths whose next response breaks after this many bytes (one-shot). */
    val failAfter: MutableMap<String, Int> = Collections.synchronizedMap(HashMap())

    /** Serve the whole file even when a range is asked for. */
    @Volatile var ignoreRange = false

    /** Replace a file's body (e.g. tampered bytes). */
    val bodies: MutableMap<String, ByteArray> = Collections.synchronizedMap(HashMap())

    @Volatile var gate: CompletableDeferred<Unit>? = null

    fun paths(): List<String> = synchronized(requests) { requests.map { it.path } }

    override fun open(url: String, offset: Long): KokoroFetchResponse {
        require(url.startsWith(BASE)) { "unexpected url" }
        val path = url.removePrefix(BASE)
        requests += Request(path, offset)
        error?.let { throw it }
        val data = when (path) {
            "manifest.json" -> manifest
            else -> bodies[path] ?: assets.files[path] ?: throw KokoroAssetException("http_404")
        }
        val start = if (ignoreRange) 0 else offset.coerceAtMost(data.size.toLong()).toInt()
        val limit = failAfter.remove(path)
        val body: InputStream = object : InputStream() {
            private val inner = ByteArrayInputStream(data, start, data.size - start)
            private var served = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                gate?.let { runBlocking { it.await() } }
                if (limit != null && served >= limit) throw IOException("connection reset")
                val n = inner.read(b, off, minOf(len, 8192, if (limit != null) maxOf(1, limit - served) else Int.MAX_VALUE))
                if (n > 0) served += n
                return n
            }
        }
        return KokoroFetchResponse(body, (data.size - start).toLong(), start.toLong())
    }

    companion object {
        const val BASE = "https://assets.example.test/kokoro/v1/"
    }
}

/** Fake loaded engine. Samples are labelled `"<text>#<piece>"` for order assertions. */
internal class FakeCore(
    private val pieces: Int = 2,
    private val pieceDelayMs: Long = 0,
    private val failOn: (String) -> Boolean = { false },
) : KokoroEngineCore {
    data class Call(val text: String, val voice: String, val language: String, val speed: Float, val thread: String)

    override val sampleRate: Int = 24_000
    val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())
    val ensured: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val stoppedEarly: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val labels: MutableMap<FloatArray, String> = Collections.synchronizedMap(IdentityHashMap())
    val closed = AtomicInteger()
    private val loaded = Collections.synchronizedSet(HashSet<String>())

    override fun ensure(files: KokoroLocalFiles, voiceId: String) {
        ensured += "${files.lang}/$voiceId"
        loaded += "${files.lang}/$voiceId"
    }

    override fun isLoaded(lang: String, voiceId: String): Boolean = "$lang/$voiceId" in loaded

    override fun generate(text: String, voice: KokoroVoice, speed: Float, onSamples: (FloatArray) -> Boolean): KokoroSynthesisStats {
        calls += Call(text, voice.id, voice.language, speed, Thread.currentThread().name)
        if (failOn(text)) throw IllegalStateException("engine failure")
        for (i in 0 until pieces) {
            val samples = FloatArray(240) { 0.1f }
            labels[samples] = "$text#$i"
            if (!onSamples(samples)) {
                stoppedEarly += text
                return KokoroSynthesisStats(i + 1, 0.0, 0.0)
            }
            if (pieceDelayMs > 0) Thread.sleep(pieceDelayMs)
        }
        return KokoroSynthesisStats(pieces, pieces * 0.01, 0.001)
    }

    override fun close() {
        loaded.clear()
        closed.incrementAndGet()
    }
}

/** Records writes (by core label), finishes and stops. [finishGate] holds playback open. */
internal class FakeAudio(private val core: FakeCore) : PcmAudioOutput {
    val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val opened = AtomicInteger()
    @Volatile var finishGate: CompletableDeferred<Unit>? = null

    override fun open(sampleRate: Int): PcmPlayback {
        opened.incrementAndGet()
        return object : PcmPlayback {
            override suspend fun write(samples: FloatArray) {
                events += "write:${core.labels[samples]}"
            }

            override suspend fun finish() {
                finishGate?.await()
                events += "finish"
            }

            override fun stop() {
                events += "stop"
            }
        }
    }

    fun writes(): List<String> = synchronized(events) { events.filter { it.startsWith("write:") }.map { it.removePrefix("write:") } }
}

internal class FakeFallback : TTSProvider {
    override val name: String = "fake-system"
    val spoken: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val cancels = AtomicInteger()
    val turnStarts = AtomicInteger()
    val shutdowns = AtomicInteger()

    override suspend fun speak(text: String, options: TTSSpeakOptions) {
        spoken += text
    }

    override fun cancel() {
        cancels.incrementAndGet()
    }

    override fun onTurnStart() {
        turnStarts.incrementAndGet()
    }

    override fun shutdown() {
        shutdowns.incrementAndGet()
    }
}

/** A network whose meteredness the test controls; [becomeUnmetered] fires the waiting callbacks. */
internal class FakeNetwork(@Volatile var metered: Boolean = false) : KokoroNetworkMonitor {
    private val waiting = Collections.synchronizedList(mutableListOf<() -> Unit>())
    val watches = AtomicInteger()

    override fun isMetered(): Boolean = metered

    override fun whenUnmetered(onUnmetered: () -> Unit): AutoCloseable {
        watches.incrementAndGet()
        waiting += onUnmetered
        return AutoCloseable { waiting.remove(onUnmetered) }
    }

    fun becomeUnmetered() {
        metered = false
        val callbacks = synchronized(waiting) { waiting.toList().also { waiting.clear() } }
        callbacks.forEach { it() }
    }
}

/** An engine over [FakeFetcher] + [FakeCore]; [installed] pre-downloads [installedVoices]. */
internal class EngineHarness(
    val dir: File = tempDir(),
    installed: Boolean = true,
    installedVoices: List<String> = listOf("af_heart"),
    val fetcher: FakeFetcher = FakeFetcher(),
    val core: FakeCore = FakeCore(),
    val options: KokoroOptions = KokoroOptions(baseUrl = FakeFetcher.BASE),
    var now: Long = 0L,
    private val loadError: Throwable? = null,
    val network: FakeNetwork = FakeNetwork(),
) {
    val store = KokoroAssetStore(dir, options.baseUrl, fetcher)
    val loads = AtomicInteger()

    init {
        if (installed) {
            runBlocking {
                val m = store.manifest()
                for (id in installedVoices) {
                    val v = KokoroVoices.find(id)!!
                    store.download(m.plan(v.id, v.language).all) { _, _ -> }
                }
            }
            fetcher.requests.clear()
        }
    }

    val engine = KokoroTTSEngine(
        options = options,
        store = store,
        ioDispatcher = Dispatchers.IO,
        loader = { _, _ ->
            loads.incrementAndGet()
            loadError?.let { throw it }
            core
        },
        clock = { now },
        network = network,
    )
}

internal class ProviderHarness(
    installed: Boolean = true,
    installedVoices: List<String> = listOf("af_heart"),
    fetcher: FakeFetcher = FakeFetcher(),
    core: FakeCore = FakeCore(),
    options: KokoroOptions = KokoroOptions(baseUrl = FakeFetcher.BASE),
    voiceId: String? = null,
    loadError: Throwable? = null,
    network: FakeNetwork = FakeNetwork(),
) {
    val e = EngineHarness(
        installed = installed, installedVoices = installedVoices, fetcher = fetcher, core = core,
        options = options, loadError = loadError, network = network,
    )
    val engine get() = e.engine
    val fetcher get() = e.fetcher
    val core get() = e.core
    val audio = FakeAudio(core)
    val fallback = FakeFallback()
    val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val fallbackCreated = AtomicInteger()
    val metrics: MutableList<KokoroSpeechMetrics> = Collections.synchronizedList(mutableListOf())

    init {
        e.engine.onSpeechMetrics = { metrics += it }
    }

    val provider = KokoroTTSProvider(
        engine = e.engine,
        defaultVoiceId = voiceId,
        fallbackFactory = { fallbackCreated.incrementAndGet(); fallback },
        audioOutput = audio,
        synthExecutor = KokoroTTSProvider.newSynthesisExecutor(),
        playbackDispatcher = Dispatchers.IO,
        clock = { e.now },
        log = { logs += it },
    )
}

/** Poll until [condition] holds (real threads are involved). */
internal fun eventually(timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        if (System.currentTimeMillis() > deadline) throw AssertionError("condition not met within ${timeoutMs}ms")
        Thread.sleep(5)
    }
}
