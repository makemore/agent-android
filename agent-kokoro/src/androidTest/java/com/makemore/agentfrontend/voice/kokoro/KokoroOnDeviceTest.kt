package com.makemore.agentfrontend.voice.kokoro

import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.makemore.agentfrontend.voice.TTSProvider
import com.makemore.agentfrontend.voice.TTSSpeakOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

/**
 * The real engine on a device or emulator: ONNX Runtime for Android, the real
 * model, AudioTrack playback. Skipped unless the asset folder was copied into
 * the test app's files directory, e.g.:
 *
 * ```
 * ./gradlew :agent-kokoro:installDebugAndroidTest
 * adb push tools/kokoro-assets/build/kokoro/v1 /data/local/tmp/kokoro-v1
 * adb shell run-as com.makemore.agentfrontend.voice.kokoro.test \
 *   sh -c "'mkdir -p files/kokoro && cp -r /data/local/tmp/kokoro-v1 files/kokoro/v1'"
 * adb shell am instrument -w -e class com.makemore.agentfrontend.voice.kokoro.KokoroOnDeviceTest \
 *   com.makemore.agentfrontend.voice.kokoro.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * (`connectedDebugAndroidTest` reinstalls the app and so removes the copy.)
 *
 * The engine "downloads" from that folder through a `file://` base URL, so the
 * manifest, size and SHA-256 checks run exactly as for HTTPS. Timings are
 * logged under the tag `KokoroOnDeviceTest` and reported as instrumentation
 * status.
 */
@RunWith(AndroidJUnit4::class)
class KokoroOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun report(message: String) {
        Log.i(TAG, message)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$TAG: $message\n") })
    }

    private fun assetsFolder(): File {
        val dir = File(context.filesDir, "kokoro/v1")
        assumeTrue("push the Kokoro asset folder to ${dir.path} to run this test", File(dir, "manifest.json").isFile)
        return dir
    }

    private fun rms(a: FloatArray) = sqrt(a.fold(0.0) { s, x -> s + x * x } / a.size.coerceAtLeast(1))

    @Test
    fun synthesisAndPlaybackOnDevice() = runBlocking {
        val assets = assetsFolder()
        val cache = File(context.cacheDir, "kokoro-test").apply { deleteRecursively() }
        val engine = KokoroTTS.engine(
            context,
            KokoroOptions(baseUrl = assets.toURI().toString(), voice = "af_heart", cacheDirectory = cache),
        )

        var t = System.nanoTime()
        engine.prepare()
        report("prepare (verify + copy ${engine.downloadedBytes} bytes, load): ${(System.nanoTime() - t) / 1_000_000} ms; load ${engine.lastLoadMs} ms")
        assertEquals(KokoroState.Ready, engine.state.value)

        val heart = engine.voice
        val emma = KokoroVoices.find("bf_emma")!!
        t = System.nanoTime()
        engine.synthesizerFor(emma)
        report("bf_emma (en-gb G2P + voice) ready in ${(System.nanoTime() - t) / 1_000_000} ms")

        val synth = engine.synthesizerFor(heart)
        val text = "Hello! This is the on-device voice, speaking without the network. " +
            "It costs \$3.50 on January 15, 2024, at 3:30 p.m."
        synth.generate("Warm up.", heart, 1f) { true }
        for (voice in listOf(heart, emma)) {
            val pieces = ArrayList<FloatArray>()
            val started = System.nanoTime()
            var firstMs = -1L
            val stats = synth.generate(text, voice, 1f) { samples ->
                if (firstMs < 0) firstMs = (System.nanoTime() - started) / 1_000_000
                pieces += samples
                true
            }
            val audio = pieces.flatMap { it.asIterable() }.toFloatArray()
            report(
                String.format(
                    Locale.ROOT,
                    "%s: %d chunks, %.2f s audio in %.2f s (%.2fx real time), first chunk %d ms, rms %.3f",
                    voice.id, stats.chunkCount, stats.audioSeconds, stats.synthSeconds,
                    stats.audioSeconds / stats.synthSeconds, firstMs, rms(audio),
                ),
            )
            assertTrue(stats.audioSeconds in 5.0..20.0)
            assertTrue(rms(audio) > 0.02)
        }

        // Through the provider with real AudioTrack playback: time to first audio.
        val metrics = ArrayList<KokoroSpeechMetrics>()
        engine.onSpeechMetrics = { synchronized(metrics) { metrics += it } }
        val fallback = object : TTSProvider {
            override val name = "unused"
            override suspend fun speak(text: String, options: TTSSpeakOptions) = throw AssertionError("fell back")
            override fun cancel() {}
        }
        val provider = KokoroTTSProvider(engine, "af_heart") { fallback }
        // As VoiceController does: the next sentence is prefetched while the first plays.
        val replies = listOf(
            "Sure! Here is what I found." to "The meeting moved to Thursday at 10:30, in room four.",
            "The meeting moved to Thursday at 10:30, in room four." to "Bring the slides from last week.",
        )
        for ((first, second) in replies) {
            val speaking = async(Dispatchers.Default) { provider.speak(first) }
            provider.prefetch(second)
            speaking.await()
            provider.speak(second)
        }
        synchronized(metrics) {
            assertEquals(4, metrics.size)
            metrics.forEach { m ->
                report(
                    String.format(
                        Locale.ROOT,
                        "speak(): first audio %d ms (load %d ms), %d chunks, %.2f s audio in %.2f s",
                        m.firstAudioMs, m.loadMs, m.chunkCount, m.audioSeconds, m.synthSeconds,
                    ),
                )
                assertTrue(m.firstAudioMs >= 0)
            }
        }
        provider.shutdown()
        engine.deleteDownloadedModel()
        assertEquals(0L, engine.downloadedBytes)
    }

    private companion object {
        const val TAG = "KokoroOnDeviceTest"
    }
}
