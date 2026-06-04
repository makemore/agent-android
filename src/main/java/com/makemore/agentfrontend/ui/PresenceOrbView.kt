package com.makemore.agentfrontend.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * A breathing, swirling sphere that signals agent presence. Compose port
 * of the iOS `PresenceOrbView` (which itself recreates
 * `clients/agent_presence_orb.svg`).
 *
 * Layout: the orb is anchored inside a square frame of side [baseSize].
 * In the default (hero) mode the orb scales up when [isSpeaking] is
 * true and shrinks when idle; the leading/centre swap is handled by
 * the parent. In [compact] mode the silhouette stays at a fixed size
 * (suitable for use as a per-message avatar) and the speaking signal
 * is delivered as a soft outer halo that fades in rather than a size
 * change, so the scrollback doesn't jitter when audio toggles.
 */
@Composable
fun PresenceOrbView(
    isSpeaking: Boolean,
    modifier: Modifier = Modifier,
    baseSize: Dp = 64.dp,
    compact: Boolean = false,
) {
    // Continuous time source — drives gradient breathing + swirl rotation
    // at 30 fps. Linear easing so the angle progresses uniformly.
    val transition = rememberInfiniteTransition(label = "orb-time")
    val tMillis by transition.animateFloat(
        initialValue = 0f,
        targetValue = TIME_PERIOD_MS,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = TIME_PERIOD_MS.toInt(), easing = LinearEasing),
        ),
        label = "orb-t",
    )

    // State-driven scale lives on the outer modifier so the implicit
    // animation tracks a stable identity (the canvas redraws on every
    // tMillis tick, which would eat any value-bound `animation` placed
    // inside the canvas). Compact mode pins to 1.0 so the avatar
    // silhouette never moves; hero mode keeps the original swap.
    val scale by animateFloatAsState(
        targetValue = if (compact) 1.0f else if (isSpeaking) 1.15f else 0.62f,
        animationSpec = tween(durationMillis = 550),
        label = "orb-scale",
    )
    // Compact-mode halo alpha. Cross-fades on `isSpeaking` so the glow
    // grows / fades softly without any movement of the silhouette.
    val compactHaloAlpha by animateFloatAsState(
        targetValue = if (compact && isSpeaking) 0.45f else 0f,
        animationSpec = tween(durationMillis = 550),
        label = "orb-compact-halo",
    )
    val description = if (isSpeaking) "Agent speaking" else "Agent idle"

    Canvas(
        modifier = modifier
            .size(baseSize)
            .semantics { contentDescription = description },
    ) {
        // Convert the dp baseSize to px and apply the implicit state scale.
        val side = size.minDimension * scale
        val cx = size.width / 2f
        val cy = size.height / 2f
        val t = tMillis / 1000.0   // seconds, double precision for trig

        // 1) Outer breathing halo — soft purple glow that sits just outside
        //    the core (radial gradient with mostly-transparent stops). In
        //    compact mode the Canvas is sized to `baseSize` exactly with
        //    no headroom for a bleed-out halo, so the speaking signal is
        //    delivered by brightening the natural halo and pulling its
        //    inner stop closer to the centre. This reads as the orb
        //    "lighting up" without any size or position change.
        val haloPhase = breathe(t, period = 4.0)
        val haloSize = side * (1.0f + 0.05f * haloPhase.toFloat())
        val haloBaseAlpha = 0.18f + compactHaloAlpha * 0.55f
        val haloInnerStop = 0.70f - compactHaloAlpha * 0.30f
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    haloInnerStop to Color(0xFF7F77DD).copy(alpha = 0f),
                    0.88f to Color(0xFF7F77DD).copy(alpha = haloBaseAlpha),
                    1.00f to Color(0xFF7F77DD).copy(alpha = 0f),
                ),
                center = Offset(cx, cy),
                radius = haloSize / 2f,
            ),
            radius = haloSize / 2f,
            center = Offset(cx, cy),
        )

        // 2) Core sphere — lavender→deep-purple radial gradient.
        val corePhase = breathe(t, period = 3.2)
        val coreSize = side * 0.75f * (1.0f + 0.025f * corePhase.toFloat())
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.00f to Color(0xFFAFA9EC),
                    0.45f to Color(0xFF7F77DD),
                    0.85f to Color(0xFF534AB7),
                    1.00f to Color(0xFF3C3489),
                ),
                center = Offset(cx, cy),
                radius = coreSize / 2f,
            ),
            radius = coreSize / 2f,
            center = Offset(cx, cy),
        )

        // 3) Swirling colour bands — ellipses clipped to the core circle
        //    so they paint the surface rather than spill outside.
        clipCircle(Offset(cx, cy), coreSize / 2f) {
            drawSwirl(
                t = t, side = side, center = Offset(cx, cy),
                gradient = SWIRL_GREEN, rxBase = 1.0f, ryBase = 0.4f,
                ryAmp = 0.16f, ryPeriod = 5.0, rotateFrom = 0.0,
                rotateDur = 9.0, alpha = 0.9f,
            )
            drawSwirl(
                t = t, side = side, center = Offset(cx, cy),
                gradient = SWIRL_BLUE, rxBase = 0.97f, ryBase = 0.36f,
                rxAmp = 0.08f, rxPeriod = 6.0, rotateFrom = 120.0,
                rotateDur = -11.0, alpha = 0.85f,
            )
            drawSwirl(
                t = t, side = side, center = Offset(cx, cy),
                gradient = SWIRL_GREEN, rxBase = 0.93f, ryBase = 0.28f,
                ryAmp = 0.15f, ryPeriod = 7.0, rotateFrom = 220.0,
                rotateDur = 13.0, alpha = 0.7f,
            )
            drawBloom(t = t, side = side, center = Offset(cx, cy))
        }

        // 4) Thin lavender rim — keeps the silhouette crisp even when
        //    the swirls fade toward the edge.
        drawCircle(
            color = Color(0xFFAFA9EC).copy(alpha = 0.4f),
            radius = coreSize / 2f,
            center = Offset(cx, cy),
            style = Stroke(width = (side * 0.006f).coerceAtLeast(0.5f)),
        )
    }
}

/** Long enough that the swirl rotations don't pop on the loop. The
 *  swirls' rotateDur values (9 / 11 / 13s) all divide cleanly into
 *  60s so the orb pattern loops seamlessly. */
private const val TIME_PERIOD_MS: Float = 60_000f

/** Returns a value in [-1, 1] that sweeps smoothly with the given period. */
private fun breathe(t: Double, period: Double): Double =
    sin(t * 2.0 * Math.PI / period)

private fun DrawScope.clipCircle(
    center: Offset,
    radius: Float,
    block: DrawScope.() -> Unit,
) {
    val path = Path().apply {
        addOval(Rect(center = center, radius = radius))
    }
    clipPath(path = path, block = block)
}

@Suppress("LongParameterList")
private fun DrawScope.drawSwirl(
    t: Double,
    side: Float,
    center: Offset,
    gradient: Array<Pair<Float, Color>>,
    rxBase: Float,
    ryBase: Float,
    rxAmp: Float = 0f,
    rxPeriod: Double = 0.0,
    ryAmp: Float = 0f,
    ryPeriod: Double = 0.0,
    rotateFrom: Double,
    rotateDur: Double,
    alpha: Float,
) {
    val rxScale = rxBase + if (rxPeriod > 0) rxAmp * breathe(t, rxPeriod).toFloat() else 0f
    val ryScale = ryBase + if (ryPeriod > 0) ryAmp * breathe(t, ryPeriod).toFloat() else 0f
    val angle = (rotateFrom + if (rotateDur == 0.0) 0.0 else (t / rotateDur) * 360.0).toFloat()
    val w = side * rxScale
    val h = side * ryScale
    rotate(degrees = angle, pivot = center) {
        drawOval(
            brush = Brush.radialGradient(
                colorStops = gradient,
                center = center,
                radius = side * 0.5f,
            ),
            topLeft = Offset(center.x - w / 2f, center.y - h / 2f),
            size = Size(w, h),
            alpha = alpha,
        )
    }
}

private fun DrawScope.drawBloom(
    t: Double,
    side: Float,
    center: Offset,
) {
    // Orange-pink bloom drifts off-centre to mimic specular hot-spot.
    val cx = (sin(t * 2.0 * Math.PI / 6.0) * side * 0.06).toFloat()
    val cy = (cos(t * 2.0 * Math.PI / 5.5) * side * 0.06).toFloat()
    val rPhase = breathe(t, 4.5).toFloat()
    val s = side * 0.62f + rPhase * side * 0.08f
    val bloomCenter = Offset(center.x + cx, center.y + cy)
    // Bloom's radial gradient origin is offset to the upper-left to mimic
    // the SVG's `UnitPoint(0.35, 0.35)` — bias the brush centre by ~-15%
    // of the ellipse on each axis.
    val brushCenter = Offset(bloomCenter.x - s * 0.15f, bloomCenter.y - s * 0.15f)
    drawOval(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.0f to Color(0xFFF09A7B).copy(alpha = 0.85f),
                0.6f to Color(0xFFD4537E).copy(alpha = 0.35f),
                1.0f to Color(0xFFD4537E).copy(alpha = 0f),
            ),
            center = brushCenter,
            radius = s * 0.5f,
        ),
        topLeft = Offset(bloomCenter.x - s / 2f, bloomCenter.y - s / 2f),
        size = Size(s, s),
    )
}

private val SWIRL_GREEN: Array<Pair<Float, Color>> = arrayOf(
    0.00f to Color(0xFF5DCAA5).copy(alpha = 0f),
    0.55f to Color(0xFF5DCAA5).copy(alpha = 0.55f),
    1.00f to Color(0xFF1D9E75).copy(alpha = 0f),
)

private val SWIRL_BLUE: Array<Pair<Float, Color>> = arrayOf(
    0.00f to Color(0xFF85B7EB).copy(alpha = 0f),
    0.50f to Color(0xFF378AdD).copy(alpha = 0.5f),
    1.00f to Color(0xFF185FA5).copy(alpha = 0f),
)
