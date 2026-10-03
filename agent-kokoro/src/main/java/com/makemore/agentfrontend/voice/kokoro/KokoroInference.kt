package com.makemore.agentfrontend.voice.kokoro

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** Shared ONNX Runtime environment and session options. */
internal object KokoroOrt {
    val env: OrtEnvironment by lazy {
        OrtEnvironment.getEnvironment().also { env ->
            // ONNX Runtime 1.28's Android build has no telemetry; this is a no-op
            // safeguard for builds that do.
            runCatching { env.setTelemetry(false) }
        }
    }

    fun session(file: File, threads: Int): OrtSession {
        val opts = OrtSession.SessionOptions()
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        if (threads > 0) opts.setIntraOpNumThreads(threads)
        return env.createSession(file.absolutePath, opts)
    }
}

// ----------------------------------------------------------------- BART G2P

/** `g2p-vocab.json` ("bart-g2p-vocab/1"). */
internal class BartVocab(val graphemeToId: Map<Int, Int>, val phonemeTokens: List<String>, val maxPositions: Int) {
    companion object {
        const val BOS = 1
        const val EOS = 2
        const val UNK = 3

        fun parse(json: String): BartVocab {
            val v = KokoroJson.parseObject(json)
            val graphemes = v.list("grapheme_tokens").map { it as String }
            val map = HashMap<Int, Int>()
            graphemes.forEachIndexed { i, g ->
                // Same as the reference's dict comprehension: later indices win.
                if (i > UNK && Py.len(g) == 1) map[g.codePointAt(0)] = i
            }
            return BartVocab(map, v.list("phoneme_tokens").map { it as String }, v.long("max_positions").toInt())
        }
    }

    fun encode(word: String): LongArray {
        val cps = Py.cps(word)
        val out = LongArray(cps.size + 2)
        out[0] = BOS.toLong()
        for (i in cps.indices) out[i + 1] = (graphemeToId[cps[i]] ?: UNK).toLong()
        out[out.size - 1] = EOS.toLong()
        return out
    }

    fun idsToPhonemes(ids: List<Int>): String {
        val sb = StringBuilder()
        for (id in ids) if (id > UNK && id < phonemeTokens.size) sb.append(phonemeTokens[id])
        return sb.toString()
    }
}

/** One decoder step: logits of the last position for (input ids, decoder ids). */
internal fun interface BartStep {
    fun lastLogits(inputIds: LongArray, decoderIds: LongArray): FloatArray
}

/**
 * Greedy decoding of the exported BART G2P (README "G2P ONNX contract"):
 * no KV cache, argmax with the lowest index winning ties, words longer than
 * 62 code points decoded in 62-code-point slices. Caches per word.
 */
internal class BartG2P(private val vocab: BartVocab, private val step: BartStep) : G2PFallback {
    private val cache = HashMap<String, String>()

    /** One slice (<= 62 code points). Returns (input ids, generated ids without BOS/EOS). */
    fun decodeIds(word: String): Pair<LongArray, List<Int>> {
        val ids = vocab.encode(word)
        require(ids.size <= vocab.maxPositions) { "word slice too long" }
        val dec = ArrayList<Long>().apply { add(BartVocab.BOS.toLong()) }
        while (dec.size < vocab.maxPositions) {
            val logits = step.lastLogits(ids, dec.toLongArray())
            var best = 0
            for (i in 1 until logits.size) if (logits[i] > logits[best]) best = i
            if (best == BartVocab.EOS) break
            dec += best.toLong()
        }
        return ids to dec.drop(1).map { it.toInt() }
    }

    override fun phonemize(word: String): String {
        cache[word]?.let { return it }
        val n = vocab.maxPositions - 2
        val cps = Py.cps(word)
        val sb = StringBuilder()
        var i = 0
        while (i < cps.size) {
            val slice = String(cps, i, minOf(n, cps.size - i))
            sb.append(vocab.idsToPhonemes(decodeIds(slice).second))
            i += n
        }
        return sb.toString().also { cache[word] = it }
    }
}

/** [BartStep] backed by an ONNX Runtime session over `g2p.onnx`. */
internal class OrtBartStep(private val session: OrtSession) : BartStep, AutoCloseable {
    override fun lastLogits(inputIds: LongArray, decoderIds: LongArray): FloatArray {
        val env = KokoroOrt.env
        OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), longArrayOf(1, inputIds.size.toLong())).use { a ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(decoderIds), longArrayOf(1, decoderIds.size.toLong())).use { b ->
                session.run(mapOf("input_ids" to a, "decoder_input_ids" to b)).use { result ->
                    val logits = result.get("logits").orElseThrow { IllegalStateException("no logits") } as OnnxTensor
                    val buf = logits.floatBuffer
                    val shape = logits.info.shape
                    val vocabSize = shape[2].toInt()
                    val out = FloatArray(vocabSize)
                    buf.position((decoderIds.size - 1) * vocabSize)
                    buf.get(out)
                    return out
                }
            }
        }
    }

    override fun close() = session.close()
}

// ----------------------------------------------------------------- Kokoro

/** `model/vocab.json` ("kokoro-vocab/1"): one code point -> token id. */
internal class KokoroVocab(private val ids: Map<Int, Long>) {
    companion object {
        const val PAD_ID = 0L
        const val MAX_PHONEMES = 510
        const val SAMPLE_RATE = 24_000

        fun parse(json: String): KokoroVocab {
            val v = KokoroJson.parseObject(json).obj("vocab")
            val map = HashMap<Int, Long>()
            for ((k, id) in v) if (Py.len(k) == 1) map[k.codePointAt(0)] = (id as Number).toLong()
            return KokoroVocab(map)
        }
    }

    /** Characters missing from the vocab are dropped. */
    fun phonemesToIds(ps: String): LongArray {
        val out = ArrayList<Long>(ps.length)
        var i = 0
        while (i < ps.length) {
            val cp = ps.codePointAt(i)
            i += Character.charCount(cp)
            ids[cp]?.let { out += it }
        }
        return out.toLongArray()
    }

    /** `[0] + ids + [0]`; at most 510 phoneme tokens (chunk first). */
    fun modelInputIds(ps: String): LongArray {
        val ids = phonemesToIds(ps)
        require(ids.size <= MAX_PHONEMES) { "${ids.size} phoneme tokens > $MAX_PHONEMES; chunk first" }
        return LongArray(ids.size + 2).also { System.arraycopy(ids, 0, it, 1, ids.size) }
    }
}

/** A voice pack: raw little-endian float32 `[510, 1, 256]`. */
internal class KokoroVoicePack(private val data: FloatArray) {
    init {
        require(data.size == ROWS * STYLE_DIM) { "voice pack has ${data.size} floats" }
    }

    companion object {
        const val ROWS = 510
        const val STYLE_DIM = 256

        /** hexgrad's KPipeline uses `pack[len(ps) - 1]`. */
        fun styleIndex(numPhonemeTokens: Int): Int = (numPhonemeTokens - 1).coerceIn(0, ROWS - 1)

        fun load(file: File): KokoroVoicePack = parse(file.readBytes())

        fun parse(bytes: ByteArray): KokoroVoicePack {
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return KokoroVoicePack(FloatArray(fb.remaining()).also { fb.get(it) })
        }
    }

    fun style(numPhonemeTokens: Int): FloatArray {
        val row = styleIndex(numPhonemeTokens)
        return data.copyOfRange(row * STYLE_DIM, (row + 1) * STYLE_DIM)
    }
}

/** Runs `kokoro-v1.0-q8.onnx`: ids + style + speed -> 24 kHz mono float PCM. */
internal class KokoroAcousticModel(private val session: OrtSession) : AutoCloseable {
    fun synthesize(inputIds: LongArray, style: FloatArray, speed: Float): FloatArray {
        val env = KokoroOrt.env
        OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), longArrayOf(1, inputIds.size.toLong())).use { ids ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(style), longArrayOf(1, style.size.toLong())).use { st ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(speed)), longArrayOf(1)).use { sp ->
                    session.run(mapOf("input_ids" to ids, "style" to st, "speed" to sp)).use { result ->
                        val out = result.get(0) as OnnxTensor
                        val buf = out.floatBuffer
                        return FloatArray(buf.remaining()).also { buf.get(it) }
                    }
                }
            }
        }
    }

    override fun close() = session.close()
}

// ----------------------------------------------------------------- chunking

/** One chunk of a segment: its merged tokens, text and phonemes (<= 510). */
internal class KokoroChunk(val tokens: List<G2PToken>, val text: String, val phonemes: String)

/** Port of `chunk_tokens()` in kokoro_ref/kokoro.py (KPipeline.en_tokenize + waterfall_last). */
internal object KokoroChunker {
    private val WATERFALL = listOf(setOf("!", ".", "?", "…"), setOf(":", ";"), setOf(",", "—"))
    private val BUMPS = setOf(")", "”")

    fun tokensToPs(tokens: List<G2PToken>): String {
        val sb = StringBuilder()
        for (t in tokens) sb.append(t.phonemes ?: "").append(if (t.whitespace.isNotEmpty()) " " else "")
        return Py.strip(sb.toString())
    }

    private fun textOf(tokens: List<G2PToken>): String {
        val sb = StringBuilder()
        for (t in tokens) sb.append(t.text).append(t.whitespace)
        return Py.strip(sb.toString())
    }

    private fun waterfallLast(tokens: List<G2PToken>, nextCount: Int): Int {
        for (w in WATERFALL) {
            var z = (tokens.size - 1 downTo 0).firstOrNull { tokens[it].phonemes in w } ?: continue
            z += 1
            if (z < tokens.size && tokens[z].phonemes in BUMPS) z += 1
            if (nextCount - Py.len(tokensToPs(tokens.subList(0, z))) <= KokoroVocab.MAX_PHONEMES) return z
        }
        return tokens.size
    }

    fun chunk(tokens: List<G2PToken>): List<KokoroChunk> {
        val out = ArrayList<KokoroChunk>()
        var tks = ArrayList<G2PToken>()
        var pcount = 0
        for (t in tokens) {
            val ps = t.phonemes ?: ""
            var nextPs = ps + if (t.whitespace.isNotEmpty()) " " else ""
            val nextPcount = pcount + Py.len(nextPs.trimEnd(*PY_WS))
            if (nextPcount > KokoroVocab.MAX_PHONEMES) {
                val z = waterfallLast(tks, nextPcount)
                val head = tks.subList(0, z).toList()
                out += KokoroChunk(head, textOf(head), tokensToPs(head))
                tks = ArrayList(tks.subList(z, tks.size))
                pcount = Py.len(tokensToPs(tks))
                if (tks.isEmpty()) nextPs = nextPs.trimStart(*PY_WS)
            }
            tks += t
            pcount += Py.len(nextPs)
        }
        if (tks.isNotEmpty()) out += KokoroChunk(tks, textOf(tks), tokensToPs(tks))
        return out.filter { it.phonemes.isNotEmpty() }
    }

    private val PY_WS = charArrayOf(
        '\t', '\n', '\u000B', '\u000C', '\r', '\u001C', '\u001D', '\u001E', '\u001F', ' ',
        '\u0085', ' ', ' ', ' ', ' ', ' ', ' ', '　',
    ) + (' '..' ').toList().toCharArray()

    private val SENTENCE_ENDS = setOf("!", ".", "?", "…")
    private val CLAUSE_ENDS = setOf(":", ";", ",", "—")

    /**
     * Latency split (README "Chunking" recommendations, not part of the golden
     * contract). Kokoro renders a whole piece at once, in about half its audio
     * duration on a phone CPU, so the first audio waits for the first piece.
     * Each golden chunk is therefore cut further after sentence-ending
     * punctuation, and the first piece of an utterance ([startsUtterance]) is
     * cut as early as [PieceRules] allow, also at a clause mark. A closing
     * `)`/`”` stays with its cut. Joined back, the pieces are the chunk.
     */
    fun speechPieces(
        chunks: List<KokoroChunk>,
        startsUtterance: Boolean = true,
        rules: PieceRules = PieceRules(),
    ): List<KokoroChunk> {
        val out = ArrayList<KokoroChunk>()
        for (chunk in chunks) {
            val tokens = chunk.tokens
            var start = 0
            var i = 0
            while (i < tokens.size) {
                val ps = tokens[i].phonemes
                var end = i + 1
                if (end < tokens.size && tokens[end].phonemes in BUMPS) end++
                val first = startsUtterance && out.isEmpty()
                val sentence = ps in SENTENCE_ENDS
                val clause = ps in CLAUSE_ENDS
                if ((sentence || (first && clause)) && end < tokens.size) {
                    val len = Py.len(tokensToPs(tokens.subList(start, end)))
                    val restLen = Py.len(tokensToPs(tokens.subList(end, tokens.size)))
                    val cut = if (first) {
                        len >= (if (sentence) rules.firstSentenceMin else rules.firstClauseMin) && restLen >= rules.firstRestMin
                    } else {
                        len >= rules.minPhonemes && restLen >= rules.minPhonemes
                    }
                    if (cut) {
                        val p = tokens.subList(start, end).toList()
                        out += KokoroChunk(p, textOf(p), tokensToPs(p))
                        start = end
                    }
                }
                i = end
            }
            if (start < tokens.size) {
                val p = tokens.subList(start, tokens.size).toList()
                val ps = tokensToPs(p)
                if (ps.isNotEmpty()) out += KokoroChunk(p, textOf(p), ps)
            }
        }
        return out
    }
}

/**
 * Thresholds of [KokoroChunker.speechPieces], in phoneme characters (about
 * 12–15 per second of speech). Kokoro voices are weaker on very short input,
 * so only the first piece of an utterance is allowed to be short.
 */
internal class PieceRules(
    /** The first piece may end at `! . ? …` once it is this long ("Sure!"). */
    val firstSentenceMin: Int = 4,
    /** … or at `, ; : —` once it is this long. */
    val firstClauseMin: Int = 12,
    /** … and only if at least this much of the chunk is left. */
    val firstRestMin: Int = 12,
    /** Later pieces end at sentence marks, with at least this much on both sides. */
    val minPhonemes: Int = 20,
)
