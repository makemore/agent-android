package com.makemore.agentfrontend.voice.kokoro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The cross-platform golden vectors (tools/kokoro-assets/golden, copied to
 * src/test/resources/kokoro). Every row must match exactly.
 *
 * The G2P, BART and chunk vectors need the dictionaries and g2p.onnx from the
 * asset folder: run with `-PkokoroAssets=<path to kokoro/v1>` (or
 * `KOKORO_ASSETS`). Without it those tests are skipped with a message; the
 * token vectors only need the small vocab.json and always run.
 */
class KokoroGoldenTest {
    companion object {
        fun resource(name: String): String =
            KokoroGoldenTest::class.java.getResource("/kokoro/$name")!!.readText()

        @Suppress("UNCHECKED_CAST")
        fun rows(name: String): List<Map<String, Any?>> =
            resource(name).lines().filter { it.isNotBlank() }.map { KokoroJson.parse(it) as Map<String, Any?> }

        val assetsDir: File? = System.getProperty("kokoro.assets")
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?.takeIf { File(it, "manifest.json").isFile }

        fun requireAssets(): File {
            assumeTrue(
                "Kokoro asset folder not set: run with -PkokoroAssets=<.../tools/kokoro-assets/build/kokoro/v1> " +
                    "(or KOKORO_ASSETS) to run this golden test",
                assetsDir != null,
            )
            return assetsDir!!
        }

        private val g2ps = HashMap<String, Pair<KokoroG2P, BartG2P>>()

        /** G2P for [lang] from the asset folder (gzip dictionaries, as the app downloads them). */
        internal fun g2p(lang: String): Pair<KokoroG2P, BartG2P> = synchronized(g2ps) {
            g2ps.getOrPut(lang) {
                val d = File(requireAssets(), "g2p/$lang")
                val golds = KokoroRuntime.loadDictionary(File(d, "gold.json.gz"), gzip = true)
                val silvers = KokoroRuntime.loadDictionary(File(d, "silver.json.gz"), gzip = true)
                val bart = BartG2P(
                    BartVocab.parse(File(d, "g2p-vocab.json").readText()),
                    OrtBartStep(KokoroOrt.session(File(d, "g2p.onnx"), 1)),
                )
                KokoroG2P(lang, golds, silvers, bart) to bart
            }
        }
    }

    @Test
    fun `golden tokens - input ids and style index from phonemes`() {
        val vocab = KokoroVocab.parse(resource("vocab.json"))
        val rows = rows("golden-tokens.jsonl")
        assertEquals(14, rows.size)
        for (r in rows) {
            val ids = vocab.modelInputIds(r["phonemes"] as String)
            val want = (r["input_ids"] as List<*>).map { (it as Number).toLong() }
            assertEquals(r["text"] as String, want, ids.toList())
            assertEquals((r["style_index"] as Number).toInt(), KokoroVoicePack.styleIndex(ids.size - 2))
        }
    }

    @Test
    fun `golden g2p - normalized text and phonemes, both languages`() {
        requireAssets()
        val rows = rows("golden-g2p.jsonl")
        assertEquals(1190, rows.size)
        val bad = ArrayList<String>()
        for (r in rows) {
            val lang = r["voice_lang"] as String
            val got = g2p(lang).first(r["text"] as String)
            if (got.normalized != r["normalized"] || got.phonemes != r["phonemes"]) {
                bad += "${r["id"]} [$lang] ${r["text"]}\n   normalized: ${got.normalized} | want ${r["normalized"]}\n" +
                    "   phonemes:   ${got.phonemes} | want ${r["phonemes"]}"
            }
            val wantFallback = (r["fallback"] as List<*>).map { it as String }
            if (got.fallbackWords != wantFallback) {
                bad += "${r["id"]} [$lang] fallback words ${got.fallbackWords} | want $wantFallback"
            }
        }
        if (bad.isNotEmpty()) println(bad.take(40).joinToString("\n"))
        assertEquals("golden-g2p rows that differ (first 40 printed above)", 0, bad.size)
        println("golden-g2p: ${rows.size} rows match")
    }

    @Test
    fun `golden bart - encoder ids and greedy output ids`() {
        requireAssets()
        val rows = rows("golden-bart.jsonl")
        assertEquals(112, rows.size)
        val bad = ArrayList<String>()
        for (r in rows) {
            val bart = g2p(r["voice_lang"] as String).second
            val (ids, out) = bart.decodeIds(r["word"] as String)
            val wantIn = (r["input_ids"] as List<*>).map { (it as Number).toLong() }
            val wantOut = (r["output_ids"] as List<*>).map { (it as Number).toInt() }
            if (ids.toList() != wantIn || out != wantOut) {
                bad += "${r["voice_lang"]} ${r["word"]}: in ${ids.toList()} out $out | want $wantIn $wantOut (margin ${r["min_margin"]})"
            }
        }
        if (bad.isNotEmpty()) println(bad.joinToString("\n"))
        assertEquals(0, bad.size)
        println("golden-bart: ${rows.size} rows match")
    }

    @Test
    fun `golden chunks - chunk phonemes within 510`() {
        requireAssets()
        val rows = rows("golden-chunks.jsonl")
        assertEquals(4, rows.size)
        for (r in rows) {
            val got = g2p(r["voice_lang"] as String).first(r["text"] as String)
            assertEquals(r["phonemes"], got.phonemes)
            val chunks = KokoroChunker.chunk(got.tokens)
            val want = (r["chunks"] as List<*>).map { (it as Map<*, *>)["phonemes"] as String }
            assertEquals(want, chunks.map { it.phonemes })
            assertTrue(chunks.all { Py.len(it.phonemes) <= KokoroVocab.MAX_PHONEMES })
        }
    }
}
