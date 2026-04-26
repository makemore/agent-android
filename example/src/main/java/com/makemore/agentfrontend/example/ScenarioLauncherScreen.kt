package com.makemore.agentfrontend.example

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.makemore.agentfrontend.example.persistence.LauncherSettings

/**
 * Manual test launcher. Shows endpoint settings (Stub URL, Django URL,
 * DRF token, agent key) at the top, then sectioned scenario buttons.
 * Tapping a scenario hands a freshly-built [HostConfiguration] back to
 * the host so it can mount a clean `ChatScreen`.
 *
 * Mirrors `ScenarioLauncherView.swift`. Settings are persisted via
 * SharedPreferences (see [LauncherSettings]) so the DRF token survives
 * across app launches.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScenarioLauncherScreen(
    onScenarioPicked: (HostConfiguration) -> Unit
) {
    val context = LocalContext.current
    val settings = remember { LauncherSettings(context) }

    var stubUrl by rememberSaveable { mutableStateOf(settings.stubUrl) }
    var backendUrl by rememberSaveable { mutableStateOf(settings.backendUrl) }
    var authToken by rememberSaveable { mutableStateOf(settings.authToken) }
    var agentKey by rememberSaveable { mutableStateOf(settings.agentKey) }

    fun stubHost(scenario: Scenario, fixture: String): HostConfiguration {
        settings.save(stubUrl, backendUrl, authToken, agentKey)
        return HostConfiguration(
            backendUrl = stubUrl,
            agentKey = "test-agent",
            testFixture = fixture,
            autoSendOnLaunch = true,
            autoSendPrompt = scenario.prompt,
            autoSendFollowUps = scenario.followUps,
            authToken = null
        )
    }

    fun realHost(scenario: Scenario): HostConfiguration {
        settings.save(stubUrl, backendUrl, authToken, agentKey)
        return HostConfiguration(
            backendUrl = backendUrl,
            agentKey = scenario.agentKeyOverride ?: agentKey,
            testFixture = "",
            autoSendOnLaunch = true,
            autoSendPrompt = scenario.prompt,
            autoSendFollowUps = scenario.followUps,
            authToken = authToken.ifBlank { null }
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("AgentFrontend") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
                start = 12.dp, end = 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { SectionHeader("Endpoints") }
            item {
                EndpointSettingsCard(
                    stubUrl = stubUrl, onStubUrl = { stubUrl = it },
                    backendUrl = backendUrl, onBackendUrl = { backendUrl = it },
                    authToken = authToken, onAuthToken = { authToken = it },
                    agentKey = agentKey, onAgentKey = { agentKey = it }
                )
            }

            item { SectionHeader("Stub fixtures (start clients/test-stub-server)") }
            items(Scenarios.stub, key = { it.id }) { scenario ->
                ScenarioRow(scenario) {
                    val fixture = (scenario.kind as Scenario.Kind.Stub).fixture
                    onScenarioPicked(stubHost(scenario, fixture))
                }
            }

            item { SectionHeader("Real backend (Django)") }
            if (authToken.isBlank()) {
                item {
                    Text(
                        "Set DRF token above to enable.",
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(Scenarios.realBackend, key = { it.id }) { scenario ->
                    ScenarioRow(scenario) { onScenarioPicked(realHost(scenario)) }
                }
            }

            // Resilient (`/Users/chris/Projects/resilient/backend`) shares the
            // Django URL + DRF token fields above. Agent key is pinned to
            // `sai-triage` per scenario; the launcher's own agent-key field
            // is ignored for these rows.
            item { SectionHeader("Resilient (sai-triage)") }
            if (authToken.isBlank()) {
                item {
                    Text(
                        "Set DRF token above to enable. Point Django URL at " +
                            "your Resilient runserver (default http://10.0.2.2:8000 " +
                            "for the Android emulator).",
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(Scenarios.resilient, key = { it.id }) { scenario ->
                    ScenarioRow(scenario) { onScenarioPicked(realHost(scenario)) }
                }
            }

            item { SectionHeader("Manual") }
            item {
                ScenarioRow(
                    scenario = Scenario(
                        id = "manual",
                        title = "Open empty chat (stub, no auto-send)",
                        subtitle = "Same widget XCUITest drives, but you type the prompts.",
                        kind = Scenario.Kind.Stub("simple_streaming"),
                        prompt = "",
                        followUps = emptyList()
                    ),
                    onClick = {
                        settings.save(stubUrl, backendUrl, authToken, agentKey)
                        onScenarioPicked(
                            HostConfiguration(
                                backendUrl = stubUrl,
                                agentKey = "test-agent",
                                testFixture = "simple_streaming",
                                autoSendOnLaunch = false,
                                autoSendPrompt = "",
                                autoSendFollowUps = emptyList(),
                                authToken = null
                            )
                        )
                    }
                )
            }
        }
    }
}
