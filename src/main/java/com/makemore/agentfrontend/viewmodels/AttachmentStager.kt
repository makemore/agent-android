package com.makemore.agentfrontend.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.makemore.agentfrontend.models.AttachmentLimits
import com.makemore.agentfrontend.models.AttachmentUploadError
import com.makemore.agentfrontend.models.FileAttachment
import com.makemore.agentfrontend.models.UploadedAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID

/**
 * A file ready to stage: copied into the app's cache (and, for photos, prepared) off the main
 * thread. The upload streams from [file]; it is deleted after the upload and on removal.
 */
class AttachmentInput(val name: String, val contentType: String, val file: java.io.File) {
    val size: Long get() = file.length()
}

/** One staged file in the composer: uploading with progress, uploaded, or failed. */
class StagedAttachment(
    val localId: String = UUID.randomUUID().toString(),
    val name: String,
    val contentType: String,
    val size: Long,
    internal val file: java.io.File,
) {
    sealed class Status {
        data class Uploading(val progress: Double) : Status()
        data class Done(val uploaded: UploadedAttachment) : Status()
        data class Failed(val message: String) : Status()
    }

    var status: Status by mutableStateOf(Status.Uploading(0.0))
        internal set
    internal var job: Job? = null
}

/**
 * Files the person has attached to the next message. Each uploads as soon as it is added
 * (at most [AttachmentLimits.MAX_IN_FLIGHT] at once) with its own progress and failure
 * (remove or retry). Sending is blocked only while an upload is running.
 */
class AttachmentStager(
    private val scope: CoroutineScope,
    private val enabled: () -> Boolean,
    private val upload: suspend (file: java.io.File, name: String, type: String, progress: (Double) -> Unit) -> UploadedAttachment,
) {
    val items = mutableStateListOf<StagedAttachment>()

    /** False once the host has no attachments URL or the server says 503 `attachments_unavailable`. */
    var available: Boolean by mutableStateOf(enabled())
        private set

    /** True while any file is still uploading. */
    val isUploading: Boolean get() = items.any { it.status is StagedAttachment.Status.Uploading }

    /** True when any file failed to upload (it is not sent; remove or retry it). */
    val hasFailed: Boolean get() = items.any { it.status is StagedAttachment.Status.Failed }

    private val gate = Semaphore(AttachmentLimits.MAX_IN_FLIGHT)

    /**
     * Add files and start uploading them. Returns a message for the person when some were
     * refused (over the limit or too big), else null.
     */
    fun add(inputs: List<AttachmentInput>): String? {
        if (!available) return "Files can't be added here."
        var message: String? = null
        for (input in inputs) {
            if (items.size >= AttachmentLimits.MAX_PER_MESSAGE) {
                message = "You can attach up to ${AttachmentLimits.MAX_PER_MESSAGE} files to one message."
                break
            }
            val cap = if (AttachmentLimits.isImage(input.contentType)) AttachmentLimits.MAX_PHOTO_BYTES else AttachmentLimits.MAX_FILE_BYTES
            if (input.size > cap) {
                input.file.delete()
                message = "${input.name} is too large (up to ${cap / (1024 * 1024)} MB)."
                continue
            }
            val staged = StagedAttachment(
                name = input.name, contentType = input.contentType,
                size = input.size, file = input.file,
            )
            items.add(staged)
            start(staged)
        }
        return message
    }

    fun remove(localId: String) {
        items.firstOrNull { it.localId == localId }?.let {
            it.job?.cancel()
            it.file.delete()
            items.remove(it)
        }
    }

    fun retry(localId: String) {
        val staged = items.firstOrNull { it.localId == localId } ?: return
        if (staged.status !is StagedAttachment.Status.Failed || !available) return
        staged.status = StagedAttachment.Status.Uploading(0.0)
        start(staged)
    }

    private fun start(staged: StagedAttachment) {
        staged.job = scope.launch {
            try {
                val uploaded = gate.withPermit {
                    // Removed while it waited for a slot: never start.
                    if (staged !in items) throw CancellationException("removed")
                    upload(staged.file, staged.name, staged.contentType) { p ->
                        staged.status = StagedAttachment.Status.Uploading(p)
                    }
                }
                staged.file.delete()
                staged.status = StagedAttachment.Status.Done(uploaded)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is AttachmentUploadError.Unavailable) available = false
                staged.status = StagedAttachment.Status.Failed(
                    (e as? AttachmentUploadError)?.message ?: "Upload failed. Try again."
                )
            }
        }
    }

    /** Wait for uploads in progress. */
    suspend fun awaitIdle() {
        items.toList().forEach { it.job?.join() }
    }

    /** Ids of the uploaded files, in the order they were added. */
    fun uploadedIds(): List<String> =
        items.mapNotNull { (it.status as? StagedAttachment.Status.Done)?.uploaded?.id }

    /** The uploaded files as transcript entries carrying their server ids. */
    fun uploadedFiles(): List<FileAttachment> = items.mapNotNull { s ->
        (s.status as? StagedAttachment.Status.Done)?.let {
            FileAttachment(name = s.name, size = s.size.toInt(), type = s.contentType, attachmentId = it.uploaded.id)
        }
    }

    /** Forget the uploaded files with these ids (once a send is accepted); others stay. */
    fun removeUploaded(ids: Set<String>) {
        items.removeAll { (it.status as? StagedAttachment.Status.Done)?.uploaded?.id in ids }
    }

    /** Forget everything staged and delete the copies. */
    fun clear() {
        items.forEach { it.job?.cancel(); it.file.delete() }
        items.clear()
    }
}
