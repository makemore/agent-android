package com.makemore.agentfrontend.viewmodels

import com.makemore.agentfrontend.configuration.AuthStrategy
import com.makemore.agentfrontend.configuration.ChatWidgetConfig
import com.makemore.agentfrontend.models.*
import com.makemore.agentfrontend.networking.*
import com.makemore.agentfrontend.services.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
class RunRecoveryTest {
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val storage = InMemoryStorage()
    private val httpClients = mutableListOf<OkHttpClient>()
    private val models = mutableListOf<ChatViewModel>()
    private val config = ChatWidgetConfig(backendUrl = "https://fixture.invalid", agentKey = "agent",
        authStrategy = AuthStrategy.NONE, recoveryAccountId = "account-a")
    private val store get() = PendingRunStore(storage,
        PendingRunStore.scope(config.backendUrl, "account-a", config.agentKey, config.defaultJourneyType),
        PendingRunStore.scope(config.backendUrl, "account-a", ""))

    @Before fun setup() { Dispatchers.setMain(main) }
    @After fun cleanup() {
        runBlocking(main) { models.forEach { it.dispose() } }
        httpClients.forEach { it.dispatcher.executorService.shutdown(); it.connectionPool.evictAll() }
        Dispatchers.resetMain()
        main.close()
    }

    private fun vm(configuration: ChatWidgetConfig = config, respond: (Request) -> Response): ChatViewModel {
        val http = OkHttpClient.Builder().addInterceptor { respond(it.request()) }.build()
        httpClients.add(http)
        return ChatViewModel(configuration, APIClient(configuration, storage, http), storage,
            sseFactory = { SSEClient(http, Dispatchers.Main.immediate) }).also { models.add(it) }
    }

    private fun response(request: Request, body: String, status: Int = 200, sse: Boolean = false) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
            .body(body.toResponseBody((if (sse) "text/event-stream" else "application/json").toMediaType())).build()

    private val completed = """{"id":"run-1","conversationId":"c","status":"succeeded","output":{"final_messages":[{"id":"final-1","role":"assistant","content":"Complete answer"}]}}"""
    private fun event(type: String, payload: String) = "event: $type\ndata: {\"payload\":$payload}\n\n"

    @Test fun lostAcknowledgementRecoversByKeyWithoutSecondPost() = runBlocking(main) {
        var posts = 0
        var lookups = 0
        val vm = vm { request ->
            if (request.method == "POST") {
                posts++
                val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                assertEquals("must persist exact body BEFORE POST", body, store.load()?.requestBody)
                assertEquals(JSONObject(body).getString("idempotency_key"), store.load()?.key)
                response(request, "{invalid acknowledgement", 201)
            } else {
                assertTrue(request.url.encodedPath.contains("by-idempotency-key"))
                lookups++
                response(request, completed)
            }
        }
        withTimeout(5_000) { vm.sendMessageAndAwait("Hello") }
        assertEquals(1, posts)
        assertEquals(1, lookups)
        assertEquals("Complete answer", vm.messages.last().content)
        assertEquals(RunState.SUCCEEDED, vm.runState.value)
        assertNull(store.load())
    }

    @Test fun unknownKeyRetriesIdenticalBodyAndSuppressesReplayActions() = runBlocking(main) {
        val bodies = mutableListOf<String>()
        var externalActions = 0
        val vm = vm(config.copy(onEvent = { _, _ -> externalActions++ })) { request ->
            when {
                request.method == "POST" -> {
                    bodies.add(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                    if (bodies.size == 1) throw java.io.IOException("synthetic lost acknowledgement")
                    response(request, """{"id":"run-1","status":"running"}""", 201)
                }
                request.url.encodedPath.contains("by-idempotency-key") -> response(request, "{}", 404)
                else -> response(request,
                    event("tool.call", """{"name":"navigate_to"}""") +
                    event("assistant.message", """{"content":"Complete answer"}""") +
                    event("run.succeeded", "{}"), sse = true)
            }
        }
        withTimeout(5_000) { vm.sendMessageAndAwait("Hello") }
        assertEquals(2, bodies.size)
        assertEquals(bodies[0], bodies[1])
        assertEquals(0, externalActions)
        assertEquals(RunState.SUCCEEDED, vm.runState.value)
    }

    @Test fun eofRepairsMissedDeltaAndNeverReportsPrematureSuccess() = runBlocking(main) {
        val paths = mutableListOf<String>()
        val vm = vm { request ->
            paths.add(request.url.encodedPath)
            when {
                request.method == "POST" -> response(request, """{"id":"run-1"}""", 201)
                request.url.encodedPath.endsWith("/events/") -> response(request,
                    event("assistant.delta", """{"delta":"Partial"}"""), sse = true)
                else -> response(request, completed)
            }
        }
        withTimeout(5_000) { vm.sendMessageAndAwait("Hello") }
        assertEquals(3, paths.size)
        assertEquals(listOf("Hello", "Complete answer"), vm.messages.map { it.content })
        assertNull(store.load())
    }

    @Test fun coldWaitingRetainsFullEphemeralContextAndBackgroundDoesNotPurge() = runBlocking(main) {
        val baseline = (0..74).map { PendingMessage("m-$it", "user", "turn-$it", "message", 1) }
        store.save(PendingRun(store.scope, "send-1", """{"idempotency_key":"send-1"}""", baseline,
            System.currentTimeMillis(), "run-1", "c"))
        val vm = vm(config.copy(ephemeral = true)) { request ->
            assertEquals("GET", request.method)
            response(request, """{"id":"run-1","status":"waiting","output":{}}""")
        }
        withTimeout(5_000) { vm.recoverPendingAndAwait() }
        assertEquals(RunState.WAITING, vm.runState.value)
        assertEquals(75, vm.messages.count { it.role == MessageRole.USER })
        assertNotNull(store.load())
        vm.onBackground()
        assertNotNull(store.load())
        vm.clearAllLocalData()
        assertNull(store.load())
    }

    @Test fun coldExpiredRunClearsPendingWithoutPost() = runBlocking(main) {
        store.save(PendingRun(store.scope, "send-1", "{}", emptyList(), System.currentTimeMillis(), "run-1"))
        val vm = vm { request ->
            assertEquals("GET", request.method)
            response(request, "{}", 410)
        }
        withTimeout(5_000) { vm.recoverPendingAndAwait() }
        assertEquals(RunState.FAILED, vm.runState.value)
        assertNull(store.load())
        assertTrue(vm.error.value!!.contains("no longer available"))
    }

    @Test fun authoritativeFinalReplacesBufferedTextImmediately() = runBlocking(main) {
        val vm = vm { error("No HTTP expected") }
        val handle = ChatViewModel::class.java.getDeclaredMethod("handleSSEEvent", SSEEvent::class.java).apply { isAccessible = true }
        handle.invoke(vm, SSEEvent("assistant.delta", """{"payload":{"delta":"partial"}}"""))
        handle.invoke(vm, SSEEvent("assistant.message", """{"payload":{"content":"Complete answer"}}"""))
        assertEquals("Complete answer", vm.messages.single().content)
        assertFalse(vm.messages.single().isStreaming)
    }

    @Test fun coldRecoveryKeepsIntegerCursorAndMergesEarlierRowsWithoutDroppingLiveRows() = runBlocking(main) {
        val baseline = listOf(PendingMessage("recent", "user", "Hello", "message", 1, seq = 51))
        store.save(PendingRun(store.scope, "key", "{}", baseline, System.currentTimeMillis(), "run-1", "c",
            messagesOffset = 50, nextBeforeSeq = 51, hasMoreMessages = true))
        val vm = vm { request ->
            if (request.url.encodedPath.contains("conversations")) {
                assertEquals("50", request.url.queryParameter("limit"))
                assertEquals("51", request.url.queryParameter("before_seq"))
                assertNull(request.url.queryParameter("offset"))
                response(request, """{"id":"c","messages":[{"id":"older","seq":50,"role":"user","content":"Earlier"},{"id":"recent","seq":51,"role":"user","content":"Hello"}],"has_more":false}""")
            } else response(request, completed)
        }
        vm.recoverPendingAndAwait()
        val liveId = vm.messages.last().id
        vm.loadMoreMessages()
        withTimeout(5_000) { while (vm.loadingMoreMessages.value) delay(10) }
        assertEquals(listOf("older", "recent", liveId), vm.messages.map { it.id })
        assertFalse(vm.hasMoreMessages.value)
    }

    @Test fun detail404NeverRepostsAnAcknowledgedRunAndKeepsTheUserMessageVisible() = runBlocking(main) {
        val baseline = listOf(PendingMessage("user", "user", "Hello", "message", 1))
        store.save(PendingRun(store.scope, "key", "{}", baseline, System.currentTimeMillis(), "run-1"))
        var gets = 0
        val vm = vm { request ->
            assertEquals("GET", request.method)
            gets++
            response(request, "{}", 404)
        }
        vm.recoverPendingAndAwait()
        assertEquals(1, gets)
        assertEquals("Hello", vm.messages.single().content)
        assertTrue(vm.error.value!!.contains("unknown"))
        assertNull(store.load())
    }

    @Test fun retryBudgetAndRetentionSurviveColdLaunchWithoutAnotherPost() = runBlocking(main) {
        for ((age, attempts) in listOf(0L to 4, 86_400_001L to 1)) {
            store.save(PendingRun(store.scope, "key", "{}", emptyList(), System.currentTimeMillis() - age,
                creationAttempts = attempts))
            val vm = vm { request ->
                assertEquals("GET", request.method)
                response(request, "{}", 404)
            }
            vm.recoverPendingAndAwait()
            assertEquals(RunState.FAILED, vm.runState.value)
            assertNull(store.load())
        }
    }

    @Test fun repeatedWaitingWithoutActionIdDoesNotDuplicateCards() = runBlocking(main) {
        store.save(PendingRun(store.scope, "key", "{}", emptyList(), System.currentTimeMillis(), "run-1"))
        val vm = vm { request -> response(request, """{"id":"run-1","status":"suspended","output":{}}""") }
        repeat(2) { vm.recoverPendingAndAwait() }
        assertEquals(RunState.WAITING, vm.runState.value)
        assertEquals(1, vm.messages.count { it.type == MessageType.REQUIRED_ACTION })
        assertNull(vm.error.value)
        assertNotNull(store.load())
    }

    @Test fun duplicateEventSequenceDoesNotRepeatExternalActionsOrText() = runBlocking(main) {
        var actions = 0
        val vm = vm(config.copy(onEvent = { _, _ -> actions++ })) { error("No HTTP expected") }
        val handle = ChatViewModel::class.java.getDeclaredMethod("handleSSEEvent", SSEEvent::class.java).apply { isAccessible = true }
        repeat(2) { handle.invoke(vm, SSEEvent("assistant.message", """{"seq":1,"payload":{"content":"Answer"}}""")) }
        assertEquals(1, actions)
        assertEquals("Answer", vm.messages.single().content)
    }

    @Test fun disposedOrLoggedOutVmCannotSendAndOldVmCannotClearReplacementCredentials() = runBlocking(main) {
        val vm = vm { error("No HTTP expected") }
        vm.apiClient.setAuthToken("replacement-fixture")
        vm.clearAllLocalData()
        assertEquals("replacement-fixture", vm.apiClient.getOrCreateSession())
        vm.onForeground()
        vm.sendMessageAndAwait("Must not send")
        assertTrue(vm.messages.isEmpty())
        val disposed = vm { error("No HTTP expected") }
        disposed.dispose()
        disposed.onForeground()
        disposed.sendMessageAndAwait("Must not send")
        assertTrue(disposed.messages.isEmpty())
    }

    @Test fun pagedEditAndRetryDoNotSubmitAWindowRelativeSupersedeIndex() = runBlocking(main) {
        val vm = vm { error("No HTTP expected") }
        vm.messages.add(Message(id = "user", role = MessageRole.USER, content = "Hello", seq = 51))
        vm.hasMoreMessages.value = true
        vm.editMessage(0, "Edited")
        vm.retryMessage(0)
        assertEquals("Hello", vm.messages.single().content)
        assertTrue(vm.error.value!!.contains("Load earlier"))
    }

    @Test fun cancellingTheAwaiterDetachesSseButKeepsTheSendRecoverable() = runBlocking(main) {
        val connected = CompletableDeferred<Unit>()
        lateinit var stream: SSEClient
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), """{"id":"run-1"}""", 201)
        }.build().also { httpClients.add(it) }
        val vm = ChatViewModel(config, APIClient(config, storage, http), storage, sseFactory = {
            SSEClient(http, main).also { stream = it; connected.complete(Unit) }
        }).also { models.add(it) }
        val sending = launch { vm.sendMessageAndAwait("Hello") }
        withTimeout(5_000) { connected.await() }
        sending.cancelAndJoin()
        // Even a late transport callback cannot resurrect the cancelled observer.
        stream.onEvent?.invoke(SSEEvent("assistant.message", """{"payload":{"content":"stale"}}"""))
        assertFalse(vm.isLoading.value)
        assertEquals(listOf("Hello"), vm.messages.map { it.content })
        assertEquals("run-1", store.load()?.runId)
    }

    @Test fun cancelledAndPurgedPendingSendsCannotBeResurrectedByOldCallbacks() = runBlocking(main) {
        val vm = vm { request -> response(request, "{}", 404) }
        store.save(PendingRun(store.scope, "key", "{}", emptyList(), System.currentTimeMillis()))
        vm.cancelRun()
        assertNull(store.load())
        assertEquals(RunState.CANCELLED, vm.runState.value)
        vm.messages.add(Message(role = MessageRole.USER, content = "private"))
        store.save(PendingRun(store.scope, "another", "{}", emptyList(), System.currentTimeMillis()))
        vm.purgeConversationData()
        assertNull(store.load())
        assertTrue(vm.messages.isEmpty())
        assertEquals(RunState.IDLE, vm.runState.value)
    }

    @Test fun legacyCumulativeFinalDoesNotDuplicateHistoryOutsideTheRecentPage() = runBlocking(main) {
        val baseline = listOf(PendingMessage("recent", "user", "Latest question", "message", 1))
        val body = """{"idempotency_key":"key","messages":[{"role":"user","content":"Latest question"}]}"""
        store.save(PendingRun(store.scope, "key", body, baseline, System.currentTimeMillis(), "run-1"))
        val vm = vm { request -> response(request,
            """{"id":"run-1","status":"succeeded","output":{"final_messages":[{"role":"user","content":"Older question"},{"role":"assistant","content":"Same answer"},{"role":"user","content":"Latest question"},{"role":"assistant","content":"Same answer"}]}}""") }
        vm.recoverPendingAndAwait()
        assertEquals(listOf("Latest question", "Same answer"), vm.messages.map { it.content })
    }

    @Test fun strongerPrivacyPolicyNeverRetriesTheOldNonPrivateBody() = runBlocking(main) {
        store.save(PendingRun(store.scope, "key", """{"idempotency_key":"key"}""", emptyList(), System.currentTimeMillis()))
        val vm = vm(config.copy(privateOnly = true, ephemeral = true)) { request ->
            assertEquals("GET", request.method)
            response(request, "{}", 404)
        }
        vm.recoverPendingAndAwait()
        assertTrue(vm.error.value!!.contains("privacy setting"))
        assertNotNull(store.load())
        assertEquals(1, store.load()?.creationAttempts)
    }

    @Test fun offsetFallbackPreservesDistinctIdenticalTextAndDeduplicatesStableIds() = runBlocking(main) {
        store.save(PendingRun(store.scope, "key", "{}",
            listOf(PendingMessage("recent", "user", "Same", "message", 1)),
            System.currentTimeMillis(), "run-1", "c", messagesOffset = 50, hasMoreMessages = true))
        val vm = vm { request ->
            if (request.url.encodedPath.contains("conversations")) {
                assertEquals("50", request.url.queryParameter("offset"))
                assertNull(request.url.queryParameter("before_seq"))
                response(request, """{"id":"c","has_more":false,"messages":[{"id":"older","role":"user","content":"Same"},{"id":"recent","role":"user","content":"Same"}]}""")
            } else response(request, completed)
        }
        vm.recoverPendingAndAwait()
        vm.loadMoreMessages()
        withTimeout(5_000) { while (vm.loadingMoreMessages.value) delay(10) }
        assertEquals(listOf("older", "recent", "final-1"), vm.messages.map { it.id })
        assertEquals(2, vm.messages.count { it.content == "Same" })
    }

    @Test fun activeRecoveryReplaysFromZeroWithoutRepeatingActions() = runBlocking(main) {
        var streams = 0
        var actions = 0
        val vm = vm(config.copy(onEvent = { _, _ -> actions++ })) { request ->
            when {
                request.method == "POST" -> response(request, """{"id":"run-1"}""", 201)
                request.url.encodedPath.endsWith("/events/") -> {
                    streams++
                    val delta = "event: assistant.delta\ndata: {\"seq\":0,\"payload\":{\"delta\":\"Partial\"}}\n\n"
                    response(request, if (streams == 1) delta else delta +
                        "event: assistant.message\ndata: {\"seq\":1,\"payload\":{\"content\":\"Complete answer\"}}\n\n" +
                        "event: run.succeeded\ndata: {\"seq\":2,\"payload\":{}}\n\n", sse = true)
                }
                else -> response(request, """{"id":"run-1","status":"running"}""")
            }
        }
        withTimeout(5_000) { vm.sendMessageAndAwait("Hello") }
        assertEquals(2, streams)
        assertEquals(1, actions)
        assertEquals(listOf("Hello", "Complete answer"), vm.messages.map { it.content })
    }

    @Test fun failedPendingCleanupDoesNotOrphanTheAwaiterOrLoseTheRecoveryKey() = runBlocking(main) {
        val failingCleanup = object : StorageService by storage {
            override fun setDurably(key: String, value: String?) {
                if (value == null && key.startsWith("pending_run_")) error("Synthetic disk failure")
                storage.setDurably(key, value)
            }
        }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.method == "POST") response(request, """{"id":"run-1"}""", 201)
            else response(request, event("assistant.message", """{"content":"Complete answer"}""") +
                event("run.succeeded", "{}"), sse = true)
        }.build().also { httpClients.add(it) }
        val vm = ChatViewModel(config, APIClient(config, failingCleanup, http), failingCleanup,
            sseFactory = { SSEClient(http, main) }).also { models.add(it) }
        withTimeout(5_000) { vm.sendMessageAndAwait("Hello") }
        assertEquals("Complete answer", vm.messages.last().content)
        assertFalse(vm.isLoading.value)
        assertNotNull(store.load())
        assertTrue(vm.error.value!!.contains("local saving failed"))
    }

    @Test fun earlierAuthoritativeMessageCannotCertifyALaterPartialAnswer() = runBlocking(main) {
        var lookups = 0
        val vm = vm { request ->
            when {
                request.method == "POST" -> response(request, """{"id":"run-1"}""", 201)
                request.url.encodedPath.endsWith("/events/") -> response(request,
                    event("assistant.message", """{"content":"I will check"}""") +
                    event("tool.call", """{"name":"lookup"}""") +
                    event("assistant.delta", """{"delta":"Partial"}""") +
                    event("run.succeeded", "{}"), sse = true)
                else -> { lookups++; response(request, completed) }
            }
        }
        withTimeout(5_000) { vm.sendMessageAndAwait("Hello") }
        assertEquals(1, lookups)
        assertEquals(listOf("Hello", "Complete answer"), vm.messages.map { it.content })
        assertNull(store.load())
    }
}