package com.makemore.agentfrontend.example

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.makemore.agentfrontend.networking.APIClient
import com.makemore.agentfrontend.services.InMemoryStorage
import com.makemore.agentfrontend.ui.ChatWidgetView
import com.makemore.agentfrontend.viewmodels.ChatViewModel
import kotlinx.coroutines.delay

/**
 * Hosts a single chat session built from [host]. Mirrors the iOS
 * `RootView`:
 *
 *   1. Builds a fresh `ChatViewModel` with `InMemoryStorage` so each
 *      launch is a clean slate (the launcher pushes a new instance per
 *      tap; this view's `remember(host)` discards the previous VM when
 *      the user backs out and picks a different scenario).
 *   2. On first composition, fires the auto-send prompt (if any), then
 *      replays scripted follow-ups one at a time. Each follow-up uses
 *      `sendMessageAndAwait` so the next turn never collides with the
 *      previous run's `isLoading` guard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(host: HostConfiguration, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    // Re-keying on `host` is what gives us a fresh chat per scenario.
    val cfg = remember(host) { host.makeWidgetConfig() }
    val storage = remember(host) { InMemoryStorage() }
    val api = remember(host) { APIClient(cfg, storage) }
    val viewModel = remember(host) { ChatViewModel(cfg, api, storage) }

    LaunchedEffect(host) {
        if (!host.autoSendOnLaunch) return@LaunchedEffect
        // Suspending send mirrors the iOS host: the awaiter resolves only
        // after the SSE stream reaches a terminal event, so the next
        // follow-up is never dropped by the `isLoading` guard.
        viewModel.sendMessageAndAwait(host.autoSendPrompt)
        for (follow in host.autoSendFollowUps) {
            if (follow.delayMs > 0) delay(follow.delayMs)
            viewModel.sendMessageAndAwait(follow.prompt)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(cfg.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to launcher"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ChatWidgetView(viewModel = viewModel, config = cfg)
        }
    }
}
