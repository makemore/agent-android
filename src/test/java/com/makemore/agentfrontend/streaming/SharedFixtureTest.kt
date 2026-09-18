package com.makemore.agentfrontend.streaming

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SharedFixtureTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun canonicalMetaRepoFixturesWinOverNearerLegacyCopiesAtAnyDepth() {
        val root = temporaryFolder.root
        val client = File(root, "clients/agent-android")
        val nested = List(12) { "nested" }.joinToString("/")
        val start = File(client, "build/tmp/testDebugUnitTest/$nested").apply { mkdirs() }
        for (relativePath in listOf("sse/probe.json", "ephemeral/contract.json")) {
            val canonical = writeFixture(root, "test-harness/fixtures/$relativePath")
            writeFixture(client, "test-fixtures/$relativePath")
            writeFixture(root, "clients/test-fixtures/$relativePath")
            assertEquals(canonical, SharedFixture.locate(relativePath, start))
        }
    }

    @Test
    fun standaloneAndLegacyClientsLayouts() {
        for (layout in listOf("test-fixtures", "clients/test-fixtures")) {
            val root = temporaryFolder.newFolder()
            val start = File(root, "agent-android/build/tmp/testDebugUnitTest").apply { mkdirs() }
            // Unique names prevent fixtures above a custom temporary directory from interfering.
            for (category in listOf("sse", "ephemeral")) {
                val relativePath = "$category/probe-${root.name}.json"
                val expected = writeFixture(root, "$layout/$relativePath")
                assertEquals(expected, SharedFixture.locate(relativePath, start))
            }
        }
    }

    @Test
    fun searchesForRequestedFileRatherThanAnExistingDirectory() {
        val root = temporaryFolder.root
        for (category in listOf("sse", "ephemeral")) {
            writeFixture(root, "test-harness/fixtures/$category/other.json")
            val relativePath = "$category/probe-${root.name}.json"
            val expected = writeFixture(root, "test-fixtures/$relativePath")
            assertEquals(expected, SharedFixture.locate(relativePath, root))
        }
    }

    @Test
    fun rejectsDirectoriesAndReportsMissingFileAndSearchedPaths() {
        val root = temporaryFolder.root
        val relativePath = "sse/missing-${root.name}.json"
        val candidate = File(root, "test-harness/fixtures/$relativePath").apply { mkdirs() }
        val error = assertThrows(IllegalStateException::class.java) {
            SharedFixture.locate(relativePath, root)
        }
        val message = error.message.orEmpty()
        assertTrue(message.contains(relativePath))
        assertTrue(message.contains("from ${root.path}"))
        assertTrue(message.contains(candidate.path))
        assertTrue(message.contains("Check out test-harness/fixtures"))
        assertTrue(message.contains(File(root, "test-fixtures/$relativePath").path))
        assertTrue(message.contains(File(root, "clients/test-fixtures/$relativePath").path))
    }

    @Test
    fun checkedInSSEFixturesAndEphemeralContractLoad() {
        val fixture = SSEFixture.load("simple_streaming")
        assertEquals("simple_streaming", fixture.name)
        assertTrue(fixture.events.isNotEmpty())
        val contract = JSONObject(SharedFixture.locate("ephemeral/contract.json").readText())
        val scenarios = contract.getJSONArray("scenarios")
        assertTrue(scenarios.length() > 0)
        for (i in 0 until scenarios.length()) {
            assertTrue(SSEFixture.load(scenarios.getJSONObject(i).getString("fixture")).events.isNotEmpty())
        }
    }

    private fun writeFixture(root: File, relativePath: String): File =
        File(root, relativePath).apply {
            requireNotNull(parentFile).mkdirs()
            writeText("{}")
        }
}