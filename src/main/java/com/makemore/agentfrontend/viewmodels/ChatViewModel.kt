package com.makemore.agentfrontend.viewmodels

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.makemore.agentfrontend.configuration.ChatWidgetConfig
import com.makemore.agentfrontend.models.*
import com.makemore.agentfrontend.networking.*
import com.makemore.agentfrontend.services.StorageService
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.util.Date

/**
 * Main view model for chat functionality.
 * Mirrors the iOS ChatViewModel class.
 */
class ChatViewModel(
    private val config: ChatWidgetConfig,
    private val apiClient: APIClient,
    private val storage: StorageService
) : ViewModel() {

    // -- Observable State --
    val messages = mutableStateListOf<Message>()
    val isLoading = mutableStateOf(false)
    val error = mutableStateOf<String?>(null)
    val conversationId = mutableStateOf<String?>(null)
    val hasMoreMessages = mutableStateOf(false)
    val loadingMoreMessages = mutableStateOf(false)

    // -- System State --
    val systems = mutableStateListOf<AgentSystem>()
    val selectedSystemSlug = mutableStateOf<String?>(null)
    val selectedSystemVersion = mutableStateOf<String?>(null)
    val selectedSystemVersionId = mutableStateOf<String?>(null)
    val isLoadingSystems = mutableStateOf(false)

    // -- Private State --
    private var messagesOffset: Int = 0
    private var currentRunId: String? = null
    private var sseClient: SSEClient? = null
    private var assistantContent: String = ""
    private var hasRestoredConversation: Boolean = false

    init {
        // Load saved conversation ID
        storage.get(config.conversationIdKey)?.let { conversationId.value = it }
        // Load saved system selection
        storage.get(config.systemKey)?.let { selectedSystemSlug.value = it }
        storage.get(config.systemVersionKey)?.let { selectedSystemVersion.value = it }
        storage.get(config.systemVersionIdKey)?.let { selectedSystemVersionId.value = it }
    }

    /** Restore the saved conversation on launch */
    fun restoreConversationIfNeeded() {
        if (hasRestoredConversation) return
        hasRestoredConversation = true

        conversationId.value?.let { savedId ->
            viewModelScope.launch { loadConversation(savedId) }
        }
    }

    /** The effective agent key — uses the selected system's entry agent if set */
    val effectiveAgentKey: String
        get() {
            val slug = selectedSystemSlug.value
            if (slug != null) {
                val system = systems.firstOrNull { it.slug == slug }
                system?.entryAgent?.slug?.let { return it }
            }
            return config.agentKey
        }

    // -- Send Message --

    fun sendMessage(
        content: String,
        files: List<FileAttachment> = emptyList(),
        model: String? = null,
        thinking: Boolean = false,
        supersedeFromMessageIndex: Int? = null
    ) {
        val trimmed = content.trim()
        if (trimmed.isEmpty() || isLoading.value) return

        isLoading.value = true
        error.value = null

        // Add user message
        messages.add(Message(
            role = MessageRole.USER,
            content = trimmed,
            files = files.ifEmpty { null }
        ))

        viewModelScope.launch {
            try {
                val apiMessages = listOf(mapOf("role" to "user", "content" to trimmed))

                val run = apiClient.createRun(
                    conversationId = conversationId.value,
                    messages = apiMessages,
                    model = model,
                    thinking = thinking,
                    supersedeFromMessageIndex = supersedeFromMessageIndex,
                    agentKeyOverride = if (effectiveAgentKey != config.agentKey) effectiveAgentKey else null,
                    systemVersionId = selectedSystemVersionId.value
                )

                currentRunId = run.id

                // Update conversation ID if new
                if (conversationId.value == null && run.conversationId != null) {
                    conversationId.value = run.conversationId
                    storage.set(config.conversationIdKey, run.conversationId)
                }

                // Subscribe to SSE events
                subscribeToEvents(run.id)

            } catch (e: Exception) {
                error.value = e.message
                isLoading.value = false
            }
        }
    }

    /** Cancel the current run */
    fun cancelRun() {
        val runId = currentRunId ?: return
        if (!isLoading.value) return

        viewModelScope.launch {
            try {
                apiClient.cancelRun(runId)
                sseClient?.disconnect()
                sseClient = null
                isLoading.value = false
                currentRunId = null

                messages.add(Message(
                    role = MessageRole.SYSTEM,
                    content = "⏹ Run cancelled",
                    type = MessageType.CANCELLED
                ))
            } catch (e: Exception) {
                // Silently fail cancel
            }
        }
    }

    /** Clear all messages and start fresh */
    fun clearMessages() {
        messages.clear()
        conversationId.value = null
        error.value = null
        hasMoreMessages.value = false
        messagesOffset = 0
        storage.set(config.conversationIdKey, null)
    }

    // -- System Selection --

    /** Load available systems from the backend */
    fun loadSystems() {
        isLoadingSystems.value = true
        viewModelScope.launch {
            try {
                val loaded = apiClient.loadSystems()
                systems.clear()
                systems.addAll(loaded)

                // Auto-select if only one system and nothing saved
                if (selectedSystemSlug.value == null && loaded.size == 1) {
                    selectSystem(loaded[0])
                }
            } catch (e: Exception) {
                // Silently fail system loading
            }
            isLoadingSystems.value = false
        }
    }

    /** Select a system */
    fun selectSystem(system: AgentSystem) {
        val previousSlug = selectedSystemSlug.value
        selectedSystemSlug.value = system.slug
        storage.set(config.systemKey, system.slug)

        selectedSystemVersion.value = system.activeVersion
        storage.set(config.systemVersionKey, system.activeVersion)

        val activeVersionId = system.versions?.firstOrNull { it.isActive }?.id
        selectedSystemVersionId.value = activeVersionId
        storage.set(config.systemVersionIdKey, activeVersionId)

        if (previousSlug != system.slug) clearMessages()
    }

    /** Select a specific version of the current system */
    fun selectSystemVersion(version: AgentSystemVersionSummary) {
        selectedSystemVersion.value = version.version
        storage.set(config.systemVersionKey, version.version)
        selectedSystemVersionId.value = version.id
        storage.set(config.systemVersionIdKey, version.id)
        clearMessages()
    }

    /** Clear the system selection */
    fun clearSystemSelection() {
        selectedSystemSlug.value = null
        selectedSystemVersion.value = null
        selectedSystemVersionId.value = null
        storage.set(config.systemKey, null)
        storage.set(config.systemVersionKey, null)
        storage.set(config.systemVersionIdKey, null)
    }

    // -- Conversation Loading --

    /** Load a specific conversation */
    fun loadConversation(convId: String) {
        viewModelScope.launch {
            isLoading.value = true
            messages.clear()
            conversationId.value = convId
            storage.set(config.conversationIdKey, convId)

            try {
                val conversation = apiClient.loadConversation(convId)
                conversation.messages?.forEach { apiMsg ->
                    messages.addAll(mapApiMessage(apiMsg))
                }
                hasMoreMessages.value = conversation.hasMore ?: false
                messagesOffset = conversation.messages?.size ?: 0
            } catch (e: NotFound) {
                conversationId.value = null
                storage.set(config.conversationIdKey, null)
            } catch (e: Exception) {
                // Silently fail
            }
            isLoading.value = false
        }
    }

    /** Load more messages (pagination) */
    fun loadMoreMessages() {
        val convId = conversationId.value ?: return
        if (loadingMoreMessages.value || !hasMoreMessages.value) return

        loadingMoreMessages.value = true
        viewModelScope.launch {
            try {
                val conversation = apiClient.loadConversation(convId, limit = 10, offset = messagesOffset)
                val apiMessages = conversation.messages
                if (apiMessages != null && apiMessages.isNotEmpty()) {
                    val olderMessages = apiMessages.flatMap { mapApiMessage(it) }
                    messages.addAll(0, olderMessages)
                    messagesOffset += apiMessages.size
                    hasMoreMessages.value = conversation.hasMore ?: false
                } else {
                    hasMoreMessages.value = false
                }
            } catch (e: Exception) {
                // Silently fail
            }
            loadingMoreMessages.value = false
        }
    }

    /** Edit a message and resend from that point */
    fun editMessage(index: Int, newContent: String, model: String? = null, thinking: Boolean = false) {
        if (isLoading.value || index >= messages.size) return
        if (messages[index].role != MessageRole.USER) return

        // Truncate
        while (messages.size > index) messages.removeAt(messages.size - 1)
        sendMessage(newContent, model = model, thinking = thinking, supersedeFromMessageIndex = index)
    }

    /** Retry from a specific message */
    fun retryMessage(index: Int, model: String? = null, thinking: Boolean = false) {
        if (isLoading.value || index >= messages.size) return

        val messageAtIndex = messages[index]
        var userMessageIndex = index
        var userMessage = messageAtIndex

        if (messageAtIndex.role == MessageRole.ASSISTANT) {
            for (i in (index - 1) downTo 0) {
                if (messages[i].role == MessageRole.USER) {
                    userMessageIndex = i
                    userMessage = messages[i]
                    break
                }
            }
            if (userMessage.role != MessageRole.USER) return
        } else if (messageAtIndex.role != MessageRole.USER) return

        while (messages.size > userMessageIndex) messages.removeAt(messages.size - 1)
        sendMessage(userMessage.content, model = model, thinking = thinking, supersedeFromMessageIndex = userMessageIndex)
    }

    // -- SSE Event Handling --

    private suspend fun subscribeToEvents(runId: String) {
        sseClient?.disconnect()

        val eventPath = config.apiPaths.runEventsUrl(runId)
        var urlString = "${config.backendUrl}$eventPath"

        val token = try { apiClient.getOrCreateSession() } catch (_: Exception) { null }
        if (token != null) {
            val encoded = URLEncoder.encode(token, "UTF-8")
            urlString += "?anonymous_token=$encoded"
        }

        assistantContent = ""

        val client = SSEClient()
        sseClient = client

        client.onEvent = { event -> handleSSEEvent(event) }
        client.onError = { e ->
            isLoading.value = false
            error.value = e.message
        }
        client.onComplete = { isLoading.value = false }

        client.connect(urlString, apiClient.authHeaders())
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleSSEEvent(event: SSEEvent) {
        val json = event.json() ?: return
        val payload = json["payload"] as? Map<String, Any?> ?: return

        config.onEvent?.invoke(event.type, payload)

        when (event.type) {
            "assistant.message" -> handleAssistantMessage(payload)
            "tool.call" -> handleToolCall(payload)
            "tool.result" -> handleToolResult(payload)
            "sub_agent.start" -> handleSubAgentStart(payload)
            "sub_agent.end" -> handleSubAgentEnd(payload)
            "content.blocks" -> handleContentBlocks(payload)
            "custom" -> handleCustomEvent(payload)
            "run.succeeded", "run.failed", "run.cancelled", "run.timed_out" -> handleTerminalEvent(event.type, payload)
        }
    }

    private fun handleAssistantMessage(payload: Map<String, Any?>) {
        val content = payload["content"] as? String ?: return
        assistantContent += content

        val lastIndex = messages.lastIndex
        if (lastIndex >= 0 &&
            messages[lastIndex].role == MessageRole.ASSISTANT &&
            messages[lastIndex].id.startsWith("assistant-stream-")
        ) {
            messages[lastIndex] = messages[lastIndex].copy(content = assistantContent)
        } else {
            messages.add(Message(
                id = "assistant-stream-${System.currentTimeMillis()}",
                role = MessageRole.ASSISTANT,
                content = assistantContent,
                type = MessageType.MESSAGE
            ))
        }
    }

    private fun handleToolCall(payload: Map<String, Any?>) {
        val name = payload["name"] as? String ?: "tool"
        messages.add(Message(
            id = "tool-call-${System.currentTimeMillis()}",
            role = MessageRole.ASSISTANT,
            content = "🔧 $name",
            type = MessageType.TOOL_CALL,
            metadata = MessageMetadata(
                toolName = name,
                toolCallId = payload["id"] as? String,
                arguments = payload["arguments"] as? String
            )
        ))
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleToolResult(payload: Map<String, Any?>) {
        val result = payload["result"] as? Map<String, Any?>
        val isError = result?.containsKey("error") == true
        val content = if (isError) "❌ ${result?.get("error") ?: "Error"}" else "✓ Done"

        messages.add(Message(
            id = "tool-result-${System.currentTimeMillis()}",
            role = MessageRole.SYSTEM,
            content = content,
            type = MessageType.TOOL_RESULT,
            metadata = MessageMetadata(
                toolName = payload["name"] as? String,
                toolCallId = payload["tool_call_id"] as? String,
                result = result
            )
        ))
    }

    private fun handleSubAgentStart(payload: Map<String, Any?>) {
        val agentName = payload["agent_name"] as? String
            ?: payload["sub_agent_key"] as? String ?: "sub-agent"
        messages.add(Message(
            id = "sub-agent-start-${System.currentTimeMillis()}",
            role = MessageRole.SYSTEM,
            content = "🔗 Delegating to $agentName...",
            type = MessageType.SUB_AGENT_START,
            metadata = MessageMetadata(
                subAgentKey = payload["sub_agent_key"] as? String,
                agentName = payload["agent_name"] as? String,
                invocationMode = payload["invocation_mode"] as? String
            )
        ))
    }

    private fun handleSubAgentEnd(payload: Map<String, Any?>) {
        val agentName = payload["agent_name"] as? String ?: "Sub-agent"
        messages.add(Message(
            id = "sub-agent-end-${System.currentTimeMillis()}",
            role = MessageRole.SYSTEM,
            content = "✓ $agentName completed",
            type = MessageType.SUB_AGENT_END,
            metadata = MessageMetadata(
                subAgentKey = payload["sub_agent_key"] as? String,
                agentName = payload["agent_name"] as? String
            )
        ))
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleContentBlocks(payload: Map<String, Any?>) {
        val blocksArray = payload["blocks"] as? List<Map<String, Any?>> ?: return
        val blocks = ContentBlock.parse(blocksArray)
        if (blocks.isEmpty()) return

        messages.add(Message(
            id = "content-blocks-${System.currentTimeMillis()}",
            role = MessageRole.ASSISTANT,
            content = "",
            type = MessageType.CONTENT_BLOCKS,
            metadata = MessageMetadata(
                toolName = payload["tool_name"] as? String,
                toolCallId = payload["tool_call_id"] as? String,
                contentBlocks = blocks
            )
        ))
    }

    private fun handleCustomEvent(payload: Map<String, Any?>) {
        if (payload["type"] == "agent_context") {
            val agentName = payload["agent_name"] as? String ?: "Sub-agent"
            messages.add(Message(
                id = "agent-context-${System.currentTimeMillis()}",
                role = MessageRole.SYSTEM,
                content = "🔗 $agentName is now handling this request",
                type = MessageType.AGENT_CONTEXT,
                metadata = MessageMetadata(
                    subAgentKey = payload["agent_key"] as? String,
                    agentName = agentName
                )
            ))
        }
    }

    private fun handleTerminalEvent(type: String, payload: Map<String, Any?>) {
        if (type == "run.failed") {
            val errMsg = payload["error"] as? String ?: "Agent run failed"
            error.value = errMsg
            messages.add(Message(
                id = "error-${System.currentTimeMillis()}",
                role = MessageRole.SYSTEM,
                content = "❌ Error: $errMsg",
                type = MessageType.ERROR
            ))
        }

        isLoading.value = false
        sseClient?.disconnect()
        sseClient = null
        currentRunId = null
    }

    // -- Message Mapping --

    private fun mapApiMessage(m: APIMessage): List<Message> {
        val timestamp = Date() // Simplified — could parse m.timestamp

        // Tool result messages (role: "tool")
        if (m.role == "tool") {
            return listOf(Message(
                role = MessageRole.SYSTEM,
                content = "✓ Done",
                timestamp = timestamp,
                type = MessageType.TOOL_RESULT,
                metadata = MessageMetadata(toolCallId = m.toolCallId, result = m.content)
            ))
        }

        // Assistant messages with tool calls
        if (m.role == "assistant" && !m.toolCalls.isNullOrEmpty()) {
            return m.toolCalls.map { tc ->
                val name = tc.function?.name ?: tc.name ?: "tool"
                Message(
                    role = MessageRole.ASSISTANT,
                    content = "🔧 $name",
                    timestamp = timestamp,
                    type = MessageType.TOOL_CALL,
                    metadata = MessageMetadata(
                        toolName = name,
                        toolCallId = tc.id,
                        arguments = tc.function?.arguments ?: tc.arguments
                    )
                )
            }
        }

        // Skip empty assistant messages
        val content = m.content ?: ""
        if (m.role == "assistant" && content.isBlank()) return emptyList()

        // Regular messages
        return listOf(Message(
            role = MessageRole.fromValue(m.role),
            content = content,
            timestamp = timestamp,
            type = MessageType.MESSAGE
        ))
    }
}

