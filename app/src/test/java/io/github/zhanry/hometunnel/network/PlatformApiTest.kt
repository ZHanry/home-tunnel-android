package io.github.zhanry.hometunnel.network

import io.github.zhanry.hometunnel.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class PlatformApiTest {
    private fun api(server: MockWebServer) = HomeTunnelApi(ServerProfile(server.url("/").toString(), server.url("/api/v1/").toString(),"home.example",7000,"home.example"))
        .apply { restoreSession("fixture-access","fixture-refresh","2099-01-01T00:00:00Z") }
    private fun response(body:String,code:Int=200) = MockResponse().setResponseCode(code).setHeader("Content-Type","application/json").setBody(body)

    @Test fun `MFA rejection is not retried or consumed twice`() = runBlocking {
        MockWebServer().use { server ->
            server.start();server.enqueue(response("""{"error_code":"MFA_INVALID","message":"Invalid code"}""",401))
            try { api(server).mfaConfirm("password-fixture","123456");fail("Expected MFA failure") }
            catch(error:ApiException){assertEquals("MFA_INVALID",error.errorCode)}
            assertEquals(1,server.requestCount)
        }
    }

    @Test fun `raw presets omit HTTP subdomain and let server allocate ports`() = runBlocking {
        MockWebServer().use { server ->
            server.start();server.enqueue(response("""{"id":"conn","proxy_type":"tcp","local_port":22}""",201))
            api(server).createConnection("device",TunnelConnection("","device","SSH","","tcp",localPort=22,version=0,applicationProtocol="ssh"))
            val body=Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("ssh",body["application_protocol"]?.jsonPrimitive?.content)
            assertFalse(body.containsKey("subdomain"));assertFalse(body.containsKey("remote_port"))
        }
    }

    @Test fun `connection pages and metadata are preserved`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(response("""{"items":[{"id":"a","proxy_type":"http"}],"total_pages":2,"capabilities":{"supported":true,"tcp":{"enabled":true,"can_create":true}}}"""))
            server.enqueue(response("""{"items":[{"id":"b","proxy_type":"udp"}],"total_pages":2,"capabilities":{"supported":true,"tcp":{"enabled":true,"can_create":true}}}"""))
            val catalog=api(server).connectionCatalog()
            assertEquals(listOf("a","b"),catalog.items.map{it.id});assertTrue(catalog.capabilities.tcp.canCreate)
            assertTrue(server.takeRequest().path!!.contains("page=1"));assertTrue(server.takeRequest().path!!.contains("page=2"))
        }
    }

    @Test fun `port settings include optimistic concurrency version and configured state`() = runBlocking {
        MockWebServer().use { server ->
            server.start();server.enqueue(response("{}"))
            api(server).adminSaveSettings(AdminSettings(transportTunnels=TransportPools(
                tcp=TransportPool(configuredEnabled=true,portStart=10000,portEnd=10009),
                udp=TransportPool(portStart=10000,portEnd=10009)),transportSettingsVersion=7))
            val body=Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals(7,body["transport_settings_version"]?.jsonPrimitive?.int)
            assertEquals(true,body["transport_tunnels"]?.jsonObject?.get("tcp")?.jsonObject?.get("enabled")?.jsonPrimitive?.boolean)
        }
    }
}
