package com.makemore.agentfrontend.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Live scrolling waveform shown in place of the text field while
 * dictation is active. Mirrors the iOS `RecordingWaveformView`.
 *
 * Driven by a single normalised [level] (0..1) pushed in by the
 * composer's recogniser callback.
 *
 * The bars sit at fixed positions and only their *heights* change, as on
 * iOS — the scrolling look is emergent from the history shifting through
 * a fixed row, not from moving anything sideways. (An earlier version
 * offset the row by a fraction of a slot to fake a continuous scroll;
 * that fought the height updates and read as a glitch.)
 *
 * One bar per pushed level, not one per frame: appending on every
 * recomposition advanced the history at display rate instead of the
 * recogniser's cadence, which made the waveform race. iOS appends in
 * `onChange(of: level)` for the same reason.
 *
 * At rest the level sits near zero and every bar renders at
 * [MIN_BAR_HEIGHT] — a 3dp-square capsule, i.e. a dot. That flat row of
 * dots is the idle state on both platforms.
 *
 * Deliberately dumb — no audio work happens here. `SpeechRecognizer`
 * already reports `onRmsChanged` for the stream it is consuming, so the
 * composer normalises that and hands the result over.
 */
@Composable
internal fun RecordingWaveformView(
    level: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val history = remember { mutableStateListOf<Float>() }
    val currentLevel by rememberUpdatedState(level.coerceIn(0f, 1f))

    // Sample on a fixed cadence rather than on each pushed value.
    // `SpeechRecognizer` reports RMS irregularly (40-100 ms here) and
    // repeats identical readings, which Compose coalesces away — keying
    // the append on the value left the row frozen between distinct
    // readings and then lurching, which is the stop-start judder. A
    // steady tick decouples scroll speed from callback jitter, so the
    // waveform advances at one rate whatever the recogniser does.
    LaunchedEffect(Unit) {
        while (isActive) {
            history.add(currentLevel)
            if (history.size > HISTORY_CAP) history.removeAt(0)
            delay(FRAME_MS)
        }
    }

    Box(modifier.fillMaxWidth().height(PREFERRED_HEIGHT.dp)) {
        Canvas(Modifier.fillMaxWidth().height(PREFERRED_HEIGHT.dp)) {
            val barW = BAR_WIDTH.dp.toPx()
            val spacing = BAR_SPACING.dp.toPx()
            val minH = MIN_BAR_HEIGHT.dp.toPx()
            val maxH = size.height
            val slot = barW + spacing
            if (slot <= 0f) return@Canvas
            val barCount = ((size.width + spacing) / slot).toInt().coerceAtLeast(1)
            // Every bar draws, always. The newest samples fill from the
            // right and the front is zero-padded, so an empty history is a
            // full row of dots rather than a single dot creeping in from
            // the edge. Mirrors the iOS `sample(at:of:)` padding.
            val window = history.takeLast(barCount)
            val padding = barCount - window.size
            repeat(barCount) { i ->
                val sample = if (i < padding) 0f else window[i - padding]
                val h = (minH + (maxH - minH) * sample).coerceIn(minH, maxH)
                val x = i * slot
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, (size.height - h) / 2f),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(barW / 2f),
                )
            }
        }
    }
}

/** Height of the waveform row. The composer clamps the hidden text
 *  field to this while dictating so the two agree by construction. */
internal const val PREFERRED_HEIGHT = 24
private const val BAR_WIDTH = 3
private const val BAR_SPACING = 3

/** Bar height at silence — a 3dp square, i.e. a dot. */
private const val MIN_BAR_HEIGHT = 3
/** Bar height at ordinary speaking volume: the resting size the waveform
 *  sits at in normal use. Bars grow above this as the room gets louder
 *  and fall toward [MIN_BAR_HEIGHT] as it quietens. */
private const val NORMAL_BAR_HEIGHT = 10
/** Bar height at full scale — the whole row. */
private const val MAX_BAR_HEIGHT = PREFERRED_HEIGHT

/** The 0..1 level that renders a bar at [NORMAL_BAR_HEIGHT]. The level
 *  mapping in InputView anchors ordinary speech here, so the waveform
 *  defaults to normal-sized bars and grows with what it hears. */
internal const val NORMAL_LEVEL =
    (NORMAL_BAR_HEIGHT - MIN_BAR_HEIGHT).toFloat() / (MAX_BAR_HEIGHT - MIN_BAR_HEIGHT)

private const val HISTORY_CAP = 400

/** Sampling interval for the scroll — one bar per tick. Close to the
 *  recogniser's own reporting rate, so no detail is lost. */
private const val FRAME_MS = 60L
