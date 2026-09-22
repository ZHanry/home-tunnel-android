package io.github.zhanry.hometunnel.remote

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zhanry.hometunnel.BuildConfig
import java.security.KeyStore
import java.util.UUID
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteIdentityTest {
    @Test fun keystoreIdentityPersistsWithoutExportingPrivateKeyAndPartitionsAccounts() {
        val server = UUID.randomUUID().toString()
        val user = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        try {
            val first = AndroidRemoteIdentity.open(server, user)
            val reopened = AndroidRemoteIdentity.open(server, user)
            assertEquals(first.publicJwk, reopened.publicJwk)
            assertNotEquals(first.publicJwk, AndroidRemoteIdentity.open(server, other).publicJwk)
            val payload = buildJsonObject { put("purpose", "instrumentation-test"); put("nonce", UUID.randomUUID().toString()) }
            val proof = RemoteCrypto.signJws(first, "ht-rd-proof+jwt", payload)
            assertEquals(payload, RemoteCrypto.verifyJws(proof, "ht-rd-proof+jwt", reopened.publicJwk))
            val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .getKey(AndroidRemoteIdentity.keyAlias(server, user), null)
            assertNull(key.encoded)
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null)
                deleteEntry(AndroidRemoteIdentity.keyAlias(server, user))
                deleteEntry(AndroidRemoteIdentity.keyAlias(server, other))
            }
        }
    }
    @Test fun nativeFixtureMatchesTheExplicitlySelectedBackend() {
        assertTrue("The native CI fixture must load JNI and all transitive native libraries", RemoteNativeBridge.loaded)
        val session = RemoteNativeSession { _, _, _, _ -> }
        try {
            if (BuildConfig.REMOTE_CONTROLLER_BACKEND) {
                assertTrue(session.capability.available)
                assertEquals(setOf("view", "input.keyboard", "input.pointer", "input.text"), session.capability.permissions)
                assertEquals("", session.capability.reason)
            } else {
                assertFalse(session.capability.available)
                assertEquals("RD_MEDIA_BACKEND_UNAVAILABLE", session.capability.reason)
            }
        } finally { session.close(); session.close() }
    }
}
