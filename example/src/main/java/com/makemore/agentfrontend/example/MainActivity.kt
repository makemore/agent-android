package com.makemore.agentfrontend.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Single-Activity host for the manual scenario launcher. Mirrors the iOS
 * `ExampleApp` entry point: shows `ScenarioLauncherScreen` by default, and
 * pushes `ChatScreen` when a scenario is picked. There's intentionally no
 * `Navigation Compose` graph here — a single nullable state is enough for
 * a two-screen test rig and avoids dragging in serialisation rules for
 * the `HostConfiguration` route argument.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier, color = MaterialTheme.colorScheme.background) {
                    AgentExampleApp()
                }
            }
        }
    }
}

@Composable
fun AgentExampleApp() {
    var current by remember { mutableStateOf<HostConfiguration?>(null) }
    val host = current
    if (host == null) {
        ScenarioLauncherScreen(onScenarioPicked = { current = it })
    } else {
        ChatScreen(host = host, onBack = { current = null })
    }
}
