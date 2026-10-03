package com.makemore.agentfrontend.voice.kokoro

import com.makemore.agentfrontend.voice.TTSProvider
import com.makemore.agentfrontend.voice.TTSSpeakOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger

internal fun tempDir(): File = Files.createTempDirectory("agent-kokoro-test").toFile().apply { deleteOnExit() }

/** Minimal files [KokoroModelFiles.from] requires, under [prefix]. */
internal val MODEL_ENTRIES: List<Pair<String, ByteArray>> = listOf(
    "model.int8.onnx" to ByteArray(2048) { it.toByte() },
    "voices.bin" to ByteArray(512) { 7 },
    "tokens.txt" to "a 1\nb 2\n".toByteArray(),
    "lexicon-us-en.txt" to "hello h e l o\n".toByteArray(),
    "espeak-ng-data/phontab" to ByteArray(16),
)

/** Build a sherpa-style `.tar.bz2` archive in memory. */
internal fun tarBz2(
    entries: List<Pair<String, ByteArray>> = MODEL_ENTRIES,
    prefix: String = "kokoro-int8-multi-lang-v1_0/",
): ByteArray {
    val bytes = ByteArrayOutputStream()
    TarArchiveOutputStream(BZip2CompressorOutputStream(bytes)).use { tar ->
        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
        for ((name, data) in entries) {
            val entry = TarArchiveEntry(prefix + name)
            entry.size = data.size.toLong()
            tar.putArchiveEntry(entry)
            tar.write(data)
            tar.closeArchiveEntry()
        }
    }
    return bytes.toByteArray()
}

/** Write an installed model into [dir] exactly as [KokoroModelManager] leaves it. */
internal fun installFakeModel(dir: File) {
    val install = File(dir, KokoroModel.INSTALL_DIR)
    for ((name, data) in MODEL_ENTRIES) {
        File(install, name).apply { parentFile?.mkdirs() }.writeBytes(data)
    }
    File(install, KokoroModel.INSTALLED_MARKER).writeText("sha256=test\n")
}

/** Records every URL it is asked for; serves [archive] or throws [error]. */
internal class FakeFetcher(
    var archive: ByteArray = tarBz2(),
    var error: Exception? = null,
    var declaredLength: Long? = null,
    var failAfterBytes: Int? = null,
) : KokoroModelFetcher {
    val urls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    var gate: CompletableDeferred<Unit>? = null

    override fun open(url: String): KokoroModelResponse {
        urls += url
        error?.let { throw it }
        val data = archive
        val body: InputStream = object : InputStream() {
            private val inner = ByteArrayInputStream(data)
            private var served = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                gate?.let { kotlinx.coroutines.runBlocking { it.await() } }
                val limit = failAfterBytes
                if (limit != null && served >= limit) throw java.io.IOException("connection reset")
                val n = inner.read(b, off, minOf(len, 4096))
                if (n > 0) served += n
                return n
            }
        }
        return KokoroModelResponse(body, declaredLength ?: data.size.toLong())
    }
}

/** Fake engine. Samples are labelled `"<text>#<piece>"` for order assertions. */
internal class FakeSynth(
    override val sampleRate: Int = KokoroModel.SAMPLE_RATE,
    override val numSpeakers: Int = 54,
    private val pieces: Int = 2,
    private val pieceDelayMs: Long = 0,
    private val failOn: (String) -> Boolean = { false },
) : KokoroSynthesizer {
    data class Call(val text: String, val speakerId: Int, val speed: Float, val language: String?, val thread: String)

    val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())
    val stoppedEarly: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val labels: MutableMap<FloatArray, String> = Collections.synchronizedMap(IdentityHashMap())
    val released = AtomicInteger()
    @Volatile var releasedOnThread: String? = null

    override fun generate(text: String, speakerId: Int, speed: Float, language: String?, onSamples: (FloatArray) -> Boolean) {
        calls += Call(text, speakerId, speed, language, Thread.currentThread().name)
        if (failOn(text)) throw IllegalStateException("engine failure")
        for (i in 0 until pieces) {
            val samples = FloatArray(8) { 0.1f }
            labels[samples] = "$text#$i"
            if (!onSamples(samples)) {
                stoppedEarly += text
                return
            }
            if (pieceDelayMs > 0) Thread.sleep(pieceDelayMs)
        }
    }

    override fun release() {
        releasedOnThread = Thread.currentThread().name
        released.incrementAndGet()
    }
}

/** Records writes (by synth label), finishes and stops. [finishGate] holds playback open. */
internal class FakeAudio(private val synth: FakeSynth) : PcmAudioOutput {
    val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val opened = AtomicInteger()
    @Volatile var finishGate: CompletableDeferred<Unit>? = null

    override fun open(sampleRate: Int): PcmPlayback {
        opened.incrementAndGet()
        return object : PcmPlayback {
            override suspend fun write(samples: FloatArray) {
                events += "write:${synth.labels[samples]}"
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

internal class ProviderHarness(
    val dir: File = tempDir(),
    installed: Boolean = true,
    val fetcher: FakeFetcher = FakeFetcher(),
    val synth: FakeSynth = FakeSynth(),
    val options: KokoroOptions = KokoroOptions(),
    voiceId: String? = null,
    var now: Long = 0L,
    private val loadError: Throwable? = null,
) {
    init {
        if (installed) installFakeModel(dir)
    }

    val manager = KokoroModelManager(dir, options.modelUrl, options.expectedSha256, fetcher, clock = { now })
    val audio = FakeAudio(synth)
    val fallback = FakeFallback()
    val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val loads = AtomicInteger()
    val fallbackCreated = AtomicInteger()

    val provider = KokoroTTSProvider(
        modelManager = manager,
        options = options,
        defaultVoiceId = voiceId,
        fallbackFactory = { fallbackCreated.incrementAndGet(); fallback },
        loader = KokoroSynthesizerLoader {
            loads.incrementAndGet()
            loadError?.let { throw it }
            synth
        },
        audioOutput = audio,
        synthExecutor = KokoroTTSProvider.newSynthesisExecutor(),
        playbackDispatcher = Dispatchers.IO,
        clock = { now },
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
