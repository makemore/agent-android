package com.makemore.agentfrontend.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.makemore.agentfrontend.configuration.ChatAppearance
import com.makemore.agentfrontend.configuration.ChatWidgetConfig
import java.util.Calendar

/**
 * Centered empty-state for the chat widget. Renders the optional
 * brand mark chosen by [ChatAppearance.brandMark] (none by default)
 * above a serif greeting line driven by
 * [ChatGreetingConfig.currentLine].
 *
 * Reads everything from the supplied [config] \u2014 no environment
 * dependencies \u2014 so host apps embedding `MessageListView` directly
 * get the same look without extra plumbing. Mirrors the iOS
 * `GreetingView` field-for-field.
 *
 * @param hourOverride optional hour-of-day override for tests /
 *   previews; `null` reads the device clock.
 */
@Composable
fun GreetingView(
    config: ChatWidgetConfig,
    hourOverride: Int? = null,
) {
    val line = if (hourOverride != null) {
        config.greeting.lineForHour(hourOverride)
    } else {
        config.greeting.currentLine(Calendar.getInstance())
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp)
            .semantics {
                testTag = "chat.empty.greeting"
                contentDescription = line
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        BrandMarkView(config.appearance)
        Text(
            text = line,
            fontFamily = config.appearance.greetingFontFamily,
            fontSize = config.appearance.greetingFontSize,
            fontWeight = FontWeight.Normal,
            color = config.appearance.textPrimary,
            textAlign = TextAlign.Center,
        )
    }
}

/** Resolves a small set of named Material icons used by
 *  [ChatAppearance.BrandMark.SystemIcon]. Names match the SF-Symbol
 *  identifiers used by the iOS side so host configs stay portable
 *  across platforms. Unknown names fall back to a chat bubble. */
@Composable
private fun BrandMarkView(appearance: ChatAppearance) {
    val mark = appearance.brandMark
    if (mark !is ChatAppearance.BrandMark.SystemIcon) return
    val icon: ImageVector = when (mark.name) {
        "ChatBubbleOutline", "bubble.left" -> Icons.Outlined.ChatBubbleOutline
        "AutoAwesome", "sparkles" -> Icons.Outlined.AutoAwesome
        "Chat" -> Icons.AutoMirrored.Outlined.Chat
        else -> Icons.Outlined.ChatBubbleOutline
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = appearance.accent,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}
