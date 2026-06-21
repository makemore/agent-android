package com.makemore.agentfrontend.configuration

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp

/**
 * Visual tokens for the chat widget. Defaults reproduce the warm-dark
 * baseline (warm near-black surfaces, off-white text, coral accent).
 * Host apps override individual tokens to re-skin the widget without
 * having to fork the views.
 *
 * Pure data; no Compose views live here. View code reads these via
 * [ChatWidgetConfig.appearance]. Mirrors the iOS `ChatAppearance`
 * struct field-for-field.
 */
data class ChatAppearance(
    // Surfaces
    /** Root background colour behind the whole widget. */
    val background: Color = Color(0xFF262624),
    /** Card / composer surface colour (slightly lighter than background). */
    val surface: Color = Color(0xFF2F2F2D),
    /** Elevated surface colour used for the assistant message bubble. */
    val surfaceElevated: Color = Color(0xFF3A3A37),
    /** Divider hairline colour. */
    val divider: Color = Color.White.copy(alpha = 0.08f),

    // Text
    /** Primary text colour — body copy, assistant messages, greeting. */
    val textPrimary: Color = Color(0xFFF5F1E8),
    /** Muted text colour — placeholders, captions, secondary metadata. */
    val textSecondary: Color = Color(0xFFA8A29A),
    /** Text colour rendered on top of [accent] (user message bubble). */
    val textOnAccent: Color = Color.White,

    // Accent
    /** Brand accent — coral by default. Also used as the primary "send"
     *  button colour when [ChatWidgetConfig.primaryColor] is not
     *  customised by the host. */
    val accent: Color = Color(0xFFD97757),

    // Bubbles
    /** Background colour for the user's own message bubbles. When `null`
     *  the transcript falls back to [ChatWidgetConfig.primaryColor],
     *  preserving the prior (host-customisable) behaviour; set a value
     *  to theme the user side of the transcript independently. */
    val userBubble: Color? = null,
    /** Background colour for assistant message bubbles. When `null` the
     *  transcript falls back to the adaptive system grey it used before
     *  the warm-dark redesign, so `classic()` is unchanged. The default
     *  supplies the warm tone for the anthropic baseline. */
    val assistantBubble: Color? = Color(0xFF3A3A37),
    /** Background colour for tool / system message bubbles. When `null`
     *  the transcript falls back to the adaptive system grey, keeping
     *  `classic()` unchanged. The default supplies the warm tone for the
     *  anthropic baseline. */
    val systemBubble: Color? = Color(0xFF2F2F2D),
    /** Colour for markdown links and `requiredAction` labels in the
     *  transcript. When `null` the transcript falls back to
     *  [ChatWidgetConfig.primaryColor], matching the prior behaviour;
     *  set a value to re-tint links independently. */
    val link: Color? = null,

    // Typography
    /** Font family used for the empty-state greeting headline.
     *  [FontFamily.Serif] gives the warm editorial look the baseline
     *  ships with; [FontFamily.Default] falls back to the system font. */
    val greetingFontFamily: FontFamily = FontFamily.Serif,
    /** Greeting headline point size. */
    val greetingFontSize: TextUnit = 32.sp,

    // Layout knobs
    /** Composer layout variant. */
    val composerStyle: ComposerStyle = ComposerStyle.ANTHROPIC,
    /** Brand mark shown above the greeting text. */
    val brandMark: BrandMark = BrandMark.None,
    /** Corner radius applied to the composer card. */
    val composerCornerRadius: Dp = 28.dp,
    /** Corner radius applied to message bubbles. */
    val bubbleCornerRadius: Dp = 18.dp,
    /** Label rendered in the model pill on the anthropic composer.
     *  When `null` the pill is hidden. Host apps drive this from their
     *  currently selected model so the composer surfaces what's active. */
    val modelPillLabel: String? = null,
    /** How sub-agent activity surfaces in the UI. Library default is
     *  [SubAgentActivityStyle.PILL] so multi-specialist chains stay calm;
     *  the classic appearance opts back into [SubAgentActivityStyle.BUBBLES]
     *  for the old per-event behaviour. */
    val subAgentActivityStyle: SubAgentActivityStyle = SubAgentActivityStyle.PILL,
) {
    /** Composer layout. [ANTHROPIC] is the rounded card with a model pill
     *  and circular voice button; [CLASSIC] is the original single-row
     *  pill input. Library default is [ANTHROPIC]. */
    enum class ComposerStyle { CLASSIC, ANTHROPIC }

    /** How to render a sub-agent's activity while it is streaming.
     *
     *  - [PILL]: hide the per-event "🔗 Delegating…" / "✓ completed" /
     *    sub-agent streaming bubbles. Instead show a single quiet pill
     *    below the message list with the current sub-agent's name and a
     *    head-truncated tail of its latest output, then collapse to a
     *    "Consulted <agent> · Xs" row in the history once the bracket
     *    ends. The parent orchestrator's own final reply renders below it
     *    as the actual answer. This is the warm-dark default and keeps
     *    complex multi-specialist chains feeling calm and on-task.
     *
     *  - [BUBBLES]: original behaviour — every `sub_agent.start` /
     *    `assistant.delta` / `assistant.message` / `sub_agent.end` appears
     *    as a separate bubble or system row, and the parent's re-stream of
     *    the sub-agent's answer is suppressed as an echo. Kept for hosts on
     *    the classic appearance. */
    enum class SubAgentActivityStyle { PILL, BUBBLES }

    /** Empty-state brand mark. [None] hides the mark and shows the
     *  greeting text alone; [SystemIcon] renders a Material icon above
     *  the greeting so hosts can drop in their own glyph without
     *  shipping a custom view. */
    sealed class BrandMark {
        data object None : BrandMark()
        /** [name] is a Material icon name resolved by the view layer
         *  (e.g. "ChatBubbleOutline"). Kept as a string for parity with
         *  iOS `BrandMark.systemIcon(name:)`. */
        data class SystemIcon(val name: String) : BrandMark()
    }

    companion object {
        /** The original library look prior to the warm-dark redesign —
         *  classic composer, no brand mark, system text colours. Host
         *  apps that want the pre-0.8 appearance can opt out with one
         *  line: `cfg.appearance = ChatAppearance.classic()`. */
        fun classic(): ChatAppearance = ChatAppearance(
            background = Color.Unspecified,
            surface = Color.Unspecified,
            surfaceElevated = Color.Unspecified,
            divider = Color.Gray.copy(alpha = 0.2f),
            textPrimary = Color.Unspecified,
            textSecondary = Color.Unspecified,
            textOnAccent = Color.White,
            accent = Color(0xFF4A6B8E),
            assistantBubble = null,
            systemBubble = null,
            greetingFontFamily = FontFamily.Default,
            greetingFontSize = 17.sp,
            composerStyle = ComposerStyle.CLASSIC,
            brandMark = BrandMark.SystemIcon("ChatBubbleOutline"),
            composerCornerRadius = 20.dp,
            bubbleCornerRadius = 16.dp,
            subAgentActivityStyle = SubAgentActivityStyle.BUBBLES,
        )

        /** Warm-dark look — the new library default. Equivalent to
         *  calling `ChatAppearance()` with no arguments; exposed as a
         *  factory for clarity when assigning at the call site. */
        fun anthropic(): ChatAppearance = ChatAppearance()
    }
}
