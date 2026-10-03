package com.makemore.agentfrontend.voice.kokoro

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream

/** Extracts the sherpa-onnx `.tar.bz2` model archive safely. */
internal object KokoroArchive {
    /**
     * Unpack [archive] into [destination] (which must be empty or absent).
     * Rejects entries that would escape [destination]; skips links and
     * special files. Throws [KokoroDownloadException] (`invalid_archive`).
     */
    suspend fun extractTarBz2(archive: File, destination: File) {
        destination.mkdirs()
        val root = destination.canonicalFile
        val buffer = ByteArray(64 * 1024)
        try {
            TarArchiveInputStream(
                BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive), 64 * 1024)),
            ).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    currentCoroutineContext().ensureActive()
                    val target = File(root, entry.name).canonicalFile
                    if (target != root && !target.path.startsWith(root.path + File.separator)) {
                        throw KokoroDownloadException("invalid_archive")
                    }
                    when {
                        entry.isDirectory -> target.mkdirs()
                        entry.isFile -> {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { out ->
                                while (true) {
                                    val n = tar.read(buffer)
                                    if (n < 0) break
                                    out.write(buffer, 0, n)
                                }
                            }
                        }
                        // Symlinks, hard links, devices: never needed by the model.
                        else -> Unit
                    }
                }
            }
        } catch (e: KokoroDownloadException) {
            throw e
        } catch (e: java.io.IOException) {
            throw KokoroDownloadException("invalid_archive", e)
        } catch (e: IllegalArgumentException) {
            throw KokoroDownloadException("invalid_archive", e)
        }
    }

    /** The directory inside [extracted] that holds the model files (archives nest one level). */
    fun findModelRoot(extracted: File): File? {
        if (KokoroModelFiles.from(extracted) != null) return extracted
        return extracted.listFiles()
            ?.filter { it.isDirectory }
            ?.firstOrNull { KokoroModelFiles.from(it) != null }
    }
}
