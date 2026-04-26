package com.makemore.agentfrontend.streaming

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Loads the shared SSE fixtures in `clients/test-fixtures/sse/` and renders
 * them to the exact wire format produced by the real backend. Mirrors the
 * iOS [SSEFixture] so both platforms stream identical bytes through their
 * respective transports.
 *
 * Fixtures are located by walking up from the working directory until a
 * `clients/test-fixtures/sse` folder is found — works under both Gradle's
 * default cwd and Android Studio test runs.
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
            val envelope = JSONObject().apply {
                put("run_id", runId)
                put("seq", seq)
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
            val dir = locateFixturesDir()
            val file = File(dir, "$name.json")
            require(file.exists()) { "Fixture not found: ${file.absolutePath}" }
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

        private fun locateFixturesDir(): File {
            // Walk up from cwd until we find `clients/test-fixtures/sse`,
            // or directly find `test-fixtures/sse` (when cwd is `clients/`).
            var dir: File? = File("").absoluteFile
            repeat(10) {
                val cur = dir ?: return@repeat
                val direct = File(cur, "clients/test-fixtures/sse")
                if (direct.isDirectory) return direct
                val sibling = File(cur, "test-fixtures/sse")
                if (sibling.isDirectory) return sibling
                dir = cur.parentFile
            }
            error("Could not locate clients/test-fixtures/sse from ${File("").absolutePath}")
        }
    }
}
