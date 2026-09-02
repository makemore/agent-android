package com.makemore.agentfrontend

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.takeOrElse
import com.makemore.agentfrontend.configuration.ChatAppearance
import com.makemore.agentfrontend.configuration.ChatWidgetConfig
import com.makemore.agentfrontend.networking.APIClient
import com.makemore.agentfrontend.services.InMemoryStorage
import com.makemore.agentfrontend.services.SecureStorageService
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
        val resolvedConfig = config.withResolvedAppearance()
        // Secrets (auth token, client memories) are encrypted via the Android
        // Keystore; non-secret UI prefs stay in plain SharedPreferences.
        val storage = remember(context, resolvedConfig.agentKey, resolvedConfig.anonymousTokenKey) {
            SecureStorageService.makeDefault(
                context,
                prefix = resolvedConfig.agentKey,
                secureKeys = setOf(resolvedConfig.anonymousTokenKey),
            )
        }
        val apiClient = remember(resolvedConfig, storage) { APIClient(resolvedConfig, storage) }
        val viewModel = remember(resolvedConfig, apiClient, storage, context) {
            ChatViewModel(resolvedConfig, apiClient, storage, context = context)
        }

        ChatWidgetView(viewModel = viewModel, config = resolvedConfig, modifier = modifier)
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
        val resolvedConfig = config.withResolvedAppearance()
        val apiClient = remember(resolvedConfig, storage) { APIClient(resolvedConfig, storage) }
        val viewModel = remember(resolvedConfig, apiClient, storage, context) {
            ChatViewModel(resolvedConfig, apiClient, storage, context = context)
        }

        ChatWidgetView(viewModel = viewModel, config = resolvedConfig, modifier = modifier)
    }

    /**
     * Create a ChatViewModel for custom UI implementations.
     *
     * @param context Android context (for SharedPreferences storage and SQLite local history)
     * @param config Configuration for the chat widget
     * @return A ChatViewModel instance
     */
    fun createViewModel(context: Context, config: ChatWidgetConfig): ChatViewModel {
        // Secrets (auth token, client memories) are encrypted via the Android
        // Keystore; non-secret UI prefs stay in plain SharedPreferences.
        val storage = SecureStorageService.makeDefault(
            context,
            prefix = config.agentKey,
            secureKeys = setOf(config.anonymousTokenKey),
        )
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

@Composable
private fun ChatWidgetConfig.withResolvedAppearance(): ChatWidgetConfig {
    val colorScheme = MaterialTheme.colorScheme
    return remember(this, colorScheme) {
        copy(appearance = appearance.resolveAgainst(colorScheme))
    }
}

internal fun ChatAppearance.resolveAgainst(colorScheme: ColorScheme): ChatAppearance = copy(
    background = background.takeOrElse { colorScheme.background },
    surface = surface.takeOrElse { colorScheme.surface },
    surfaceElevated = surfaceElevated.takeOrElse { colorScheme.surfaceContainerHigh },
    textPrimary = textPrimary.takeOrElse { colorScheme.onSurface },
    textSecondary = textSecondary.takeOrElse { colorScheme.onSurfaceVariant },
)

