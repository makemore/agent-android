package com.makemore.agentfrontend.services

import android.content.Context
import android.content.SharedPreferences

/**
 * Storage service for persisting data.
 * Mirrors the iOS StorageService protocol.
 */
interface StorageService {
    fun get(key: String): String?
    fun set(key: String, value: String?)
}

/**
 * SharedPreferences-based storage implementation.
 * Android equivalent of iOS UserDefaultsStorage.
 */
class SharedPreferencesStorage(
    context: Context,
    private val prefix: String = ""
) : StorageService {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("agent_frontend", Context.MODE_PRIVATE)

    private fun storageKey(key: String): String =
        if (prefix.isEmpty()) key else "${key}_$prefix"

    override fun get(key: String): String? =
        prefs.getString(storageKey(key), null)

    override fun set(key: String, value: String?) {
        val fullKey = storageKey(key)
        if (value != null) {
            prefs.edit().putString(fullKey, value).apply()
        } else {
            prefs.edit().remove(fullKey).apply()
        }
    }
}

/**
 * In-memory storage for testing.
 * Android equivalent of iOS InMemoryStorage.
 */
class InMemoryStorage : StorageService {
    private val storage = mutableMapOf<String, String>()

    override fun get(key: String): String? = storage[key]

    override fun set(key: String, value: String?) {
        if (value != null) {
            storage[key] = value
        } else {
            storage.remove(key)
        }
    }
}

