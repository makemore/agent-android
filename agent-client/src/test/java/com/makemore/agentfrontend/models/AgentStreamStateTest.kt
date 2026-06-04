package com.makemore.agentfrontend.models

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AgentStreamStateTest {
    @Test
    fun parserPreservesKnownEnvelopeAndMalformedFrames() {
        val event = AgentStreamEvent.parse("""{"run_id":"r1","seq":2,"type":"assistant.delta","payload":{"delta":"hi"}}""")
        assertTrue(event.known)
        assertEquals("assistant.delta", event.type)
        assertEquals("r1", event.runId)
        assertEquals(2, event.seq)
        assertEquals("hi", event.payload["delta"])

        val malformed = AgentStreamEvent.parse("{not-json}")
        assertFalse(malformed.known)
        assertEquals("unknown", malformed.type)
        assertNotNull(malformed.parseError)
    }

    @Test
    fun reducerMergesDeltasAndDedupesReplay() {
        val state = reduceFixture("duplicate_replayed_event")
        assertEquals(AgentRunLifecycleStatus.SUCCEEDED, state.status)
        assertEquals("Hello world", state.assistantText)
        assertTrue(state.seenEventKeys.contains("test-run-duplicate-001:0"))
    }

    @Test
    fun reducerTracksToolFailureAndRequiredActionLifecycle() {
        val toolState = reduceFixture("tool_call_failure")
        assertEquals("failed", toolState.toolCalls["call_fail_001"]?.status)
        assertEquals("record_not_found", toolState.toolCalls["call_fail_001"]?.error)

        val actionState = reduceFixture("required_action_lifecycle")
        assertEquals("resolved", actionState.requiredActions["act-approval-001"]?.status)
        assertTrue(actionState.unknownEvents.any { it.type == "client.action.submitted" })
    }

    private fun reduceFixture(name: String): AgentRunReducerState = fixtureEvents(name)
        .fold(AgentRunReducerState()) { state, event -> state.reduce(event) }

    private fun fixtureEvents(name: String): List<AgentStreamEvent> {
        val raw = JSONObject(locateFixture("$name.json").readText())
        val runId = raw.getString("run_id")
        val events = raw.getJSONArray("events")
        return (0 until events.length()).map { idx ->
            val ev = events.getJSONObject(idx)
            val type = ev.getString("event")
            val envelope = JSONObject().apply {
                put("run_id", runId)
                put("seq", if (ev.has("seq_override")) ev.getInt("seq_override") else idx)
                put("type", type)
                put("payload", ev.optJSONObject("payload") ?: JSONObject())
            }
            AgentStreamEvent.parse(envelope, type)
        }
    }

    /** Walk up from the test working directory looking for the requested
     *  fixture under `clients/test-fixtures/sse/` or `test-fixtures/sse/`.
     *  Checking for the *file* (not just the directory) avoids stopping at
     *  a partial sibling fixtures dir that only contains a subset. */
    private fun locateFixture(fileName: String): File {
        var dir: File? = File("").absoluteFile
        repeat(10) {
            val cur = dir ?: return@repeat
            val direct = File(cur, "clients/test-fixtures/sse/$fileName")
            if (direct.isFile) return direct
            val sibling = File(cur, "test-fixtures/sse/$fileName")
            if (sibling.isFile) return sibling
            dir = cur.parentFile
        }
        error("Could not locate fixture: $fileName under clients/test-fixtures/sse")
    }
}
