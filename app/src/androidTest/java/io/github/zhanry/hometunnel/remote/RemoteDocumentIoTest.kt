package io.github.zhanry.hometunnel.remote

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class RemoteDocumentIoTest {
    private fun directory(): File = File(ApplicationProvider.getApplicationContext<Context>().cacheDir,
        "document-io-${java.util.UUID.randomUUID()}").apply { check(mkdir()) }

    @Test fun nativeNonblockingDescriptorsWorkWithoutNewerJavaApis() {
        assertTrue(RemoteNativeBridge.loaded)
        assertEquals(-1, RemoteNativeBridge.nonBlocking(-1))
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            nonBlockingDocument(pipe[0])
            nonBlockingDocument(pipe[1])
            // The writer remains open, so a blocking read would hang here.
            try {
                Os.read(pipe[0].fileDescriptor, ByteArray(1), 0, 1)
                fail("An empty nonblocking pipe must return EAGAIN")
            } catch (error: ErrnoException) { assertEquals(OsConstants.EAGAIN, error.errno) }
            assertEquals(1, Os.write(pipe[1].fileDescriptor, byteArrayOf(42), 0, 1))
            val data = ByteArray(1)
            assertEquals(1, Os.read(pipe[0].fileDescriptor, data, 0, 1))
            assertEquals(42, data[0].toInt())
        } finally { pipe.forEach { it.close() } }
    }

    @Test fun emptyAndBinaryFilesAreSavedExactly() = runBlocking {
        val directory = directory()
        try {
            for (data in listOf(byteArrayOf(), ByteArray(512 * 1024) { (it % 251).toByte() })) {
                val source = File(directory, "报告.bin").apply { writeBytes(data) }
                val target = File(directory, "saved.bin")
                ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE).use {
                    withContext(Dispatchers.IO) { exportRemoteFile(source, data.size.toLong(), it) }
                }
                assertArrayEquals(data, target.readBytes())
                assertArrayEquals(data, source.readBytes())
            }
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }

    @Test fun cancellingAStalledUploadPipeReleasesTheCopyWorker() = runBlocking {
        val directory = directory()
        val source = File(directory, "source.bin").apply { writeBytes(ByteArray(1024 * 1024) { 42 }) }
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            val started = CompletableDeferred<Unit>()
            val operation = launch(Dispatchers.IO) {
                started.complete(Unit)
                exportRemoteFile(source, source.length(), pipe[1])
            }
            started.await(); delay(250)
            assertTrue(operation.isActive)
            withTimeout(2_000) { operation.cancelAndJoin() }
            assertTrue(source.exists()) // Verified staging remains available for a retry.
        } finally {
            pipe.forEach { it.close() }; source.delete(); directory.delete()
        }
    }

    @Test fun cancellingWhileAProviderIsOpeningSendsItsCancellationSignal() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CountDownLatch(1)
        val operation = launch {
            remoteDocumentIo { signal ->
                signal.setOnCancelListener { cancelled.countDown() }
                started.complete(Unit)
                cancelled.await()
            }
        }
        started.await()
        withTimeout(2_000) { operation.cancelAndJoin() }
        assertTrue(cancelled.count == 0L)
    }
}
