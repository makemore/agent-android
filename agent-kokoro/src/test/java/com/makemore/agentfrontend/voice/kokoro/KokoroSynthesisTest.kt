package com.makemore.agentfrontend.voice.kokoro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

/**
 * End to end with the real model on the JVM (ONNX Runtime's desktop build):
 * text -> phonemes -> Kokoro -> audio. Needs `-PkokoroAssets` (see
 * [KokoroGoldenTest]); skipped otherwise. Prints load time, time to the first
 * chunk and the real-time factor of this machine.
 */
class KokoroSynthesisTest {
    private fun localFiles(root: File, voice: KokoroVoice): KokoroLocalFiles {
        val g = File(root, "g2p/${voice.language}")
        return KokoroLocalFiles(
            model = File(root, "model/kokoro-v1.0-q8.onnx"),
            vocab = File(root, "model/vocab.json"),
            voice = File(root, "voices/${voice.id}.bin"),
            lang = voice.language,
            gold = File(g, "gold.json.gz"),
            goldGzip = true,
            silver = File(g, "silver.json.gz"),
            silverGzip = true,
            g2pModel = File(g, "g2p.onnx"),
            g2pVocab = File(g, "g2p-vocab.json"),
        )
    }

    private fun rms(a: FloatArray): Double = sqrt(a.fold(0.0) { s, x -> s + x * x } / a.size.coerceAtLeast(1))

    @Test
    fun `speaks non-silent audio of plausible length in both languages`() {
        val root = KokoroGoldenTest.requireAssets()
        val heart = KokoroVoices.find("af_heart")!!
        val emma = KokoroVoices.find("bf_emma")!!

        var t0 = System.nanoTime()
        val runtime = KokoroRuntime.load(localFiles(root, heart), threads = 0)
        val modelMs = (System.nanoTime() - t0) / 1_000_000
        t0 = System.nanoTime()
        runtime.ensure(localFiles(root, heart), heart.id)
        val usMs = (System.nanoTime() - t0) / 1_000_000
        t0 = System.nanoTime()
        runtime.ensure(localFiles(root, emma), emma.id)
        val gbMs = (System.nanoTime() - t0) / 1_000_000
        println("load: kokoro session $modelMs ms, en-us G2P+voice $usMs ms, en-gb G2P+voice $gbMs ms")

        // Warm-up (first ONNX run allocates).
        runtime.generate("Hi.", heart, 1f) { true }

        val text = "Hello! This is the on-device voice, speaking without the network. " +
            "It costs \$3.50 on January 15, 2024, at 3:30 p.m."
        runtime.use { rt ->
            for (voice in listOf(heart, emma)) {
                val pieces = ArrayList<FloatArray>()
                val started = System.nanoTime()
                var firstMs = -1L
                val stats = rt.generate(text, voice, 1f) { samples ->
                    if (firstMs < 0) firstMs = (System.nanoTime() - started) / 1_000_000
                    pieces += samples
                    true
                }
                val audio = pieces.flatMap { it.asIterable() }.toFloatArray()
                val seconds = audio.size / 24_000.0
                println(
                    String.format(
                        Locale.ROOT,
                        "%s: %d chunks, %.2f s audio in %.2f s (%.1fx real time), first chunk after %d ms, rms %.3f",
                        voice.id, stats.chunkCount, stats.audioSeconds, stats.synthSeconds,
                        stats.audioSeconds / stats.synthSeconds, firstMs, rms(audio),
                    ),
                )
                assertEquals(seconds, stats.audioSeconds, 1e-6)
                assertTrue("plausible length: $seconds s", seconds in 5.0..20.0)
                assertTrue("not silent", rms(audio) > 0.02)
                assertTrue("clipped to [-1, 1]", audio.all { it in -1f..1f })
                assertTrue("streamed in more than one piece", stats.chunkCount >= 2)
            }
        }
    }
}
