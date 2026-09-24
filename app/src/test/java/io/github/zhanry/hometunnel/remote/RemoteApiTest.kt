package io.github.zhanry.hometunnel.remote

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class RemoteApiTest {
    private val endpointId = "00112233-4455-4677-8899-aabbccddeeff"
    private val instance = "instance-fixture"
    private fun signer(): RemoteIdentity {
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        return object : RemoteIdentity {
            override val publicJwk = RemoteCrypto.jwk(key.public as ECPublicKey)
            override fun sign(bytes: ByteArray) = Signature.getInstance("SHA256withECDSA").run { initSign(key.private); update(bytes); sign() }
        }
    }
    private fun token(seconds: Long = 600) = buildJsonObject {
        put("token", "fixture-rd-token-for-unit-tests"); put("expires_at", Instant.now().plusSeconds(seconds).toString()); put("dpop_nonce", "fixture-nonce")
    }
    private fun challenge(purpose: String = "controller_refresh") = buildJsonObject {
        val id = UUID.randomUUID().toString()
        val nonce = RemoteCrypto.base64(ByteArray(32) { 7 })
        put("challenge_id", id); put("nonce", nonce); put("expires_at", Instant.now().plusSeconds(30).toString())
        put("proof_payload", buildJsonObject {
            put("purpose", purpose); put("challenge_id", id); put("nonce", nonce)
            put("server_instance_id", instance); put("endpoint_id", endpointId); put("issued_at", Instant.now().toString())
        })
    }
    @Test fun `clearing the remote account cancels active calls and rejects delayed responses`() = runTest {
        val started = CompletableDeferred<Unit>()
        val resume = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            started.complete(Unit)
            check(resume.await(10, TimeUnit.SECONDS))
            assertTrue(chain.call().isCanceled())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = RemoteApi("https://home.example/api/v1/", { _, _, _ -> error("No account credentials expected") }, client)
        val request = async { runCatching { api.capabilities() } }
        try { started.await(); api.clear() } finally { resume.countDown() }
        assertTrue(request.await().isFailure)
    }
    @Test fun `only server keysets use the larger bounded response budget`() = runTest {
        var size = 70_000
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(("{\"fixture\":\"" + "x".repeat(size) + "\"}").toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = RemoteApi("https://home.example/api/v1/", { _, _, _ -> error("No account credentials expected") }, client)
        assertEquals(size, api.serverKeys().string("fixture").length)
        assertFails { api.capabilities() }
        size = 256 * 1024
        assertFails { api.serverKeys() }
    }
    @Test fun `RD requests use DPoP and concurrent expiry performs one parent bound refresh`() = runTest {
        val identity = signer()
        val refreshes = AtomicInteger()
        val requests = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("DPoP fixture-rd-token-for-unit-tests", request.header("Authorization"))
            val proof = requireNotNull(request.header("DPoP"))
            val payload = RemoteJson.parse(RemoteCrypto.decode(proof.split('.')[1]))
            assertEquals(JsonPrimitive("https://home.example/api/v1/rd/endpoints"), payload["htu"])
            assertEquals(JsonPrimitive("GET"), payload["htm"])
            assertEquals(identity.publicJwk, RemoteJson.parse(RemoteCrypto.decode(proof.split('.')[0]))["jwk"])
            requests.incrementAndGet()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"items\":[]}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = RemoteApi("https://home.example/api/v1/", { _, path, body ->
            when (path) {
                "rd/token-challenges" -> challenge()
                "rd/tokens" -> {
                    assertEquals(endpointId, requireNotNull(body).string("endpoint_id"))
                    assertEquals(JsonPrimitive("controller_refresh"), RemoteCrypto.verifyJws(body.string("proof"), "ht-rd-proof+jwt", identity.publicJwk)["purpose"])
                    token(if (refreshes.incrementAndGet() == 1) 10 else 600)
                }
                else -> error("Unexpected account route")
            }
        }, client)
        api.restore(identity, endpointId, instance)
        List(24) { async { api.endpoints() } }.awaitAll()
        assertEquals(2, refreshes.get())
        assertEquals(24, requests.get())
        api.clear()
        assertNull(api.endpointId)
        assertFails { api.endpoints() }
    }
    @Test fun `clearing a remote account during a refresh cannot restore its token`() = runTest {
        val waiting = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val api = RemoteApi("https://home.example/api/v1/", { _, path, _ ->
            if (path == "rd/token-challenges") challenge() else { waiting.complete(Unit); finish.await(); token() }
        })
        supervisorScope {
            val result = async { api.restore(signer(), endpointId, instance) }
            waiting.await(); api.clear(); finish.complete(Unit)
            assertFailsWith<IllegalStateException> { result.await() }
        }
        assertNull(api.endpointId)
    }
    @Test fun `challenge purpose substitution and unsafe API origins are rejected`() = runTest {
        val api = RemoteApi("https://home.example/api/v1/", { _, _, _ -> challenge("host_online") })
        assertFails { api.restore(signer(), endpointId, instance) }
        for (url in listOf("http://home.example/api/v1/", "https://user:pass@home.example/api/v1/", "https://home.example/other/")) {
            assertFails { RemoteApi(url, { _, _, _ -> token() }) }
        }
        assertTrue(RemoteCrypto.thumbprint(signer().publicJwk).isNotBlank())
    }
    @Test fun `session creation includes the selected host display and request binding`() = runTest {
        val sessionRequest = UUID.randomUUID().toString()
        val grant = UUID.randomUUID().toString()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("/api/v1/rd/sessions", request.url.encodedPath)
            assertEquals(sessionRequest, request.header("Idempotency-Key"))
            val body = okio.Buffer().also { request.body?.writeTo(it) }.readUtf8()
            val payload = RemoteJson.parse(body.toByteArray())
            assertEquals(JsonPrimitive("display-main"), payload["display_id"])
            assertEquals(JsonPrimitive(grant), payload["grant_id"])
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(202).message("Accepted")
                .body("{\"id\":\"$sessionRequest\"}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = RemoteApi("https://home.example/api/v1/", { _, path, _ ->
            if (path == "rd/token-challenges") challenge() else token()
        }, client)
        api.restore(signer(), endpointId, instance)
        assertEquals(sessionRequest, api.createSession(endpointId, grant, setOf("view"), sessionRequest, "display-main").string("id"))
        assertFails { api.createSession(endpointId, grant, setOf("view"), sessionRequest, "") }
    }
    @Test fun `temporary assistance sends the secret only in redemption and binds pairing to its invitation`() = runTest {
        val inviteId = UUID.randomUUID().toString()
        val requestId = UUID.randomUUID().toString()
        val requests = mutableListOf<Pair<String, JsonObject>>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = okio.Buffer().also { request.body?.writeTo(it) }.readUtf8()
            requests += request.url.encodedPath to RemoteJson.parse(body.toByteArray())
            val result = if (request.url.encodedPath.endsWith("/redeem")) "{\"invite_id\":\"$inviteId\"}" else "{\"id\":\"$requestId\"}"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(result.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = RemoteApi("https://home.example/api/v1/", { _, path, _ ->
            if (path == "rd/token-challenges") challenge() else token()
        }, client)
        api.restore(signer(), endpointId, instance)
        assertEquals(inviteId, api.redeemAssist("123456789", "ABcd2345EFgh").string("invite_id"))
        api.pairing(endpointId, setOf("view"), requestId, RemoteCrypto.base64(ByteArray(32) { 3 }), inviteId)
        assertEquals("/api/v1/rd/assist-invites/redeem", requests[0].first)
        assertEquals(JsonPrimitive("ABcd2345EFgh"), requests[0].second["temporary_password"])
        assertEquals(JsonPrimitive(inviteId), requests[1].second["assist_invite_id"])
        assertTrue("temporary_password" !in requests[1].second)
        assertFails { api.redeemAssist("123", "wrong") }
    }
    @Test fun `trusted binding requests persistent mode without an invitation`() = runTest {
        val requestId = UUID.randomUUID().toString()
        val inviteId = UUID.randomUUID().toString()
        val requests = mutableListOf<JsonObject>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("/api/v1/rd/pairings", request.url.encodedPath)
            requests += RemoteJson.parse(okio.Buffer().also { request.body?.writeTo(it) }.readUtf8().toByteArray())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"id\":\"$requestId\"}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = RemoteApi("https://home.example/api/v1/", { _, path, _ ->
            if (path == "rd/token-challenges") challenge() else token()
        }, client)
        api.restore(signer(), endpointId, instance)
        api.pairing(endpointId, setOf("view"), requestId, RemoteCrypto.base64(ByteArray(32) { 4 }), mode = "persistent")
        assertEquals(JsonPrimitive("persistent"), requests.single()["mode"])
        assertTrue("assist_invite_id" !in requests.single())
        assertFails { api.pairing(endpointId, setOf("view"), requestId, RemoteCrypto.base64(ByteArray(32) { 4 }), inviteId, "persistent") }
        assertFails { api.pairing(endpointId, setOf("view"), requestId, RemoteCrypto.base64(ByteArray(32) { 4 }), mode = "forever") }
        assertEquals(1, requests.size)
    }
}
