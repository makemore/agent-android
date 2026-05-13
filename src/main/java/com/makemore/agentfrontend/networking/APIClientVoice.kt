package com.makemore.agentfrontend.networking

import com.makemore.agentfrontend.voice.VoiceDescriptor
import com.makemore.agentfrontend.voice.VoiceToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Mint a short-lived bearer token for the TTS streaming endpoint.
 *
 * The token is bound to the current authenticated principal and may
 * embed quota/rate-limit metadata. Voice providers should call this
 * before each playback session and refresh on 401.
 *
 * Returns `null` when the backend has no voice endpoint configured
 * (i.e. `apiPaths.voiceToken` is null) or returns 404.
 */
suspend fun APIClient.voiceToken(): VoiceToken? = withContext(Dispatchers.IO) {
    val path = config.apiPaths.voiceToken ?: return@withContext null
    val token = getOrCreateSession()
    val body = "{}".toRequestBody("application/json".toMediaType())
    val request = buildRequest(path, "POST", body, token)

    val response = httpClient.newCall(request).await()
    if (response.code == 404) return@withContext null
    if (response.code !in 200..299) {
        response.close()
        throw HttpError(response.code)
    }
    val raw = response.body?.string() ?: throw InvalidResponse
    val obj = JSONObject(raw)

    val tokenStr = obj.optString("token", "").takeIf { it.isNotEmpty() }
        ?: throw InvalidResponse
    val ttsField = obj.optString("ttsUrl", "")
        .ifEmpty { obj.optString("tts_url", "") }
        .trim()
    val absoluteTts = when {
        ttsField.isEmpty() -> "${config.backendUrl}${config.apiPaths.voiceTts ?: "/api/agent-runtime/voice/tts/"}"
        ttsField.startsWith("/") -> "${config.backendUrl}$ttsField"
        else -> ttsField
    }
    val expiresField = obj.optString("expiresAt", "")
        .ifEmpty { obj.optString("expires_at", "") }
    val expiresAtMillis = parseIsoMillis(expiresField)
        ?: (System.currentTimeMillis() + 240_000L)

    VoiceToken(token = tokenStr, ttsUrl = absoluteTts, expiresAtMillis = expiresAtMillis)
}

/**
 * List voices the configured TTS provider exposes (e.g. ElevenLabs).
 * Returns `[]` when the backend has no voices endpoint configured.
 */
suspend fun APIClient.voices(): List<VoiceDescriptor> = withContext(Dispatchers.IO) {
    val path = config.apiPaths.voiceVoices ?: return@withContext emptyList()
    val token = getOrCreateSession()
    val request = buildRequest(path, "GET", token = token)

    val response = httpClient.newCall(request).await()
    if (response.code == 404) return@withContext emptyList()
    if (response.code !in 200..299) {
        response.close()
        throw HttpError(response.code)
    }
    val raw = response.body?.string() ?: return@withContext emptyList()
    val obj = JSONObject(raw)
    val arr: JSONArray = obj.optJSONArray("voices") ?: return@withContext emptyList()

    (0 until arr.length()).mapNotNull { i ->
        val v = arr.optJSONObject(i) ?: return@mapNotNull null
        val id = v.optString("id", "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val name = v.optString("name", id)
        val labels = v.optJSONObject("labels")?.let { lbl ->
            val out = mutableMapOf<String, String>()
            for (k in lbl.keys()) out[k] = lbl.optString(k, "")
            out.toMap()
        }
        VoiceDescriptor(id = id, name = name, labels = labels)
    }
}

/** Best-effort ISO 8601 parser tolerant of the few formats the Django mint returns. */
private fun parseIsoMillis(text: String): Long? {
    if (text.isEmpty()) return null
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
    )
    for (p in patterns) {
        try {
            val fmt = SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            return fmt.parse(text)?.time
        } catch (_: Exception) {
            // try next
        }
    }
    return null
}
