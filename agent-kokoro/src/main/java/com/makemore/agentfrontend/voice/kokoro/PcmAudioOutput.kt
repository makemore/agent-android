package com.makemore.agentfrontend.voice.kokoro

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Opens one playback per utterance. */
fun interface PcmAudioOutput {
    fun open(sampleRate: Int): PcmPlayback
}

/** Mono float PCM playback of one utterance. Cancellable at every suspension point. */
interface PcmPlayback {
    /** Queue [samples] for playback (suspends while the device buffer is full). */
    suspend fun write(samples: FloatArray)

    /** Suspend until everything written has been heard, then release. */
    suspend fun finish()

    /** Stop immediately and release. Safe from any thread, idempotent. */
    fun stop()
}

/** [AudioTrack] (MODE_STREAM, PCM float) output with speech attributes. */
internal object AudioTrackOutput : PcmAudioOutput {
    override fun open(sampleRate: Int): PcmPlayback = AudioTrackPlayback(sampleRate)
}

private class AudioTrackPlayback(private val sampleRate: Int) : PcmPlayback {
    private val bufferBytes: Int = maxOf(
        AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT),
        sampleRate * 4 / 4, // ~250 ms
    )
    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(bufferBytes)
        .build()
        .also { it.play() }

    private var framesWritten = 0L

    @Volatile
    private var released = false

    override suspend fun write(samples: FloatArray) {
        writeAll(samples, samples.size)
        framesWritten += samples.size
    }

    override suspend fun finish() {
        try {
            if (framesWritten == 0L) return
            // A streaming track may not start until its buffer is full, so pad
            // with one buffer of silence and stop once the real audio has played.
            val padFrames = bufferBytes / 4
            val budgetMs = (framesWritten - headPosition()).coerceAtLeast(0) * 1000 / sampleRate + 2_000
            withTimeoutOrNull(budgetMs) {
                writeAll(FloatArray(padFrames), padFrames, stopWhenPlayed = true)
                while (headPosition() < framesWritten && !released) delay(10)
            }
        } finally {
            stop()
        }
    }

    override fun stop() {
        synchronized(this) {
            if (released) return
            released = true
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
        }
    }

    private fun headPosition(): Long =
        synchronized(this) { if (released) Long.MAX_VALUE else track.playbackHeadPosition.toLong() and 0xffffffffL }

    private suspend fun writeAll(samples: FloatArray, count: Int, stopWhenPlayed: Boolean = false) {
        var offset = 0
        while (offset < count) {
            currentCoroutineContext().ensureActive()
            if (stopWhenPlayed && headPosition() >= framesWritten) return
            val n = synchronized(this) {
                if (released) return
                track.write(samples, offset, count - offset, AudioTrack.WRITE_NON_BLOCKING)
            }
            if (n < 0) throw IllegalStateException("AudioTrack write failed ($n)")
            offset += n
            if (offset < count) delay(10)
        }
    }
}
