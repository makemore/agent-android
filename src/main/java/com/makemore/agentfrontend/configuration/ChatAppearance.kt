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
    /** Text colour inside the user's own message bubbles. When `null` the
     *  transcript falls back to [textOnAccent], the prior behaviour. Set a
     *  value when [userBubble] is not the accent colour — e.g. a neutral
     *  grey bubble whose text needs white while [textOnAccent] stays dark
     *  for the send button. Mirrors iOS `userBubbleText`. */
    val userBubbleText: Color? = null,
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
    /** Type size for the user's own messages and the composer field.
     *  Separate from [messageTextSize] because the two sides of the
     *  transcript are set in different faces — a sans bubble and serif
     *  prose at the same nominal size do not read as the same size — and
     *  because tool / system rows deliberately stay at the Material body
     *  size regardless.
     *
     *  [TextUnit.Unspecified] (the default) means "inherit the Material
     *  style", so an unthemed host is byte-identical to before.
     *
     *  iOS models this as `userTextStyle: Font.TextStyle`. Compose has no
     *  Dynamic Type ladder, but `sp` already tracks the system font-size
     *  setting, so the token is a size here: iOS `.title3` is `20.sp`. */
    val userTextSize: TextUnit = TextUnit.Unspecified,
    /** Base type size for assistant prose. Headings are sized *relative*
     *  to it (see [messageBlockSpacing]'s neighbours in `MessageView`),
     *  so raising this raises the whole reply coherently instead of
     *  flattening the hierarchy.
     *
     *  [TextUnit.Unspecified] means "inherit the Material style".
     *  Mirrors iOS `messageTextStyle`; iOS `.title3` is `20.sp`. */
    val messageTextSize: TextUnit = TextUnit.Unspecified,
    /** Line pitch for assistant prose — baseline to baseline. Long-form
     *  serif text needs more air than the Material default gives it.
     *  [TextUnit.Unspecified] means "inherit the Material style".
     *
     *  iOS expresses this as `messageLineSpacing`: *extra* leading added
     *  on top of the font's natural line height. Compose's `TextStyle`
     *  has no additive equivalent, so this token is the resulting total.
     *  iOS's 20pt serif at `lineSpacing: 6` is `30.sp` here. */
    val messageLineHeight: TextUnit = TextUnit.Unspecified,
    /** Typeface for assistant prose in the transcript — body copy,
     *  headings and list items alike. [FontFamily.Serif] gives the
     *  editorial look where the agent reads as the page rather than as a
     *  chat partner; [FontFamily.Default] keeps the system sans. Code
     *  blocks stay monospaced whatever this is — a serif code block is
     *  unreadable.
     *
     *  Deliberately does *not* touch user bubbles or UI chrome: those
     *  stay sans so the two voices in the transcript are typographically
     *  distinct. Mirrors iOS `messageFontDesign`. */
    val messageFontFamily: FontFamily = FontFamily.Default,
    /** Vertical gap between markdown blocks in an assistant reply —
     *  paragraph to paragraph, paragraph to heading, heading to list.
     *  The default repeats the markdown renderer's own `block` padding so
     *  hosts that don't set it see no change. Mirrors iOS
     *  `messageBlockSpacing` (whose library default is 6pt). */
    val messageBlockSpacing: Dp = 2.dp,

    // Layout knobs
    /** Composer layout variant. */
    val composerStyle: ComposerStyle = ComposerStyle.ANTHROPIC,
    /** Brand mark shown above the greeting text. */
    val brandMark: BrandMark = BrandMark.None,
    /** Size of that mark. Defaults to the Material icon size so existing
     *  hosts are unchanged; surfaces whose empty state leads with the glyph
     *  rather than the greeting will want it considerably larger. */
    val brandMarkSize: Dp = 24.dp,
    /** Corner radius applied to the composer card. */
    val composerCornerRadius: Dp = 28.dp,
    /** Corner radius applied to message bubbles. */
    val bubbleCornerRadius: Dp = 18.dp,
    /** Whether assistant replies are drawn as bubbles or as plain text on
     *  the background. Library default is [AssistantMessageStyle.BUBBLE].
     *  Mirrors iOS `assistantMessageStyle`. */
    val assistantMessageStyle: AssistantMessageStyle = AssistantMessageStyle.BUBBLE,
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

    /** How an assistant reply is drawn in the transcript.
     *
     *  - [BUBBLE]: the reply sits in its own filled, rounded bubble,
     *    mirroring the user's side of the conversation.
     *  - [PLAIN]: no fill, no bubble padding, no right-hand gutter — the
     *    reply is just text on the chat background, so the agent reads as
     *    the page itself rather than as another participant posting
     *    messages. The per-message avatar is suppressed in this style
     *    too; the presence orb above the list already carries agent
     *    identity. Tool, system and content-block rows keep their own
     *    treatments, which is what makes them legible as *not* prose. */
    enum class AssistantMessageStyle { BUBBLE, PLAIN }

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
