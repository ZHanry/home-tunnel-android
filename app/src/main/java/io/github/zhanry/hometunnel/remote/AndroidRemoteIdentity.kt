package io.github.zhanry.hometunnel.remote

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import kotlinx.serialization.json.JsonObject

/** Non-exportable signing key; never reuse the tunnel credential or encryption key. */
class AndroidRemoteIdentity private constructor(private val alias: String) : RemoteIdentity {
    override val publicJwk: JsonObject
        get() = RemoteCrypto.jwk(store().getCertificate(alias).publicKey as ECPublicKey)
    override fun sign(bytes: ByteArray): ByteArray {
        require(bytes.size in 1..64 * 1024) { "RD_PROOF_TOO_LARGE" }
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(store().getKey(alias, null) as PrivateKey)
            update(bytes)
            sign()
        }
    }
    companion object {
        internal fun keyAlias(serverInstance: String, userId: String): String {
            require(serverInstance.length in 1..128 && userId.length in 1..128)
            val partition = "ht-rd-identity-v1\n${serverInstance.length}:$serverInstance\n${userId.length}:$userId"
            return "ht_rd_v1_${RemoteCrypto.base64(RemoteCrypto.sha256(partition.toByteArray()))}"
        }
        private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        @Synchronized fun open(serverInstance: String, userId: String): AndroidRemoteIdentity {
            val alias = keyAlias(serverInstance, userId)
            if (!store().containsAlias(alias)) {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").run {
                    initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256).build())
                    generateKeyPair()
                }
            }
            return AndroidRemoteIdentity(alias).also { RemoteCrypto.thumbprint(it.publicJwk) }
        }
    }
}
