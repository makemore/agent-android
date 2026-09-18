package com.makemore.agentfrontend.models

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentStreamStateTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

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

    @Test
    fun fixtureDiscoveryPrefersCanonicalLayoutAtAnyDepth() {
        val root = temporaryFolder.root
        val client = File(root, "clients/agent-android")
        val nested = List(12) { "nested" }.joinToString("/")
        val start = File(client, "agent-client/build/tmp/$nested").apply { mkdirs() }
        val canonical = writeFixture(root, "test-harness/fixtures/sse/probe.json")
        writeFixture(client, "test-fixtures/sse/probe.json")
        writeFixture(root, "clients/test-fixtures/sse/probe.json")
        assertEquals(canonical, locateFixture("probe.json", start))
    }

    @Test
    fun fixtureDiscoverySupportsLegacyLayoutsAndChecksRequestedFile() {
        for (layout in listOf("test-fixtures", "clients/test-fixtures")) {
            val root = temporaryFolder.newFolder()
            val fileName = "probe-${root.name}.json"
            val start = File(root, "agent-android/agent-client/build/tmp").apply { mkdirs() }
            writeFixture(root, "test-harness/fixtures/sse/other.json")
            val expected = writeFixture(root, "$layout/sse/$fileName")
            assertEquals(expected, locateFixture(fileName, start))
        }
    }

    @Test
    fun fixtureDiscoveryRejectsDirectoriesAndReportsSearchedPaths() {
        val root = temporaryFolder.root
        val fileName = "missing-${root.name}.json"
        val candidate = File(root, "test-harness/fixtures/sse/$fileName").apply { mkdirs() }
        val error = assertThrows(IllegalStateException::class.java) { locateFixture(fileName, root) }
        val message = error.message.orEmpty()
        assertTrue(message.contains(fileName))
        assertTrue(message.contains("from ${root.path}"))
        assertTrue(message.contains(candidate.path))
        assertTrue(message.contains("Check out test-harness/fixtures/sse"))
        assertTrue(message.contains(File(root, "test-fixtures/sse/$fileName").path))
        assertTrue(message.contains(File(root, "clients/test-fixtures/sse/$fileName").path))
    }

    private fun writeFixture(root: File, relativePath: String): File =
        File(root, relativePath).apply {
            requireNotNull(parentFile).mkdirs()
            writeText("{}")
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

    /** Mirrors the widget test module's canonical-first discovery. These test
     *  source sets are separate; neither helper belongs in the runtime library.
     *  Match the requested file so partial standalone fixtures still work. */
    private fun locateFixture(fileName: String, from: File = File("").absoluteFile): File {
        val start = from.absoluteFile.normalize()
        val ancestors = generateSequence(start) { it.parentFile }.toList()
        val searched = mutableListOf<String>()
        for (layout in listOf("test-harness/fixtures", "test-fixtures", "clients/test-fixtures")) {
            for (ancestor in ancestors) {
                val candidate = File(ancestor, "$layout/sse/$fileName")
                searched += candidate.path
                if (candidate.isFile) return candidate
            }
        }
        error(
            "Could not locate SSE fixture $fileName from ${start.path}. " +
                "Check out test-harness/fixtures/sse in a common ancestor of the client, " +
                "or provide test-fixtures/sse (legacy clients/test-fixtures/sse is also supported). " +
                "Searched:\n${searched.joinToString("\n")}"
        )
    }
}
