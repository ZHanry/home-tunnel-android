package io.github.zhanry.hometunnel.remote

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class RemotePersistentGrantTest {
    private val now = Instant.parse("2026-09-24T00:00:00Z")
    private val grantId = "10000000-0000-4000-8000-000000000001"
    private val host = RemoteEndpoint("20000000-0000-4000-8000-000000000001", "Test host", "host-fingerprint", "main", true,
        "30000000-0000-4000-8000-000000000001")
    private val controllerId = "40000000-0000-4000-8000-000000000001"
    private val permissions = setOf("view", "input.keyboard")
    private fun row() = buildJsonObject {
        put("id", grantId); put("host_endpoint_id", host.id); put("controller_endpoint_id", controllerId)
        put("status", "active"); put("mode", "persistent")
        put("permissions", JsonArray(permissions.map(::JsonPrimitive)))
        put("expires_at", now.plusSeconds(600).toString())
    }
    private fun listed(row: JsonObject) = buildJsonObject { put("items", JsonArray(listOf(row))) }

    @Test fun `trusted grant requires exact same-account endpoint scope and expiry`() {
        val grant = row()
        assertEquals(grantId, matchingPersistentGrant(listed(grant), host, requireNotNull(host.ownerUserId), controllerId, permissions, now))
        val changes = listOf(
            grant + ("host_endpoint_id" to JsonPrimitive("50000000-0000-4000-8000-000000000001")),
            grant + ("controller_endpoint_id" to JsonPrimitive("50000000-0000-4000-8000-000000000001")),
            grant + ("status" to JsonPrimitive("revoked")),
            grant + ("mode" to JsonPrimitive("one_session")),
            grant + ("expires_at" to JsonPrimitive(now.toString())),
            grant + ("permissions" to JsonArray(listOf(JsonPrimitive("view")))),
        )
        changes.forEach { assertNull(matchingPersistentGrant(listed(JsonObject(it)), host, requireNotNull(host.ownerUserId), controllerId, permissions, now)) }
        assertNull(matchingPersistentGrant(listed(grant), host.copy(assistInviteId = grantId), requireNotNull(host.ownerUserId), controllerId, permissions, now))
        assertNull(matchingPersistentGrant(listed(grant), host, "another-owner", controllerId, permissions, now))
    }
    @Test fun `trusted binding requires a capable same-account host`() {
        val ready = host.copy(unattendedEnabled = true)
        assertTrue(eligibleForTrustedBinding(ready, host.ownerUserId, permissions))
        assertFalse(eligibleForTrustedBinding(host, host.ownerUserId, permissions))
        assertFalse(eligibleForTrustedBinding(ready.copy(available = false), host.ownerUserId, permissions))
        assertFalse(eligibleForTrustedBinding(ready.copy(assistInviteId = grantId), host.ownerUserId, permissions))
        assertFalse(eligibleForTrustedBinding(ready, "another-owner", permissions))
        assertFalse(eligibleForTrustedBinding(ready, host.ownerUserId, setOf("input.keyboard")))
        assertFalse(eligibleForTrustedBinding(ready, host.ownerUserId, permissions + "unknown.permission"))
    }
}
