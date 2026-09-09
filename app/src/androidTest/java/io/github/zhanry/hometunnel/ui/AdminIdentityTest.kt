package io.github.zhanry.hometunnel.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zhanry.hometunnel.R
import io.github.zhanry.hometunnel.model.*
import io.github.zhanry.hometunnel.network.HomeTunnelApi
import io.github.zhanry.hometunnel.repository.AdminPage
import io.github.zhanry.hometunnel.repository.HomeTunnelRepository
import io.github.zhanry.hometunnel.storage.SecureStateStore
import io.github.zhanry.hometunnel.ui.theme.HomeTunnelTheme
import java.util.concurrent.atomic.AtomicReference
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Uses the real encrypted store, session manager, API parsing and role-dependent navigation. */
class AdminIdentityTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val actor = AtomicReference(UserInfo("owner", "admin", "Owner", "admin", "normal"))
    private var repository: HomeTunnelRepository? = null
    private val requests = java.util.concurrent.CopyOnWriteArrayList<String>()

    @After fun clear() = runBlocking<Unit> { repository?.clearLocalState() ?: SecureStateStore(context).clear() }

    private fun restore() {
        val store = SecureStateStore(context)
        runBlocking {
            store.clear()
            store.save(PersistedState(profile = ServerProfile("https://console.example.test", "https://console.example.test/api/v1/", "home.example", 7000, "home.example"),
                username = "admin", accessToken = "fixture-access", refreshToken = "fixture-refresh", accessExpiresAt = "2099-01-01T00:00:00Z"))
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            requests.add(path)
            val body = when (path) {
                "/api/v1/auth/me" -> Json.encodeToString(UserInfo.serializer(), actor.get())
                "/api/v1/admin/users" -> """{"items":[{"id":"member-1","username":"member","display_name":"Member","role":"user","status":"active","version":1}]}"""
                "/api/v1/admin/summary" -> """{"users":2,"online_devices":0,"connections":0}"""
                else -> """{"items":[]}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val restored = HomeTunnelRepository(context, store) { profile, sessions -> HomeTunnelApi(profile, sessions, client) }
        repository = restored
        compose.setContent { HomeTunnelTheme { HomeTunnelApp(restored) } }
        compose.waitUntil(5_000) { restored.uiState.value.currentUser != null }
    }

    @Test fun restoredAdministratorGetsManagementAndRoleChangesClearItsData() {
        restore()
        val restored = requireNotNull(repository)
        compose.onNodeWithText(context.getString(R.string.nav_management)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.nav_management)).performClick()
        compose.waitUntil(5_000) { restored.administration.state.value.summary != null && !restored.administration.state.value.loading }
        File(context.getExternalFilesDir(null), "admin-entry.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        assertTrue(requests.contains("/api/v1/auth/me"))
        val administrator = actor.get()
        for (restricted in listOf(administrator.copy(deviceId = "pc-1"), administrator.copy(passwordState = "must_change"), administrator.copy(role = "user"))) {
            actor.set(administrator)
            compose.runOnIdle { restored.refreshConnections() }
            compose.waitUntil(5_000) { restored.uiState.value.isAdmin && !restored.uiState.value.busy }
            compose.runOnIdle { restored.administration.open(AdminPage.USERS) }
            compose.waitUntil(5_000) { restored.administration.state.value.users.isNotEmpty() }
            actor.set(restricted)
            compose.runOnIdle { restored.refreshConnections() }
            compose.waitUntil(5_000) { restored.uiState.value.currentUser == restricted && !restored.uiState.value.busy }
            compose.onNodeWithText(context.getString(R.string.nav_management)).assertDoesNotExist()
            assertTrue(restored.administration.state.value.users.isEmpty())
            assertNull(restored.administration.state.value.issuedPassword)
        }
    }

    @Test fun anAdminUsernameDoesNotGrantAdministratorPermissions() {
        actor.set(actor.get().copy(role = "user"))
        restore()
        val restored = requireNotNull(repository)
        assertEquals("admin", restored.uiState.value.currentUser?.username)
        assertFalse(restored.uiState.value.isAdmin)
        compose.onNodeWithText(context.getString(R.string.nav_management)).assertDoesNotExist()
        assertFalse(requests.any { it.startsWith("/api/v1/admin/") })
    }
}
