package com.makemore.agentfrontend.voice.kokoro

import android.content.Context
import com.makemore.agentfrontend.voice.LocalTTSEngine
import com.makemore.agentfrontend.voice.LocalTTSEngineRequest
import com.makemore.agentfrontend.voice.TTSProvider
import java.io.File

/** Settings for the Kokoro engine. Defaults match the iOS and web clients. */
data class KokoroOptions(
    /** Model archive URL (HTTPS, `.tar.bz2` laid out like sherpa-onnx's Kokoro releases). */
    val modelUrl: String = KokoroModel.DEFAULT_MODEL_URL,
    /** Optional SHA-256 (hex) of the archive; pin it when you mirror the model. */
    val expectedSha256: String? = null,
    /** Cache directory; `null` = `context.noBackupFilesDir/agent-kokoro`. */
    val modelDirectory: File? = null,
    /**
     * Download the model in the background the first time Kokoro is asked to
     * speak (the system voice is used until it is ready). Set `false` to only
     * download when the host calls [KokoroModelManager.download].
     */
    val autoDownload: Boolean = true,
    /** Voice used when the host does not pick a Kokoro voice id. */
    val defaultVoiceId: String = KokoroVoices.DEFAULT_VOICE_ID,
    /** Speaking rate multiplier (1.0 = Kokoro's natural pace). */
    val speed: Float = 1.0f,
    /** CPU threads for synthesis. */
    val numThreads: Int = 2,
)

/**
 * [LocalTTSEngine] for `ChatWidgetConfig.localTtsEngine`. Get one from
 * [KokoroTTS.engine]; equal options return the same instance, so passing
 * `KokoroTTS.engine(context)` inline in a config is safe.
 */
class KokoroTTSEngine internal constructor(
    val modelManager: KokoroModelManager,
    val options: KokoroOptions,
) : LocalTTSEngine {
    override val name: String = KokoroTTS.ENGINE_NAME

    override fun makeProvider(context: Context, request: LocalTTSEngineRequest): TTSProvider =
        KokoroTTSProvider(
            modelManager = modelManager,
            options = options,
            defaultVoiceId = request.voiceId,
            fallback = request.fallback,
        )
}

/**
 * Entry point for the optional on-device Kokoro voice.
 *
 * ```kotlin
 * val config = ChatWidgetConfig(
 *     enableTTS = true,
 *     ttsProviderPolicy = TTSProviderPolicy.LOCAL_ONLY,
 *     localTtsEngine = KokoroTTS.engine(context),
 *     voiceId = "af_heart",
 * )
 * ```
 */
object KokoroTTS {
    /** Engine name shared across platforms. */
    const val ENGINE_NAME = "kokoro"

    private val managers = HashMap<String, KokoroModelManager>()
    private val engines = HashMap<Pair<String, KokoroOptions>, KokoroTTSEngine>()

    /** The engine for [options] (cached per options). */
    fun engine(context: Context, options: KokoroOptions = KokoroOptions()): KokoroTTSEngine {
        val manager = modelManager(context, options)
        return synchronized(this) {
            engines.getOrPut(manager.directory.path to options) { KokoroTTSEngine(manager, options) }
        }
    }

    /**
     * The process-wide model manager for [options]' cache directory — use it
     * to observe download progress, prefetch the model (e.g. on Wi-Fi) or
     * delete it. One manager per directory; asking for the same directory
     * with a different URL/checksum is an error.
     */
    fun modelManager(context: Context, options: KokoroOptions = KokoroOptions()): KokoroModelManager {
        val dir = (options.modelDirectory ?: defaultModelDirectory(context)).absoluteFile
        return synchronized(this) {
            val existing = managers[dir.path]
            if (existing != null) {
                require(existing.modelUrl == options.modelUrl && existing.expectedSha256 == options.expectedSha256) {
                    "A Kokoro model manager for this directory already uses a different model source"
                }
                existing
            } else {
                KokoroModelManager(dir, options.modelUrl, options.expectedSha256).also { managers[dir.path] = it }
            }
        }
    }

    /** `context.noBackupFilesDir/agent-kokoro` — kept out of cloud backups, not evicted like the cache dir. */
    fun defaultModelDirectory(context: Context): File =
        File(context.applicationContext.noBackupFilesDir, "agent-kokoro")
}
