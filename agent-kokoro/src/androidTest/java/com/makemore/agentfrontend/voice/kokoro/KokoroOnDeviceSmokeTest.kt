package com.makemore.agentfrontend.voice.kokoro

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.makemore.agentfrontend.voice.TTSProvider
import com.makemore.agentfrontend.voice.TTSSpeakOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections

/**
 * Opt-in smoke test with the REAL model, sherpa-onnx JNI and AudioTrack.
 * Skipped unless the archive has been pushed to the device first:
 *
 * ```
 * curl -LO https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2
 * adb push kokoro-int8-multi-lang-v1_0.tar.bz2 /data/local/tmp/kokoro.tar.bz2
 * ./gradlew :agent-kokoro:connectedDebugAndroidTest
 * ```
 *
 * The archive is read through the shell (no network), installed by the real
 * [KokoroModelManager], then spoken by the real [KokoroTTSProvider].
 */
@RunWith(AndroidJUnit4::class)
class KokoroOnDeviceSmokeTest {
    companion object {
        private const val ARCHIVE = "/data/local/tmp/kokoro.tar.bz2"
        private const val TAG = "KokoroSmoke"
        private lateinit var manager: KokoroModelManager

        private fun shell(command: String): ParcelFileDescriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)

        @BeforeClass
        @JvmStatic
        fun install() {
            val size = ParcelFileDescriptor.AutoCloseInputStream(shell("stat -c %s $ARCHIVE"))
                .bufferedReader().readText().trim().toLongOrNull()
            assumeTrue("push the model archive to $ARCHIVE to run this test", size != null && size > 0)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dir = File(context.noBackupFilesDir, "agent-kokoro-smoke")
            manager = KokoroModelManager(
                directory = dir,
                fetcher = KokoroModelFetcher { _ ->
                    KokoroModelResponse(ParcelFileDescriptor.AutoCloseInputStream(shell("cat $ARCHIVE")), size)
                },
            )
            val started = System.nanoTime()
            val state = runBlocking { withTimeout(600_000) { manager.download() } }
            Log.i(TAG, "install: $state in ${(System.nanoTime() - started) / 1_000_000} ms")
            assertEquals(KokoroModelState.Ready, state)
        }
    }

    private class RecordingFallback : TTSProvider {
        override val name = "fallback"
        val spoken: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun speak(text: String, options: TTSSpeakOptions) { spoken += text }
        override fun cancel() {}
    }

    @Test
    fun speaksWithTheRealEngine() = runBlocking {
        val fallback = RecordingFallback()
        val provider = KokoroTTSProvider(manager, KokoroOptions(), defaultVoiceId = "af_heart") { fallback }
        try {
            var started = System.nanoTime()
            assertTrue("engine loads", withContext(Dispatchers.Main) { provider.prepare() })
            Log.i(TAG, "engine load ${(System.nanoTime() - started) / 1_000_000} ms")

            for ((text, voice) in listOf(
                "Hello! This is Kokoro speaking on the device." to null,
                "And this is a British voice, reading the second chunk." to "bm_george",
                "Hola, esto es una prueba en español." to "ef_dora",
            )) {
                started = System.nanoTime()
                withContext(Dispatchers.Main) {
                    withTimeout(60_000) { provider.speak(text, TTSSpeakOptions(voiceId = voice)) }
                }
                Log.i(TAG, "spoke chunk (${voice ?: "default"}) in ${(System.nanoTime() - started) / 1_000_000} ms")
            }
            assertEquals("no fallback with a healthy engine", emptyList<String>(), fallback.spoken)
        } finally {
            provider.shutdown()
        }
    }

    @Test
    fun cancelStopsRealPlaybackPromptly() = runBlocking {
        val fallback = RecordingFallback()
        val provider = KokoroTTSProvider(manager, KokoroOptions()) { fallback }
        try {
            withContext(Dispatchers.Main) { provider.prepare() }
            val long = "This is a deliberately long chunk of text. ".repeat(6)
            val speaking = async(Dispatchers.Main) { runCatching { provider.speak(long) } }
            delay(2_500)
            val started = System.nanoTime()
            withContext(Dispatchers.Main) { provider.cancel() }
            val result = withTimeout(5_000) { speaking.await() }
            val ms = (System.nanoTime() - started) / 1_000_000
            Log.i(TAG, "cancel returned in $ms ms")
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertTrue("cancel within 1 s (was $ms ms)", ms < 1_000)
            assertEquals(emptyList<String>(), fallback.spoken)
        } finally {
            provider.shutdown()
        }
    }
}
