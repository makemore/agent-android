package com.makemore.agentfrontend.voice.kokoro

import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig

/**
 * A loaded Kokoro engine. Not thread-safe: [KokoroTTSProvider] drives it
 * from a single dedicated thread, never the main thread.
 */
interface KokoroSynthesizer {
    val sampleRate: Int

    /** Number of voices in the loaded `voices.bin`. */
    val numSpeakers: Int

    /**
     * Render [text] (blocking). [onSamples] receives mono float PCM pieces in
     * order as they are produced; return `false` from it to stop early.
     * Throws on engine failure.
     */
    fun generate(
        text: String,
        speakerId: Int,
        speed: Float,
        language: String?,
        onSamples: (FloatArray) -> Boolean,
    )

    /** Free native memory. No calls after this. */
    fun release()
}

/** Loads a [KokoroSynthesizer] from installed model files (blocking, slow: ~1–3 s). */
fun interface KokoroSynthesizerLoader {
    fun load(files: KokoroModelFiles): KokoroSynthesizer
}

/** sherpa-onnx `OfflineTts` backed engine. */
internal class SherpaKokoroSynthesizer(files: KokoroModelFiles, numThreads: Int) : KokoroSynthesizer {
    private val tts = OfflineTts(
        assetManager = null,
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = files.model.absolutePath,
                    voices = files.voices.absolutePath,
                    tokens = files.tokens.absolutePath,
                    dataDir = files.dataDir.absolutePath,
                    lexicon = files.lexicons.joinToString(",") { it.absolutePath },
                ),
                numThreads = numThreads,
                debug = false,
                provider = "cpu",
            ),
        ),
    )

    override val sampleRate: Int = tts.sampleRate()
    override val numSpeakers: Int = tts.numSpeakers()

    override fun generate(
        text: String,
        speakerId: Int,
        speed: Float,
        language: String?,
        onSamples: (FloatArray) -> Boolean,
    ) {
        val config = GenerationConfig(
            sid = speakerId,
            speed = speed,
            extra = language?.let { mapOf("lang" to it) },
        )
        val callback = SampleCallback(onSamples)
        val audio = tts.generateWithConfigAndCallback(text, config, callback)
        if (!callback.delivered) {
            // Older builds may not stream through the callback.
            if (audio.samples.isEmpty()) throw IllegalStateException("Kokoro produced no audio")
            onSamples(audio.samples)
        }
    }

    override fun release() {
        tts.release()
    }

    /**
     * sherpa-onnx's JNI looks the callback up as `invoke([F)Ljava/lang/Integer;`
     * on the object's class. A Kotlin lambda compiles to an invokedynamic /
     * D8 synthetic class that only has `invoke(Object)Object`, which aborts the
     * process with a JNI NoSuchMethodError — so this must be a real class
     * (kept by consumer-rules.pro so R8 does not rename the method).
     */
    internal class SampleCallback(private val onSamples: (FloatArray) -> Boolean) : (FloatArray) -> Int {
        @Volatile
        var delivered = false
            private set

        override fun invoke(samples: FloatArray): Int {
            if (samples.isNotEmpty()) delivered = true
            return if (onSamples(samples)) 1 else 0
        }
    }

    companion object {
        fun loader(numThreads: Int) = KokoroSynthesizerLoader { files -> SherpaKokoroSynthesizer(files, numThreads) }
    }
}
