package com.makemore.agentfrontend.voice.kokoro

import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.GZIPInputStream
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Local files of one voice's asset plan (verified copies in the cache). */
internal class KokoroLocalFiles(
    val model: File,
    val vocab: File,
    val voice: File,
    val lang: String,
    val gold: File,
    val goldGzip: Boolean,
    val silver: File,
    val silverGzip: Boolean,
    val g2pModel: File,
    val g2pVocab: File,
)

/** What one [KokoroSynthesizer.generate] call did (for metrics). */
data class KokoroSynthesisStats(val chunkCount: Int, val audioSeconds: Double, val synthSeconds: Double)

/**
 * A loaded Kokoro engine for one voice. [KokoroTTSProvider] drives it from a
 * single background thread, never the main thread.
 */
internal interface KokoroSynthesizer {
    val sampleRate: Int

    /**
     * Render [text] (blocking): G2P, chunking and ONNX inference. [onSamples]
     * receives mono float PCM per chunk, in order, as soon as each chunk is
     * rendered; return `false` from it to stop early. Throws on engine failure.
     */
    fun generate(text: String, voice: KokoroVoice, speed: Float, onSamples: (FloatArray) -> Boolean): KokoroSynthesisStats
}

/** A loaded engine that can add languages/voices on demand. */
internal interface KokoroEngineCore : KokoroSynthesizer, AutoCloseable {
    /** Load what [files] adds for its language and voice (no-op if already loaded). Blocking. */
    fun ensure(files: KokoroLocalFiles, voiceId: String)

    fun isLoaded(lang: String, voiceId: String): Boolean
}

/**
 * The loaded engine: one Kokoro ONNX session (shared), and lazily per
 * language a G2P (grown dictionaries + BART session) and per voice a style
 * pack. Thread-safe: G2P runs under a lock (its caches are not thread-safe),
 * ONNX sessions may run concurrently, and [close] waits for running calls.
 */
internal class KokoroRuntime private constructor(
    private val acoustic: KokoroAcousticModel,
    private val vocab: KokoroVocab,
): KokoroEngineCore {
    override val sampleRate: Int = KokoroVocab.SAMPLE_RATE

    private class LoadedG2P(val g2p: KokoroG2P, val bart: OrtBartStep)

    private val g2ps = ConcurrentHashMap<String, LoadedG2P>()
    private val voices = ConcurrentHashMap<String, KokoroVoicePack>()
    private val g2pLock = Any()
    private val life = ReentrantReadWriteLock()
    @Volatile private var closed = false

    companion object {
        /** Silence between newline-separated segments. */
        const val SEGMENT_PAUSE_SECONDS = 0.08

        private val NEWLINES = Regex("\n+")

        /** Load the shared Kokoro session and vocab (slow: seconds on a phone). */
        fun load(files: KokoroLocalFiles, threads: Int): KokoroRuntime {
            val vocab = KokoroVocab.parse(files.vocab.readText())
            val session = KokoroOrt.session(files.model, threads)
            return KokoroRuntime(KokoroAcousticModel(session), vocab)
        }

        private fun readText(file: File, gzip: Boolean): String {
            val input: InputStream = if (gzip) GZIPInputStream(file.inputStream().buffered(), 64 * 1024) else file.inputStream()
            return input.use { it.readBytes().toString(Charsets.UTF_8) }
        }

        fun loadDictionary(file: File, gzip: Boolean): HashMap<String, Any?> =
            growDictionary(KokoroJson.parseObject(readText(file, gzip)))
    }

    override fun ensure(files: KokoroLocalFiles, voiceId: String): Unit = life.read {
        check(!closed) { "closed" }
        if (!g2ps.containsKey(files.lang)) {
            synchronized(g2pLock) {
                if (!g2ps.containsKey(files.lang)) {
                    val golds = loadDictionary(files.gold, files.goldGzip)
                    val silvers = loadDictionary(files.silver, files.silverGzip)
                    val bartVocab = BartVocab.parse(files.g2pVocab.readText())
                    val bart = OrtBartStep(KokoroOrt.session(files.g2pModel, 1))
                    g2ps[files.lang] = LoadedG2P(KokoroG2P(files.lang, golds, silvers, BartG2P(bartVocab, bart)), bart)
                }
            }
        }
        voices.computeIfAbsent(voiceId) { KokoroVoicePack.load(files.voice) }
        Unit
    }

    override fun isLoaded(lang: String, voiceId: String): Boolean = !closed && g2ps.containsKey(lang) && voices.containsKey(voiceId)

    /** G2P + chunking of [text] for [lang]: the speech pieces, in order (newline segments separated). */
    fun pieces(text: String, lang: String): List<List<KokoroChunk>> {
        val g = g2ps[lang] ?: error("G2P for $lang is not loaded")
        val segments = text.split(NEWLINES).filter { it.isNotBlank() }
        return synchronized(g2pLock) {
            var first = true
            segments.map { seg ->
                val chunks = KokoroChunker.chunk(g.g2p(seg).tokens)
                val pieces = KokoroChunker.speechPieces(chunks, startsUtterance = first)
                if (pieces.isNotEmpty()) first = false
                pieces
            }
        }
    }

    override fun generate(text: String, voice: KokoroVoice, speed: Float, onSamples: (FloatArray) -> Boolean): KokoroSynthesisStats =
        life.read {
            check(!closed) { "closed" }
            val pack = voices[voice.id] ?: error("voice ${voice.id} is not loaded")
            val started = System.nanoTime()
            var chunks = 0
            var samples = 0L
            val segments = pieces(text, voice.language)
            for ((si, segment) in segments.withIndex()) {
                if (segment.isEmpty()) continue
                if (si > 0 && samples > 0) {
                    val pause = FloatArray((SEGMENT_PAUSE_SECONDS * sampleRate).toInt())
                    if (!onSamples(pause)) return@read stats(chunks, samples, started)
                    samples += pause.size
                }
                for (piece in segment) {
                    val ids = vocab.modelInputIds(piece.phonemes)
                    if (ids.size <= 2) continue
                    val audio = acoustic.synthesize(ids, pack.style(ids.size - 2), speed)
                    for (i in audio.indices) audio[i] = audio[i].coerceIn(-1f, 1f)
                    chunks++
                    samples += audio.size
                    if (!onSamples(audio)) return@read stats(chunks, samples, started)
                }
            }
            stats(chunks, samples, started)
        }

    private fun stats(chunks: Int, samples: Long, startedNanos: Long) = KokoroSynthesisStats(
        chunkCount = chunks,
        audioSeconds = samples.toDouble() / sampleRate,
        synthSeconds = (System.nanoTime() - startedNanos) / 1e9,
    )

    /** Releases native memory once running calls finish. */
    override fun close() {
        life.write {
            if (closed) return
            closed = true
            g2ps.values.forEach { runCatching { it.bart.close() } }
            g2ps.clear()
            voices.clear()
            runCatching { acoustic.close() }
        }
    }

    // Exposed for tests.
    internal fun g2p(lang: String): KokoroG2P? = g2ps[lang]?.g2p
}
