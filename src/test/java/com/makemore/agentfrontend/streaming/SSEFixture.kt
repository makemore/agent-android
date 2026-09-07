package com.makemore.agentfrontend.streaming

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Loads the shared SSE fixtures in `test-harness/fixtures/sse/` and renders
 * them to the exact wire format produced by the real backend. Mirrors the
 * iOS [SSEFixture] so both platforms stream identical bytes through their
 * respective transports.
 *
 * Fixtures are located by walking up from the working directory, preferring
 * the canonical meta-repo fixtures over standalone/legacy copies. Works under
 * both Gradle's default cwd and Android Studio test runs.
 */
data class SSEFixture(
    val name: String,
    val runId: String,
    val conversationId: String,
    val events: List<JSONObject>,
) {
    /** The full SSE wire body — one `event:`/`data:` frame per event. */
    fun sseBody(): String {
        val ts = "1970-01-01T00:00:00Z"
        val sb = StringBuilder()
        events.forEachIndexed { seq, ev ->
            val type = ev.getString("event")
            val payload = ev.optJSONObject("payload") ?: JSONObject()
            val eventSeq = if (ev.has("seq_override")) ev.getInt("seq_override") else seq
            val envelope = JSONObject().apply {
                put("run_id", runId)
                put("seq", eventSeq)
                put("type", type)
                put("payload", payload)
                put("ts", ts)
                put("visibility_level", "user")
                put("ui_visible", true)
            }
            sb.append("event: ").append(type).append('\n')
            sb.append("data: ").append(envelope.toString()).append('\n')
            sb.append('\n')
        }
        return sb.toString()
    }

    companion object {
        fun load(name: String): SSEFixture {
            val file = SharedFixture.locate("sse/$name.json")
            val raw = JSONObject(file.readText())
            val eventsArr: JSONArray = raw.getJSONArray("events")
            val events = (0 until eventsArr.length()).map { eventsArr.getJSONObject(it) }
            return SSEFixture(
                name = raw.optString("name", name),
                runId = raw.getString("run_id"),
                conversationId = raw.getString("conversation_id"),
                events = events,
            )
        }
    }
}

/** Shared discovery for SSE fixtures and the ephemeral parity contract. */
internal object SharedFixture {
    fun locate(relativePath: String, from: File = File("").absoluteFile): File {
        val start = from.absoluteFile.normalize()
        val ancestors = generateSequence(start) { it.parentFile }.toList()
        val searched = mutableListOf<String>()
        // Search every ancestor for the canonical fixture before trying local copies.
        for (layout in listOf("test-harness/fixtures", "test-fixtures", "clients/test-fixtures")) {
            for (ancestor in ancestors) {
                val candidate = File(File(ancestor, layout), relativePath)
                searched += candidate.path
                if (candidate.isFile) return candidate
            }
        }
        error(
            "Could not locate fixture $relativePath from ${start.path}. " +
                "Check out test-harness/fixtures in a common ancestor of the client, " +
                "or provide test-fixtures (legacy clients/test-fixtures is also supported). " +
                "Searched:\n${searched.joinToString("\n")}"
        )
    }
}
