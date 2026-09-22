package io.github.zhanry.hometunnel.remote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import io.github.zhanry.hometunnel.remote.protocol.RemoteProtocol as P
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

/** Clipboard access is explicit and requires both foreground focus and granted direction. */
class RemoteClipboard(context: Context, private val allowed: (String) -> Boolean) {
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    fun readText(hasWindowFocus: Boolean): String? {
        check(hasWindowFocus && allowed("clipboard.write")) { "RD_CLIPBOARD_NOT_ALLOWED" }
        val item = clipboard.primaryClip?.takeIf { it.itemCount == 1 }?.getItemAt(0) ?: return null
        // Do not coerce URIs or HTML into text, or dereference another provider.
        if (item.uri != null || item.intent != null || item.htmlText != null) return null
        val text = item.text?.toString() ?: return null
        require(RemoteJson.validUnicode(text) && text.toByteArray().size <= P.CLIPBOARD_BYTES)
        return text
    }
    fun writeText(text: String, hasWindowFocus: Boolean) {
        check(hasWindowFocus && allowed("clipboard.read")) { "RD_CLIPBOARD_NOT_ALLOWED" }
        require(RemoteJson.validUnicode(text) && text.toByteArray().size <= P.CLIPBOARD_BYTES)
        clipboard.setPrimaryClip(ClipData.newPlainText("Home Tunnel", text))
    }
}

data class RemoteSelectedFile(val uri: Uri, val name: String, val length: Long?) {
    override fun toString() = "RemoteSelectedFile(name=[redacted], length=$length)"
}

class RemoteFiles(private val resolver: ContentResolver) {
    companion object {
        const val MAX_SELECTION = P.BATCH_FILES
        const val MAX_FILE_BYTES = P.FILE_BYTES
        fun safeName(value: String): String {
            require(value.isNotBlank() && value.length <= 255 && value != "." && value != "..") { "RD_FILE_NAME" }
            require(value.none { it == '/' || it == '\\' || it.code < 32 || it == ':' } && RemoteJson.validUnicode(value)) { "RD_FILE_NAME" }
            return value
        }
    }
    fun selected(uris: List<Uri>): List<RemoteSelectedFile> {
        require(uris.size in 1..MAX_SELECTION)
        return uris.distinct().map { uri ->
            require(uri.scheme == "content") { "RD_FILE_PROVIDER_REQUIRED" }
            val type = resolver.getType(uri)
            require(type != DocumentsContract.Document.MIME_TYPE_DIR && type?.startsWith("image/") != true) { "RD_FILE_TYPE_UNSUPPORTED" }
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                require(cursor.moveToFirst()) { "RD_FILE_UNAVAILABLE" }
                val name = safeName(cursor.getString(0))
                val length = if (cursor.isNull(1)) null else cursor.getLong(1)
                require(length == null || length in 0..MAX_FILE_BYTES) { "RD_FILE_TOO_LARGE" }
                RemoteSelectedFile(uri, name, length)
            } ?: error("RD_FILE_UNAVAILABLE")
        }.also { selected -> require(selected.sumOf { it.length ?: 0 } <= P.BATCH_FILE_BYTES) { "RD_FILE_BATCH_TOO_LARGE" } }
    }
    fun open(file: RemoteSelectedFile): InputStream = requireNotNull(resolver.openInputStream(file.uri)) { "RD_FILE_UNAVAILABLE" }
}

/** Bounded transfer primitive. The peer must explicitly accept an offer before creating this sink. */
class RemoteReceiveTransfer(
    val id: UUID,
    private val expectedLength: Long,
    private val output: OutputStream,
    private val sync: () -> Unit,
    private val discard: () -> Unit,
) : Closeable {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var offset = 0L
    private var closed = false
    private var completed = false
    init { require(expectedLength in 0..RemoteFiles.MAX_FILE_BYTES) }
    /** Return an ACK offset only after the selected sink has committed the chunk. */
    fun chunk(position: Long, bytes: ByteArray): Long {
        check(!closed)
        try {
            require(position == offset && bytes.size in 1..P.FILE_CHUNK_BYTES && bytes.size <= expectedLength - offset) { "RD_FILE_OFFSET" }
            output.write(bytes); output.flush(); sync()
            digest.update(bytes); offset += bytes.size
            return offset
        }
        catch (error: Exception) { close(); throw error }
    }
    /** FILE_COMPLETE supplies the final digest; the initial offer carries only the size. */
    fun finish(length: Long, expectedHash: ByteArray): ByteArray {
        check(!closed)
        if (length != expectedLength || offset != expectedLength || expectedHash.size != 32 || !MessageDigest.isEqual(digest.digest(), expectedHash)) {
            close(); error("RD_FILE_INTEGRITY")
        }
        try { output.flush(); sync(); output.close(); completed = true; closed = true; return expectedHash.copyOf() }
        catch (error: Exception) { close(); throw error }
    }
    override fun close() {
        if (closed) return
        closed = true
        try { output.close() } finally { if (!completed) discard() }
    }
}
