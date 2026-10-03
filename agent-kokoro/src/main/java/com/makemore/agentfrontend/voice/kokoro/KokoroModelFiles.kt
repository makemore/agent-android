package com.makemore.agentfrontend.voice.kokoro

import java.io.File

/** Upstream facts about the Kokoro model this artifact downloads by default. */
object KokoroModel {
    /**
     * sherpa-onnx's published int8 Kokoro v1.0 multi-language model
     * (`kokoro-int8-multi-lang-v1_0`, ~126 MiB compressed, ~186 MiB on disk).
     * Same default as the iOS and web clients.
     */
    const val DEFAULT_MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2"

    /** Sample rate of every Kokoro voice. */
    const val SAMPLE_RATE = 24_000

    /** Directory name of the installed model inside the model directory. */
    internal const val INSTALL_DIR = "kokoro-v1.0"
    internal const val INSTALLED_MARKER = ".agent-kokoro-installed"
}

/** Paths of an installed Kokoro model, as sherpa-onnx's OfflineTts expects them. */
data class KokoroModelFiles(
    val directory: File,
    val model: File,
    val voices: File,
    val tokens: File,
    /** espeak-ng data directory (`espeak-ng-data`). */
    val dataDir: File,
    /** Lexicons present in the archive (US English first, then Chinese). */
    val lexicons: List<File>,
) {
    companion object {
        private val MODEL_NAMES = listOf("model.int8.onnx", "model.onnx")
        private val LEXICON_NAMES = listOf("lexicon-us-en.txt", "lexicon-zh.txt")

        /** Resolve the files in [dir], or `null` if a required one is missing. */
        fun from(dir: File): KokoroModelFiles? {
            if (!dir.isDirectory) return null
            val model = MODEL_NAMES.map { File(dir, it) }.firstOrNull { it.isFile }
                ?: dir.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".onnx") }
                ?: return null
            val voices = File(dir, "voices.bin").takeIf { it.isFile } ?: return null
            val tokens = File(dir, "tokens.txt").takeIf { it.isFile } ?: return null
            val dataDir = File(dir, "espeak-ng-data")
            if (!File(dataDir, "phontab").isFile) return null
            val lexicons = LEXICON_NAMES.map { File(dir, it) }.filter { it.isFile }
            return KokoroModelFiles(dir, model, voices, tokens, dataDir, lexicons)
        }
    }
}
