package io.github.zhanry.hometunnel.remote

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import android.system.StructPollfd
import android.os.SystemClock
import android.os.StatFs
import io.github.zhanry.hometunnel.remote.protocol.RemoteProtocol as P
import java.io.Closeable
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

data class RemoteFileItem(
    val id: String, val name: String, val size: Long, val offset: Long,
    val outgoing: Boolean, val status: String, val error: String? = null,
) {
    val terminal: Boolean get() = status in setOf("complete", "ready_to_save", "saving", "saved", "cancelled", "error")
}

/** Native events report transport integrity; saving into SAF is a separate local operation. */
internal fun nativeFileItem(body: JsonObject, previous: RemoteFileItem?): RemoteFileItem {
    val id = body.string("id")
    require(UUID.fromString(id).toString() == id) { "RD_FILE_INVALID" }
    val name = RemoteFiles.safeName(body.string("name"))
    val size = body.nonNegativeNumber("size", P.FILE_BYTES)
    val offset = body.nonNegativeNumber("offset", size)
    val outgoing = requireNotNull(body["outgoing"]?.jsonPrimitive?.booleanOrNull)
    val event = body.string("event")
    require(event in setOf("offer", "progress", "complete", "cancelled", "error")) { "RD_FILE_INVALID" }
    require(previous != null || event == "offer") { "RD_FILE_STATE" }
    if (previous != null) {
        require(previous.id == id && previous.name == name && previous.size == size && previous.outgoing == outgoing &&
            !previous.terminal && offset >= previous.offset) { "RD_FILE_STATE" }
    }
    if (event == "complete") require(offset == size) { "RD_FILE_INCOMPLETE" }
    return RemoteFileItem(id, name, size, offset, outgoing,
        if (event == "complete" && !outgoing) "ready_to_save" else event,
        body["error_code"]?.jsonPrimitive?.content?.takeIf { Regex("RD_[A-Z0-9_]{1,72}").matches(it) })
}

/** Owned by one authenticated session, serialized on Main. File I/O runs on IO. */
internal class RemoteNativeFiles(
    context: Context,
    private val scope: CoroutineScope,
    private val owner: () -> RemoteNativeSession?,
    private val current: () -> Boolean,
    private val enabled: (String) -> Boolean,
    private val changed: (List<RemoteFileItem>, Boolean) -> Unit,
    private val failed: (String) -> Unit,
) : Closeable {
    private val resolver = context.contentResolver
    private val directory = File(context.noBackupFilesDir, "remote-files-${UUID.randomUUID()}")
    private val items = linkedMapOf<String, RemoteFileItem>()
    private val incoming = mutableMapOf<String, File>()
    private val outgoingCopies = mutableListOf<File>()
    private val exports = mutableMapOf<String, Job>()
    private var sendJob: Job? = null
    private var expectedOffers = 0
    private var busy = false
    @Volatile private var closed = false

    init {
        cleanStale(context.noBackupFilesDir)
        check(directory.mkdir()) { "RD_FILE_WRITE_FAILED" }
        Os.chmod(directory.absolutePath, 448) // 0700, excluded from Android backup.
    }

    private fun notifyChanged() { if (!closed) changed(items.values.toList(), busy) }
    // Remote staging must fit in physically available storage without evicting
    // another app's caches. Each accepted transfer also reserves its remaining bytes.
    private fun stagingFreeBytes(): Long = StatFs(directory.absolutePath).availableBytes
    fun event(body: JsonObject) {
        if (closed) return
        if (body["id"] == null && body["event"]?.jsonPrimitive?.content == "error") {
            failed(body["error_code"]?.jsonPrimitive?.content ?: "RD_FILE_READ_FAILED")
            return
        }
        val id = body.string("id")
        val previous = items[id]
        // A locally cancelled item may be followed by its native cancellation ACK.
        if (previous?.status == "cancelled") return
        if (body.string("event") == "offer" && (!current() ||
                body["outgoing"]?.jsonPrimitive?.booleanOrNull == true && expectedOffers == 0)) return
        val item = nativeFileItem(body, previous)
        if (!item.outgoing && item.status == "ready_to_save") {
            check(incoming[id]?.length() == item.size) { "RD_FILE_INCOMPLETE" }
        }
        if (item.outgoing && item.status == "offer") {
            check(expectedOffers > 0) { "RD_FILE_STATE" }; expectedOffers--
        }
        items[id] = item
        if (!item.outgoing && item.status in setOf("cancelled", "error")) incoming.remove(id)?.delete()
        if (expectedOffers == 0 && items.values.none { it.outgoing && !it.terminal }) clearOutgoingCopies()
        while (items.size > 128) {
            val old = items.values.firstOrNull { it.terminal && it.status != "ready_to_save" && it.id !in exports } ?: break
            items.remove(old.id)
        }
        notifyChanged()
    }

    fun send(selection: List<RemoteSelectedFile>) {
        check(!closed && current() && enabled("files.send") && !busy) { "RD_FILE_TRANSPORT_UNAVAILABLE" }
        require(selection.size in 1..P.BATCH_FILES)
        busy = true; notifyChanged()
        sendJob = scope.launch {
            try {
                remoteDocumentIo { cancellation ->
                    val descriptors = mutableListOf<ParcelFileDescriptor>()
                    val copies = mutableListOf<File>()
                    var retained = false
                    try {
                        var total = 0L
                        for (file in selection) {
                            currentCoroutineContext().ensureActive()
                            check(!closed) { "RD_FILE_CANCELLED" }
                            RemoteFiles.safeName(file.name)
                            val descriptor = requireNotNull(resolver.openFileDescriptor(file.uri, "r", cancellation)) { "RD_FILE_UNAVAILABLE" }
                            descriptors += descriptor
                            val state = Os.fstat(descriptor.fileDescriptor)
                            if (OsConstants.S_ISREG(state.st_mode) && state.st_size >= 0) {
                                require(state.st_size <= P.FILE_BYTES && state.st_size <= P.BATCH_FILE_BYTES - total) { "RD_FILE_TOO_LARGE" }
                                total += state.st_size
                            } else {
                                val copy = File.createTempFile("source-", ".part", directory)
                                copies += copy; Os.chmod(copy.absolutePath, 384) // 0600.
                                var length = 0L
                                descriptor.use {
                                    // Cloud providers often return pipes. Poll nonblocking reads
                                    // so cancellation cannot strand a worker inside InputStream.read.
                                    nonBlockingDocument(descriptor)
                                    val poll = StructPollfd().apply {
                                        fd = descriptor.fileDescriptor
                                        events = (OsConstants.POLLIN or OsConstants.POLLHUP).toShort()
                                    }
                                    copy.outputStream().use { output ->
                                        val buffer = ByteArray(64 * 1024)
                                        var lastData = SystemClock.elapsedRealtime()
                                        while (true) {
                                            currentCoroutineContext().ensureActive()
                                            check(!closed && SystemClock.elapsedRealtime() - lastData < 60_000) { "RD_FILE_READ_FAILED" }
                                            if (Os.poll(arrayOf(poll), 100) == 0) continue
                                            val count = try { Os.read(descriptor.fileDescriptor, buffer, 0, buffer.size) }
                                            catch (error: ErrnoException) {
                                                if (error.errno == OsConstants.EAGAIN || error.errno == OsConstants.EINTR) continue
                                                throw error
                                            }
                                            if (count == 0) break
                                            lastData = SystemClock.elapsedRealtime()
                                            require(count <= P.FILE_BYTES - length && count <= P.BATCH_FILE_BYTES - total) { "RD_FILE_TOO_LARGE" }
                                            check(stagingFreeBytes() > count + 8L * 1024 * 1024) { "RD_FILE_STORAGE_FULL" }
                                            output.write(buffer, 0, count); length += count; total += count
                                        }
                                    }
                                }
                                descriptors.remove(descriptor)
                                descriptors += ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY)
                            }
                        }
                        withContext(Dispatchers.Main.immediate) {
                            check(!closed && current() && enabled("files.send")) { "RD_FILE_CANCELLED" }
                            val previousOffers = expectedOffers
                            expectedOffers += descriptors.size
                            try { requireNotNull(owner()).offerFiles(descriptors.map { it.fd }.toIntArray(), selection.map { it.name }) }
                            catch (error: Exception) { expectedOffers = previousOffers; throw error }
                            outgoingCopies += copies; retained = true
                        }
                    } finally {
                        descriptors.forEach { it.close() }
                        if (!retained) copies.forEach { it.delete() }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (!closed) failed(fileError(error, "RD_FILE_READ_FAILED")) }
            finally { busy = false; notifyChanged(); if (closed) directory.delete() }
        }
    }

    fun accept(id: String) {
        check(!closed && current() && enabled("files.receive")) { "RD_FILE_TRANSPORT_UNAVAILABLE" }
        val item = requireNotNull(items[id])
        check(!item.outgoing && item.status == "offer" && id !in incoming) { "RD_FILE_STATE" }
        check(incoming.size < P.BATCH_FILES) { "RD_FILE_LIMIT" }
        val reserved = items.values.filter { it.id in incoming && !it.terminal }.sumOf { it.size - it.offset }
        check(stagingFreeBytes() - reserved - 8L * 1024 * 1024 >= item.size) { "RD_FILE_STORAGE_FULL" }
        val file = File.createTempFile("receive-", ".part", directory)
        try {
            Os.chmod(file.absolutePath, 384)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use {
                requireNotNull(owner()).acceptFile(id, it.fd)
            }
            incoming[id] = file
            items[id] = item.copy(status = "progress")
            notifyChanged()
        } catch (error: Exception) { file.delete(); throw error }
    }

    fun cancel(id: String) {
        val item = items[id] ?: return
        exports.remove(id)?.cancel()
        if (!item.terminal) runCatching { owner()?.cancelFile(id) }
        incoming.remove(id)?.delete()
        items[id] = item.copy(status = "cancelled")
        if (expectedOffers == 0 && items.values.none { it.outgoing && !it.terminal }) clearOutgoingCopies()
        notifyChanged()
    }

    fun export(id: String, destination: Uri) {
        check(!closed && destination.scheme == "content" && id !in exports) { "RD_FILE_STATE" }
        val item = requireNotNull(items[id])
        check(item.status == "ready_to_save" && !item.outgoing) { "RD_FILE_INCOMPLETE" }
        val file = requireNotNull(incoming[id])
        items[id] = item.copy(status = "saving"); notifyChanged()
        exports[id] = scope.launch {
            var opened = false
            try {
                remoteDocumentIo { cancellation ->
                    requireNotNull(resolver.openFileDescriptor(destination, "wt", cancellation)).use { output ->
                        opened = true
                        exportRemoteFile(file, item.size, output)
                    }
                }
                if (!closed) { incoming.remove(id)?.delete(); items[id] = item.copy(status = "saved") }
            } catch (error: Exception) {
                // Destination comes only from CreateDocument. Remove this new
                // incomplete export; retain verified staging for a save retry.
                if (opened) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    runCatching { DocumentsContract.deleteDocument(resolver, destination) }
                }
                if (!closed && items[id]?.status != "cancelled") {
                    items[id] = item.copy(error = "RD_FILE_WRITE_FAILED")
                    if (error !is CancellationException) failed("RD_FILE_WRITE_FAILED")
                }
                if (error is CancellationException) throw error
            } finally { exports.remove(id); notifyChanged(); if (closed) directory.delete() }
        }
    }

    /** Call after native pause has synchronously cancelled native transfers. */
    fun pause() {
        sendJob?.cancel(); sendJob = null; expectedOffers = 0
        items.values.filter { !it.terminal && it.status != "saving" }.toList().forEach {
            incoming.remove(it.id)?.delete(); items[it.id] = it.copy(status = "cancelled")
        }
        clearOutgoingCopies(); notifyChanged()
    }
    private fun clearOutgoingCopies() { outgoingCopies.forEach { it.delete() }; outgoingCopies.clear() }
    override fun close() {
        if (closed) return
        closed = true; sendJob?.cancel(); exports.values.forEach { it.cancel() }
        incoming.values.forEach { it.delete() }; incoming.clear(); clearOutgoingCopies()
        directory.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
        directory.delete()
    }
    private fun fileError(error: Exception, fallback: String) = error.message?.takeIf { Regex("RD_[A-Z0-9_]{1,72}").matches(it) } ?: fallback

    companion object {
        private var cleaned = false
        @Synchronized private fun cleanStale(root: File) {
            if (cleaned) return
            cleaned = true
            root.listFiles()?.filter { Regex("remote-files-[0-9a-f-]{36}").matches(it.name) && it.canonicalFile.parentFile == root.canonicalFile }?.forEach { folder ->
                folder.listFiles()?.filter { it.isFile && it.canonicalFile.parentFile == folder.canonicalFile }?.forEach { it.delete() }
                folder.delete()
            }
        }
    }
}
