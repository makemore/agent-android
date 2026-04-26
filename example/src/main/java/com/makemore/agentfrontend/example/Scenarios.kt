package com.makemore.agentfrontend.example

/**
 * Canned scenario shown as a button in `ScenarioLauncherScreen`. Mirrors
 * the iOS `Scenario` struct — keep the two lists in sync so the same
 * manual scenarios are available on both platforms (and to the
 * instrumentation tests in `ChatStreamingUITests`).
 */
data class Scenario(
    val id: String,
    val title: String,
    val subtitle: String,
    val kind: Kind,
    val prompt: String,
    val followUps: List<HostConfiguration.FollowUp>,
    /** When non-null, forces the agent key for this scenario instead of
     *  reading the launcher's "Agent key" field. Used by Resilient
     *  scenarios to pin `sai-triage` so the user doesn't have to retype
     *  it after switching backends. */
    val agentKeyOverride: String? = null
) {
    sealed class Kind {
        /** Replays the named JSON fixture via the local Python stub server. */
        data class Stub(val fixture: String) : Kind()
        /** Hits the real Django `agent_studio` backend with DRF token auth. */
        data object RealBackend : Kind()
        /** Hits a real Resilient backend (`/api/agent-runtime/`) with DRF
         *  token auth. Same wire format as [RealBackend] — the separate
         *  kind exists so the launcher can group these under their own
         *  section and pre-pin the `sai-triage` agent key. */
        data object Resilient : Kind()
    }
}

object Scenarios {
    /** Stub-server scenarios. Mirrors `ScenarioLauncherView.stubScenarios`. */
    val stub: List<Scenario> = listOf(
        Scenario(
            id = "simple_streaming",
            title = "Simple streaming",
            subtitle = "Plain typewriter delta → final assistant bubble",
            kind = Scenario.Kind.Stub("simple_streaming"),
            prompt = "Hello agent",
            followUps = emptyList()
        ),
        Scenario(
            id = "tool_call_with_content_blocks",
            title = "Tool call + content blocks",
            subtitle = "Card + Table from a tool result",
            kind = Scenario.Kind.Stub("tool_call_with_content_blocks"),
            prompt = "Find me a place to stay",
            followUps = emptyList()
        ),
        Scenario(
            id = "sai_multi_agent_handoff",
            title = "Multi-agent hand-off",
            subtitle = "Parent intro → sub-agent reply, parent echo suppressed",
            kind = Scenario.Kind.Stub("sai_multi_agent_handoff"),
            prompt = "I'm feeling overwhelmed",
            followUps = emptyList()
        ),
        Scenario(
            id = "sai_multi_agent_with_blocks",
            title = "Multi-agent with blocks",
            subtitle = "Sub-agent emits Callout + CardList + ActionButtons",
            kind = Scenario.Kind.Stub("sai_multi_agent_with_blocks"),
            prompt = "Show me my account",
            followUps = emptyList()
        ),
        Scenario(
            id = "demo_big_conversation",
            title = "Demo: big conversation (3 turns)",
            subtitle = "~30 s multi-agent itinerary + 2 scripted follow-ups",
            kind = Scenario.Kind.Stub("demo_big_conversation"),
            prompt = "Plan me a 3-day trip to Tokyo",
            followUps = listOf(
                HostConfiguration.FollowUp("Yes, please book it.", delayMs = 1500),
                HostConfiguration.FollowUp("Add the trip to my calendar.", delayMs = 1500)
            )
        ),
        Scenario(
            id = "run_failed",
            title = "Run failed (error banner)",
            subtitle = "run.failed surfaces the error banner",
            kind = Scenario.Kind.Stub("run_failed"),
            prompt = "Crash on purpose",
            followUps = emptyList()
        )
    )

    /** Real-backend scenarios. Mirrors `ScenarioLauncherView.realBackendScenarios`. */
    val realBackend: List<Scenario> = listOf(
        Scenario(
            id = "real_simple",
            title = "Hello round-trip",
            subtitle = "Single LLM round trip — \"hello from agent builder\"",
            kind = Scenario.Kind.RealBackend,
            prompt = "Reply with exactly the words: hello from agent builder",
            followUps = emptyList()
        ),
        Scenario(
            id = "real_big",
            title = "Big conversation (3 turns)",
            subtitle = "ACK-ALPHA → ACK-BRAVO → RECAP, exercises memory",
            kind = Scenario.Kind.RealBackend,
            prompt = "Reply with exactly the token ACK-ALPHA on its own line, then a one-sentence greeting.",
            followUps = listOf(
                HostConfiguration.FollowUp(
                    prompt = "Now reply with exactly the token ACK-BRAVO on its own line, then a one-sentence weather remark.",
                    delayMs = 2000
                ),
                HostConfiguration.FollowUp(
                    prompt = "Recap: list the two ACK tokens you used so far, in order, on a single line that begins with the literal prefix RECAP: (e.g. \"RECAP: ACK-ALPHA, ACK-BRAVO\").",
                    delayMs = 2000
                )
            )
        )
    )

    /** Resilient scenarios — point the launcher's "Django URL" field at
     *  a running Resilient backend (`./manage.py runserver` or `runagent`)
     *  and paste the DRF token. Agent key is pinned to `sai-triage`. */
    val resilient: List<Scenario> = listOf(
        Scenario(
            id = "resilient_simple",
            title = "sai-triage: hello round-trip",
            subtitle = "Single LLM round trip — sanity-check token + URL + camelCase wiring",
            kind = Scenario.Kind.Resilient,
            prompt = "Reply with exactly the words: hello from agent builder",
            followUps = emptyList(),
            agentKeyOverride = "sai-triage"
        ),
        Scenario(
            id = "resilient_big",
            title = "sai-triage: 3-turn ACK test",
            subtitle = "ACK-ALPHA → ACK-BRAVO → RECAP, exercises memory + multi-run SSE",
            kind = Scenario.Kind.Resilient,
            prompt = "Reply with exactly the token ACK-ALPHA on its own line, then a one-sentence greeting.",
            followUps = listOf(
                HostConfiguration.FollowUp(
                    prompt = "Now reply with exactly the token ACK-BRAVO on its own line, then a one-sentence weather remark.",
                    delayMs = 2000
                ),
                HostConfiguration.FollowUp(
                    prompt = "Recap: list the two ACK tokens you used so far, in order, on a single line that begins with the literal prefix RECAP: (e.g. \"RECAP: ACK-ALPHA, ACK-BRAVO\").",
                    delayMs = 2000
                )
            ),
            agentKeyOverride = "sai-triage"
        )
    )
}
