package io.github.zhanry.hometunnel.remote

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

interface RemoteIdentity {
    val publicJwk: JsonObject
    fun sign(bytes: ByteArray): ByteArray
}

object RemoteCrypto {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    fun base64(bytes: ByteArray): String = encoder.encodeToString(bytes)
    fun decode(value: String, maximum: Int = 65536): ByteArray {
        require(value.length <= maximum * 4 / 3 + 4 && Regex("[A-Za-z0-9_-]*").matches(value)) { "RD_BASE64" }
        return decoder.decode(value).also { require(it.size <= maximum && base64(it) == value) { "RD_BASE64" } }
    }
    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    fun thumbprint(jwk: JsonObject): String {
        publicKey(jwk)
        return base64(sha256(RemoteJson.canonical(jwk).toByteArray(Charsets.UTF_8)))
    }
    fun jwk(key: ECPublicKey): JsonObject = buildJsonObject {
        require(key.params.curve.field.fieldSize == 256) { "RD_KEY_CURVE" }
        put("kty", "EC"); put("crv", "P-256")
        put("x", base64(unsigned32(key.w.affineX))); put("y", base64(unsigned32(key.w.affineY)))
    }
    fun publicKey(jwk: JsonObject): ECPublicKey {
        require(jwk.keys == setOf("kty", "crv", "x", "y") && jwk["kty"] == JsonPrimitive("EC") && jwk["crv"] == JsonPrimitive("P-256")) { "RD_KEY_FORMAT" }
        val x = decode(jwk.getValue("x").jsonPrimitive.content, 32)
        val y = decode(jwk.getValue("y").jsonPrimitive.content, 32)
        require(x.size == 32 && y.size == 32) { "RD_KEY_LENGTH" }
        val parameters = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        val px = BigInteger(1, x); val py = BigInteger(1, y)
        val prime = (parameters.curve.field as java.security.spec.ECFieldFp).p
        require(px < prime && py < prime && py.pow(2).mod(prime) ==
            px.pow(3).add(parameters.curve.a.multiply(px)).add(parameters.curve.b).mod(prime)) { "RD_KEY_POINT" }
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(px, py), parameters)) as ECPublicKey
    }
    private fun unsigned32(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        require(value.signum() >= 0 && value.bitLength() <= 256) { "RD_KEY_RANGE" }
        return ByteArray(32).also { bytes.takeLast(32).toByteArray().copyInto(it, 32 - minOf(32, bytes.size)) }
    }
    fun signJws(identity: RemoteIdentity, type: String, payload: JsonObject, includeJwk: Boolean = false): String {
        val header = buildJsonObject {
            put("alg", "ES256"); put("typ", type)
            if (includeJwk) put("jwk", identity.publicJwk)
        }
        val input = "${base64(RemoteJson.canonical(header).toByteArray())}.${base64(RemoteJson.canonical(payload).toByteArray())}"
        return "$input.${base64(derToRaw(identity.sign(input.toByteArray(Charsets.US_ASCII))))}"
    }
    fun verifyJws(compact: String, type: String, trustedJwk: JsonObject, expectedKid: String? = null): JsonObject {
        require(compact.length <= RemoteJson.MAX_BYTES && compact.count { it == '.' } == 2) { "RD_JWS_FORMAT" }
        val pieces = compact.split('.')
        val header = RemoteJson.parse(decode(pieces[0], 2048), 2048)
        require(header.keys == (if (expectedKid == null) setOf("alg", "typ") else setOf("alg", "typ", "kid"))) { "RD_JWS_HEADER" }
        require(header["alg"] == JsonPrimitive("ES256") && header["typ"] == JsonPrimitive(type)) { "RD_JWS_TYPE" }
        if (expectedKid != null) require(header["kid"] == JsonPrimitive(expectedKid)) { "RD_JWS_KID" }
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey(trustedJwk))
        verifier.update("${pieces[0]}.${pieces[1]}".toByteArray(Charsets.US_ASCII))
        require(verifier.verify(rawToDer(decode(pieces[2], 64)))) { "RD_PROOF_INVALID" }
        return RemoteJson.parse(decode(pieces[1], 48 * 1024))
    }
    fun verifyTranscript(jwk: JsonObject, transcript: ByteArray, rawSignature: ByteArray): Boolean {
        require(transcript.size in 1..4096)
        return Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey(jwk)); update(transcript); verify(rawToDer(rawSignature))
        }
    }
    fun dpop(identity: RemoteIdentity, method: String, url: String, token: String, nonce: String, now: Instant = Instant.now()): String {
        val uri = java.net.URI(url)
        require(uri.scheme == "https" && uri.userInfo == null && uri.fragment == null && nonce.length in 1..256) { "RD_DPOP_TARGET" }
        val htu = java.net.URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toASCIIString()
        return signJws(identity, "dpop+jwt", buildJsonObject {
            put("jti", UUID.randomUUID().toString()); put("htm", method); put("htu", htu)
            put("iat", now.epochSecond); put("ath", base64(sha256(token.toByteArray(Charsets.US_ASCII)))); put("nonce", nonce)
        }, includeJwk = true)
    }
    fun transcript(session: UUID, epoch: Long, controllerNonce: ByteArray, hostNonce: ByteArray, offerHash: ByteArray, answerHash: ByteArray, ticketHash: ByteArray): ByteArray {
        require(epoch in 1..0xffffffffL)
        require(listOf(controllerNonce, hostNonce, offerHash, answerHash, ticketHash).all { it.size == 32 })
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            fun field(value: ByteArray) { output.writeInt(value.size); output.write(value) }
            field("ht-rd-proof-v1".toByteArray())
            field(java.nio.ByteBuffer.allocate(16).putLong(session.mostSignificantBits).putLong(session.leastSignificantBits).array())
            output.writeInt(epoch.toInt())
            listOf(controllerNonce, hostNonce, offerHash, answerHash, ticketHash).forEach(::field)
        }
        return bytes.toByteArray()
    }

    /** AndroidKeyStore ECDSA uses ASN.1 DER; JWS ES256 uses exactly 64 raw bytes. */
    fun derToRaw(der: ByteArray): ByteArray {
        require(der.size in 8..72 && der[0] == 0x30.toByte() && (der[1].toInt() and 255) == der.size - 2) { "RD_SIGNATURE_DER" }
        var offset = 2
        fun integer(): ByteArray {
            require(offset + 2 <= der.size && der[offset++] == 2.toByte()) { "RD_SIGNATURE_DER" }
            val length = der[offset++].toInt() and 255
            require(length in 1..33 && offset + length <= der.size) { "RD_SIGNATURE_DER" }
            val value = der.copyOfRange(offset, offset + length); offset += length
            require(value[0].toInt() >= 0 && !(length > 1 && value[0] == 0.toByte() && value[1].toInt() >= 0)) { "RD_SIGNATURE_DER" }
            val unsigned = if (value[0] == 0.toByte()) value.drop(1).toByteArray() else value
            require(unsigned.size <= 32 && unsigned.any { it != 0.toByte() }) { "RD_SIGNATURE_RANGE" }
            return ByteArray(32).also { unsigned.copyInto(it, 32 - unsigned.size) }
        }
        return (integer() + integer()).also { require(offset == der.size) { "RD_SIGNATURE_DER" } }
    }
    fun rawToDer(raw: ByteArray): ByteArray {
        require(raw.size == 64) { "RD_SIGNATURE_LENGTH" }
        fun integer(part: ByteArray): ByteArray {
            val value = part.dropWhile { it == 0.toByte() }.toByteArray()
            require(value.isNotEmpty()) { "RD_SIGNATURE_RANGE" }
            val encoded = if (value[0].toInt() < 0) byteArrayOf(0) + value else value
            return byteArrayOf(2, encoded.size.toByte()) + encoded
        }
        val payload = integer(raw.copyOfRange(0, 32)) + integer(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, payload.size.toByte()) + payload
    }
}
