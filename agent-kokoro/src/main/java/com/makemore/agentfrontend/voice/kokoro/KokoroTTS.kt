package com.makemore.agentfrontend.voice.kokoro

import android.content.Context
import com.makemore.agentfrontend.voice.LocalTTSEngine
import com.makemore.agentfrontend.voice.LocalTTSEngineRequest
import com.makemore.agentfrontend.voice.TTSProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Settings for the Kokoro engine. Same names and defaults as the iOS and web clients. */
data class KokoroOptions(
    /** Folder holding `manifest.json` (HTTPS, or `file://` for a side-loaded copy). */
    val baseUrl: String = KokoroTTS.DEFAULT_BASE_URL,
    /** Kokoro voice id; `a*` voices speak en-us, `b*` voices en-gb. */
    val voice: String = KokoroVoices.DEFAULT_VOICE_ID,
    /** Speaking rate multiplier (1.0 = Kokoro's natural pace; clamped to 0.5–2.0). */
    val speed: Float = 1.0f,
    /** Cache directory; `null` = `context.noBackupFilesDir/agent-kokoro`. */
    val cacheDirectory: File? = null,
    /**
     * Download the voice in the background the first time Kokoro is asked to
     * speak or a turn starts (the system voice is used until it is ready). Set
     * `false` to download only when the host calls [KokoroTTSEngine.prefetch]
     * or [KokoroTTSEngine.prepare].
     */
    val autoDownload: Boolean = true,
    /**
     * Allow the ~97 MB download on a metered network (cellular, metered
     * Wi-Fi). Off by default: on a metered network the engine stays
     * [KokoroState.NotDownloaded] (the system voice speaks) and starts the
     * download by itself once the device is on an unmetered network. Can be
     * changed at runtime with [KokoroTTSEngine.allowCellularDownload].
     */
    val allowCellularDownload: Boolean = false,
    /** ONNX Runtime intra-op threads for the Kokoro model; 0 = ONNX Runtime's default. */
    val numThreads: Int = 0,
)

/** Download/load lifecycle of the configured voice. */
sealed interface KokoroState {
    /** Its files are not (all) on the device. */
    data object NotDownloaded : KokoroState

    /** Files are being downloaded; see [KokoroModelProgress]. */
    data object Downloading : KokoroState

    /** Files are verified; ONNX sessions and dictionaries are loading. */
    data object Loading : KokoroState

    /**
     * Downloaded and verified. The engine loads on first use if [KokoroTTSEngine.prepare]
     * was not called (state [Loading] meanwhile).
     */
    data object Ready : KokoroState

    /**
     * The last download or load failed. [error] is a value-free code:
     * `network`, `http_<status>`, `insecure_url`, `too_many_redirects`,
     * `checksum_mismatch`, `size_mismatch`, `insufficient_storage`,
     * `invalid_manifest`, `unknown_voice`, `load_failed`, `io`. (A download
     * held back on a metered network is not a failure: the state stays
     * [NotDownloaded].)
     */
    data class Failed(val error: String) : KokoroState
}

/** Progress report for [KokoroTTSEngine.onModelProgress]. */
data class KokoroModelProgress(
    val state: KokoroState,
    /** 0.0–1.0 over every file the voice needs (cached files count as done). */
    val fraction: Float,
    val bytesDownloaded: Long,
    val bytesTotal: Long,
)

/** Timing of one spoken utterance, for [KokoroTTSEngine.onSpeechMetrics]. */
data class KokoroSpeechMetrics(
    /** Time this utterance waited for the engine to load (0 when it was loaded). */
    val loadMs: Long,
    /** From `speak()` to the first audio buffer handed to the audio output. */
    val firstAudioMs: Long,
    val chunkCount: Int,
    val audioSeconds: Double,
    val synthSeconds: Double,
)

/**
 * The on-device Kokoro voice: a [LocalTTSEngine] for
 * `ChatWidgetConfig.localTtsEngine` plus the controls a host needs (download,
 * progress, voice list, delete). Get one from [KokoroTTS.engine]; equal options
 * return the same instance, so passing `KokoroTTS.engine(context)` inline in a
 * config is safe. The loaded engine is shared by every chat using it.
 */
class KokoroTTSEngine internal constructor(
    val options: KokoroOptions,
    internal val store: KokoroAssetStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val loader: (KokoroLocalFiles, Int) -> KokoroEngineCore = { files, threads -> KokoroRuntime.load(files, threads) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val network: KokoroNetworkMonitor = KokoroNetworkMonitor.UNMETERED,
) : LocalTTSEngine {
    override val name: String = KokoroTTS.ENGINE_NAME

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val lock = Any()
    private val loadMutex = Mutex()

    /** The configured voice (unknown ids use [KokoroVoices.DEFAULT_VOICE_ID]). */
    val voice: KokoroVoice = KokoroVoices.find(options.voice) ?: KokoroVoices.default

    private val _state = MutableStateFlow<KokoroState>(KokoroState.NotDownloaded)

    /** Lifecycle of the configured [voice]; observe it to show "Downloading voice… 42%". */
    val state: StateFlow<KokoroState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(KokoroModelProgress(KokoroState.NotDownloaded, 0f, 0, 0))

    /** The latest [KokoroModelProgress] as a flow (the same values [onModelProgress] receives). */
    val modelProgress: StateFlow<KokoroModelProgress> = _progress.asStateFlow()

    /** Called (on a background thread) with download/load progress of the configured voice. */
    @Volatile var onModelProgress: ((KokoroModelProgress) -> Unit)? = null

    /** Called (on a background thread) after each utterance Kokoro speaks. */
    @Volatile var onSpeechMetrics: ((KokoroSpeechMetrics) -> Unit)? = null

    // Guarded by [lock].
    private var runtime: KokoroEngineCore? = null
    private val inFlight = HashMap<String, Deferred<KokoroSynthesizer>>()
    private val waitingForUnmetered = LinkedHashSet<String>()
    private var unmeteredWatch: AutoCloseable? = null

    /**
     * Whether downloads may use a metered network. Starts as
     * [KokoroOptions.allowCellularDownload]; set it to `true` (e.g. after asking
     * the user) and call [prepare] to download now.
     */
    @Volatile var allowCellularDownload: Boolean = options.allowCellularDownload
    private var providers = 0

    /** Duration of the most recent engine load, in ms (0 if none yet). */
    @Volatile var lastLoadMs: Long = 0L
        private set

    /** Epoch millis of the last failed attempt, or 0. */
    @Volatile internal var lastFailureAtMillis: Long = 0L
        private set

    init {
        // Disk check off the caller's (often main) thread.
        scope.launch {
            if (_state.value == KokoroState.NotDownloaded && isDownloaded(voice)) setState(KokoroState.Ready, 1f, 0, 0)
        }
    }

    // -- Public controls ------------------------------------------------

    /**
     * Download (if needed), verify and load the configured voice. Idempotent;
     * concurrent calls share the work. Throws [KokoroAssetException] on failure
     * (also reported through [state]). When files are missing, the network is
     * metered and [allowCellularDownload] is false, nothing is fetched: it
     * throws reason `metered_network`, [state] stays [KokoroState.NotDownloaded],
     * and the download starts by itself on the next unmetered network.
     */
    suspend fun prepare() {
        synthesizerFor(voice)
    }

    /** [prepare] in the background; returns immediately. */
    fun prefetch() {
        startPrepare(voice)
    }

    /** The voices of the asset set (voices.json, or the built-in copy when offline). */
    suspend fun voices(): List<KokoroVoice> = withContext(ioDispatcher) {
        try {
            val m = store.manifest()
            val f = m.file(m.voices)
            store.download(listOf(f)) { _, _ -> }
            KokoroVoices.parse(store.cached(f)!!.readText())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KokoroVoices.all
        }
    }

    /** Bytes the cache uses on the device (verified files plus partial downloads). Reads the disk. */
    val downloadedBytes: Long get() = store.downloadedBytes()

    /** Unload the engine and delete every downloaded file. Providers use the system voice until it is fetched again. */
    suspend fun deleteDownloadedModel() {
        val (rt, jobs) = synchronized(lock) {
            val r = runtime
            runtime = null
            r to inFlight.values.toList().also { inFlight.clear() }
        }
        jobs.forEach { it.cancel() }
        withContext(ioDispatcher) { rt?.close() }
        store.clear()
        setState(KokoroState.NotDownloaded, 0f, 0, 0)
    }

    /** Same as [deleteDownloadedModel]. */
    suspend fun clearCache() = deleteDownloadedModel()

    /** Release the loaded engine's memory (it reloads on next use). Files stay cached. */
    fun unload() {
        val rt = synchronized(lock) { runtime.also { runtime = null } } ?: return
        scope.launch { rt.close() }
    }

    // -- LocalTTSEngine ---------------------------------------------------

    override fun makeProvider(context: Context, request: LocalTTSEngineRequest): TTSProvider =
        KokoroTTSProvider(engine = this, defaultVoiceId = request.voiceId, fallback = request.fallback)

    // -- Internals used by the provider ----------------------------------------

    internal fun providerCreated() = synchronized(lock) { providers++ }

    /** When the last chat closes, free the engine's memory (files stay cached). */
    internal fun providerReleased() {
        val last = synchronized(lock) { providers = (providers - 1).coerceAtLeast(0); providers == 0 }
        if (last) unload()
    }

    /** The loaded synthesizer for [voice] if it is ready right now, without waiting. */
    internal fun loadedSynthesizer(voice: KokoroVoice): KokoroSynthesizer? = synchronized(lock) {
        runtime?.takeIf { it.isLoaded(voice.language, voice.id) }
    }

    /** True when every file [voice] needs is cached (so a load will not touch the network). */
    internal fun isDownloaded(voice: KokoroVoice): Boolean {
        val m = store.cachedManifest() ?: return false
        return runCatching { store.isCached(m.plan(voice.id, voice.language)) }.getOrDefault(false)
    }

    internal fun startPrepare(voice: KokoroVoice): Deferred<KokoroSynthesizer> = synchronized(lock) {
        inFlight[voice.id]?.takeIf { it.isActive }?.let { return it }
        scope.async { doPrepare(voice) }.also { job ->
            inFlight[voice.id] = job
            job.invokeOnCompletion { synchronized(lock) { if (inFlight[voice.id] === job) inFlight.remove(voice.id) } }
        }
    }

    /** Download (if needed) and load [voice]; returns the synthesizer. */
    internal suspend fun synthesizerFor(voice: KokoroVoice): KokoroSynthesizer {
        loadedSynthesizer(voice)?.let { return it }
        return startPrepare(voice).await()
    }

    private suspend fun doPrepare(voice: KokoroVoice): KokoroSynthesizer {
        val tracked = voice.id == this.voice.id
        try {
            if (!isDownloaded(voice) && !allowCellularDownload && network.isMetered()) {
                waitForUnmetered(voice)
                throw KokoroAssetException("metered_network", "waiting for an unmetered network")
            }
            val manifest = store.manifest()
            val plan = try {
                manifest.plan(voice.id, voice.language)
            } catch (e: KokoroAssetException) {
                throw KokoroAssetException("unknown_voice", cause = e)
            }
            if (!store.isCached(plan)) {
                if (tracked) setState(KokoroState.Downloading, 0f, 0, plan.totalBytes)
                store.download(plan.all) { done, total ->
                    if (tracked) setState(KokoroState.Downloading, if (total > 0) done.toFloat() / total else 0f, done, total)
                }
            }
            val files = KokoroLocalFiles(
                model = store.cached(plan.model)!!,
                vocab = store.cached(plan.vocab)!!,
                voice = store.cached(plan.voice)!!,
                lang = plan.lang,
                gold = store.cached(plan.gold)!!,
                goldGzip = plan.gold.path.endsWith(".gz"),
                silver = store.cached(plan.silver)!!,
                silverGzip = plan.silver.path.endsWith(".gz"),
                g2pModel = store.cached(plan.g2pModel)!!,
                g2pVocab = store.cached(plan.g2pVocab)!!,
            )
            return loadMutex.withLock {
                loadedSynthesizer(voice)?.let { return@withLock it }
                if (tracked) setState(KokoroState.Loading, 1f, plan.totalBytes, plan.totalBytes)
                val started = clock()
                val rt = try {
                    val existing = synchronized(lock) { runtime }
                    (existing ?: loader(files, options.numThreads)).also { it.ensure(files, voice.id) }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    // Includes UnsatisfiedLinkError (no native library for this ABI).
                    throw KokoroAssetException("load_failed", cause = t)
                }
                synchronized(lock) { runtime = rt }
                lastLoadMs = clock() - started
                if (tracked) setState(KokoroState.Ready, 1f, plan.totalBytes, plan.totalBytes)
                rt
            }
        } catch (e: CancellationException) {
            if (tracked) setState(if (isDownloaded(voice)) KokoroState.Ready else KokoroState.NotDownloaded, 0f, 0, 0)
            throw e
        } catch (e: KokoroAssetException) {
            if (e.reason == METERED) {
                if (tracked) setState(KokoroState.NotDownloaded, 0f, 0, 0)
            } else {
                fail(tracked, e.reason)
            }
            throw e
        } catch (e: java.io.IOException) {
            fail(tracked, "network")
            throw KokoroAssetException("network", cause = e)
        } catch (e: Exception) {
            fail(tracked, "io")
            throw KokoroAssetException("io", cause = e)
        }
    }

    /** Remember [voice] and prepare it once the default network is unmetered. */
    private fun waitForUnmetered(voice: KokoroVoice) {
        synchronized(lock) {
            waitingForUnmetered += voice.id
            if (unmeteredWatch != null) return
            unmeteredWatch = network.whenUnmetered { scope.launch { onUnmetered() } }
        }
    }

    private fun onUnmetered() {
        val ids = synchronized(lock) {
            unmeteredWatch = null
            waitingForUnmetered.toList().also { waitingForUnmetered.clear() }
        }
        for (id in ids) KokoroVoices.find(id)?.let { startPrepare(it) }
    }

    private fun fail(tracked: Boolean, reason: String) {
        lastFailureAtMillis = clock()
        if (tracked) setState(KokoroState.Failed(reason), _progress.value.fraction, _progress.value.bytesDownloaded, _progress.value.bytesTotal)
    }

    private fun setState(state: KokoroState, fraction: Float, done: Long, total: Long) {
        _state.value = state
        val p = KokoroModelProgress(state, fraction.coerceIn(0f, 1f), done, total)
        _progress.value = p
        runCatching { onModelProgress?.invoke(p) }
    }

    internal fun reportMetrics(m: KokoroSpeechMetrics) {
        runCatching { onSpeechMetrics?.invoke(m) }
    }
}

/**
 * Entry point for the optional on-device Kokoro voice.
 *
 * ```kotlin
 * val kokoro = KokoroTTS.engine(context, KokoroOptions(voice = "af_heart"))
 * val config = ChatWidgetConfig(
 *     enableTTS = true,
 *     ttsProviderPolicy = TTSProviderPolicy.LOCAL_ONLY,
 *     localTtsEngine = kokoro,
 * )
 * kokoro.prefetch() // optional: download + load now, e.g. on Wi-Fi
 * ```
 */
private const val METERED = "metered_network"

object KokoroTTS {
    /** Engine id shared across platforms. */
    const val ENGINE_NAME = "kokoro"

    /** Public, versioned asset folder (immutable; see tools/kokoro-assets). */
    const val DEFAULT_BASE_URL = "https://storage.googleapis.com/makemore-voice-models/kokoro/v1/"

    private val engines = HashMap<KokoroOptions, KokoroTTSEngine>()
    private val stores = HashMap<Pair<String, String>, KokoroAssetStore>()

    /** The engine for [options] (cached per options). */
    fun engine(context: Context, options: KokoroOptions = KokoroOptions()): KokoroTTSEngine {
        val dir = (options.cacheDirectory ?: defaultCacheDirectory(context)).absoluteFile
        return synchronized(this) {
            val resolved = options.copy(cacheDirectory = dir)
            engines.getOrPut(resolved) {
                val store = stores.getOrPut(dir.path to options.baseUrl) { KokoroAssetStore(dir, options.baseUrl) }
                KokoroTTSEngine(resolved, store, network = AndroidNetworkMonitor(context))
            }
        }
    }

    /** `context.noBackupFilesDir/agent-kokoro`: kept out of cloud backups and not evicted like the cache dir. */
    fun defaultCacheDirectory(context: Context): File =
        File(context.applicationContext.noBackupFilesDir, "agent-kokoro")
}
