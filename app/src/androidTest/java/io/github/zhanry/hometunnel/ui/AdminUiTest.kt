package io.github.zhanry.hometunnel.ui

import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zhanry.hometunnel.R
import io.github.zhanry.hometunnel.model.*
import io.github.zhanry.hometunnel.network.AdministrationApi
import io.github.zhanry.hometunnel.repository.*
import io.github.zhanry.hometunnel.ui.theme.HomeTunnelTheme
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AdminUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val backend = AdminFixture()
    private var actor: UserInfo? = UserInfo("owner", "owner", "Owner", "admin", "normal")
    private val controller = AdminRepository({ backend }, { actor }, { actor = null })

    @After fun reset() { compose.runOnIdle { controller.reset() } }
    private fun text(id: Int) = context.getString(id)
    private fun open(page: AdminPage = AdminPage.OVERVIEW, largeText: Boolean = false) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeText) 2f else density.fontScale)) {
                HomeTunnelTheme { Surface(Modifier.fillMaxSize()) { AdminWorkspace(controller, "https://console.example.test") } }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { controller.open(page) }
        compose.waitUntil(5_000) { !controller.state.value.loading }
    }
    private fun click(id: Int) { compose.onAllNodesWithText(text(id)).onLast().performClick() }
    private fun reveal(id: Int) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(id)))
        compose.onAllNodesWithText(text(id)).onLast().performScrollTo().performClick()
    }
    private fun openMember() {
        compose.runOnIdle { controller.openUser("member-1") }
        compose.waitUntil(5_000) { controller.state.value.user != null && !controller.state.value.loading }
    }
    private fun capture(name: String) {
        val file = File(context.getExternalFilesDir(null), "admin-$name.png")
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun userLifecycleShowsOneTimePasswordsAndAppliesAccountChanges() {
        open(AdminPage.USERS)
        capture("users")
        click(R.string.admin_new_user)
        compose.onNodeWithText(text(R.string.admin_username)).performTextInput("newmember")
        compose.onNodeWithText(text(R.string.admin_display_name)).performTextInput("New member")
        click(R.string.admin_create)
        compose.waitUntil(5_000) { controller.state.value.issuedPassword != null }
        assertEquals("newmember", controller.state.value.issuedPassword?.username)
        click(R.string.admin_password_saved)
        compose.waitUntil(5_000) { controller.state.value.issuedPassword == null }
        openMember()
        click(R.string.admin_edit)
        compose.onNodeWithText(text(R.string.admin_display_name)).performTextReplacement("Updated member")
        click(R.string.save)
        compose.waitUntil(5_000) { controller.state.value.user?.displayName == "Updated member" }
        reveal(R.string.admin_disable)
        click(R.string.admin_disable)
        compose.waitUntil(5_000) { controller.state.value.user?.status == "disabled" }
        reveal(R.string.admin_enable)
        click(R.string.admin_enable)
        compose.waitUntil(5_000) { controller.state.value.user?.status == "active" }
        reveal(R.string.admin_reset)
        click(R.string.admin_reset)
        compose.waitUntil(5_000) { controller.state.value.issuedPassword != null }
        click(R.string.admin_password_saved)
        compose.waitUntil(5_000) { controller.state.value.issuedPassword == null }
        assertTrue(backend.operations.containsAll(listOf("create", "edit", "disable", "enable", "reset")))
    }

    @Test fun deleteRequiresTheExactUsernameAndPreservesConflictUntilReviewed() {
        open(AdminPage.USERS)
        openMember()
        reveal(R.string.admin_delete)
        compose.onAllNodesWithText(text(R.string.admin_delete)).onLast().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.admin_delete_confirmation)).performTextInput("wrong")
        compose.onAllNodesWithText(text(R.string.admin_delete)).onLast().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.admin_delete_confirmation)).performTextReplacement("member")
        backend.conflictOnDelete = true
        click(R.string.admin_delete)
        compose.waitUntil(5_000) { controller.state.value.errorCode == "VERSION_CONFLICT" }
        assertTrue(backend.users.any { it.id == "member-1" })
        compose.onNodeWithText(text(R.string.admin_delete_confirmation)).assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.admin_load_latest)).onLast().performScrollTo().performClick()
        compose.waitUntil(5_000) { controller.state.value.errorCode == null && !controller.state.value.loading }
        click(R.string.admin_delete)
        compose.waitUntil(5_000) { controller.state.value.page == AdminPage.USERS && !controller.state.value.saving }
        assertFalse(backend.users.any { it.id == "member-1" })
        assertEquals(listOf(12L, 13L), backend.deleteVersions)
    }

    @Test fun soleAdministratorIsProtectedAndSettingsRequireAnExplicitSave() {
        open()
        compose.runOnIdle { controller.openUser("owner") }
        compose.waitUntil(5_000) { controller.state.value.user?.id == "owner" }
        compose.onNodeWithText(text(R.string.admin_delete)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.admin_disable)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.admin_reset)).assertDoesNotExist()
        compose.runOnIdle { controller.open(AdminPage.SETTINGS) }
        compose.waitUntil(5_000) { controller.state.value.settings != null && !controller.state.value.loading }
        capture("settings")
        compose.onNodeWithText(text(R.string.save)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.admin_prefix_enforce)).performScrollTo().performClick()
        compose.onNodeWithContentDescription(text(R.string.admin_raw_permission)).performScrollTo().performClick()
        assertEquals(AdminSettings("suggest", false), backend.settings)
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithContentDescription(text(R.string.admin_back)).performClick()
        click(R.string.admin_discard)
        compose.waitUntil(5_000) { controller.state.value.page == AdminPage.OVERVIEW }
        compose.runOnIdle { controller.open(AdminPage.SETTINGS) }
        compose.waitUntil(5_000) { !controller.state.value.loading }
        compose.onNodeWithContentDescription(text(R.string.admin_raw_permission)).performScrollTo().assertIsOff()
        compose.onNodeWithText(text(R.string.admin_prefix_enforce)).performScrollTo().performClick()
        compose.onNodeWithContentDescription(text(R.string.admin_raw_permission)).performScrollTo().performClick()
        reveal(R.string.save)
        compose.waitUntil(5_000) { backend.settings == AdminSettings("enforce", true) }
        backend.settings = AdminSettings("suggest", null)
        compose.runOnIdle { controller.refresh() }
        compose.waitUntil(5_000) { controller.state.value.settings?.clientRawTunnelsEnabled == null && !controller.state.value.loading }
        compose.onNodeWithText(text(R.string.admin_raw_upgrade)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.admin_raw_permission)).assertDoesNotExist()
    }

    @Test fun roleRevocationClearsAdministrativeDataAndBlocksFurtherWrites() {
        open(AdminPage.USERS)
        assertTrue(controller.state.value.users.isNotEmpty())
        backend.deny = true
        compose.runOnIdle { controller.refresh() }
        compose.waitUntil(5_000) { controller.state.value.errorCode == "FORBIDDEN" }
        assertTrue(controller.state.value.users.isEmpty())
        assertNull(controller.state.value.issuedPassword)
        assertNull(controller.state.value.settings)
        actor = UserInfo("ordinary", "admin", "Ordinary", "user", "normal")
        compose.runOnIdle { controller.createUser("blocked", "Blocked") {} }
        compose.waitUntil(5_000) { !controller.state.value.saving }
        assertFalse(backend.operations.contains("create"))
        actor = UserInfo("owner", "owner", "Owner", "admin", "normal", "device-bound")
        compose.runOnIdle { controller.createUser("blocked", "Blocked") {} }
        compose.waitUntil(5_000) { !controller.state.value.saving }
        assertFalse(backend.operations.contains("create"))
    }

    @Test fun userScopeAndPaginationStayExplicitWithLargeText() {
        open(AdminPage.USERS, largeText = true)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Member"))
        compose.onNodeWithText("Member").performScrollTo().assertIsDisplayed()
        capture("large-text")
        openMember()
        reveal(R.string.admin_view_connections)
        compose.waitUntil(5_000) { controller.state.value.connections != null && !controller.state.value.loading }
        assertEquals("member-1", backend.lastOwner)
        reveal(R.string.admin_next)
        compose.waitUntil(5_000) { controller.state.value.connections?.page == 2 }
        assertEquals("member-1", backend.lastOwner)
        assertEquals(2, backend.lastPage)
    }
}

private class AdminFixture : AdministrationApi {
    val users = mutableListOf(
        AdminUser("owner", "owner", "Owner", "admin", "active", version = 1),
        AdminUser("member-1", "member", "Member", "user", "active", version = 12, deviceCount = 2, connectionCount = 3),
    )
    val operations = mutableListOf<String>()
    val deleteVersions = mutableListOf<Long>()
    var settings = AdminSettings("suggest", false)
    var conflictOnDelete = false
    var deny = false
    var lastOwner = ""
    var lastPage = 1
    private fun checkAccess() { if (deny) throw ApiException(403, "FORBIDDEN", "Denied") }
    override suspend fun adminSummary() = AdminSummary(2, 1, 3, 2)
    override suspend fun adminUsers(search: String): AdminUserList { checkAccess(); return AdminUserList(users.filter { search.isEmpty() || it.username.contains(search) || it.displayName.contains(search) }) }
    override suspend fun adminUser(id: String): AdminUser { checkAccess(); return users.single { it.id == id } }
    override suspend fun adminCreateUser(username: String, displayName: String): AdminPasswordResponse {
        operations.add("create")
        val user = AdminUser("created", username, displayName, "user", "active", "must_change", 1)
        users.add(user)
        return AdminPasswordResponse(user, "fixture-temporary-password")
    }
    private fun replace(user: AdminUser): AdminUser { users[users.indexOfFirst { it.id == user.id }] = user; return user }
    override suspend fun adminUpdateUser(id: String, displayName: String, version: Long): AdminUser {
        operations.add("edit"); return replace(adminUser(id).copy(displayName = displayName, version = version + 1))
    }
    override suspend fun adminSetUserEnabled(id: String, enabled: Boolean): AdminUser {
        operations.add(if (enabled) "enable" else "disable")
        val user = adminUser(id); return replace(user.copy(status = if (enabled) "active" else "disabled", version = user.version + 1))
    }
    override suspend fun adminResetPassword(id: String): AdminPasswordResponse {
        operations.add("reset"); val user = adminUser(id); replace(user.copy(passwordState = "must_change", version = user.version + 1))
        return AdminPasswordResponse(temporaryPassword = "fixture-reset-password", expiresInSeconds = 3600)
    }
    override suspend fun adminDeleteUser(id: String, version: Long) {
        deleteVersions.add(version)
        if (conflictOnDelete) {
            conflictOnDelete = false
            replace(adminUser(id).copy(version = version + 1))
            throw ApiException(409, "VERSION_CONFLICT", "Changed elsewhere")
        }
        check(version == adminUser(id).version)
        operations.add("delete"); users.removeAll { it.id == id }
    }
    override suspend fun adminDevices(userId: String) = AdminDeviceList(listOf(AdminDevice("device", userId, "member", "Home NAS", "active", true)))
    override suspend fun adminConnections(userId: String, search: String, page: Int): AdminConnectionList {
        lastOwner = userId; lastPage = page
        return AdminConnectionList(listOf(AdminConnection("camera-$page", "Camera $page", "member", userId, "device", "tcp", publicEndpoint = "home.example:11000")), 30, page, 2)
    }
    override suspend fun adminSettings() = settings
    override suspend fun adminSaveSettings(settings: AdminSettings): AdminSettings { operations.add("settings"); this.settings = settings; return settings }
    override suspend fun adminHealth() = AdminHealth("healthy", listOf(HealthComponent("control-center", "healthy", "6.1.1")))
    override suspend fun adminAudit(page: Int) = AdminAuditList(listOf(AdminAuditEvent(1, "UserCreated", "owner", "User", "member-1", "2026-09-09T12:00:00Z")), page, 1)
}
