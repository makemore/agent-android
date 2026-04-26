package com.makemore.agentfrontend.example.persistence

import android.content.Context

/**
 * Tiny SharedPreferences-backed settings store for the launcher's endpoint
 * fields. Mirrors the iOS launcher's `@AppStorage` properties so the DRF
 * token, backend URLs and agent key survive across app launches.
 *
 * Kept deliberately separate from the library's `SharedPreferencesStorage`
 * so the launcher's own settings don't leak into the chat widget's
 * conversation/session storage namespace.
 */
class LauncherSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val stubUrl: String get() = prefs.getString(KEY_STUB_URL, DEFAULT_STUB) ?: DEFAULT_STUB
    val backendUrl: String get() = prefs.getString(KEY_BACKEND_URL, DEFAULT_BACKEND) ?: DEFAULT_BACKEND
    val authToken: String get() = prefs.getString(KEY_AUTH_TOKEN, "") ?: ""
    val agentKey: String get() = prefs.getString(KEY_AGENT_KEY, DEFAULT_AGENT_KEY) ?: DEFAULT_AGENT_KEY

    fun save(stubUrl: String, backendUrl: String, authToken: String, agentKey: String) {
        prefs.edit()
            .putString(KEY_STUB_URL, stubUrl)
            .putString(KEY_BACKEND_URL, backendUrl)
            .putString(KEY_AUTH_TOKEN, authToken)
            .putString(KEY_AGENT_KEY, agentKey)
            .apply()
    }

    companion object {
        private const val PREFS = "agent_example_launcher"
        private const val KEY_STUB_URL = "stub_url"
        private const val KEY_BACKEND_URL = "backend_url"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_AGENT_KEY = "agent_key"

        // The Android emulator maps `10.0.2.2` to the host machine, so
        // `clients/test-stub-server/server.py` (host port 8765) and a local
        // Django dev server (host port 8000) are reachable at these URLs
        // out of the box.
        private const val DEFAULT_STUB = "http://10.0.2.2:8765"
        private const val DEFAULT_BACKEND = "http://10.0.2.2:8000"
        private const val DEFAULT_AGENT_KEY = "agent-echo"
    }
}
