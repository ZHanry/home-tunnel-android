package io.github.zhanry.hometunnel.network

import io.github.zhanry.hometunnel.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

data class AvailableUpdate(val version: String, val url: String)

object ReleaseUpdates {
    private const val endpoint = "https://api.github.com/repos/ZHanry/home-tunnel-android/releases/latest"
    private val stableVersion = Regex("^v?([0-9]+)\\.([0-9]+)\\.([0-9]+)$")
    private val currentVersion = Regex("^([0-9]+)\\.([0-9]+)\\.([0-9]+)(?:-rc\\.[1-9][0-9]*)?(?:-debug)?$")
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun check(): AvailableUpdate? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(endpoint)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "HomeTunnel-Android/${BuildConfig.VERSION_NAME}")
            .get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Update check returned HTTP ${response.code}")
            val body = response.body ?: throw IOException("Update check returned no data")
            if (body.contentLength() > 64 * 1024) throw IOException("Update response is too large")
            val bytes = body.byteStream().use { stream ->
                val output = ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                while (true) {
                    val count = stream.read(chunk)
                    if (count < 0) break
                    output.write(chunk, 0, count)
                    if (output.size() > 64 * 1024) throw IOException("Update response is too large")
                }
                output.toByteArray()
            }
            if (bytes.size > 64 * 1024) throw IOException("Update response is too large")
            parse(bytes.decodeToString(), BuildConfig.VERSION_NAME)
        }
    }

    internal fun parse(payload: String, installed: String): AvailableUpdate? {
        val release = Json.parseToJsonElement(payload) as? JsonObject
            ?: throw IOException("Invalid release response")
        if (release["draft"]?.jsonPrimitive?.booleanOrNull != false ||
            release["prerelease"]?.jsonPrimitive?.booleanOrNull != false) return null
        val tag = release["tag_name"]?.jsonPrimitive?.content ?: throw IOException("Release has no version")
        val latest = stableVersion.matchEntire(tag) ?: return null
        val current = currentVersion.matchEntire(installed) ?: return null
        val latestNumbers = (1..3).map { latest.groupValues[it].toIntOrNull() ?: return null }
        val currentNumbers = (1..3).map { current.groupValues[it].toIntOrNull() ?: return null }
        val comparison = latestNumbers.zip(currentNumbers).firstOrNull { it.first != it.second }
        if (comparison != null && comparison.first < comparison.second) return null
        if (comparison == null && !installed.contains("-rc.")) return null
        val version = latest.groupValues.drop(1).joinToString(".")
        return AvailableUpdate(version, "https://github.com/ZHanry/home-tunnel-android/releases/tag/v$version")
    }
}
