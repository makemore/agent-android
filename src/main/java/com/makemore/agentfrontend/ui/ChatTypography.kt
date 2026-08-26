package com.makemore.agentfrontend.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.isSpecified
import com.makemore.agentfrontend.configuration.ChatAppearance

/**
 * Typography derived from the host's [ChatAppearance] message tokens.
 *
 * The transcript and the composer both need it — the composer measures
 * its own wrap height, so a field set in one size and measured in
 * another wraps a word early — hence a shared file rather than helpers
 * private to `MessageView`.
 *
 * Mirrors the derivations iOS does inline in `MarkdownTextView` and
 * `InputView`.
 */

/**
 * The assistant's prose style: the Material [base] restyled by the host's
 * message typography tokens.
 *
 * Every token defaults to "inherit", so an unthemed host lands back on
 * [base] exactly and nothing about the library default moves.
 *
 * Mirrors iOS `MarkdownTextView`'s `bodyFont` / `lineSpacing` pair.
 */
internal fun ChatAppearance.proseStyle(base: TextStyle, color: Color): TextStyle {
    val size = if (messageTextSize.isSpecified) messageTextSize else base.fontSize
    val pitch = when {
        messageLineHeight.isSpecified -> messageLineHeight
        // A size raised without a pitch to go with it would set solid
        // against the base style's fixed line height, so carry the base's
        // own size-to-pitch ratio up with it.
        size != base.fontSize && base.fontSize.isSpecified && base.lineHeight.isSpecified ->
            base.lineHeight * (size.value / base.fontSize.value)
        else -> base.lineHeight
    }
    return base.copy(
        color = color,
        fontFamily = messageFontFamily,
        fontSize = size,
        lineHeight = pitch,
        // Trim the leading off the first line's top and the last line's
        // bottom, and drop the font's own vertical padding.
        //
        // Compose applies `lineHeight` to *every* line, first and last
        // included, so each paragraph carries (pitch - natural) / 2 of dead
        // space above and below it. Invisible in isolation; at a paragraph
        // boundary the two halves meet and add a full `pitch - natural` on
        // top of `messageBlockSpacing`. `includeFontPadding` stacks more on
        // again, and a serif face has plenty of it.
        //
        // The iOS side has no equivalent: SwiftUI's `lineSpacing` only opens
        // gaps *between* wrapped lines and adds nothing above the first or
        // below the last. So identical tokens rendered visibly airier here —
        // and turning `messageBlockSpacing` down couldn't fix it, because
        // most of the gap wasn't the block spacing. With this, the token
        // means the same thing on both platforms.
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.Both,
        ),
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
}

/**
 * The user's own bubble text: [base] at the host's [ChatAppearance.userTextSize].
 * Face and pitch are deliberately left alone — the user side stays sans
 * so the two voices in the transcript read as distinct.
 */
internal fun ChatAppearance.userStyle(base: TextStyle): TextStyle =
    if (userTextSize.isSpecified) {
        base.copy(
            fontSize = userTextSize,
            lineHeight = if (base.fontSize.isSpecified && base.lineHeight.isSpecified) {
                base.lineHeight * (userTextSize.value / base.fontSize.value)
            } else {
                base.lineHeight
            },
        )
    } else {
        base
    }

/**
 * Headings sized *relative to the body*, so raising
 * [ChatAppearance.messageTextSize] lifts the whole reply and keeps the
 * hierarchy intact. h3 and below sit at body size and earn their rank
 * from weight alone.
 *
 * The ratios are iOS's ladder made explicit: there, headings step two
 * and one rungs up the Dynamic Type scale from the body style. That
 * scale isn't evenly spaced, so no single pair of multipliers matches it
 * at every size; these are taken at `.body`, which is where the hosts
 * actually sit — `.body` (17pt) steps to `.title2` (22pt, 1.3x) for h1
 * and `.title3` (20pt, 1.2x) for h2.
 */
internal fun TextStyle.heading(level: Int): TextStyle {
    val scale = when (level) {
        1 -> 1.3f
        2 -> 1.2f
        else -> 1.0f
    }
    return copy(
        fontSize = fontSize * scale,
        lineHeight = lineHeight * scale,
        fontWeight = FontWeight.Bold,
    )
}
