package com.makemore.agentfrontend.voice.kokoro

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Where the on-device Kokoro model is in its download/install lifecycle. */
sealed interface KokoroModelState {
    /** Not on the device. [KokoroModelManager.download] (or first use) fetches it. */
    data object NotDownloaded : KokoroModelState

    /** Archive download in progress. */
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long?) : KokoroModelState {
        /** 0.0–1.0, or `null` when the server sent no length. */
        val fraction: Float?
            get() = totalBytes?.takeIf { it > 0 }?.let { (bytesDownloaded.toFloat() / it).coerceIn(0f, 1f) }
    }

    /** Download finished; verifying and unpacking. */
    data object Installing : KokoroModelState

    /** Installed and usable. */
    data object Ready : KokoroModelState

    /**
     * Last attempt failed. [reason] is a value-free code: `network`,
     * `http_<status>`, `insecure_url`, `too_many_redirects`,
     * `checksum_mismatch`, `insufficient_storage`, `invalid_archive`, `io`.
     */
    data class Failed(val reason: String) : KokoroModelState
}

/**
 * Downloads the Kokoro model once, caches it on the device and reports
 * progress through [state]. Thread-safe; concurrent [download] calls share
 * one transfer. Obtain the shared instance with [KokoroTTS.modelManager].
 *
 * Layout under [directory]: `kokoro-v1.0/` (installed model + marker),
 * plus transient `download.part` / `staging/` while installing.
 */
class KokoroModelManager(
    /** Root directory for the cached model (default: `noBackupFilesDir/agent-kokoro`). */
    val directory: File,
    /** Archive URL (HTTPS). Defaults to sherpa-onnx's upstream release asset. */
    val modelUrl: String = KokoroModel.DEFAULT_MODEL_URL,
    /** Optional lowercase hex SHA-256 of the archive; the install fails on mismatch. */
    val expectedSha256: String? = null,
    private val fetcher: KokoroModelFetcher = HttpKokoroModelFetcher(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val lock = Any()
    private var inFlight: Deferred<KokoroModelState>? = null

    private val installDir = File(directory, KokoroModel.INSTALL_DIR)
    private val marker = File(installDir, KokoroModel.INSTALLED_MARKER)
    private val partFile = File(directory, "download.part")
    private val stagingDir = File(directory, "staging")

    private val _state = MutableStateFlow<KokoroModelState>(
        if (marker.isFile && KokoroModelFiles.from(installDir) != null) KokoroModelState.Ready
        else KokoroModelState.NotDownloaded,
    )

    /** Observe for download progress / readiness (e.g. to show "Downloading voice… 42%"). */
    val state: StateFlow<KokoroModelState> = _state.asStateFlow()

    /** Epoch millis of the last failed attempt, or 0. */
    @Volatile
    var lastFailureAtMillis: Long = 0L
        private set

    /** True when the model is installed and usable. */
    val isAvailable: Boolean get() = _state.value == KokoroModelState.Ready

    /** Installed model files, or `null` when not [isAvailable]. */
    fun modelFiles(): KokoroModelFiles? =
        if (isAvailable) KokoroModelFiles.from(installDir) else null

    /**
     * Start the download if the model is not installed and none is running.
     * Returns immediately; the transfer continues in the background even if
     * the caller goes away. Observe [state] for progress.
     */
    fun startDownload(): Deferred<KokoroModelState> = synchronized(lock) {
        if (_state.value == KokoroModelState.Ready) return CompletableDeferred(KokoroModelState.Ready)
        inFlight?.takeIf { it.isActive }?.let { return it }
        scope.async { install() }.also { inFlight = it }
    }

    /** Download and install (if needed), suspending until done. Returns the final state. */
    suspend fun download(): KokoroModelState = startDownload().await()

    /** Abort an in-flight download. The state returns to [KokoroModelState.NotDownloaded]. */
    fun cancelDownload() {
        val job = synchronized(lock) { inFlight }
        job?.cancel()
    }

    /**
     * Cancel any download and delete the cached model and temporary files.
     * Providers notice and use the system voice until it is downloaded again.
     */
    suspend fun delete() {
        val job = synchronized(lock) { inFlight.also { inFlight = null } }
        job?.cancel()
        runCatching { job?.join() }
        withContext(Dispatchers.IO) {
            installDir.deleteRecursively()
            stagingDir.deleteRecursively()
            partFile.delete()
        }
        _state.value = KokoroModelState.NotDownloaded
    }

    // -- Internals ---------------------------------------------------

    private suspend fun install(): KokoroModelState {
        return try {
            directory.mkdirs()
            val sha256 = fetchArchive()
            _state.value = KokoroModelState.Installing
            if (expectedSha256 != null && !expectedSha256.equals(sha256, ignoreCase = true)) {
                throw KokoroDownloadException("checksum_mismatch")
            }
            stagingDir.deleteRecursively()
            KokoroArchive.extractTarBz2(partFile, stagingDir)
            val root = KokoroArchive.findModelRoot(stagingDir)
                ?: throw KokoroDownloadException("invalid_archive")
            installDir.deleteRecursively()
            if (!root.renameTo(installDir)) throw KokoroDownloadException("io")
            marker.writeText("sha256=$sha256\n")
            KokoroModelState.Ready.also { _state.value = it }
        } catch (e: CancellationException) {
            _state.value = KokoroModelState.NotDownloaded
            throw e
        } catch (e: KokoroDownloadException) {
            fail(e.reason)
        } catch (e: IOException) {
            fail("network")
        } catch (e: Exception) {
            fail("io")
        } finally {
            partFile.delete()
            stagingDir.deleteRecursively()
        }
    }

    private fun fail(reason: String): KokoroModelState {
        lastFailureAtMillis = clock()
        return KokoroModelState.Failed(reason).also { _state.value = it }
    }

    /** Stream the archive to [partFile], reporting progress. Returns its SHA-256 (hex). */
    private suspend fun fetchArchive(): String {
        _state.value = KokoroModelState.Downloading(0, null)
        val digest = MessageDigest.getInstance("SHA-256")
        fetcher.open(modelUrl).use { response ->
            val total = response.contentLength
            if (total != null && directory.usableSpace in 1 until requiredSpace(total)) {
                throw KokoroDownloadException("insufficient_storage")
            }
            _state.value = KokoroModelState.Downloading(0, total)
            var received = 0L
            var lastReported = 0L
            val buffer = ByteArray(64 * 1024)
            partFile.outputStream().use { out ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = response.body.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    received += n
                    if (received - lastReported >= PROGRESS_STEP_BYTES) {
                        lastReported = received
                        _state.value = KokoroModelState.Downloading(received, total)
                    }
                }
            }
            if (total != null && received != total) throw KokoroDownloadException("network")
            _state.value = KokoroModelState.Downloading(received, total ?: received)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val PROGRESS_STEP_BYTES = 512L * 1024
        // Archive + unpacked model (~1.5x the bz2 size) + headroom.
        fun requiredSpace(archiveBytes: Long): Long = archiveBytes * 5 / 2 + 32L * 1024 * 1024
    }
}
