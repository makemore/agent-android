package com.makemore.agentfrontend.models

import java.util.Date
import java.util.UUID

/**
 * Transient state describing "what the sub-agents are doing right now".
 * Populated only when [com.makemore.agentfrontend.configuration.ChatAppearance.subAgentActivityStyle]
 * is `PILL` — the warm-dark default. In `BUBBLES` mode it stays empty and
 * the UI falls back to the legacy bubble per event.
 *
 * Mirrors the iOS `SubAgentActivityState` struct. The model is a small
 * stack of [Frame]s because sub-agents can recurse (a triage agent invokes
 * a specialist that itself invokes a tool-using helper). The outermost
 * frame's [bracketStartedAt] drives the "Consulted X · 4s" caption that
 * ends up on the collapsed history row.
 *
 * Immutable: the mutators return a new instance so the value can live in a
 * Compose `mutableStateOf` and trigger recomposition on reassignment —
 * the Kotlin/Compose equivalent of iOS's `@Published` + `mutating func`.
 */
data class SubAgentActivityState(
    /** Stack of in-flight sub-agent invocations, outermost first. */
    val frames: List<Frame> = emptyList(),
    /** When the *outermost* frame was pushed. Captured separately from
     *  `frames.first().startedAt` so the counter keeps ticking accurately
     *  across nested push/pop activity within a single bracket. */
    val bracketStartedAt: Date? = null,
) {
    /** One in-flight sub-agent invocation. Pushed on `sub_agent.start`,
     *  updated by intermediate `assistant.delta` / `assistant.message` /
     *  `tool.call` events that arrive while it's on top of the stack, and
     *  popped on the matching `sub_agent.end`. */
    data class Frame(
        val id: String = UUID.randomUUID().toString(),
        val agentName: String,
        val subAgentKey: String? = null,
        val startedAt: Date = Date(),
        /** Latest snippet of streamed text from the sub-agent. The pill
         *  view head-truncates this so it reads as a live ticker tail
         *  rather than a wall of accumulating prose. */
        val liveText: String = "",
        /** Most recent tool the sub-agent invoked, surfaced in the pill as
         *  a subtle "· <tool>" caption. Cleared when a new delta arrives so
         *  the pill prioritises the live narration. */
        val currentToolName: String? = null,
    )

    /** True while at least one sub-agent is in flight. The UI uses this to
     *  decide whether to render the activity pill in place of the generic
     *  "Thinking…" spinner. */
    val isActive: Boolean get() = frames.isNotEmpty()

    /** The frame currently receiving deltas / tool events (top of stack). */
    val topFrame: Frame? get() = frames.lastOrNull()

    /** Push a new frame onto the stack, starting a bracket if the stack was
     *  previously empty so the counter measures the whole bracket rather
     *  than just the innermost call. */
    fun pushing(frame: Frame): SubAgentActivityState =
        copy(
            frames = frames + frame,
            bracketStartedAt = if (frames.isEmpty()) frame.startedAt else bracketStartedAt,
        )

    /** Pop the top frame. Returns the new state paired with the popped
     *  frame (or null if the stack was empty) so callers can emit a
     *  collapsed history row. When the stack drains to empty the
     *  [bracketStartedAt] marker is cleared so the next bracket starts a
     *  fresh counter. */
    fun popping(): Pair<SubAgentActivityState, Frame?> {
        if (frames.isEmpty()) return this to null
        val popped = frames.last()
        val remaining = frames.dropLast(1)
        return copy(
            frames = remaining,
            bracketStartedAt = if (remaining.isEmpty()) null else bracketStartedAt,
        ) to popped
    }

    /** Append streamed text to the top frame's live ticker. No-op if the
     *  stack is empty — callers gate on [isActive] before diverting. */
    fun appendingDelta(text: String): SubAgentActivityState {
        val top = frames.lastOrNull() ?: return this
        return replaceTop(top.copy(liveText = top.liveText + text, currentToolName = null))
    }

    /** Replace the top frame's live ticker with the authoritative final
     *  message content. Used when `assistant.message` lands while a
     *  sub-agent frame is on top of the stack. */
    fun settingFinal(text: String): SubAgentActivityState {
        val top = frames.lastOrNull() ?: return this
        return replaceTop(top.copy(liveText = text, currentToolName = null))
    }

    /** Record the most recent tool the sub-agent invoked so the pill can
     *  show "· <tool>" until the next delta arrives. */
    fun notingToolCall(toolName: String): SubAgentActivityState {
        val top = frames.lastOrNull() ?: return this
        return replaceTop(top.copy(currentToolName = toolName))
    }

    private fun replaceTop(newTop: Frame): SubAgentActivityState =
        copy(frames = frames.dropLast(1) + newTop)
}
