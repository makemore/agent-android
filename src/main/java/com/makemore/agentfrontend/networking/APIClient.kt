package com.makemore.agentfrontend.networking

import com.makemore.agentfrontend.configuration.AuthStrategy
import com.makemore.agentfrontend.configuration.ChatWidgetConfig
import com.makemore.agentfrontend.models.*
import com.makemore.agentfrontend.services.StorageService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * API client for chat widget backend communication.
 * Mirrors the iOS APIClient class.
 */
class APIClient(
    val config: ChatWidgetConfig,
    val storage: StorageService
) {
    private var authToken: String? = config.authToken

    internal val httpClient = OkHttpClient.Builder()
        .connectTimeout(java.time.Duration.ofSeconds(30))
        .readTimeout(java.time.Duration.ofSeconds(60))
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // -- Authentication --

    /** Get the effective auth strategy */
    val authStrategy: AuthStrategy
        get() {
            config.authStrategy?.let { return it }
            if (config.authToken != null) return AuthStrategy.TOKEN
            if (config.apiPaths.anonymousSession.isNotEmpty()) return AuthStrategy.ANONYMOUS
            return AuthStrategy.NONE
        }

    companion object {
        /** Local-dev hosts that may use cleartext HTTP even when
         * allowInsecureHTTP is off: loopback, the emulator alias, `.local`. */
        fun isDevHost(host: String?): Boolean {
            val h = host?.lowercase() ?: return false
            return h == "localhost" || h == "127.0.0.1" || h == "::1" ||
                h == "10.0.2.2" || h.endsWith(".local")
        }
    }

    /** Fail closed on cleartext transport — called before any network egress. */
    fun validateTransport() {
        val url = config.backendUrl.toHttpUrlOrNull()
            ?: throw InsecureTransport(config.backendUrl)
        if (url.scheme == "https") return
        if (config.allowInsecureHTTP) return
        if (isDevHost(url.host)) return
        throw InsecureTransport(url.host)
    }

    /** Get or create a session token */
    suspend fun getOrCreateSession(forceRefresh: Boolean = false): String? = withContext(Dispatchers.IO) {
        validateTransport()
        val strategy = authStrategy
        if (strategy != AuthStrategy.ANONYMOUS) {
            return@withContext authToken ?: config.authToken
        }

        // Check existing token
        if (!forceRefresh) {
            authToken?.let { return@withContext it }
            storage.get(config.anonymousTokenKey)?.let {
                authToken = it
                return@withContext it
            }
        }

        // Fetch new token
        val url = "${config.backendUrl}${config.apiPaths.anonymousSession}"
        val request = Request.Builder()
            .url(url)
            .header("X-Api-Format", "camel")
            .post("".toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).await()
        if (response.code != 200) throw SessionCreationFailed

        val body = response.body?.string() ?: throw SessionCreationFailed
        val tokenResponse = JSONObject(body)
        val token = tokenResponse.getString("token")
        authToken = token
        storage.set(config.anonymousTokenKey, token)
        token
    }

    /** Clear the stored session */
    fun clearSession() {
        authToken = null
        storage.set(config.anonymousTokenKey, null)
    }

    /** Update auth token */
    fun setAuthToken(token: String?) {
        authToken = token
    }

    // -- Request Building --

    /** Build auth headers for a request */
    fun authHeaders(token: String? = null): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        val strategy = authStrategy
        val effectiveToken = token ?: authToken ?: config.authToken

        when (strategy) {
            AuthStrategy.TOKEN, AuthStrategy.JWT -> {
                effectiveToken?.let {
                    val header = config.authHeader ?: strategy.defaultHeader
                    val prefix = config.authTokenPrefix ?: strategy.defaultPrefix
                    headers[header] = if (prefix.isEmpty()) it else "$prefix $it"
                }
            }
            AuthStrategy.ANONYMOUS -> {
                effectiveToken?.let {
                    val header = config.authHeader ?: config.anonymousTokenHeader
                    headers[header] = it
                }
            }
            AuthStrategy.SESSION, AuthStrategy.NONE -> { /* no headers */ }
        }

        return headers
    }

    /** Build a Request with auth headers */
    fun buildRequest(
        path: String,
        method: String = "GET",
        body: RequestBody? = null,
        token: String? = null
    ): Request {
        val url = "${config.backendUrl}$path"
        val builder = Request.Builder().url(url)

        authHeaders(token).forEach { (key, value) ->
            builder.header(key, value)
        }
        // Opt in to the camelCase JSON wire format on backends that
        // support per-request format negotiation. Backends that do
        // not recognise the header simply ignore it.
        builder.header("X-Api-Format", "camel")

        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post(body ?: "".toRequestBody(null))
            "PUT" -> builder.put(body ?: "".toRequestBody(null))
            "DELETE" -> builder.delete(body)
            "PATCH" -> builder.patch(body ?: "".toRequestBody(null))
        }

        return builder.build()
    }
}

