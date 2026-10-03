package com.makemore.agentfrontend.voice.kokoro

import android.util.Log
import com.makemore.agentfrontend.voice.Emotion
import com.makemore.agentfrontend.voice.TTSProvider
import com.makemore.agentfrontend.voice.TTSSpeakOptions
import com.makemore.agentfrontend.voice.VoiceDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * On-device neural TTS: Kokoro-82M (v1.0) on ONNX Runtime with our own
 * English G2P (no espeak).
 *
 * - Text never leaves the device. The only network access is the asset
 *   download done by [KokoroTTSEngine] (GETs of the configured base URL).
 * - Each [speak] call (one [com.makemore.agentfrontend.voice.VoiceController]
 *   sentence chunk) is converted to phonemes and split into pieces on a
 *   dedicated background thread; each piece is rendered and streamed to one
 *   [android.media.AudioTrack] as soon as it is ready, so playback starts
 *   after the first piece and the next renders while it plays. [prefetch]
 *   renders the next sentence while the current one plays.
 * - [cancel] stops playback and abandons queued synthesis promptly.
 * - Falls back to [fallback] (the local-only system voice) while the voice is
 *   not downloaded, if the engine cannot load, and — for the rest of the
 *   turn — after a synthesis/playback error. Logs carry reason codes and
 *   timings only, never text.
 */
class KokoroTTSProvider internal constructor(
    private val engine: KokoroTTSEngine,
    defaultVoiceId: String?,
    private val fallbackFactory: () -> TTSProvider,
    private val audioOutput: PcmAudioOutput,
    private val synthExecutor: ExecutorService,
    private val playbackDispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
    private val log: (String) -> Unit,
) : TTSProvider {

    /**
     * @param engine usually [KokoroTTS.engine].
     * @param defaultVoiceId a Kokoro voice id (e.g. `"bm_george"`); unknown ids
     *   use the engine's configured voice.
     * @param fallback builds the provider used when Kokoro cannot speak; it
     *   must itself keep text on the device (e.g.
     *   `AndroidTTSProvider(context, localOnly = true)`).
     */
    constructor(
        engine: KokoroTTSEngine,
        defaultVoiceId: String? = null,
        fallback: () -> TTSProvider,
    ) : this(
        engine = engine,
        defaultVoiceId = defaultVoiceId,
        fallbackFactory = fallback,
        audioOutput = AudioTrackOutput,
        synthExecutor = newSynthesisExecutor(),
        playbackDispatcher = Dispatchers.IO,
        clock = System::currentTimeMillis,
        log = { message -> Log.i(LOG_TAG, message) },
    )

    override val name: String = KokoroTTS.ENGINE_NAME

    private val synthDispatcher = synthExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob())
    private val lock = Any()
    private val defaultVoice: KokoroVoice

    // Guarded by [lock].
    private var shutDown = false
    private val prefetched = LinkedHashMap<String, Utterance>()
    private var current: Utterance? = null
    private var playback: PcmPlayback? = null
    private var fallbackProvider: TTSProvider? = null

    /** Set when Kokoro could not speak this turn; cleared by [onTurnStart] / [cancel]. */
    @Volatile private var fallbackForTurn = false

    init {
        val requested = KokoroVoices.find(defaultVoiceId)
        if (defaultVoiceId != null && requested == null) {
            log("kokoro: configured voice id is not a Kokoro voice; using the engine's voice")
        }
        defaultVoice = requested ?: engine.voice
        engine.providerCreated()
    }

    // -- TTSProvider ------------------------------------------------

    override suspend fun speak(text: String, options: TTSSpeakOptions) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val started = clock()
        val request = request(trimmed, options)
        val synth = engineForSpeech(request.voice)
        if (synth == null) {
            fallback().speak(trimmed, options)
            return
        }
        val loadMs = clock() - started
        val utterance = synchronized(lock) {
            val u = takePrefetchedLocked(request) ?: Utterance(request)
            current = u
            // This chunk first, then anything prefetched behind it, so the
            // single synthesis thread renders in playback order.
            launchLocked(u, synth)
            prefetched.values.forEach { launchLocked(it, synth) }
            u
        }
        try {
            val firstAudioAt = withContext(playbackDispatcher) { play(utterance, synth.sampleRate) }
            utterance.stats?.let { s ->
                val metrics = KokoroSpeechMetrics(
                    loadMs = loadMs,
                    firstAudioMs = firstAudioAt?.let { it - started } ?: -1,
                    chunkCount = s.chunkCount,
                    audioSeconds = s.audioSeconds,
                    synthSeconds = s.synthSeconds,
                )
                log(
                    "kokoro: first audio ${metrics.firstAudioMs} ms (load ${metrics.loadMs} ms), " +
                        "${s.chunkCount} chunks, ${"%.2f".format(Locale.ROOT, s.audioSeconds)} s audio " +
                        "in ${"%.2f".format(Locale.ROOT, s.synthSeconds)} s",
                )
                engine.reportMetrics(metrics)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            var reason: Throwable = e
            while (reason is KokoroEngineFailure && reason.cause != null) reason = reason.cause!!
            log("kokoro: synthesis failed (${reason.javaClass.simpleName}); using the system voice for this turn")
            fallbackForTurn = true
            dropPrefetched()
            fallback().speak(trimmed, options)
        } finally {
            utterance.cancel()
            synchronized(lock) { if (current === utterance) current = null }
        }
    }

    override fun prefetch(text: String, options: TTSSpeakOptions) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || fallbackForTurn) return
        val request = request(trimmed, options)
        val synth = engine.loadedSynthesizer(request.voice) ?: return
        synchronized(lock) {
            if (shutDown) return
            if (prefetched.size >= MAX_PREFETCH || prefetched.containsKey(trimmed)) return
            val u = Utterance(request)
            prefetched[trimmed] = u
            // Start now only if a chunk is already speaking (it was submitted
            // first); otherwise the next speak() starts it in order.
            if (current != null) launchLocked(u, synth)
        }
    }

    override fun cancel() {
        val (utterances, activePlayback) = synchronized(lock) {
            val all = prefetched.values.toList() + listOfNotNull(current)
            prefetched.clear()
            current = null
            (all to playback).also { playback = null }
        }
        utterances.forEach { it.cancel() }
        activePlayback?.stop()
        synchronized(lock) { fallbackProvider }?.cancel()
        fallbackForTurn = false
    }

    /** A new assistant turn: recover from a fallback and get the engine loaded (or downloading) for the reply. */
    override fun onTurnStart() {
        fallbackForTurn = false
        synchronized(lock) { fallbackProvider }?.onTurnStart()
        if (engine.loadedSynthesizer(defaultVoice) != null) return
        scope.launch(playbackDispatcher) {
            if (engine.isDownloaded(defaultVoice)) {
                runCatching { engine.startPrepare(defaultVoice) }
            } else {
                maybeStartDownload(defaultVoice)
            }
        }
    }

    override suspend fun listVoices(): List<VoiceDescriptor> = KokoroVoices.all.map { it.toDescriptor() }

    override fun shutdown() {
        cancel()
        val (wasShutDown, fallback) = synchronized(lock) {
            val was = shutDown
            shutDown = true
            was to fallbackProvider
        }
        if (wasShutDown) return
        synthExecutor.shutdown()
        scope.cancel()
        fallback?.shutdown()
        engine.providerReleased()
    }

    // -- Host helpers -------------------------------------------------

    /**
     * Load the engine now (e.g. when the chat opens) so the first reply does
     * not wait for it. Returns true when Kokoro is ready to speak. Does not
     * download; see [KokoroTTSEngine.prepare].
     */
    suspend fun prepare(): Boolean = engineForSpeech(defaultVoice, forTurn = false) != null

    // -- Internals ------------------------------------------------------

    private suspend fun engineForSpeech(voice: KokoroVoice, forTurn: Boolean = true): KokoroSynthesizer? {
        if (forTurn && fallbackForTurn) return null
        engine.loadedSynthesizer(voice)?.let { return it }
        val downloaded = withContext(playbackDispatcher) { engine.isDownloaded(voice) }
        if (!downloaded) {
            if (!forTurn) return null
            maybeStartDownload(voice)
            // Keep one voice for the whole reply even if the download
            // finishes part-way through it.
            fallbackForTurn = true
            log("kokoro: voice not on the device yet; using the system voice for this turn")
            return null
        }
        return try {
            engine.synthesizerFor(voice)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: KokoroAssetException) {
            log("kokoro: engine unavailable (${e.reason}); using the system voice for this turn")
            if (forTurn) fallbackForTurn = true
            null
        }
    }

    private fun maybeStartDownload(voice: KokoroVoice) {
        if (!engine.options.autoDownload) return
        val failed = engine.state.value is KokoroState.Failed
        if (failed && clock() - engine.lastFailureAtMillis < DOWNLOAD_RETRY_AFTER_MS) return
        engine.startPrepare(voice)
    }

    private fun fallback(): TTSProvider = synchronized(lock) {
        fallbackProvider ?: fallbackFactory().also { fallbackProvider = it }
    }

    private fun dropPrefetched() {
        val dropped = synchronized(lock) { prefetched.values.toList().also { prefetched.clear() } }
        dropped.forEach { it.cancel() }
    }

    private fun request(text: String, speakOptions: TTSSpeakOptions): SpeechRequest {
        val voice = KokoroVoices.find(speakOptions.voiceId) ?: defaultVoice
        val speed = (engine.options.speed * emotionSpeed(speakOptions.emotion)).coerceIn(0.5f, 2.0f)
        return SpeechRequest(text, voice, speed)
    }

    /** Remove and return the prefetched entry for [request]; entries queued before it are stale. */
    private fun takePrefetchedLocked(request: SpeechRequest): Utterance? {
        val match = prefetched[request.text] ?: return null
        val iterator = prefetched.values.iterator()
        while (iterator.hasNext()) {
            val u = iterator.next()
            iterator.remove()
            if (u === match) break
            u.cancel()
        }
        if (match.request != request) {
            match.cancel()
            return null
        }
        return match
    }

    private fun launchLocked(u: Utterance, synth: KokoroSynthesizer) {
        if (u.job != null || u.cancelled) return
        val r = u.request
        u.job = scope.launch(synthDispatcher) {
            try {
                u.stats = synth.generate(r.text, r.voice, r.speed) { samples ->
                    if (u.cancelled) {
                        false
                    } else {
                        if (samples.isNotEmpty()) u.chunks.trySend(samples)
                        true
                    }
                }
                u.chunks.close()
            } catch (t: Throwable) {
                u.chunks.close(KokoroEngineFailure(t))
            }
        }.also { job ->
            // Cancelled before it ever ran: still end the stream.
            job.invokeOnCompletion { cause -> if (cause != null) u.chunks.close(cause) }
        }
    }

    /** Plays [u]; returns the clock time of the first write (null if nothing played). */
    private suspend fun play(u: Utterance, sampleRate: Int): Long? {
        var out: PcmPlayback? = null
        var firstAudioAt: Long? = null
        try {
            for (samples in u.chunks) {
                val p = out ?: openPlayback(u, sampleRate).also { out = it }
                if (firstAudioAt == null) firstAudioAt = clock()
                p.write(samples)
            }
            out?.finish()
        } finally {
            out?.let { p ->
                p.stop()
                synchronized(lock) { if (playback === p) playback = null }
            }
        }
        return firstAudioAt
    }

    private fun openPlayback(u: Utterance, sampleRate: Int): PcmPlayback {
        val p = audioOutput.open(sampleRate)
        synchronized(lock) {
            if (u.cancelled || shutDown) {
                p.stop()
                throw CancellationException("cancelled")
            }
            playback = p
        }
        return p
    }

    private fun emotionSpeed(emotion: Emotion?): Float {
        if (emotion == null) return 1f
        val i = emotion.intensity.toFloat().coerceIn(0f, 1f)
        return when (emotion.name.lowercase(Locale.ROOT)) {
            "happy", "excited" -> 1f + 0.08f * i
            "sad", "concerned" -> 1f - 0.08f * i
            else -> 1f
        }
    }

    private data class SpeechRequest(val text: String, val voice: KokoroVoice, val speed: Float)

    private class Utterance(val request: SpeechRequest) {
        val chunks = Channel<FloatArray>(Channel.UNLIMITED)

        @Volatile var cancelled = false
        @Volatile var job: Job? = null
        @Volatile var stats: KokoroSynthesisStats? = null

        fun cancel() {
            cancelled = true
            job?.cancel()
            chunks.cancel()
        }
    }

    private class KokoroEngineFailure(cause: Throwable) : Exception(cause)

    internal companion object {
        const val LOG_TAG = "AgentVoice"
        const val MAX_PREFETCH = 4
        const val DOWNLOAD_RETRY_AFTER_MS = 5 * 60 * 1000L

        fun newSynthesisExecutor(): ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "agent-kokoro-tts").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY + 1
            }
        }
    }
}
