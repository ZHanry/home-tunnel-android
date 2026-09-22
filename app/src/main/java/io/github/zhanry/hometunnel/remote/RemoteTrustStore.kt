package io.github.zhanry.hometunnel.remote

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.json.JsonObject

/** Public trust anchors only. Tokens and private keys are never serialized here. */
class RemoteTrustStore(context: Context) {
    private val root = File(context.noBackupFilesDir, "remote-trust")
    @Synchronized fun pinServer(origin: String, user: String, keyset: JsonObject): RemoteTrustUpdate {
        val id = RemoteCrypto.base64(RemoteCrypto.sha256("${origin.length}:$origin${user.length}:$user".toByteArray()))
        require(root.isDirectory || root.mkdirs())
        val file = AtomicFile(File(root, "$id.json"))
        val previous = if (file.baseFile.exists()) RemoteJson.parse(file.readFully()) else null
        val update = RemoteKeyset.advance(previous, keyset)
        if (previous == update.anchor) return update
        val stream = file.startWrite()
        try { stream.write(update.anchor.toString().toByteArray()); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
        return update
    }
}
