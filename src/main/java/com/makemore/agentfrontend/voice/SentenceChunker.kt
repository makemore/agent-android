package com.makemore.agentfrontend.voice

/**
 * Buffers incremental text deltas and emits chunks suitable for
 * low-latency TTS playback. Port of the JS / Swift `SentenceChunker` so
 * web, iOS, and Android behave identically.
 *
 * Default behaviour fires after each sentence terminator (`.`, `!`, `?`)
 * once the buffer holds at least [minChars] characters — short fragments
 * don't trigger their own audio request. Tune [minChars] / [maxChars] to
 * trade end-to-end latency against the number of synth calls.
 *
 * NOT thread-safe. Drive it from a single coroutine (e.g. the
 * [VoiceController]'s main scope).
 */
class SentenceChunker(
    private val minChars: Int = 40,
    private val maxChars: Int = 240,
    private val onChunk: (String) -> Unit,
) {
    private val buffer = StringBuilder()

    /** Append a delta and emit any complete sentences. */
    fun push(delta: String) {
        if (delta.isEmpty()) return
        buffer.append(delta)
        drain()
    }

    /** Force-emit whatever is left (e.g. on `assistant.message`). */
    fun flush() {
        val remaining = buffer.toString().trim()
        buffer.setLength(0)
        if (remaining.isNotEmpty()) emit(remaining)
    }

    /** Discard buffered text without emitting (e.g. on user interrupt). */
    fun reset() {
        buffer.setLength(0)
    }

    private fun drain() {
        // Hard cap: emit even mid-sentence if the buffer is uncomfortably
        // long, so the user hears something while a long monologue
        // continues to stream in.
        while (buffer.length >= maxChars) {
            val cut = findSafeCut(buffer, maxChars)
            val head = buffer.substring(0, cut).trim()
            buffer.delete(0, cut)
            if (head.isNotEmpty()) emit(head)
        }

        // Soft sentence boundary: only flush once we have enough chars
        // to make a worthwhile TTS request. The emitted *chunk* (not just
        // the buffer) must be at least [minChars] long — otherwise a
        // short opener like "Hello!" gets shipped as a 1-word TTS request,
        // which ElevenLabs voices with noticeably different prosody than
        // the same voice mid-paragraph. Skip past short sentence ends and
        // emit at the next terminator that meets the threshold.
        while (buffer.length >= minChars) {
            val end = sentenceEnd(buffer, minChunkChars = minChars) ?: break
            val head = buffer.substring(0, end).trim()
            buffer.delete(0, end)
            if (head.isNotEmpty()) emit(head)
        }
    }

    /**
     * Locate the index *after* the first sentence terminator that
     * produces a chunk of at least [minChunkChars] characters and is
     * followed by whitespace or end-of-buffer. Returns `null` when no
     * such break exists yet — caller should wait for more text.
     */
    private fun sentenceEnd(text: CharSequence, minChunkChars: Int = 0): Int? {
        for (i in text.indices) {
            val c = text[i]
            if (c == '.' || c == '!' || c == '?') {
                val next = i + 1
                val chunkLen = i + 1   // characters consumed up to and including terminator
                if (chunkLen >= minChunkChars &&
                    (next == text.length || text[next].isWhitespace())
                ) {
                    return next
                }
            }
        }
        return null
    }

    private fun findSafeCut(text: CharSequence, max: Int): Int {
        // Prefer breaking at the last whitespace before [max] so we
        // don't chop a word mid-syllable. Fall back to a hard cut when
        // the early portion has no whitespace at all (long URL, code).
        val head = text.subSequence(0, max)
        val lastSpace = (head.length - 1 downTo 0).firstOrNull { head[it].isWhitespace() }
        if (lastSpace != null && lastSpace > max * 0.6) return lastSpace
        return max
    }

    private fun emit(text: String) {
        val cleaned = sanitizeForSpeech(text)
        if (cleaned.isNotEmpty()) onChunk(cleaned)
    }

    companion object {
        // Compiled once. `RegexOption.DOT_MATCHES_ALL` is the Kotlin
        // equivalent of JS's `s` flag — needed for fenced code blocks
        // that span newlines.
        private val FENCED = Regex("```[\\s\\S]*?```")
        private val INLINE_CODE = Regex("`([^`]+)`")
        private val BOLD = Regex("(\\*\\*|__)(.*?)\\1")
        private val ITALIC = Regex("(\\*|_)(.*?)\\1")
        private val LINK = Regex("\\[([^\\]]+)\\]\\([^)]+\\)")
        private val URL = Regex("https?://\\S+")
        private val HEADING = Regex("^#{1,6}\\s+", RegexOption.MULTILINE)
        private val WHITESPACE = Regex("\\s+")

        /**
         * Strip markdown noise that TTS reads literally and reads
         * poorly. Conservative — leaves punctuation alone so prosody
         * works.
         */
        fun sanitizeForSpeech(text: String): String {
            if (text.isEmpty()) return ""
            var s = text
            s = FENCED.replace(s, " ")
            s = INLINE_CODE.replace(s, "$1")
            s = BOLD.replace(s, "$2")
            s = ITALIC.replace(s, "$2")
            s = LINK.replace(s, "$1")
            s = URL.replace(s, "")
            s = HEADING.replace(s, "")
            s = WHITESPACE.replace(s, " ")
            return s.trim()
        }
    }
}
