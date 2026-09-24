package io.github.zhanry.hometunnel.remote

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zhanry.hometunnel.BuildConfig
import io.github.zhanry.hometunnel.model.ServerProfile
import io.github.zhanry.hometunnel.model.UserInfo
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in integration acceptance. No synthetic frames, callbacks, or authority are injected. */
@RunWith(AndroidJUnit4::class)
class RemoteSurfaceAcceptanceTest {
    @Test fun decodedSurfacePausesResumesAndStopsWithTheProductionController() = runBlocking {
        assumeTrue("Requires an explicitly configured, approved live test host",
            InstrumentationRegistry.getArguments().getString("remoteSurfaceAcceptance") == "true")
        assertTrue("Import a real controller SDK before media acceptance", BuildConfig.REMOTE_CONTROLLER_BACKEND)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtureFile = File(context.filesDir, "remote-surface-fixture.json")
        require(fixtureFile.length() in 1..16_384) { "RD_ACCEPTANCE_FIXTURE_REQUIRED" }
        val fixture = try { RemoteJson.parse(fixtureFile.readBytes(), 16_384) }
            finally { check(fixtureFile.delete()) { "RD_ACCEPTANCE_FIXTURE_CLEANUP" } }
        require(fixture["allow_test_pairing"] == JsonPrimitive(true)) { "RD_ACCEPTANCE_OPT_IN_REQUIRED" }
        val base = fixture.string("api_base_url").toHttpUrl().also {
            require(it.isHttps && it.encodedPath == "/api/v1/" && it.query == null && it.fragment == null &&
                it.username.isEmpty() && it.password.isEmpty()) { "RD_ACCEPTANCE_ORIGIN" }
        }
        val origin = base.newBuilder().encodedPath("/").build().toString().trimEnd('/')
        val parentHttp = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
        val parentRequest: suspend (String, String, JsonObject?) -> JsonObject = { method, path, body ->
            withContext(Dispatchers.IO) {
                require(path.startsWith("rd/") && !path.contains("..") && !path.contains('?') && !path.contains('#'))
                val request = Request.Builder().url(requireNotNull(base.resolve(path)))
                    .header("Authorization", "Bearer ${fixture.string("management_access_token")}")
                    .header("Accept", "application/json")
                if (method == "GET") request.get()
                else request.method(method, (body?.toString() ?: "{}").toRequestBody("application/json".toMediaType()))
                parentHttp.newCall(request.build()).execute().use { response ->
                    // Bound all responses and never include bodies/tokens in assertion messages.
                    val source = requireNotNull(response.body).source()
                    require(!source.request(64 * 1024L + 1)) { "RD_ACCEPTANCE_RESPONSE_TOO_LARGE" }
                    val value = RemoteJson.parse(source.readByteArray())
                    check(response.isSuccessful) { "RD_ACCEPTANCE_HTTP_${response.code}" }
                    value
                }
            }
        }
        val account = RemoteAccount(
            ServerProfile(origin, base.toString(), base.host, 7000, base.host),
            UserInfo(remoteUuid(fixture.string("user_id")), "surface-acceptance", "Surface acceptance", "user", "active"),
        )
        val controller = RemoteController(context, { account }, parentRequest)
        val frames = AtomicInteger()
        val variedFrames = AtomicInteger()
        val firstHash = AtomicReference<String>()
        val firstSize = AtomicReference<Pair<Int, Int>>()
        val distinctHashes = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val imageFailure = AtomicReference<Throwable>()
        val width = fixture.number("width", 4096).toInt()
        val height = fixture.number("height", 4096).toInt()
        val inputPoint = fixture["input_point"]?.jsonObject
        val accessMode = fixture["access_mode"]?.jsonPrimitive?.content ?: "same-account"
        require(accessMode in setOf("same-account", "cross-account-assist", "cross-account-fixed", "cross-account-request")) {
            "RD_ACCEPTANCE_ACCESS_MODE"
        }
        val crossAccount = accessMode != "same-account"
        require(width >= 64 && height >= 64)
        val images = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        val replacement = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        val replacementFrames = AtomicInteger()
        val imageThread = HandlerThread("remote-surface-acceptance").apply { start() }
        val evidenceFile = File(context.filesDir, "remote-surface-evidence.json")
        evidenceFile.delete()
        val imageListener = ImageReader.OnImageAvailableListener { reader ->
            try {
                reader.acquireLatestImage()?.use { image ->
                    require(image.width in maxOf(640, width / 2)..width && image.height in maxOf(360, height / 2)..height &&
                        kotlin.math.abs(image.width.toLong() * height - image.height.toLong() * width) <= width.toLong() * height / 100) {
                        "RD_ACCEPTANCE_FRAME_SIZE_${image.width}_${image.height}_${width}_${height}"
                    }
                    val plane = image.planes.single()
                    require(plane.pixelStride == 4 && plane.rowStride >= image.width * 4) { "RD_ACCEPTANCE_PIXEL_STRIDE" }
                    firstSize.compareAndSet(null, image.width to image.height)
                    val pixels = plane.buffer
                    val digest = MessageDigest.getInstance("SHA-256")
                    val colors = mutableSetOf<Int>()
                    // Sample a fixed grid; no desktop pixels are persisted to disk or test logs.
                    for (y in 0 until image.height step maxOf(1, image.height / 32)) {
                        for (x in 0 until image.width step maxOf(1, image.width / 32)) {
                            val offset = y * plane.rowStride + x * plane.pixelStride
                            val red = pixels.get(offset).toInt() and 255
                            val green = pixels.get(offset + 1).toInt() and 255
                            val blue = pixels.get(offset + 2).toInt() and 255
                            colors += red * 65536 + green * 256 + blue
                            digest.update(byteArrayOf(red.toByte(), green.toByte(), blue.toByte()))
                        }
                    }
                    val hash = RemoteCrypto.base64(digest.digest())
                    firstHash.compareAndSet(null, hash)
                    if (distinctHashes.size < 128) distinctHashes += hash
                    if (colors.size >= 8) variedFrames.incrementAndGet()
                    frames.incrementAndGet()
                    if (reader === replacement) replacementFrames.incrementAndGet()
                }
            } catch (error: Exception) {
                val reason = error.message?.takeIf { Regex("RD_ACCEPTANCE_[A-Z0-9_]{1,60}").matches(it) }
                    ?: if (error is IndexOutOfBoundsException) "RD_ACCEPTANCE_IMAGE_BUFFER" else "RD_ACCEPTANCE_IMAGE_READ_FAILED"
                imageFailure.compareAndSet(null, IllegalStateException(reason))
            }
        }
        images.setOnImageAvailableListener(imageListener, Handler(imageThread.looper))
        replacement.setOnImageAvailableListener(imageListener, Handler(imageThread.looper))
        try {
            withContext(Dispatchers.Main) {
                // Pin the explicitly selected fixture server before any account mutation.
                val probe = RemoteApi(base.toString(), parentRequest)
                val keys = probe.serverKeys()
                require(keys["server_instance_id"] == fixture["server_instance_id"] &&
                    keys["active_kid"] == fixture["server_active_kid"]) { "RD_ACCEPTANCE_SERVER_IDENTITY" }
                operation(controller) { controller.refresh() }
                assertTrue(controller.state.value.native.available)
                operation(controller) { controller.authenticate(fixture.string("password"), fixture.string("mfa_code")) }
                val permissions = if (inputPoint == null) setOf("view") else setOf("view", "input.keyboard", "input.pointer", "input.text")
                val host = if (crossAccount) {
                    operation(controller) {
                        when (accessMode) {
                            "cross-account-assist" -> controller.assist(fixture.string("assist_device_id"), fixture.string("assist_temporary_password"), permissions)
                            "cross-account-fixed" -> controller.fixedPassword(fixture.string("access_device_id"), fixture.string("fixed_password"), permissions)
                            else -> controller.requestAccess(fixture.string("access_device_id"), permissions)
                        }
                    }
                    requireNotNull(controller.state.value.assistTarget)
                } else {
                    controller.state.value.endpoints.single { it.id == fixture.string("host_endpoint_id") }.also {
                        operation(controller) { controller.pair(it, permissions) }
                    }
                }
                require(host.available && host.id == fixture.string("host_endpoint_id") && host.fingerprint == fixture.string("host_jkt") &&
                    (host.ownerUserId != fixture.string("user_id")) == crossAccount) { "RD_ACCEPTANCE_HOST_IDENTITY" }
                withTimeout(60_000) {
                    while (controller.state.value.pairingCode == null) {
                        check(controller.state.value.error == null) { controller.state.value.error ?: "RD_ACCEPTANCE_PAIRING" }
                        delay(500)
                    }
                }
                val pairingId = requireNotNull(controller.state.value.pairingId)
                val pairingCode = requireNotNull(controller.state.value.pairingCode)
                File(context.filesDir, "remote-surface-pairing.json").writeText(buildJsonObject {
                    put("pairing_id", pairingId); put("comparison_code", pairingCode)
                    put("host_jkt", host.fingerprint); put("controller_jkt", controller.state.value.fingerprint)
                }.toString())
                withTimeout(60_000) {
                    while (controller.state.value.sessionId == null) {
                        check(controller.state.value.error == null) { controller.state.value.error ?: "RD_ACCEPTANCE_PAIRING" }
                        delay(500)
                    }
                }
                controller.setSurface(images.surface)
                awaitFrames(controller, imageFailure, frames, 30, "INITIAL")
                assertEquals("active", controller.state.value.phase)
                assertTrue("Use a moving test pattern with at least eight colors", variedFrames.get() >= 30)
                assertTrue("The decoded test pattern must change over time", distinctHashes.size >= 2)

                if (inputPoint != null) {
                    controller.requestControl()
                    withTimeout(10_000) {
                        while (!controller.state.value.inputEnabled) {
                            check(controller.state.value.error == null) { controller.state.value.error ?: "RD_ACCEPTANCE_INPUT" }
                            delay(50)
                        }
                    }
                    val pointerX = inputPoint.nonNegativeNumber("x", width.toLong() - 1).toFloat()
                    val pointerY = inputPoint.nonNegativeNumber("y", height.toLong() - 1).toFloat()
                    check(controller.canUse("input.pointer")) { "RD_ACCEPTANCE_POINTER_PERMISSION" }
                    val layout = requireNotNull(controller.state.value.display) { "RD_ACCEPTANCE_POINTER_LAYOUT" }
                    check(RemoteWire.point(pointerX, pointerY, width, height, layout.width, layout.height) != null) { "RD_ACCEPTANCE_POINTER_COORDINATE" }
                    check(controller.pointer(pointerX, pointerY, width, height, true)) { "RD_ACCEPTANCE_POINTER_DOWN" }
                    check(controller.pointer(pointerX, pointerY, width, height, false)) { "RD_ACCEPTANCE_POINTER_UP" }
                    controller.key(4, true)
                    controller.key(4, false)
                    controller.submitText("验收✓")
                    withTimeout(10_000) {
                        while (controller.state.value.textStatus == "pending") {
                            check(controller.state.value.error == null) { controller.state.value.error ?: "RD_ACCEPTANCE_TEXT" }
                            delay(50)
                        }
                    }
                    assertEquals("confirmed", controller.state.value.textStatus)
                    controller.releaseControl()
                }

                controller.onBackground()
                delay(750) // Drain frames already queued before the pause reached the renderer.
                val pausedAt = frames.get()
                delay(1500)
                assertEquals("Background rendering must stop", pausedAt, frames.get())
                controller.onForeground()
                awaitFrames(controller, imageFailure, frames, pausedAt + 10, "RESUME")
                assertEquals("active", controller.state.value.phase)

                controller.setSurface(replacement.surface)
                assertEquals("connecting", controller.state.value.phase)
                assertTrue("Replacement must release control", !controller.state.value.inputEnabled)
                awaitFrames(controller, imageFailure, replacementFrames, 10, "REPLACEMENT")
                assertEquals("active", controller.state.value.phase)
                controller.setSurface(null)
                delay(750)
                val detachedAt = frames.get()
                delay(1500)
                assertEquals("Detached targets must stop rendering", detachedAt, frames.get())
                controller.setSurface(images.surface)
                awaitFrames(controller, imageFailure, frames, detachedAt + 10, "REATTACH")
                assertEquals("active", controller.state.value.phase)

                controller.closeSession()
                delay(750)
                val closedAt = frames.get()
                delay(1500)
                assertEquals("Closed sessions must stop rendering", closedAt, frames.get())
                assertEquals("idle", controller.state.value.phase)
                evidenceFile.writeText(buildJsonObject {
                    put("passed", true); put("frames_observed", closedAt); put("varied_frames", variedFrames.get())
                    put("distinct_sample_hashes", distinctHashes.size); put("first_sample_sha256", firstHash.get())
                    put("width", requireNotNull(firstSize.get()).first); put("height", requireNotNull(firstSize.get()).second)
                    put("source_width", width); put("source_height", height); put("background_stopped", true)
                    put("foreground_resumed", true); put("close_stopped", true)
                    put("direct_surface_replacement", true); put("replacement_frames", replacementFrames.get())
                    put("detach_stopped", true); put("reattached_presented", true)
                    put("native_backend", "linked-controller"); put("transport_policy", "verified-direct-udp")
                    put("access_mode", accessMode)
                    put("native_input_attempted", inputPoint != null)
                    put("app_version", BuildConfig.VERSION_NAME); put("android_api", android.os.Build.VERSION.SDK_INT)
                    put("device_manufacturer", android.os.Build.MANUFACTURER); put("device_model", android.os.Build.MODEL)
                }.toString())
            }
        } finally {
            withContext(Dispatchers.Main) {
                controller.closeSession()
                try { withTimeout(25_000) { while (controller.state.value.loading) delay(25) } }
                finally { controller.onAccountChanged() }
            }
            images.setOnImageAvailableListener(null, null)
            images.close()
            replacement.setOnImageAvailableListener(null, null)
            replacement.close()
            imageThread.quitSafely()
            imageThread.join(5000)
            parentHttp.dispatcher.executorService.shutdown()
            parentHttp.connectionPool.evictAll()
        }
    }

    private suspend fun operation(controller: RemoteController, action: () -> Unit) {
        action()
        withTimeout(30_000) { while (controller.state.value.loading) delay(25) }
        check(controller.state.value.error == null) { controller.state.value.error ?: "RD_ACCEPTANCE_OPERATION" }
    }

    private suspend fun awaitFrames(controller: RemoteController, failure: AtomicReference<Throwable>, frames: AtomicInteger, count: Int, stage: String) {
        val complete = withTimeoutOrNull(25_000) {
            while (frames.get() < count || controller.state.value.phase != "active") {
                check(failure.get() == null) { failure.get()?.message ?: "RD_ACCEPTANCE_IMAGE_READ_FAILED" }
                check(controller.state.value.error == null) { controller.state.value.error ?: "RD_ACCEPTANCE_SESSION" }
                check(controller.state.value.sessionId != null) { "RD_ACCEPTANCE_SESSION_CLOSED_$stage" }
                delay(50)
            }
            true
        }
        check(complete == true) { "RD_ACCEPTANCE_FRAMES_$stage" }
    }
}
