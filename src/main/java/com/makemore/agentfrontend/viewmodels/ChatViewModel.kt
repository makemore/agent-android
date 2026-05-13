package com.makemore.agentfrontend.viewmodels

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.makemore.agentfrontend.configuration.ChatWidgetConfig
import com.makemore.agentfrontend.models.*
import com.makemore.agentfrontend.networking.*
import com.makemore.agentfrontend.services.LocalConversation
import com.makemore.agentfrontend.services.LocalConversationSummary
import com.makemore.agentfrontend.services.LocalHistoryStore
import com.makemore.agentfrontend.services.LocalMessage
import com.makemore.agentfrontend.services.StorageService
import com.makemore.agentfrontend.voice.Emotion
import com.makemore.agentfrontend.voice.VoiceController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder
import java.util.Date

/**
 * Main view model for chat functionality.
 * Mirrors the iOS ChatViewModel class.
 */
class ChatViewModel(
    private val config: ChatWidgetConfig,
    val apiClient: APIClient,
    private val storage: StorageService,
    private val context: Context? = null
) : ViewModel() {

    // -- Observable State --
    val messages = mutableStateListOf<Message>()
    val isLoading = mutableStateOf(false)
    val error = mutableStateOf<String?>(null)
    val conversationId = mutableStateOf<String?>(null)
    val hasMoreMessages = mutableStateOf(false)
    val loadingMoreMessages = mutableStateOf(false)
    val runState = mutableStateOf(RunState.IDLE)

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

    // -- Streaming buffer --
    // Decouples network receive rate from visual display rate. Providers emit
    // tokens in bursts; rendering those bursts directly causes visible stutter.
    // Deltas are buffered and drained at a steady cadence.
    private var streamBuffer: StringBuilder = StringBuilder()
    private var drainJob: Job? = null
    private val drainIntervalMs: Long = 33L   // ~30 Hz
    /** Set true when server signals stream end — lets the drain accelerate. */
    private var streamingDone: Boolean = false
    /** ID of the in-flight streaming message; tracked explicitly so we can
     *  find it even after non-streaming messages have been appended. */
    private var currentStreamingMessageId: String? = null
    /** Set true when an `assistant.message` finalises the current turn; any
     *  further `assistant.delta` events are dropped so they don't spawn a
     *  duplicate typewriter bubble. Reset on any non-streaming event. */
    private var turnFinalized: Boolean = false

    // -- Sub-agent echo suppression --
    /** Snapshot of the sub-agent's last streamed answer, captured at
     *  `sub_agent.end`. The parent agent often re-streams the same text
     *  verbatim as its own deltas; this lets us detect and suppress that
     *  echo while still rendering genuinely novel parent output. */
    private var pendingEchoReference: String? = null
    /** Parent deltas buffered silently while we're still deciding whether
     *  its output is an echo. Chars here are not committed to any bubble. */
    private var pendingEchoBuffer: StringBuilder = StringBuilder()
    /** Once the parent's stream diverges from the snapshot we stop
     *  comparing and treat subsequent deltas as a normal stream. */
    private var pendingEchoDiverged: Boolean = false

    // -- Voice (TTS) --
    /** Optional voice controller. When set, `assistant.delta` and
     *  `assistant.message` events are streamed into it for sentence-level
     *  TTS playback. Owned by the host UI so its lifecycle matches the
     *  composable / activity rather than the view model. */
    var voiceController: VoiceController? = null

    // -- Stream completion awaiter --
    /** Resolved when the in-flight SSE stream reaches a terminal state
     *  (run.succeeded / run.failed / run.cancelled / transport error or
     *  manual cancel). Lets [sendMessageAndAwait] suspend until the run
     *  truly finishes — without it the launched coroutine returns the
     *  moment `client.connect()` is enqueued and a scripted follow-up
     *  message is silently dropped by the `isLoading` guard. Single-shot:
     *  only one stream is in flight at a time because [sendMessage] is
     *  gated by [isLoading]. */
    private var streamCompletion: CompletableDeferred<Unit>? = null

    // -- Ephemeral Memories --
    // Client-side memories (facts/preferences) persisted locally and
    // sent to the server with each ephemeral run. Updated via the
    // `memory.update` SSE event.
    private var clientMemories: MutableList<Map<String, String>> = mutableListOf()

    // -- Local History (ephemeral mode) --
    /** Observable list of locally-persisted conversations (newest first). */
    val localConversations = mutableStateListOf<LocalConversationSummary>()
    private var localHistoryStore: LocalHistoryStore? = null
    private var localConversationCreatedAt: Long? = null

    companion object {
        private const val MEMORIES_STORAGE_KEY = "chat_widget_memories"
    }

    init {
        // Load saved conversation ID — server-side mode only.
        // In ephemeral mode the conversationIdKey slot holds a stale
        // server id that the user has no way to resume; rehydrating it
        // causes createRun to 404. Local conversations are loaded via
        // loadLocalConversation(id) instead.
        if (!config.ephemeral) {
            storage.get(config.conversationIdKey)?.let { conversationId.value = it }
        }
        // Load saved system selection
        storage.get(config.systemKey)?.let { selectedSystemSlug.value = it }
        storage.get(config.systemVersionKey)?.let { selectedSystemVersion.value = it }
        storage.get(config.systemVersionIdKey)?.let { selectedSystemVersionId.value = it }

        // Load persisted client-side memories (ephemeral mode)
        storage.get(MEMORIES_STORAGE_KEY)?.let { json ->
            try {
                val parsed = org.json.JSONArray(json)
                for (i in 0 until parsed.length()) {
                    val obj = parsed.getJSONObject(i)
                    val entry = mutableMapOf<String, String>()
                    obj.keys().forEach { key -> entry[key] = obj.getString(key) }
                    clientMemories.add(entry)
                }
            } catch (_: Exception) { /* corrupted — start fresh */ }
        }

        // Initialise local history store for ephemeral mode
        if (config.ephemeral && context != null) {
            val store = LocalHistoryStore(context.applicationContext, config.agentKey)
            localHistoryStore = store
            localConversations.addAll(store.loadIndex())
        }
    }

    /** Restore the saved conversation on launch */
    fun restoreConversationIfNeeded() {
        if (hasRestoredConversation) return
        hasRestoredConversation = true

        // Ephemeral mode: nothing to restore from the server.
        if (config.ephemeral) return

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

    /**
     * Fire-and-forget send. Compose-friendly: callable directly from a
     * button `onClick`. The launched coroutine awaits the full SSE stream
     * before returning, so subsequent calls from inside a single launched
     * scope queue naturally behind it.
     */
    fun sendMessage(
        content: String,
        files: List<FileAttachment> = emptyList(),
        model: String? = null,
        thinking: Boolean = false,
        supersedeFromMessageIndex: Int? = null
    ) {
        viewModelScope.launch {
            sendMessageAndAwait(content, files, model, thinking, supersedeFromMessageIndex)
        }
    }

    /**
     * Suspending send that returns when the stream reaches a terminal
     * state (succeeded / failed / cancelled / transport error). Use this
     * from coroutine-driven flows (instrumented tests, host scripts) that
     * need to enqueue follow-up turns sequentially without losing them
     * to the [isLoading] guard.
     */
    suspend fun sendMessageAndAwait(
        content: String,
        files: List<FileAttachment> = emptyList(),
        model: String? = null,
        thinking: Boolean = false,
        supersedeFromMessageIndex: Int? = null
    ) {
        val trimmed = content.trim()
        if (trimmed.isEmpty() || isLoading.value) return

        isLoading.value = true
        runState.value = RunState.SENDING
        error.value = null

        // New turn — drop any half-spoken audio from the previous assistant
        // response and clear the chunker's buffer.
        voiceController?.reset()

        // Add user message
        messages.add(Message(
            role = MessageRole.USER,
            content = trimmed,
            files = files.ifEmpty { null }
        ))

        try {
            // In ephemeral mode send the full conversation history.
            val apiMessages: List<Map<String, Any>> = if (config.ephemeral) {
                val history = messages
                    .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
                    .dropLast(1)  // the user message we just appended is re-added below
                    .map { mapOf("role" to it.role.value, "content" to (it.content ?: "")) }
                history + listOf(mapOf("role" to "user", "content" to trimmed))
            } else {
                listOf(mapOf("role" to "user", "content" to trimmed))
            }

            val run = apiClient.createRun(
                conversationId = conversationId.value,
                messages = apiMessages,
                model = model,
                thinking = thinking,
                supersedeFromMessageIndex = supersedeFromMessageIndex,
                agentKeyOverride = if (effectiveAgentKey != config.agentKey) effectiveAgentKey else null,
                systemVersionId = selectedSystemVersionId.value,
                ephemeral = config.ephemeral,
                memories = if (config.ephemeral) clientMemories else null
            )

            currentRunId = run.id
            runState.value = RunState.STREAMING

            // Update conversation ID if new
            if (conversationId.value == null && run.conversationId != null) {
                conversationId.value = run.conversationId
                storage.set(config.conversationIdKey, run.conversationId)
                if (config.ephemeral) localConversationCreatedAt = System.currentTimeMillis()
            }

            // Subscribe to SSE events and suspend until the stream
            // reaches a terminal state.
            subscribeToEvents(run.id)

        } catch (e: Exception) {
            error.value = e.message
            isLoading.value = false
            runState.value = RunState.FAILED
            resolveStreamCompletion()
        }
    }

    /** Cancel the current run */
    fun cancelRun() {
        val runId = currentRunId ?: return
        if (!isLoading.value) return

        viewModelScope.launch {
            try {
                runState.value = RunState.CANCELLING
                apiClient.cancelRun(runId)
                sseClient?.disconnect()
                sseClient = null
                // Drop any buffered-but-not-yet-drained characters and stop the
                // typewriter loop. Without this, the drain job keeps revealing
                // whatever the server sent before the disconnect — the user sees
                // text continuing to type for seconds after tapping Stop. This
                // differs from the natural-end path (`handleTerminalEvent`) which
                // deliberately lets the drain finish smoothly.
                resetStreamBuffer()
                // Cut off any in-flight TTS playback when the user cancels.
                voiceController?.stop()
                isLoading.value = false
                runState.value = RunState.CANCELLED
                currentRunId = null

                messages.add(Message(
                    role = MessageRole.SYSTEM,
                    content = "⏹ Run cancelled",
                    type = MessageType.CANCELLED
                ))
                // Wake any awaiter inside `subscribeToEvents`.
                resolveStreamCompletion()
            } catch (e: Exception) {
                // Silently fail cancel — but still wake the awaiter so a
                // pending `sendMessageAndAwait` doesn't hang forever.
                resolveStreamCompletion()
            }
        }
    }

    /** Clear all messages and start fresh.
     *  Does NOT delete the conversation from local storage — it just
     *  starts a new in-memory conversation. */
    fun clearMessages() {
        messages.clear()
        conversationId.value = null
        localConversationCreatedAt = null
        error.value = null
        hasMoreMessages.value = false
        messagesOffset = 0
        runState.value = RunState.IDLE
        storage.set(config.conversationIdKey, null)
    }

    // -- Local History (ephemeral mode) --

    /** Returns the list of locally-persisted conversations (newest first). */
    fun loadLocalConversations(): List<LocalConversationSummary> {
        val store = localHistoryStore ?: return emptyList()
        val list = store.loadIndex()
        localConversations.clear()
        localConversations.addAll(list)
        return list
    }

    /** Hydrates the VM with a locally-persisted conversation.
     *  Returns true if the conversation was found and loaded. */
    fun loadLocalConversation(id: String): Boolean {
        val store = localHistoryStore ?: return false
        val conv = store.load(id) ?: return false
        messages.clear()
        messages.addAll(conv.messages.map { it.toMessage() })
        conversationId.value = id
        localConversationCreatedAt = conv.createdAt
        storage.set(config.conversationIdKey, id)
        hasMoreMessages.value = false
        messagesOffset = 0
        error.value = null
        return true
    }

    /** Delete a single locally-persisted conversation. */
    fun deleteLocalConversation(id: String) {
        localHistoryStore?.delete(id)
        localConversations.clear()
        localConversations.addAll(localHistoryStore?.loadIndex() ?: emptyList())
        if (conversationId.value == id) clearMessages()
    }

    /** Purge all locally-persisted conversations for this agent. */
    fun purgeLocalHistory() {
        localHistoryStore?.purgeAll()
        localConversations.clear()
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
        // Ephemeral mode: conversation is local-only, nothing to fetch.
        if (config.ephemeral) {
            conversationId.value = convId
            return
        }

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
        resetStreamBuffer()
        currentStreamingMessageId = null
        turnFinalized = false
        clearPendingEcho()

        val client = SSEClient()
        sseClient = client

        // Single-shot completion gate. Resolved by `onComplete`,
        // `onError`, or `cancelRun` — whichever fires first. The await
        // below suspends [sendMessageAndAwait] until that happens.
        val completion = CompletableDeferred<Unit>()
        streamCompletion = completion

        client.onEvent = { event -> handleSSEEvent(event) }
        client.onError = { e ->
            isLoading.value = false
            error.value = e.message
            resolveStreamCompletion()
        }
        client.onComplete = {
            isLoading.value = false
            resolveStreamCompletion()
        }

        client.connect(urlString, apiClient.authHeaders())

        // Suspend until terminal state. Safe under structured concurrency:
        // if the parent coroutine is cancelled, the await throws and the
        // outer try/finally in [sendMessageAndAwait] handles cleanup.
        completion.await()
    }

    /** Single-shot resume of the in-flight stream awaiter. Safe to call
     *  from `onComplete`, `onError`, and [cancelRun]; only the first call
     *  resolves the deferred, subsequent calls are no-ops. */
    private fun resolveStreamCompletion() {
        val pending = streamCompletion ?: return
        streamCompletion = null
        pending.complete(Unit)
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleSSEEvent(event: SSEEvent) {
        val json = event.json() ?: return
        val payload = json["payload"] as? Map<String, Any?> ?: return

        config.onEvent?.invoke(event.type, payload)
        runState.value = runState.value.apply(event.type)

        when (event.type) {
            "assistant.delta" -> handleAssistantDelta(payload)
            "assistant.message" -> handleAssistantMessage(payload)
            "tool.call" -> handleToolCall(payload)
            "tool.result" -> handleToolResult(payload)
            "sub_agent.start" -> handleSubAgentStart(payload)
            "sub_agent.end" -> handleSubAgentEnd(payload)
            "content.blocks" -> handleContentBlocks(payload)
            "custom" -> handleCustomEvent(payload)
            "memory.update" -> handleMemoryUpdate(payload)
            "client.action.required", "run.suspended" -> handleRequiredAction(payload)
            "run.succeeded", "run.failed", "run.cancelled", "run.timed_out" -> handleTerminalEvent(event.type, payload)
        }
    }

    private fun handleAssistantDelta(payload: Map<String, Any?>) {
        val delta = payload["delta"] as? String ?: return

        // Drop late-arriving deltas for a turn whose authoritative
        // `assistant.message` has already been applied. A new turn is
        // signalled by any non-streaming event, which resets `turnFinalized`.
        if (turnFinalized) return

        // Per-delta emotion overrides the turn-level value when present.
        val emotion = Emotion.from(payload["emotion"])

        // Sub-agent echo suppression: if a sub-agent just finished, buffer
        // parent deltas silently while they still match the snapshot as a
        // prefix. Render only when the parent diverges or extends past it.
        val reference = pendingEchoReference
        if (reference != null && !pendingEchoDiverged) {
            pendingEchoBuffer.append(delta)
            val buffered = pendingEchoBuffer.toString()
            if (reference.startsWith(buffered)) {
                return
            }
            pendingEchoDiverged = true
            pendingEchoReference = null
            val replay = if (buffered.startsWith(reference)) {
                buffered.substring(reference.length)
            } else {
                buffered
            }
            pendingEchoBuffer = StringBuilder()
            if (replay.isEmpty()) return
            assistantContent = ""
            resetStreamBuffer()
            streamBuffer.append(replay)
            voiceController?.pushDelta(replay, emotion)
            startDrainTimerIfNeeded()
            return
        }

        // Detect a *fresh* stream session — one with nothing in-flight to
        // belong to. We can't rely on `currentStreamingMessageId` alone:
        // that ID is only assigned on the first `drainTick`, so when
        // multiple deltas arrive within the same coroutine tick (network
        // bursts where several SSE events sit in the same socket read,
        // or replay in tests) the later deltas would all see a null ID
        // and wipe each other's contribution to the buffer. Treat any of:
        // an existing bubble, a running drain job, or un-drained buffered
        // text as proof we're still mid-session. A just-snapped bubble
        // shares the "assistant-stream-" prefix but belongs to a finalised
        // turn — the next delta must create a fresh bubble below it.
        val hasActiveSession = currentStreamingMessageId != null
            || drainJob != null
            || streamBuffer.isNotEmpty()
        if (!hasActiveSession) {
            assistantContent = ""
            resetStreamBuffer()
        }

        streamBuffer.append(delta)
        voiceController?.pushDelta(delta, emotion)
        startDrainTimerIfNeeded()
    }

    /**
     * Handle an `assistant.message` event — the final authoritative text
     * emitted after any deltas (or on its own when streaming is off).
     * We *replace* the accumulator with the full content so the message is
     * correct whether or not the client received every delta.
     */
    private fun handleAssistantMessage(payload: Map<String, Any?>) {
        val content = payload["content"] as? String ?: return

        // Unconditionally mark the turn finalised — the server's "this turn
        // is done" signal. Any `assistant.delta` that arrives later must be
        // dropped to avoid a second typewriter bubble.
        turnFinalized = true

        // Voice: if the run streamed deltas the chunker has been fed
        // throughout — `finishTurn(finalText = null)` flushes the trailing
        // fragment. If it didn't (non-streaming run), pass `content` so
        // the user still hears the reply.
        val voiceEmotion = Emotion.from(payload["emotion"])
        val needsFallbackText = streamBuffer.isEmpty() && drainJob == null
        voiceController?.finishTurn(
            finalText = if (needsFallbackText) content else null,
            emotion = voiceEmotion,
        )

        // Sub-agent echo resolution. If we were still comparing the parent's
        // stream against a sub-agent snapshot when the final message lands,
        // the snapshot's bubble already shows the authoritative text.
        val reference = pendingEchoReference
        if (reference != null) {
            clearPendingEcho()
            if (content == reference || reference.startsWith(content)) {
                return
            }
            if (content.startsWith(reference)) {
                val suffix = content.substring(reference.length)
                if (suffix.isEmpty()) return
                assistantContent = suffix
                upsertStreamingMessage(assistantContent)
                closeStreamingSession()
                turnFinalized = true
                return
            }
            // Parent said something genuinely different — fall through.
        }

        // If deltas are still draining, the same content is already queued
        // in streamBuffer; snapping here would produce a visible leap to
        // the end. Let the drain finish smoothly.
        if (drainJob != null || streamBuffer.isNotEmpty()) {
            return
        }

        assistantContent = content
        upsertStreamingMessage(assistantContent)
        closeStreamingSession()
        turnFinalized = true
    }

    /**
     * Create or update the in-flight streaming assistant message. Uses
     * `currentStreamingMessageId` to locate it even after non-streaming
     * messages have been appended below.
     */
    private fun upsertStreamingMessage(content: String) {
        val id = currentStreamingMessageId
        val idx = if (id != null) messages.indexOfFirst { it.id == id } else -1
        if (idx >= 0) {
            messages[idx] = messages[idx].copy(content = content, isStreaming = true)
        } else {
            val newId = "assistant-stream-${System.nanoTime()}"
            currentStreamingMessageId = newId
            messages.add(Message(
                id = newId,
                role = MessageRole.ASSISTANT,
                content = content,
                type = MessageType.MESSAGE,
                isStreaming = true,
            ))
        }
    }

    /**
     * Close out the current streaming session before inserting any
     * non-delta message. Flushes any pending buffer into the current
     * bubble and forgets the streaming ID so the next text event creates
     * a new bubble below the inserted non-streaming one.
     */
    private fun closeStreamingSession() {
        if (drainJob != null || streamBuffer.isNotEmpty()) {
            flushStreamBuffer()
        }
        // Flip the outgoing streaming bubble off the fast-path renderer
        // so it re-renders once with full Markdown.
        currentStreamingMessageId?.let { id ->
            val idx = messages.indexOfFirst { it.id == id }
            if (idx >= 0 && messages[idx].isStreaming) {
                messages[idx] = messages[idx].copy(isStreaming = false)
            }
        }
        currentStreamingMessageId = null
        assistantContent = ""
        streamingDone = false
        turnFinalized = false
    }

    private fun handleToolCall(payload: Map<String, Any?>) {
        closeStreamingSession()
        clearPendingEcho()
        val name = payload["name"] as? String ?: payload["tool_name"] as? String ?: "tool"
        messages.add(Message(
            id = "tool-call-${System.currentTimeMillis()}",
            role = MessageRole.ASSISTANT,
            content = "🔧 $name",
            type = MessageType.TOOL_CALL,
            metadata = MessageMetadata(
                toolName = name,
                toolCallId = payload["id"] as? String ?: payload["tool_call_id"] as? String,
                arguments = stringifyPayload(payload["arguments"] ?: payload["tool_args"])
            )
        ))
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleToolResult(payload: Map<String, Any?>) {
        closeStreamingSession()
        val result = payload["result"] as? Map<String, Any?>
        val isError = result?.containsKey("error") == true
        val content = if (isError) "❌ ${result?.get("error") ?: "Error"}" else "✓ Done"

        messages.add(Message(
            id = "tool-result-${System.currentTimeMillis()}",
            role = MessageRole.SYSTEM,
            content = content,
            type = MessageType.TOOL_RESULT,
            metadata = MessageMetadata(
                toolName = payload["name"] as? String ?: payload["tool_name"] as? String,
                toolCallId = payload["tool_call_id"] as? String ?: payload["id"] as? String,
                result = result
            )
        ))
    }

    private fun stringifyPayload(value: Any?): String? {
        return when (value) {
            null -> null
            is String -> value
            is Map<*, *> -> org.json.JSONObject(value).toString()
            is List<*> -> org.json.JSONArray(value).toString()
            else -> value.toString()
        }
    }

    private fun handleSubAgentStart(payload: Map<String, Any?>) {
        closeStreamingSession()
        clearPendingEcho()
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
        closeStreamingSession()
        // Capture the sub-agent's final streamed text as the echo reference
        // so the parent's upcoming re-stream of the same answer can be
        // suppressed while letting the sub-agent's narration render.
        val echoReference = lastStreamedAssistantText()
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
        if (!echoReference.isNullOrEmpty()) {
            pendingEchoReference = echoReference
            pendingEchoBuffer = StringBuilder()
            pendingEchoDiverged = false
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleContentBlocks(payload: Map<String, Any?>) {
        closeStreamingSession()
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

    @Suppress("UNCHECKED_CAST")
    private fun handleRequiredAction(payload: Map<String, Any?>) {
        closeStreamingSession()
        clearPendingEcho()
        voiceController?.stop()

        val action = payload["required_action"] as? Map<String, Any?> ?: payload
        val title = action["title"] as? String ?: "Action required"
        val message = action["message"] as? String ?: "Please complete the requested action to continue."
        val actionId = action["action_id"] as? String
        val alreadyRendered = actionId != null && messages.any {
            it.type == MessageType.REQUIRED_ACTION && it.metadata?.actionId == actionId
        }
        if (!alreadyRendered) {
            messages.add(Message(
                id = "required-action-${System.currentTimeMillis()}",
                role = MessageRole.SYSTEM,
                content = message,
                type = MessageType.REQUIRED_ACTION,
                metadata = MessageMetadata(
                    actionId = actionId,
                    actionType = action["action_type"] as? String,
                    actionUrl = action["action_url"] as? String,
                    actionLabel = action["action_label"] as? String ?: title,
                    resumeHint = action["resume_hint"]
                )
            ))
        }

        isLoading.value = false
        sseClient?.disconnect()
        sseClient = null
        currentRunId = null
        runState.value = RunState.WAITING
        resolveStreamCompletion()
        persistToLocalHistory()
    }

    private fun handleCustomEvent(payload: Map<String, Any?>) {
        closeStreamingSession()
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

    /**
     * Handle a `memory.update` event — the server extracted memories from
     * the conversation and is sending them back for client-side persistence.
     */
    @Suppress("UNCHECKED_CAST")
    private fun handleMemoryUpdate(payload: Map<String, Any?>) {
        val memoriesArray = payload["memories"] as? List<Map<String, Any?>> ?: return

        for (mem in memoriesArray) {
            val key = mem["key"] as? String ?: continue
            val action = mem["action"] as? String ?: "upsert"

            if (action == "delete") {
                clientMemories.removeAll { it["key"] == key }
            } else {
                // Upsert: remove old entry with same key, then append
                clientMemories.removeAll { it["key"] == key }
                val entry = mutableMapOf("key" to key)
                (mem["value"] as? String)?.let { entry["value"] = it }
                (mem["type"] as? String)?.let { entry["type"] = it }
                clientMemories.add(entry)
            }
        }

        // Persist to local storage
        val jsonArray = org.json.JSONArray()
        for (entry in clientMemories) {
            val obj = org.json.JSONObject()
            entry.forEach { (k, v) -> obj.put(k, v) }
            jsonArray.put(obj)
        }
        storage.set(MEMORIES_STORAGE_KEY, jsonArray.toString())
    }

    private fun handleTerminalEvent(type: String, payload: Map<String, Any?>) {
        if (type == "run.failed") {
            // Close the stream so the error doesn't orphan a streaming bubble
            // or let subsequent text overwrite it.
            closeStreamingSession()
            clearPendingEcho()
            // Cancel any in-flight TTS — the user shouldn't hear a half
            // sentence after the failure banner appears.
            voiceController?.stop()
            val errMsg = payload["error"] as? String ?: "Agent run failed"
            error.value = errMsg
            messages.add(Message(
                id = "error-${System.currentTimeMillis()}",
                role = MessageRole.SYSTEM,
                content = "❌ Error: $errMsg",
                type = MessageType.ERROR
            ))
        } else {
            // Success / cancelled / timed-out: let the drain timer finish
            // smoothly at its elevated rate; flushing would produce a
            // visible leap. The timer self-cancels when the buffer empties.
            streamingDone = true
            clearPendingEcho()
            if (type == "run.cancelled" || type == "run.timed_out") {
                voiceController?.stop()
            } else {
                // Success: flush any trailing text the chunker still
                // holds so the final fragment gets spoken. No-op when
                // assistant.message already flushed.
                voiceController?.finishTurn()
            }
        }

        isLoading.value = false
        sseClient?.disconnect()
        sseClient = null
        currentRunId = null

        // Persist to local history store (ephemeral mode)
        persistToLocalHistory()
    }

    /** Save the current conversation to the local history store. */
    private fun persistToLocalHistory() {
        if (!config.ephemeral) return
        val store = localHistoryStore ?: return
        val convId = conversationId.value ?: return
        if (messages.isEmpty()) return

        val now = System.currentTimeMillis()
        val title = deriveConversationTitle()
        val localMsgs = messages.map { LocalMessage(it) }
        val conv = LocalConversation(
            id = convId,
            title = title,
            createdAt = localConversationCreatedAt ?: now,
            updatedAt = now,
            messages = localMsgs
        )
        store.upsert(conv)
        localConversations.clear()
        localConversations.addAll(store.loadIndex())
    }

    /** Derive a title for the conversation from the first user message. */
    private fun deriveConversationTitle(): String {
        val firstUser = messages.firstOrNull { it.role == MessageRole.USER }
            ?: return "Untitled conversation"
        val collapsed = firstUser.content.trim().replace(Regex("\\s+"), " ")
        if (collapsed.isEmpty()) return "Untitled conversation"
        return if (collapsed.length <= 60) collapsed else collapsed.take(60) + "…"
    }

    // -- Stream buffer helpers --

    /**
     * Start the drain loop if it isn't already running.
     * The delay runs on [Dispatchers.Default] so its timing isn't affected
     * by main-thread contention during scroll gestures (Choreographer frame
     * callbacks can starve Handler.postDelayed-based coroutine resumptions
     * while the user is actively touch-scrolling). The actual state mutation
     * ([drainTick]) switches back to [Dispatchers.Main] automatically because
     * [viewModelScope] is Main-confined.
     */
    private fun startDrainTimerIfNeeded() {
        if (drainJob != null) return
        drainJob = viewModelScope.launch {
            while (isActive) {
                withContext(Dispatchers.Default) { delay(drainIntervalMs) }
                if (!drainTick()) break
            }
        }
    }

    /**
     * Move a slice of buffered chars into the visible message. Rate is
     * adaptive: large buffers drain faster so long responses don't lag
     * far behind the server. A short word-boundary lookahead lets short
     * words land as a unit instead of being cut into 2-char pulses,
     * which reads as much smoother at the same effective char-per-second
     * rate.
     *
     * @return false if the drain is complete and the loop should exit.
     */
    private fun drainTick(): Boolean {
        if (streamBuffer.isEmpty()) {
            drainJob = null
            streamingDone = false
            return false
        }
        val pending = streamBuffer.length
        val cap = if (streamingDone) 6 else 2
        var take = maxOf(1, minOf(pending / 120, cap))

        // Word-boundary preference: if the slice would end mid-word,
        // look ahead a few chars and extend through the next whitespace
        // so the word lands whole. Long words (no space within the
        // lookahead window) still reveal at the base rate, preserving
        // the typewriter feel for them.
        if (take < pending && !streamBuffer[take - 1].isWhitespace()) {
            val lookahead = minOf(pending - take, 5)
            for (offset in 0 until lookahead) {
                if (streamBuffer[take + offset].isWhitespace()) {
                    take += offset + 1
                    break
                }
            }
        }

        val slice = streamBuffer.substring(0, take)
        streamBuffer.delete(0, take)
        assistantContent += slice
        upsertStreamingMessage(assistantContent)
        return true
    }

    /** Drain all remaining buffered chars and stop the loop. */
    private fun flushStreamBuffer() {
        if (streamBuffer.isNotEmpty()) {
            assistantContent += streamBuffer.toString()
            streamBuffer = StringBuilder()
            upsertStreamingMessage(assistantContent)
        }
        drainJob?.cancel()
        drainJob = null
    }

    /** Drop buffered chars and stop the loop (used on stream start). */
    private fun resetStreamBuffer() {
        streamBuffer = StringBuilder()
        drainJob?.cancel()
        drainJob = null
        streamingDone = false
    }

    /** Reset sub-agent echo-suppression state at turn boundaries. */
    private fun clearPendingEcho() {
        pendingEchoReference = null
        pendingEchoBuffer = StringBuilder()
        pendingEchoDiverged = false
    }

    /**
     * Content of the most recent streamed assistant bubble, used as the
     * echo-suppression reference when a sub-agent has just ended.
     * Intermediate tool-call, tool-result, content-block and sub-agent
     * markers are skipped.
     */
    private fun lastStreamedAssistantText(): String? {
        for (i in messages.indices.reversed()) {
            val msg = messages[i]
            if (msg.role == MessageRole.ASSISTANT && msg.type == MessageType.MESSAGE && msg.content.isNotEmpty()) {
                return msg.content
            }
        }
        return null
    }

    // -- Message Mapping --

    private fun mapApiMessage(m: APIMessage): List<Message> {
        val timestamp = Date() // Simplified — could parse m.timestamp

        // Tool result messages (role: "tool"). If the backend persisted
        // contentBlocks on the tool message, synthesise an extra bubble so
        // rich UI (videos, cards, etc.) re-renders on conversation reload.
        if (m.role == "tool") {
            val out = mutableListOf(Message(
                role = MessageRole.SYSTEM,
                content = "✓ Done",
                timestamp = timestamp,
                type = MessageType.TOOL_RESULT,
                metadata = MessageMetadata(
                    toolName = m.metadata?.toolName,
                    toolCallId = m.toolCallId,
                    result = m.content
                )
            ))
            val rawBlocks = m.metadata?.contentBlocks
            if (!rawBlocks.isNullOrEmpty()) {
                val blocks = ContentBlock.parse(rawBlocks.map { it.toAnyMap() })
                if (blocks.isNotEmpty()) {
                    out.add(Message(
                        role = MessageRole.ASSISTANT,
                        content = "",
                        timestamp = timestamp,
                        type = MessageType.CONTENT_BLOCKS,
                        metadata = MessageMetadata(
                            toolName = m.metadata?.toolName,
                            toolCallId = m.toolCallId,
                            contentBlocks = blocks
                        )
                    ))
                }
            }
            return out
        }

        // Assistant messages with tool calls
        val toolCalls = m.toolCalls
        if (m.role == "assistant" && !toolCalls.isNullOrEmpty()) {
            return toolCalls.map { tc ->
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

/** Convert a JsonObject to the raw Map<String, Any?> shape ContentBlock.parse expects. */
private fun JsonObject.toAnyMap(): Map<String, Any?> =
    mapValues { (_, v) -> v.toAnyValue() }

private fun JsonElement.toAnyValue(): Any? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
    is JsonObject -> toAnyMap()
    is JsonArray -> map { it.toAnyValue() }
}

