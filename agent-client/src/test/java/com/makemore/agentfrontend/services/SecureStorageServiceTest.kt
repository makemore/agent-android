package com.makemore.agentfrontend.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * On-device encryption routing. Secrets (auth token, client memories) must go
 * to the secure (Keystore-encrypted) backend; non-secret UI prefs stay in
 * plain SharedPreferences. Mirrors the iOS SecureStorageService test.
 *
 * The real KeystoreEncryptedStorage needs the Android Keystore (device /
 * instrumented test); here the routing is verified with in-memory doubles.
 */
class SecureStorageServiceTest {

    @Test
    fun routesSecretsToSecureBackendAndPrefsToStandard() {
        val secure = InMemoryStorage()
        val standard = InMemoryStorage()
        val store = SecureStorageService(
            secure = secure,
            standard = standard,
            explicitSecureKeys = setOf("chat_widget_anonymous_token"),
        )

        store.set("chat_widget_anonymous_token", "tok-123")
        store.set("chat_widget_memories", "[{\"k\":\"v\"}]")
        store.set("chat_widget_model_selection", "gpt-4o")

        // Secrets only in the secure backend.
        assertEquals("tok-123", secure.get("chat_widget_anonymous_token"))
        assertNull(standard.get("chat_widget_anonymous_token"))
        assertEquals("[{\"k\":\"v\"}]", secure.get("chat_widget_memories"))
        assertNull(standard.get("chat_widget_memories"))

        // Non-secret UI prefs stay in plain SharedPreferences.
        assertEquals("gpt-4o", standard.get("chat_widget_model_selection"))
        assertNull(secure.get("chat_widget_model_selection"))

        // Reads route to the same backend they were written to.
        assertEquals("tok-123", store.get("chat_widget_anonymous_token"))
    }

    @Test
    fun isSecureClassifiesCredentialAndMemoryKeys() {
        val store = SecureStorageService(InMemoryStorage(), InMemoryStorage())
        assertEquals(true, store.isSecure("chat_widget_anonymous_token"))
        assertEquals(true, store.isSecure("chat_widget_memories"))
        assertEquals(true, store.isSecure("authToken"))
        assertEquals(false, store.isSecure("chat_widget_model_selection"))
        assertEquals(false, store.isSecure("chat_widget_conversation_id"))
    }
}
