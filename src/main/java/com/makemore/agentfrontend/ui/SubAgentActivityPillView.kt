package com.makemore.agentfrontend.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.makemore.agentfrontend.configuration.ChatAppearance
import com.makemore.agentfrontend.models.SubAgentActivityState
import kotlinx.coroutines.delay

/**
 * Quiet activity indicator shown in the message list while a sub-agent
 * bracket is in flight. Replaces the generic "Thinking…" spinner in pill
 * mode ([ChatAppearance.subAgentActivityStyle] == `PILL`) so the user sees
 * *who* is thinking and a hint of *what* they're saying, without each delta
 * producing its own speech bubble. Mirrors the iOS `SubAgentActivityPillView`.
 *
 * Layout (compact card, up to 3 lines of live ticker text):
 * ```
 *     ✨ <agent name> · <tool?>                       <Xs>
 *        <live text tail line 1>
 *        <live text tail line 2>
 *        <live text tail line 3>
 * ```
 * where the live-text tail truncates from the head as more content streams
 * in, giving a ticker-style "scrolling past" feel — older lines disappear
 * off the top while newer text appears at the bottom.
 */
@Composable
fun SubAgentActivityPillView(
    activity: SubAgentActivityState,
    appearance: ChatAppearance,
    modifier: Modifier = Modifier,
) {
    val frame = activity.topFrame ?: return

    // Drives the elapsed-seconds counter on the right of the pill. A 1 Hz
    // ticker re-reads the wall clock while the pill is on screen.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(frame.id) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }
    val startMs = (activity.bracketStartedAt ?: frame.startedAt).time
    val elapsed = ((nowMs - startMs) / 1000).coerceAtLeast(0)

    val surface = appearance.surfaceElevated.takeOrElse { Color(0xFF3A3A37) }
    val textPrimary = appearance.textPrimary.takeOrElse { Color(0xFFF5F1E8) }
    val textSecondary = appearance.textSecondary.takeOrElse { Color(0xFFA8A29A) }
    val accent = appearance.accent

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = surface,
    ) {
        Row(
            modifier = Modifier
                .border(0.5.dp, appearance.divider.takeOrElse { Color.White.copy(alpha = 0.08f) }, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.padding(top = 1.dp).width(16.dp),
            )

            // Name + tool caption, then the live ticker tail. weight(1f)
            // so the seconds counter is pushed to the trailing edge.
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = frame.agentName,
                        color = textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    frame.currentToolName?.let { tool ->
                        Text(
                            text = "· $tool",
                            color = textSecondary,
                            fontSize = 12.sp,
                        )
                    }
                }
                Text(
                    text = tickerText(frame.liveText),
                    color = textSecondary,
                    fontSize = 12.sp,
                    // Reserve three lines so the pill height stays stable as
                    // text streams in (iOS uses `reservesSpace: true`).
                    minLines = TICKER_LINE_LIMIT,
                    maxLines = TICKER_LINE_LIMIT,
                    // `tickerText` already keeps only the trailing slice and
                    // prefixes a leading "…", giving the "tail of a scrolling
                    // ticker" feel — older text falls off the top, newest
                    // stays visible. Clip drops any residual overflow rather
                    // than risking the multi-line StartEllipsis limitation.
                    overflow = TextOverflow.Clip,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = "${elapsed}s",
                color = textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** Number of live-ticker lines the pill reserves. Three gives the
 *  affordance enough room to feel "alive" while streaming without
 *  dominating the chat history. */
private const val TICKER_LINE_LIMIT = 3

/** Maximum trailing characters of live text fed to the ticker. Sized to
 *  comfortably fill the three reserved lines (~60 chars/line) without
 *  overflowing them, so the newest narration always stays on screen while
 *  older text is dropped from the head. */
private const val TICKER_MAX_CHARS = 180

/** Take only the trailing slice of the live text so the visual feel is
 *  "tail of a scrolling ticker" rather than "growing paragraph". When
 *  truncated, a leading "…" signals there's more text above the tail. */
private fun tickerText(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return "…"
    if (trimmed.length <= TICKER_MAX_CHARS) return trimmed
    return "…" + trimmed.substring(trimmed.length - TICKER_MAX_CHARS)
}
