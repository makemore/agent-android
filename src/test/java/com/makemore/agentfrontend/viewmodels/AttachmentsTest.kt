package com.makemore.agentfrontend.viewmodels

import com.makemore.agentfrontend.configuration.APIPaths
import com.makemore.agentfrontend.configuration.AuthStrategy
import com.makemore.agentfrontend.configuration.ChatWidgetConfig
import com.makemore.agentfrontend.models.*
import com.makemore.agentfrontend.networking.*
import com.makemore.agentfrontend.services.InMemoryStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
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
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentsTest {
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val storage = InMemoryStorage()
    private val httpClients = mutableListOf<OkHttpClient>()
    private val models = mutableListOf<ChatViewModel>()
    private val withPath = ChatWidgetConfig(
        backendUrl = "https://fixture.invalid", agentKey = "agent", authStrategy = AuthStrategy.NONE,
        recoveryAccountId = "account-a",
        apiPaths = APIPaths(attachments = "/api/threads/t1/attachments/"),
    )
    private val withoutPath = withPath.copy(apiPaths = APIPaths())

    @Before fun setup() { Dispatchers.setMain(main) }
    @After fun cleanup() {
        runBlocking(main) { models.forEach { it.dispose() } }
        httpClients.forEach { it.dispatcher.executorService.shutdown(); it.connectionPool.evictAll() }
        Dispatchers.resetMain()
        main.close()
    }

    private fun http(respond: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { respond(it.request()) }.build().also { httpClients.add(it) }

    private fun vm(config: ChatWidgetConfig, respond: (Request) -> Response): ChatViewModel {
        val http = http(respond)
        return ChatViewModel(config, APIClient(config, storage, http), storage,
            sseFactory = { SSEClient(http, Dispatchers.Main.immediate) }).also { models.add(it) }
    }

    private fun json(request: Request, body: String, status: Int = 200) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
            .body(body.toResponseBody("application/json".toMediaType())).build()

    private fun sse(request: Request, body: String) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(body.toResponseBody("text/event-stream".toMediaType())).build()

    private fun bodyOf(request: Request) = Buffer().also { request.body!!.writeTo(it) }.readUtf8()

    private val listShape = """{"attachments":[{"attachment_id":"abc","name":"notes.txt","size":5,"content_type":"text/plain","created_at":"2026-10-30T10:00:00Z"}]}"""
    private val uploaded = """{"id":"11111111-1111-1111-1111-111111111111","name":"notes.txt","content_type":"text/plain","size":5}"""

    // -- upload request shape --

    @Test fun uploadPostsMultipartFileFieldToTheThreadPathAndParsesTheReply() = runBlocking(main) {
        lateinit var seen: Request
        var body = ""
        val api = APIClient(withPath, storage, http { r -> seen = r; body = bodyOf(r); json(r, uploaded, 201) })
        val progress = mutableListOf<Double>()
        val result = api.uploadAttachment("hello".toByteArray(), "notes.txt", "text/plain") { progress.add(it) }
        assertEquals("POST", seen.method)
        assertEquals("/api/threads/t1/attachments/", seen.url.encodedPath)
        assertEquals("camel", seen.header("X-Api-Format"))
        assertTrue(seen.body!!.contentType().toString().startsWith("multipart/form-data"))
        assertTrue(body.contains("""name="file"; filename="notes.txt""""))
        assertTrue(body.contains("Content-Type: text/plain"))
        assertTrue(body.contains("hello"))
        assertEquals("11111111-1111-1111-1111-111111111111", result.id)
        assertEquals("notes.txt", result.name)
        assertEquals("text/plain", result.contentType)
        assertEquals(5L, result.size)
        assertEquals(1.0, progress.last(), 0.0)
    }

    @Test fun uploadErrorsAreMappedToTheirReasons() = runBlocking(main) {
        suspend fun fail(status: Int, reply: String): AttachmentUploadError {
            val api = APIClient(withPath, storage, http { r -> json(r, reply, status) })
            try { api.uploadAttachment(ByteArray(1), "a.bin", "application/octet-stream"); fail("expected failure") }
            catch (e: AttachmentUploadError) { return e }
            throw AssertionError()
        }
        assertTrue(fail(413, """{"code":"too_large"}""") is AttachmentUploadError.TooLarge)
        assertTrue(fail(503, """{"code":"attachments_unavailable"}""") is AttachmentUploadError.Unavailable)
        assertEquals("Programs can't be attached.", fail(400, """{"file":"Programs can't be attached."}""").message)
        assertEquals("Bad.", fail(400, """{"file":["Bad."]}""").message)
        assertTrue(fail(403, "{}") is AttachmentUploadError.NotAllowed)
    }

    @Test fun uploadWithoutAnAttachmentsPathNeverGoesOut() = runBlocking(main) {
        val api = APIClient(withoutPath, storage, http { error("must not call") })
        assertFalse(api.attachmentsEnabled)
        try { api.uploadAttachment(ByteArray(1), "a", "text/plain"); fail() } catch (e: AttachmentUploadError.NotConfigured) {}
        Unit
    }

    @Test fun listAndDownloadUseTheSamePath() = runBlocking(main) {
        val paths = mutableListOf<String>()
        val api = APIClient(withPath, storage, http { r ->
            paths.add(r.url.encodedPath)
            if (r.url.encodedPath.endsWith("/attachments/")) json(r, listShape) else json(r, "bytes")
        })
        val listed = api.listAttachments().single()
        assertEquals("abc", listed.id)
        assertEquals("notes.txt", listed.name)
        assertEquals("text/plain", listed.contentType)
        assertEquals("bytes", String(api.fetchAttachment("abc")))
        assertEquals(listOf("/api/threads/t1/attachments/", "/api/threads/t1/attachments/abc/"), paths)
    }

    // -- limits and concurrency --

    private val temp = java.nio.file.Files.createTempDirectory("att").toFile()
    private fun stager(upload: suspend (java.io.File, String, String, (Double) -> Unit) -> UploadedAttachment) =
        AttachmentStager(CoroutineScope(main + SupervisorJob()), { true }, upload)

    private fun input(i: Int, size: Int = 3) =
        AttachmentInput("f$i.txt", "text/plain", java.io.File(temp, "f$i-${System.nanoTime()}").apply { writeBytes(ByteArray(size)) })
    private fun ok(name: String) = UploadedAttachment("id-$name", name, "text/plain", 3)

    @Test fun limitOfTwentyWithAClearMessage() = runBlocking(main) {
        val s = stager { _, name, _, _ -> ok(name) }
        val message = s.add((1..22).map { input(it) })
        assertEquals(20, s.items.size)
        assertEquals("You can attach up to 20 files to one message.", message)
        s.awaitIdle()
        assertEquals(20, s.uploadedIds().size)
    }

    @Test fun overLimitFileIsRefusedBeforeStaging() = runBlocking(main) {
        val s = stager { _, name, _, _ -> ok(name) }
        val big = java.io.File(temp, "photo.jpg")
        java.io.RandomAccessFile(big, "rw").use { it.setLength(20L * 1024 * 1024 + 1) }
        val message = s.add(listOf(AttachmentInput("photo.jpg", "image/jpeg", big)))
        assertTrue(message!!.contains("too large"))
        assertTrue(s.items.isEmpty())
        assertFalse(big.exists())
    }

    @Test fun atMostThreeUploadsAtOnceAndEachFailsOnItsOwn() = runBlocking(main) {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val s = stager { _, name, _, _ ->
            peak.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
            gate.await()
            running.decrementAndGet()
            if (name == "f2.txt") throw AttachmentUploadError.Rejected("Programs can't be attached.")
            ok(name)
        }
        s.add((1..6).map { input(it) })
        delay(200)
        assertEquals(3, peak.get())
        assertTrue(s.isUploading)
        gate.complete(Unit)
        s.awaitIdle()
        assertEquals(3, peak.get())
        assertFalse(s.isUploading)
        assertTrue(s.hasFailed)
        assertEquals(5, s.uploadedIds().size)
        val failed = s.items.single { it.status is StagedAttachment.Status.Failed }
        assertEquals("Programs can't be attached.", (failed.status as StagedAttachment.Status.Failed).message)
        s.remove(failed.localId)
        assertFalse(s.hasFailed)
    }

    @Test fun unavailableFromTheServerTurnsAttachingOff() = runBlocking(main) {
        val s = stager { _, _, _, _ -> throw AttachmentUploadError.Unavailable }
        s.add(listOf(input(1)))
        s.awaitIdle()
        assertFalse(s.available)
    }

    // -- sending --

    @Test fun messageCarriesAttachmentIdsAndAFilesOnlySendGoes() = runBlocking(main) {
        val bodies = mutableListOf<String>()
        val vm = vm(withPath) { r ->
            when {
                r.method == "POST" -> { bodies.add(bodyOf(r)); json(r, """{"id":"run-1"}""", 201) }
                else -> sse(r, "event: run.succeeded\ndata: {\"payload\":{}}\n\n")
            }
        }
        val file = FileAttachment(name = "notes.txt", size = 5, type = "text/plain", attachmentId = "att-1")
        val second = FileAttachment(name = "b.pdf", size = 5, type = "application/pdf", attachmentId = "att-2")
        withTimeout(5_000) { vm.sendMessageAndAwait("", listOf(file, second)) }
        assertEquals(1, bodies.size)
        val message = JSONObject(bodies[0]).getJSONArray("messages").getJSONObject(0)
        assertEquals("user", message.getString("role"))
        assertEquals("", message.getString("content"))
        assertEquals(listOf("att-1", "att-2"), (0 until 2).map { message.getJSONArray("attachment_ids").getString(it) })
        assertEquals(2, vm.messages.first().files?.size)
    }

    @Test fun textOnlyMessageHasNoAttachmentIds() = runBlocking(main) {
        val bodies = mutableListOf<String>()
        val vm = vm(withPath) { r ->
            if (r.method == "POST") { bodies.add(bodyOf(r)); json(r, """{"id":"run-1"}""", 201) }
            else sse(r, "event: run.succeeded\ndata: {\"payload\":{}}\n\n")
        }
        withTimeout(5_000) { vm.sendMessageAndAwait("Hello") }
        assertFalse(JSONObject(bodies[0]).getJSONArray("messages").getJSONObject(0).has("attachment_ids"))
    }

    @Test fun emptyMessageWithNoFilesIsStillNotSent() = runBlocking(main) {
        val vm = vm(withPath) { error("must not call") }
        vm.sendMessageAndAwait("   ")
        assertTrue(vm.messages.isEmpty())
    }

    @Test fun filesWithoutAHostPathAreNeverSilentlyDropped() = runBlocking(main) {
        val vm = vm(withoutPath) { error("must not call") }
        vm.sendMessageAndAwait("see attached", listOf(FileAttachment(name = "a.txt", size = 1, type = "text/plain", data = ByteArray(1))))
        assertEquals("Files can't be added here.", vm.error.value)
        assertTrue(vm.messages.isEmpty())
        assertFalse(vm.isLoading.value)
    }

    @Test fun unstagedFilesWithDataUploadFirstThenTravelAsIds() = runBlocking(main) {
        val bodies = mutableListOf<String>()
        val vm = vm(withPath) { r ->
            when {
                r.method == "POST" && r.url.encodedPath.endsWith("/attachments/") -> json(r, uploaded, 201)
                r.method == "POST" -> { bodies.add(bodyOf(r)); json(r, """{"id":"run-1"}""", 201) }
                else -> sse(r, "event: run.succeeded\ndata: {\"payload\":{}}\n\n")
            }
        }
        withTimeout(5_000) {
            vm.sendMessageAndAwait("", listOf(FileAttachment(name = "notes.txt", size = 5, type = "text/plain", data = "hello".toByteArray())))
        }
        val ids = JSONObject(bodies.single()).getJSONArray("messages").getJSONObject(0).getJSONArray("attachment_ids")
        assertEquals("11111111-1111-1111-1111-111111111111", ids.getString(0))
    }

    @Test fun historyMetadataDecodesAttachmentIdsInBothCases() {
        val j = Json { ignoreUnknownKeys = true }
        val snake = j.decodeFromString<APIMessage>("""{"role":"user","content":"x","metadata":{"attachment_ids":["a","b"]}}""")
        val camel = j.decodeFromString<APIMessage>("""{"role":"user","content":"x","metadata":{"attachmentIds":["a"]}}""")
        assertEquals(listOf("a", "b"), snake.metadata?.attachmentIds)
        assertEquals(listOf("a"), camel.metadata?.attachmentIds)
    }

    @Test fun photoRulesFollowTheWebClient() {
        assertFalse(AttachmentLimits.photoNeedsReencode("image/jpeg", 1_000_000, 4096, 3000))
        assertTrue(AttachmentLimits.photoNeedsReencode("image/jpeg", 1_000_000, 4097, 100))
        assertTrue(AttachmentLimits.photoNeedsReencode("image/heic", 1_000, 100, 100))
        assertTrue(AttachmentLimits.photoNeedsReencode("image/png", 21L * 1024 * 1024, 100, 100))
        assertEquals(4096 to 2048, AttachmentLimits.scaledPhotoSize(8192, 4096))
    }

    @Test fun fileUploadStreamsFromDiskWithTheFileField() = runBlocking(main) {
        var body = ""
        val api = APIClient(withPath, storage, http { r -> body = bodyOf(r); json(r, uploaded, 201) })
        val f = java.io.File(temp, "doc.txt").apply { writeText("from disk") }
        assertEquals("11111111-1111-1111-1111-111111111111", api.uploadAttachment(f, "doc.txt", "text/plain").id)
        assertTrue(body.contains("""name="file"; filename="doc.txt""""))
        assertTrue(body.contains("from disk"))
    }

    @Test fun removedQueuedFileNeverUploadsAndItsCopyIsDeleted() = runBlocking(main) {
        val gate = CompletableDeferred<Unit>()
        val names = java.util.Collections.synchronizedList(mutableListOf<String>())
        val s = stager { _, name, _, _ -> names.add(name); gate.await(); ok(name) }
        val inputs = (1..5).map { input(it) }
        s.add(inputs)
        delay(100)
        val queued = s.items[3]
        s.remove(queued.localId)
        gate.complete(Unit)
        s.awaitIdle()
        assertFalse(names.contains("f4.txt"))
        assertEquals(4, names.size)
        assertFalse(inputs[3].file.exists())
        assertFalse("uploaded copies are deleted", inputs[0].file.exists())
    }

    private fun sendingVm(bodies: MutableList<String>) = vm(withPath) { r ->
        if (r.method == "POST" && r.url.encodedPath.endsWith("/attachments/")) {
            val n = Regex("filename=\"([^\"]+)\"").find(bodyOf(r))!!.groupValues[1]
            json(r, """{"id":"id-$n","name":"$n","content_type":"text/plain","size":3}""", 201)
        } else if (r.method == "POST") { bodies.add(bodyOf(r)); json(r, """{"id":"run-${bodies.size}"}""", 201) }
        else sse(r, "event: assistant.message\ndata: {\"payload\":{\"content\":\"ok\"}}\n\nevent: run.succeeded\ndata: {\"payload\":{}}\n\n")
    }

    private suspend fun waitForPosts(bodies: List<String>, n: Int) = withTimeout(5_000) { while (bodies.size < n) delay(20) }

    @Test fun editAndRetryKeepTheMessagesFiles() = runBlocking(main) {
        val bodies = java.util.Collections.synchronizedList(mutableListOf<String>())
        val vm = sendingVm(bodies)
        val file = FileAttachment(name = "a.txt", size = 1, type = "text/plain", attachmentId = "att-9")
        withTimeout(5_000) { vm.sendMessageAndAwait("", listOf(file)) }
        vm.editMessage(0, "")
        waitForPosts(bodies, 2)
        fun ids(i: Int) = JSONObject(bodies[i]).getJSONArray("messages").getJSONObject(0).getJSONArray("attachment_ids").getString(0)
        assertEquals("att-9", ids(1))
        assertEquals("", JSONObject(bodies[1]).getJSONArray("messages").getJSONObject(0).getString("content"))
        withTimeout(5_000) { while (vm.isLoading.value) delay(20) }
        vm.retryMessage(0)
        waitForPosts(bodies, 3)
        assertEquals("att-9", ids(2))
        assertEquals(1, vm.messages.count { it.role == MessageRole.USER })
    }

    @Test fun acceptedSendClearsOnlyTheFilesSentAndRefusedSendClearsNothing() = runBlocking(main) {
        val bodies = java.util.Collections.synchronizedList(mutableListOf<String>())
        val vm = sendingVm(bodies)
        val one = FileAttachment(name = "a.txt", size = 1, type = "text/plain", attachmentId = "att-1")
        assertFalse("empty", vm.canAcceptSend("  ", emptyList()))
        assertTrue(vm.canAcceptSend("", listOf(one)))
        assertFalse("no path, unsent file", vmNoPath().canAcceptSend("x", listOf(FileAttachment(name = "b", size = 1, type = "t", data = ByteArray(1)))))
        vm.isLoading.value = true
        assertFalse("reply in flight", vm.canAcceptSend("hello", listOf(one)))
        assertTrue(bodies.isEmpty())
        vm.isLoading.value = false

        vm.attachments.add(listOf(input(1), input(2)))
        vm.attachments.awaitIdle()
        val staged = vm.attachments.uploadedFiles()
        assertEquals(2, staged.size)
        // Only the first file goes with this message: the second stays staged.
        withTimeout(5_000) { vm.sendMessageAndAwait("", staged.take(1)) }
        assertEquals(1, bodies.size)
        assertEquals(1, vm.attachments.items.size)
    }

    private fun vmNoPath() = vm(withoutPath) { error("must not call") }

    @Test fun listResponseWithoutAttachmentsKeyIsAnError() = runBlocking(main) {
        val api = APIClient(withPath, storage, http { r -> json(r, """{"results":[]}""") })
        try { api.listAttachments(); fail() } catch (e: APIError) {}
        Unit
    }
}
