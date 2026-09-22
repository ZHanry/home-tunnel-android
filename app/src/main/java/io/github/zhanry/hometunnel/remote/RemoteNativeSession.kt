package io.github.zhanry.hometunnel.remote

import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.annotation.Keep
import java.io.Closeable

data class NativeRemoteCapability(val available: Boolean, val reason: String, val maxSessions: Int = 0, val permissions: Set<String> = emptySet())

/** Optional verified shared core. A missing backend is an explicit product state, never success. */
@Keep
class RemoteNativeSession(private val event: (Int, Int, Long, ByteArray) -> Unit) : Closeable {
    private val main = Handler(Looper.getMainLooper())
    private var handle = 0L
    private var generation = 0L
    private var closed = false
    private var started = false
    val capability: NativeRemoteCapability

    init {
        capability = if (!RemoteNativeBridge.loaded) NativeRemoteCapability(false, "RD_NATIVE_NOT_INSTALLED") else {
            try {
                check(RemoteNativeBridge.abi() == 1) { "RD_NATIVE_ABI_MISMATCH" }
                handle = RemoteNativeBridge.create(this)
                check(handle != 0L) { "RD_NATIVE_CREATE_FAILED" }
                val values = RemoteNativeBridge.capabilities(handle)
                require(values.size == 5)
                val available = values[0] == 1L && values[2] == 1L
                NativeRemoteCapability(available, if (available) "" else "RD_MEDIA_BACKEND_UNAVAILABLE", values[3].toInt().coerceIn(0, 4),
                    io.github.zhanry.hometunnel.remote.protocol.RemoteProtocol.permissions.filterIndexed { index, _ -> values[4] and (1L shl index) != 0L }.toSet())
            } catch (_: LinkageError) {
                NativeRemoteCapability(false, "RD_NATIVE_ABI_MISMATCH")
            } catch (_: RuntimeException) {
                NativeRemoteCapability(false, "RD_NATIVE_UNAVAILABLE")
            }
        }
    }
    @Synchronized fun start(context: ByteArray) {
        checkAvailable()
        require(context.size in 1..64 * 1024)
        checkResult(RemoteNativeBridge.start(handle, context))
        started = true
    }
    @Synchronized fun signal(envelope: ByteArray) {
        checkAvailable(); require(envelope.size in 1..64 * 1024)
        checkResult(RemoteNativeBridge.signal(handle, envelope))
    }
    @Synchronized fun submit(message: ByteArray) {
        checkAvailable(); require(message.size in 24..8192)
        checkResult(RemoteNativeBridge.input(handle, message))
    }
    @Synchronized fun surface(surface: Surface?) {
        if (closed || !capability.available) return
        checkResult(RemoteNativeBridge.surface(handle, surface, ++generation))
    }
    @Synchronized fun pause() { if (!closed && handle != 0L) RemoteNativeBridge.pause(handle, 1) }
    @Synchronized fun resume() { if (!closed && started) signal("{\"type\":\"local.resume\"}".toByteArray()) }
    @Synchronized override fun close() {
        if (closed) return
        closed = true; generation++
        if (handle != 0L) {
            RemoteNativeBridge.close(handle, 1)
            RemoteNativeBridge.release(handle)
            handle = 0
        }
        main.removeCallbacksAndMessages(null)
    }
    @Keep fun onNativeEvent(type: Int, code: Int, nativeGeneration: Long, payload: ByteArray) {
        if (payload.size > 64 * 1024) return
        main.post { if (!closed) event(type, code, nativeGeneration, payload) }
    }
    private fun checkAvailable() { check(!closed && capability.available && handle != 0L) { capability.reason.ifBlank { "RD_NATIVE_CLOSED" } } }
    private fun checkResult(result: Int) { check(result == 0) { "RD_NATIVE_ERROR_$result" } }
}

@Keep
internal object RemoteNativeBridge {
    val loaded = try { System.loadLibrary("home_tunnel_remote_jni"); true } catch (_: LinkageError) { false }
    external fun abi(): Int
    external fun create(owner: RemoteNativeSession): Long
    external fun capabilities(handle: Long): LongArray
    external fun start(handle: Long, ticket: ByteArray): Int
    external fun signal(handle: Long, message: ByteArray): Int
    external fun input(handle: Long, message: ByteArray): Int
    external fun surface(handle: Long, surface: Surface?, generation: Long): Int
    external fun pause(handle: Long, reason: Int): Int
    external fun close(handle: Long, reason: Int): Int
    external fun release(handle: Long)
}
