package io.github.zhanry.hometunnel.remote

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class RemoteTrustUpdate(val anchor: JsonObject, val restoreChanged: Boolean)

/** Trust advances only through consecutive ES256 proofs signed by the pinned active key. */
object RemoteKeyset {
    fun advance(previous: JsonObject?, response: JsonObject, now: Instant = Instant.now()): RemoteTrustUpdate {
        require(response.keys == setOf("server_instance_id", "restore_epoch", "keyset_version", "active_kid", "keys", "rotation_proofs")) { "RD_KEYSET_FORMAT" }
        val instance = response.string("server_instance_id")
        require(UUID.fromString(instance).toString() == instance) { "RD_KEYSET_INSTANCE" }
        val restore = positive(response, "restore_epoch")
        val target = keyset(response)
        validateKeys(target, now)
        val proofs = response.getValue("rotation_proofs") as JsonArray
        require(proofs.size <= 32) { "RD_KEYSET_CHAIN" }
        if (previous != null) {
            require(previous.string("server_instance_id") == instance && restore >= positive(previous, "restore_epoch")) { "RD_SERVER_TRUST_CHANGED" }
            var current = keyset(previous)
            val targetVersion = positive(target, "keyset_version")
            require(targetVersion >= positive(current, "keyset_version") && targetVersion - positive(current, "keyset_version") <= 32) { "RD_KEYSET_ROLLBACK" }
            val byVersion = mutableMapOf<Long, String>()
            proofs.forEach { proof ->
                require(proof.jsonPrimitive.isString) { "RD_KEYSET_CHAIN" }
                val compact = proof.jsonPrimitive.content
                require(compact.length <= 8192 && compact.count { it == '.' } == 2) { "RD_KEYSET_CHAIN" }
                val claims = RemoteJson.parse(RemoteCrypto.decode(compact.split('.')[1], 48 * 1024))
                require(byVersion.put(positive(claims, "from_version"), compact) == null) { "RD_KEYSET_CHAIN" }
            }
            while (positive(current, "keyset_version") < targetVersion) {
                val from = positive(current, "keyset_version")
                val kid = current.string("active_kid")
                val signer = keys(current).single { it.string("kid") == kid }
                val proof = RemoteCrypto.verifyJws(requireNotNull(byVersion[from]) { "RD_KEYSET_CHAIN" },
                    "ht-rd-keyset+jwt", signer.getValue("public_jwk").jsonObject, kid)
                require(proof.keys == setOf("server_instance_id", "from_version", "to_version", "from_kid", "issued_at", "keyset") &&
                    proof["server_instance_id"] == JsonPrimitive(instance) && positive(proof, "from_version") == from &&
                    positive(proof, "to_version") == from + 1 && proof["from_kid"] == JsonPrimitive(kid)) { "RD_KEYSET_CHAIN" }
                val issued = Instant.parse(proof.string("issued_at"))
                require(!issued.isBefore(Instant.parse(signer.string("not_before"))) && issued.isBefore(Instant.parse(signer.string("not_after"))) &&
                    !issued.isAfter(now.plusSeconds(60))) { "RD_KEYSET_TIME" }
                current = proof.getValue("keyset").jsonObject
                require(current.keys == setOf("keyset_version", "active_kid", "keys") && positive(current, "keyset_version") == from + 1) { "RD_KEYSET_CHAIN" }
                validateKeys(current, null)
            }
            require(current == target) { "RD_SERVER_TRUST_CHANGED" }
        }
        val anchor = buildJsonObject {
            put("server_instance_id", instance); put("restore_epoch", restore)
            target.forEach { (name, value) -> put(name, value) }
        }
        return RemoteTrustUpdate(anchor, previous != null && restore != positive(previous, "restore_epoch"))
    }

    private fun keyset(value: JsonObject) = JsonObject(value.filterKeys { it in setOf("keyset_version", "active_kid", "keys") })
    private fun keys(value: JsonObject): List<JsonObject> = (value.getValue("keys") as JsonArray).map { it.jsonObject }
    private fun validateKeys(value: JsonObject, now: Instant?) {
        positive(value, "keyset_version")
        val keys = keys(value)
        require(keys.size in 1..8 && keys.map { it.string("kid") }.toSet().size == keys.size) { "RD_KEYSET_KEYS" }
        keys.forEach { key ->
            require(key.keys == setOf("kid", "alg", "public_jwk", "not_before", "not_after") && key["alg"] == JsonPrimitive("ES256") &&
                key.string("kid") == RemoteCrypto.thumbprint(key.getValue("public_jwk").jsonObject)) { "RD_KEYSET_KEYS" }
            require(Instant.parse(key.string("not_before")).isBefore(Instant.parse(key.string("not_after")))) { "RD_KEYSET_TIME" }
        }
        val active = keys.single { it.string("kid") == value.string("active_kid") }
        if (now != null) require(!now.isBefore(Instant.parse(active.string("not_before"))) && now.isBefore(Instant.parse(active.string("not_after")))) { "RD_KEYSET_EXPIRED" }
    }
    private fun positive(value: JsonObject, field: String): Long {
        val number = value.getValue(field).jsonPrimitive
        require(!number.isString)
        return requireNotNull(number.longOrNull).also { require(it in 1..Int.MAX_VALUE.toLong()) { "RD_KEYSET_VERSION" } }
    }
}
