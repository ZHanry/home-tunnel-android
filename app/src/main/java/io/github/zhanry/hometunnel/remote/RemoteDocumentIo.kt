package io.github.zhanry.hometunnel.remote

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Cancelling the waiting coroutine also interrupts a provider's pending open. */
internal suspend fun <T> remoteDocumentIo(block: suspend (CancellationSignal) -> T): T = coroutineScope {
    val cancellation = CancellationSignal()
    val operation = async(Dispatchers.IO) { block(cancellation) }
    try { operation.await() }
    finally { cancellation.cancel(); operation.cancel() }
}

/** fcntl is an NDK API on every supported version; Java Os.fcntlInt is public only since API 30. */
internal fun nonBlockingDocument(descriptor: ParcelFileDescriptor) {
    check(RemoteNativeBridge.loaded && RemoteNativeBridge.nonBlocking(descriptor.fd) == 0) { "RD_FILE_DESCRIPTOR_FAILED" }
}

/** Providers may return upload pipes. Never block indefinitely in OutputStream.write. */
internal suspend fun exportRemoteFile(source: File, size: Long, destination: ParcelFileDescriptor) {
    check(source.length() == size) { "RD_FILE_INCOMPLETE" }
    val descriptor = destination.fileDescriptor
    val regular = OsConstants.S_ISREG(Os.fstat(descriptor).st_mode)
    nonBlockingDocument(destination)
    val poll = StructPollfd().apply { fd = descriptor; events = OsConstants.POLLOUT.toShort() }
    var total = 0L
    var lastData = SystemClock.elapsedRealtime()
    source.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            check(count <= size - total) { "RD_FILE_INCOMPLETE" }
            var offset = 0
            while (offset < count) {
                currentCoroutineContext().ensureActive()
                check(SystemClock.elapsedRealtime() - lastData < 60_000) { "RD_FILE_WRITE_FAILED" }
                if (!regular && Os.poll(arrayOf(poll), 100) == 0) continue
                val written = try { Os.write(descriptor, buffer, offset, count - offset) }
                catch (error: ErrnoException) {
                    if (error.errno == OsConstants.EAGAIN || error.errno == OsConstants.EINTR) continue
                    throw error
                }
                check(written > 0) { "RD_FILE_WRITE_FAILED" }
                offset += written; total += written; lastData = SystemClock.elapsedRealtime()
            }
        }
    }
    check(total == size && source.length() == size) { "RD_FILE_INCOMPLETE" }
    if (regular) Os.fsync(descriptor)
    destination.checkError()
}
