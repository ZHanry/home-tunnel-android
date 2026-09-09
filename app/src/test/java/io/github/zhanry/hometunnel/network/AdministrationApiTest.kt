package io.github.zhanry.hometunnel.network

import io.github.zhanry.hometunnel.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AdministrationApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: HomeTunnelApi
    private val user = """{"id":"member-1","username":"member","display_name":"Member","role":"user","status":"active","password_state":"normal","version":12,"device_count":2,"connection_count":3,"month_to_date_bytes":4096}"""

    @Before fun start() {
        server = MockWebServer().apply { start() }
        val profile = ServerProfile(server.url("/").toString(), server.url("/api/v1/").toString(), "home.example", 7000, "home.example")
        api = HomeTunnelApi(profile).apply { restoreSession("fixture-access", "fixture-refresh", "2099-01-01T00:00:00Z") }
    }
    @After fun stop() { server.shutdown() }
    private fun respond(body: String, code: Int = 200) { server.enqueue(MockResponse().setResponseCode(code).setBody(body).setHeader("Content-Type", "application/json")) }

    @Test fun `current identity restores server role and distinguishes device sessions`() = runBlocking {
        respond("""{"id":"root","username":"owner","display_name":"Owner","role":"admin","password_state":"normal","device_id":null}""")
        assertEquals("admin", api.currentUser().role)
        assertEquals("/api/v1/auth/me", server.takeRequest().path)
        respond("""{"id":"root","username":"owner","display_name":"Owner","role":"admin","password_state":"normal","device_id":"pc-1"}""")
        assertEquals("pc-1", api.currentUser().deviceId)
    }

    @Test fun `create uses bearer authentication and always creates a regular user`() = runBlocking {
        respond("""{"user":$user,"temporary_password":"fixture-temporary"}""", 201)
        val result = api.adminCreateUser(" member ", " Member ")
        assertEquals("fixture-temporary", result.temporaryPassword)
        assertFalse(result.toString().contains("fixture-temporary"))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/admin/users", request.path)
        assertEquals("Bearer fixture-access", request.getHeader("Authorization"))
        assertEquals(buildJsonObject { put("username", "member"); put("display_name", "Member"); put("role", "user") }, Json.parseToJsonElement(request.body.readUtf8()))
    }

    @Test fun `edit and delete use the reviewed user version`() = runBlocking {
        respond(user)
        assertEquals(12L, api.adminUpdateUser("member-1", "Updated", 12).version)
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("\"12\"", patch.getHeader("If-Match"))
        assertEquals("12", Json.parseToJsonElement(patch.body.readUtf8()).jsonObject["expected_version"]?.jsonPrimitive?.content)
        respond("", 204)
        api.adminDeleteUser("member-1", 12)
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("/api/v1/admin/users/member-1", delete.path)
        assertEquals("\"12\"", delete.getHeader("If-Match"))
    }

    @Test fun `enable disable and password reset preserve endpoint semantics`() = runBlocking {
        respond(user, 202)
        api.adminSetUserEnabled("member-1", false)
        assertEquals("/api/v1/admin/users/member-1/disable", server.takeRequest().path)
        respond(user, 202)
        api.adminSetUserEnabled("member-1", true)
        assertEquals("/api/v1/admin/users/member-1/enable", server.takeRequest().path)
        respond("""{"temporary_password":"new-fixture-password","expires_in_seconds":3600}""")
        assertEquals(3600L, api.adminResetPassword("member-1").expiresInSeconds)
        assertEquals("/api/v1/admin/users/member-1/reset-password", server.takeRequest().path)
    }

    @Test fun `older servers omit the unsupported raw connection setting`() = runBlocking {
        respond("""{"subdomain_prefix_policy":"suggest"}""")
        val settings = api.adminSettings()
        assertNull(settings.clientRawTunnelsEnabled)
        server.takeRequest()
        respond("""{"subdomain_prefix_policy":"enforce"}""")
        api.adminSaveSettings(settings.copy(prefixPolicy = "enforce"))
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(setOf("subdomain_prefix_policy"), body.keys)
    }

    @Test fun `raw permission updates keep the chosen prefix policy`() = runBlocking {
        respond("""{"subdomain_prefix_policy":"enforce","client_raw_tunnels_enabled":true}""")
        val updated = api.adminSaveSettings(AdminSettings("enforce", true))
        assertTrue(updated.clientRawTunnelsEnabled == true)
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("enforce", body["subdomain_prefix_policy"]?.jsonPrimitive?.content)
        assertEquals(true, body["client_raw_tunnels_enabled"]?.jsonPrimitive?.boolean)
    }

    @Test fun `search is encoded and administrative lists retain scope and pagination`() = runBlocking {
        respond("""{"items":[$user]}""")
        assertEquals(4096L, api.adminUsers(" a&role=admin ").items.single().monthToDateBytes)
        assertEquals("a&role=admin", server.takeRequest().requestUrl?.queryParameter("search"))
        respond("""{"items":[],"page":2,"total_pages":3}""")
        assertEquals(2, api.adminConnections("member-1", "camera", 2).page)
        val request = server.takeRequest()
        assertEquals("member-1", request.requestUrl?.queryParameter("user_id"))
        assertEquals("2", request.requestUrl?.queryParameter("page"))
        respond("""{"items":[],"page":2,"total_pages":4}""")
        assertEquals(4, api.adminAudit(2).totalPages)
    }

    @Test fun `forbidden and stale versions are surfaced without a write retry`() = runBlocking {
        respond("""{"error_code":"FORBIDDEN","message":"Not an administrator"}""", 403)
        val denied = runCatching { api.adminUsers("") }.exceptionOrNull() as ApiException
        assertEquals(403, denied.statusCode)
        respond("""{"error_code":"VERSION_CONFLICT","message":"Changed elsewhere"}""", 409)
        val conflict = runCatching { api.adminDeleteUser("member-1", 12) }.exceptionOrNull() as ApiException
        assertEquals("VERSION_CONFLICT", conflict.errorCode)
        assertEquals(2, server.requestCount)
    }

    @Test fun `resource ids cannot change the administrative route`() = runBlocking {
        val error = runCatching { api.adminDeleteUser("../root", 1) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals(0, server.requestCount)
    }
}
