package com.makemore.agentfrontend.voice.kokoro

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

// ----------------------------------------------------------------- manifest

/** One file of the asset set, as listed in `manifest.json`. */
internal data class KokoroAssetFile(val path: String, val size: Long, val sha256: String)

/** `manifest.json` ("kokoro-asset-manifest/1"). */
internal class KokoroManifest(
    val files: Map<String, KokoroAssetFile>,
    val model: String,
    val vocab: String,
    val voices: String,
    /** lang -> role (gold, silver, model, vocab) -> path. */
    val g2p: Map<String, Map<String, String>>,
) {
    companion object {
        const val FORMAT = "kokoro-asset-manifest/1"

        fun parse(json: String): KokoroManifest {
            val m = KokoroJson.parseObject(json)
            if (m.string("format") != FORMAT) throw KokoroJson.ParseException("unsupported manifest format")
            val files = m.list("files").associate { raw ->
                @Suppress("UNCHECKED_CAST")
                val f = raw as Map<String, Any?>
                val path = f.string("path")
                require(isSafePath(path)) { "unsafe path in manifest" }
                val sha = f.string("sha256").lowercase()
                require(sha.length == 64 && sha.all { it in '0'..'9' || it in 'a'..'f' }) { "bad sha256 in manifest" }
                path to KokoroAssetFile(path, f.long("size"), sha)
            }
            val entry = m.obj("entry")
            val g2p = entry.obj("g2p").mapValues { (_, v) ->
                @Suppress("UNCHECKED_CAST")
                (v as Map<String, Any?>).mapValues { it.value as String }
            }
            return KokoroManifest(files, entry.string("model"), entry.string("vocab"), entry.string("voices"), g2p)
        }

        private fun isSafePath(p: String): Boolean =
            p.isNotEmpty() && !p.startsWith("/") && !p.contains("\\") && p.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    fun file(path: String): KokoroAssetFile =
        files[path] ?: throw KokoroAssetException("invalid_manifest", "manifest does not list $path")

    /** Prefer the byte-identical `.gz` copy of a dictionary when the manifest lists it. */
    private fun preferGz(path: String): KokoroAssetFile = files["$path.gz"] ?: file(path)

    /** Everything one voice needs (README "Assets"): model, vocab, voice index, voice pack, its language's G2P. */
    fun plan(voiceId: String, lang: String): KokoroAssetPlan {
        val g = g2p[lang] ?: throw KokoroAssetException("invalid_manifest", "manifest has no $lang G2P")
        return KokoroAssetPlan(
            model = file(model),
            vocab = file(vocab),
            voiceIndex = file(voices),
            voice = file("voices/$voiceId.bin"),
            lang = lang,
            gold = preferGz(g.getValue("gold")),
            silver = preferGz(g.getValue("silver")),
            g2pModel = file(g.getValue("model")),
            g2pVocab = file(g.getValue("vocab")),
        )
    }
}

/** The files to fetch for one voice. */
internal data class KokoroAssetPlan(
    val model: KokoroAssetFile,
    val vocab: KokoroAssetFile,
    val voiceIndex: KokoroAssetFile,
    val voice: KokoroAssetFile,
    val lang: String,
    val gold: KokoroAssetFile,
    val silver: KokoroAssetFile,
    val g2pModel: KokoroAssetFile,
    val g2pVocab: KokoroAssetFile,
) {
    val all: List<KokoroAssetFile> get() = listOf(vocab, voiceIndex, voice, g2pVocab, gold, silver, g2pModel, model)
    val totalBytes: Long get() = all.distinctBy { it.sha256 }.sumOf { it.size }
}

/** A failure with a value-free [reason] code (see [KokoroState.Failed]). */
class KokoroAssetException(val reason: String, message: String = reason, cause: Throwable? = null) :
    IOException(message, cause)

// ----------------------------------------------------------------- fetching

/**
 * Fetches asset files. The only network access agent-kokoro performs: plain
 * GETs of the configured base URL (no assistant text, no identifiers).
 */
fun interface KokoroFetcher {
    /**
     * Open [url] (blocking; called on an IO thread). When [offset] > 0, ask for
     * the bytes from [offset] on; servers may ignore that and send everything,
     * which [KokoroFetchResponse.offset] reports. Throws [KokoroAssetException]
     * or [IOException].
     */
    fun open(url: String, offset: Long): KokoroFetchResponse
}

class KokoroFetchResponse(
    val body: InputStream,
    /** Number of bytes [body] will deliver, if known. */
    val contentLength: Long?,
    /** File offset of [body]'s first byte: the requested offset when resumed, else 0. */
    val offset: Long = 0,
    private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() {
        runCatching { body.close() }
        onClose()
    }
}

/**
 * Default fetcher: `https://` (redirects must stay on HTTPS) and `file://`
 * (for hosts that side-load the asset folder). Resumes with HTTP `Range`.
 */
class HttpKokoroFetcher(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : KokoroFetcher {
    override fun open(url: String, offset: Long): KokoroFetchResponse {
        if (url.startsWith("file:", ignoreCase = true)) return openFile(url, offset)
        if (!url.startsWith("https://", ignoreCase = true)) throw KokoroAssetException("insecure_url")
        var current = URL(url)
        repeat(MAX_REDIRECTS) {
            val conn = current.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.requestMethod = "GET"
            // Byte-exact files: no transparent decompression.
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
            when (val code = conn.responseCode) {
                200 -> {
                    val length = conn.contentLengthLong.takeIf { it >= 0 }
                    return KokoroFetchResponse(conn.inputStream, length, 0) { conn.disconnect() }
                }
                206 -> {
                    val start = conn.getHeaderField("Content-Range")
                        ?.let { Regex("""bytes (\d+)-""").find(it)?.groupValues?.get(1)?.toLongOrNull() }
                    if (start != offset) {
                        conn.disconnect()
                        throw KokoroAssetException("network", "unexpected Content-Range")
                    }
                    val length = conn.contentLengthLong.takeIf { it >= 0 }
                    return KokoroFetchResponse(conn.inputStream, length, offset) { conn.disconnect() }
                }
                301, 302, 303, 307, 308 -> {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    val next = location?.let { URL(current, it) } ?: throw KokoroAssetException("http_$code")
                    if (!next.protocol.equals("https", ignoreCase = true)) throw KokoroAssetException("insecure_url")
                    current = next
                }
                416 -> {
                    // The partial file is not a prefix of this resource any more: start over.
                    conn.disconnect()
                    return open(current.toString(), 0)
                }
                else -> {
                    conn.disconnect()
                    throw KokoroAssetException("http_$code")
                }
            }
        }
        throw KokoroAssetException("too_many_redirects")
    }

    private fun openFile(url: String, offset: Long): KokoroFetchResponse {
        val file = File(URI(url))
        if (!file.isFile) throw KokoroAssetException("http_404")
        val input = FileInputStream(file)
        val start = offset.coerceIn(0, file.length())
        if (start > 0) input.channel.position(start)
        return KokoroFetchResponse(input, file.length() - start, start)
    }

    private companion object {
        const val MAX_REDIRECTS = 5
    }
}

// ----------------------------------------------------------------- the cache

/**
 * Downloads, verifies (size + SHA-256 against the manifest) and caches asset
 * files by their SHA-256. Interrupted downloads resume from their `.part`
 * file (HTTP Range) or restart when the server ignores the range.
 *
 * Layout under [directory]: `files/<sha256>` (verified), `partial/<sha256>.part`,
 * `manifests/<hash of base URL>.json`.
 */
internal class KokoroAssetStore(
    val directory: File,
    baseUrl: String,
    private val fetcher: KokoroFetcher = HttpKokoroFetcher(),
) {
    val baseUrl: String = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    private val filesDir = File(directory, "files")
    private val partialDir = File(directory, "partial")
    private val manifestFile = File(File(directory, "manifests"), sha256Hex(this.baseUrl.toByteArray()).take(16) + ".json")
    private val mutex = Mutex()

    @Volatile private var manifest: KokoroManifest? = null

    /** The cached manifest, without network. */
    fun cachedManifest(): KokoroManifest? {
        manifest?.let { return it }
        if (!manifestFile.isFile) return null
        return runCatching { KokoroManifest.parse(manifestFile.readText()) }.getOrNull()?.also { manifest = it }
    }

    /** The manifest: cached copy (the folder is immutable) or fetched once. */
    suspend fun manifest(): KokoroManifest {
        cachedManifest()?.let { return it }
        return mutex.withLock {
            cachedManifest() ?: withContext(Dispatchers.IO) {
                val text = fetcher.open(baseUrl + "manifest.json", 0).use { r ->
                    if ((r.contentLength ?: 0) > MAX_MANIFEST_BYTES) throw KokoroAssetException("invalid_manifest")
                    r.body.readBytes().toString(Charsets.UTF_8)
                }
                val parsed = try {
                    KokoroManifest.parse(text)
                } catch (e: Exception) {
                    throw KokoroAssetException("invalid_manifest", "manifest could not be parsed", e)
                }
                manifestFile.parentFile?.mkdirs()
                val tmp = File(manifestFile.path + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(manifestFile)) throw KokoroAssetException("io")
                parsed.also { manifest = it }
            }
        }
    }

    /** The verified local copy of [f], or null. */
    fun cached(f: KokoroAssetFile): File? = File(filesDir, f.sha256).takeIf { it.isFile && it.length() == f.size }

    fun isCached(plan: KokoroAssetPlan): Boolean = plan.all.all { cached(it) != null }

    /** Bytes of verified files plus partial downloads. */
    fun downloadedBytes(): Long =
        listOf(filesDir, partialDir).sumOf { d -> d.listFiles()?.sumOf { it.length() } ?: 0L }

    /**
     * Fetch every file of [files] that is not cached yet, in order. [onProgress]
     * gets (bytes available, bytes total) over the whole list, counting files
     * already cached and partial bytes being resumed.
     */
    suspend fun download(files: List<KokoroAssetFile>, onProgress: (Long, Long) -> Unit) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val unique = files.distinctBy { it.sha256 }
            val total = unique.sumOf { it.size }
            var done = unique.filter { cached(it) != null }.sumOf { it.size }
            onProgress(done, total)
            for (f in unique) {
                if (cached(f) != null) continue
                val base = done
                fetchOne(f) { got -> onProgress(base + got, total) }
                done += f.size
                onProgress(done, total)
            }
        }
    }

    private suspend fun fetchOne(f: KokoroAssetFile, onBytes: (Long) -> Unit) {
        filesDir.mkdirs()
        partialDir.mkdirs()
        val part = File(partialDir, "${f.sha256}.part")
        if (part.length() > f.size) part.delete()
        val usable = directory.usableSpace
        if (usable in 1 until (f.size - part.length()) + FREE_SPACE_MARGIN) throw KokoroAssetException("insufficient_storage")

        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var have = part.length()
        // Re-hash what an earlier attempt already stored.
        if (have > 0) {
            FileInputStream(part).use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
        }
        if (have < f.size) {
            fetcher.open(baseUrl + f.path, have).use { response ->
                if (response.offset != have) {
                    // Server ignored the range: start over.
                    have = 0
                    digest.reset()
                }
                if (response.contentLength != null && have + response.contentLength != f.size) {
                    throw KokoroAssetException("size_mismatch")
                }
                FileOutputStream(part, have > 0).use { out ->
                    var lastReported = have
                    onBytes(have)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = response.body.read(buffer)
                        if (n < 0) break
                        if (have + n > f.size) throw KokoroAssetException("size_mismatch")
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        have += n
                        if (have - lastReported >= PROGRESS_STEP_BYTES) {
                            lastReported = have
                            onBytes(have)
                        }
                    }
                }
            }
        }
        if (have != f.size) throw KokoroAssetException("network", "download ended early")
        val sha = digest.digest().toHex()
        if (sha != f.sha256) {
            part.delete()
            throw KokoroAssetException("checksum_mismatch")
        }
        val target = File(filesDir, f.sha256)
        if (!part.renameTo(target)) throw KokoroAssetException("io")
    }

    /** Delete every cached and partial file (and the cached manifest). */
    suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) {
            filesDir.deleteRecursively()
            partialDir.deleteRecursively()
            File(directory, "manifests").deleteRecursively()
            manifest = null
        }
    }

    companion object {
        const val PROGRESS_STEP_BYTES = 256L * 1024
        const val FREE_SPACE_MARGIN = 32L * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 4L * 1024 * 1024

        fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

        private fun ByteArray.toHex(): String {
            val hex = "0123456789abcdef"
            val sb = StringBuilder(size * 2)
            for (b in this) {
                val v = b.toInt() and 0xff
                sb.append(hex[v ushr 4]).append(hex[v and 0x0f])
            }
            return sb.toString()
        }
    }
}
