package io.github.zhanry.hometunnel.remote

import java.io.Closeable
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

class RemoteSignaling(
    private val baseUrl: HttpUrl,
    private val endpoint: String,
    private val identity: RemoteIdentity,
    private val renewTicket: suspend () -> JsonObject,
    private val onEvent: (JsonObject) -> Unit,
    private val onError: (String) -> Unit,
) : WebSocketListener(), Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS).pingInterval(50, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private var socket: WebSocket? = null
    private var ticket: String? = null
    private var connectionId: String? = null
    private var connectionNonce: String? = null
    @Volatile private var renewing = false
    @Volatile private var state = State.NEW
    private enum class State { NEW, CHALLENGE, AUTHENTICATING, READY, CLOSED }

    @Synchronized fun connect(ticketResponse: JsonObject) {
        check(state == State.NEW)
        require(ticketResponse.string("subprotocol") == "ht.rd.signal.v1" && ticketResponse.string("signal_path") == "/api/v1/rd/signal")
        val deadline = Instant.parse(ticketResponse.string("expires_at"))
        require(deadline.isAfter(Instant.now()) && deadline.isBefore(Instant.now().plusSeconds(35)))
        ticket = ticketResponse.string("ticket").also { require(it.length in 16..4096) }
        val url = baseUrl.newBuilder().encodedPath("/api/v1/rd/signal").query(null).fragment(null).build()
        state = State.CHALLENGE
        socket = client.newWebSocket(Request.Builder().url(url)
            .header("Sec-WebSocket-Protocol", "ht.rd.signal.v1").build(), this)
        scope.launch { delay(5000); if (state != State.READY && state != State.CLOSED) fail("RD_SIGNAL_AUTH_TIMEOUT") }
    }
    override fun onOpen(webSocket: WebSocket, response: Response) {
        if (response.header("Sec-WebSocket-Protocol") != "ht.rd.signal.v1") fail("RD_PROTOCOL_MISMATCH")
    }
    @Synchronized override fun onMessage(webSocket: WebSocket, text: String) {
        if (state == State.CLOSED) return
        try {
            val value = RemoteJson.parse(text.toByteArray())
            require(value["v"] == kotlinx.serialization.json.JsonPrimitive(1)) { "RD_PROTOCOL_MISMATCH" }
            when (value.string("type")) {
                "auth.challenge" -> {
                    check(state == State.CHALLENGE)
                    require(value.keys == setOf("v", "type", "connection_id", "nonce"))
                    val nonce = value.string("nonce").also { require(RemoteCrypto.decode(it, 64).size >= 16) }
                    val activeTicket = requireNotNull(ticket)
                    connectionId = value.string("connection_id"); connectionNonce = nonce
                    val proof = RemoteCrypto.signJws(identity, "ht-rd-signal+jwt", buildJsonObject {
                        put("connection_id", value.string("connection_id")); put("nonce", nonce)
                        put("ticket_hash", RemoteCrypto.base64(RemoteCrypto.sha256(activeTicket.toByteArray())))
                        put("endpoint_id", endpoint)
                    })
                    state = State.AUTHENTICATING
                    check(webSocket.send(buildJsonObject { put("v", 1); put("type", "auth"); put("ticket", activeTicket); put("proof", proof) }.toString()))
                    ticket = null
                }
                "auth.ok" -> { check(state == State.AUTHENTICATING); state = State.READY; onEvent(value) }
                "auth.reauth_required" -> {
                    check(state == State.READY && !renewing && value.string("connection_id") == connectionId && value.string("nonce") == connectionNonce)
                    renewing = true
                    scope.launch {
                        try {
                            val renewal = renewTicket()
                            val replacement = renewal.string("ticket")
                            require(Instant.parse(renewal.string("expires_at")).isAfter(Instant.now()))
                            val proof = RemoteCrypto.signJws(identity, "ht-rd-signal+jwt", buildJsonObject {
                                put("connection_id", requireNotNull(connectionId)); put("nonce", requireNotNull(connectionNonce))
                                put("ticket_hash", RemoteCrypto.base64(RemoteCrypto.sha256(replacement.toByteArray())))
                                put("endpoint_id", endpoint)
                            })
                            send(buildJsonObject { put("v", 1); put("type", "auth.reauth"); put("ticket", replacement); put("proof", proof) })
                            delay(5000)
                            if (renewing) fail("RD_SIGNAL_AUTH_TIMEOUT")
                        } catch (_: Exception) { fail("RD_SIGNAL_REAUTH_FAILED") }
                    }
                }
                "auth.renewed" -> { check(state == State.READY && renewing); renewing = false; onEvent(value) }
                else -> { check(state == State.READY); onEvent(value) }
            }
        } catch (_: Exception) { fail("RD_SIGNAL_INVALID") }
    }
    override fun onMessage(webSocket: WebSocket, bytes: ByteString) { fail("RD_SIGNAL_INVALID") }
    @Synchronized fun send(envelope: JsonObject) {
        check(state == State.READY)
        val text = envelope.toString()
        require(text.toByteArray().size <= RemoteJson.MAX_BYTES)
        val ws = requireNotNull(socket)
        check(ws.queueSize() + text.toByteArray().size <= 128 * 1024) { "RD_SIGNAL_BACKPRESSURE" }
        check(ws.send(text)) { "RD_SIGNAL_CLOSED" }
    }
    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { fail("RD_SIGNAL_CLOSED") }
    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { fail("RD_SIGNAL_CLOSED") }
    @Synchronized private fun fail(code: String) { if (state != State.CLOSED) { close(); onError(code) } }
    @Synchronized override fun close() {
        if (state == State.CLOSED) return
        state = State.CLOSED; ticket = null; connectionId = null; connectionNonce = null; renewing = false
        socket?.cancel(); socket = null
        scope.cancel(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
    }
}
