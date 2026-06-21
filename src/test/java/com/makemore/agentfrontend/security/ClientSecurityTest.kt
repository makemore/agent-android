package com.makemore.agentfrontend.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.io.File

/**
 * Layer C — client security guards (Android).
 *
 * Locks in the security properties the *library* owns: it must not ship
 * insecure transport defaults (cleartext / arbitrary backup). The libraries
 * declare no `<application>` element, so storage-at-rest encryption, token
 * encryption, and `allowBackup` are host-app concerns — encoded here as a
 * documented `@Ignore` and tracked in
 * agent/docs/ephemeral-security-validation-plan.md (Layer C).
 */
class ClientSecurityTest {

    @Test
    fun libraryManifestsShipNoInsecureTransportFlags() {
        val manifests = listOf(
            "src/main/AndroidManifest.xml",
            "agent-client/src/main/AndroidManifest.xml",
        ).map { moduleFile(it) }

        for (manifest in manifests) {
            assertTrue("missing manifest: ${manifest.absolutePath}", manifest.isFile)
            val text = manifest.readText()
            assertFalse(
                "${manifest.name} must not enable cleartext traffic",
                text.contains("usesCleartextTraffic=\"true\"")
            )
            assertFalse(
                "${manifest.name} must not enable allowBackup (would back up tokens/history)",
                text.contains("allowBackup=\"true\"")
            )
        }
    }

    @Test
    @Ignore(
        "PARTIAL: auth tokens + client memories are now encrypted via the Android " +
            "Keystore (SecureStorageService + KeystoreEncryptedStorage; routing covered " +
            "by SecureStorageServiceTest, real crypto needs an instrumented device test). " +
            "REMAINING: the local conversation SQLite DB is still OS-FBE-only — SQLCipher " +
            "would add native libs to the published AAR and is tracked as a follow-up in " +
            "ephemeral-security-validation-plan.md (Layer C, C-Store)."
    )
    fun localConversationDatabaseShouldBeEncryptedAtRest() {
        // Placeholder: SQLCipher migration + instrumented device test.
    }

    /** Walk up from the test cwd to the module dir that holds [rel]. */
    private fun moduleFile(rel: String): File {
        var dir: File? = File("").absoluteFile
        repeat(10) {
            val cur = dir ?: return@repeat
            val direct = File(cur, rel)
            if (direct.isFile) return direct
            val nested = File(cur, "agent-android/$rel")
            if (nested.isFile) return nested
            dir = cur.parentFile
        }
        return File(rel) // let the assertTrue(isFile) fail with a clear path
    }
}
