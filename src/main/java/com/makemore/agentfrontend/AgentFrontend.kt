package com.makemore.agentfrontend

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.makemore.agentfrontend.configuration.ChatWidgetConfig
import com.makemore.agentfrontend.networking.APIClient
import com.makemore.agentfrontend.services.InMemoryStorage
import com.makemore.agentfrontend.services.SharedPreferencesStorage
import com.makemore.agentfrontend.services.StorageService
import com.makemore.agentfrontend.ui.ChatWidgetView
import com.makemore.agentfrontend.viewmodels.ChatViewModel

/**
 * Main entry point for the AgentFrontend chat widget.
 * Mirrors the iOS AgentFrontend struct.
 *
 * Usage:
 * ```kotlin
 * // Simple usage — one line:
 * AgentFrontend.ChatWidget(
 *     context = LocalContext.current,
 *     config = ChatWidgetConfig(
 *         backendUrl = "https://api.example.com",
 *         agentKey = "my-agent"
 *     )
 * )
 *
 * // With authentication:
 * AgentFrontend.ChatWidget(
 *     context = LocalContext.current,
 *     config = ChatWidgetConfig.make(
 *         backendUrl = "https://api.example.com",
 *         agentKey = "my-agent"
 *     ).withAuth(AuthStrategy.JWT, token = userToken)
 * )
 * ```
 */
object AgentFrontend {

    /**
     * Create a chat widget composable with the given configuration.
     *
     * @param context Android context (for SharedPreferences storage)
     * @param config Configuration for the chat widget
     * @param modifier Optional Modifier for the root composable
     */
    @Composable
    fun ChatWidget(
        context: Context,
        config: ChatWidgetConfig,
        modifier: Modifier = Modifier
    ) {
        val storage = SharedPreferencesStorage(context, prefix = config.agentKey)
        val apiClient = APIClient(config, storage)
        val viewModel = ChatViewModel(config, apiClient, storage, context = context)

        ChatWidgetView(viewModel = viewModel, config = config, modifier = modifier)
    }

    /**
     * Create a chat widget composable with custom storage.
     *
     * @param context Android context (for SQLite local history in ephemeral mode)
     * @param config Configuration for the chat widget
     * @param storage Custom storage service implementation
     * @param modifier Optional Modifier for the root composable
     */
    @Composable
    fun ChatWidget(
        context: Context,
        config: ChatWidgetConfig,
        storage: StorageService,
        modifier: Modifier = Modifier
    ) {
        val apiClient = APIClient(config, storage)
        val viewModel = ChatViewModel(config, apiClient, storage, context = context)

        ChatWidgetView(viewModel = viewModel, config = config, modifier = modifier)
    }

    /**
     * Create a ChatViewModel for custom UI implementations.
     *
     * @param context Android context (for SharedPreferences storage and SQLite local history)
     * @param config Configuration for the chat widget
     * @return A ChatViewModel instance
     */
    fun createViewModel(context: Context, config: ChatWidgetConfig): ChatViewModel {
        val storage = SharedPreferencesStorage(context, prefix = config.agentKey)
        val apiClient = APIClient(config, storage)
        return ChatViewModel(config, apiClient, storage, context = context)
    }

    /**
     * Create a ChatViewModel with custom dependencies.
     *
     * @param context Android context (for SQLite local history in ephemeral mode)
     * @param config Configuration for the chat widget
     * @param storage Custom storage service implementation
     * @return A ChatViewModel instance
     */
    fun createViewModel(context: Context, config: ChatWidgetConfig, storage: StorageService): ChatViewModel {
        val apiClient = APIClient(config, storage)
        return ChatViewModel(config, apiClient, storage, context = context)
    }
}

