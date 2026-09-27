package io.github.zhanry.hometunnel.repository

import io.github.zhanry.hometunnel.model.ApiException
import io.github.zhanry.hometunnel.network.ServerDiscovery
import io.github.zhanry.hometunnel.network.DiscoveryException
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class FailureCodeTest {
    @Test fun serverMessageLanguageDoesNotChangeTheUiError() {
        assertEquals("AUTH_INVALID", failureCode(ApiException(401, "AUTH_INVALID", "用户名或密码错误")))
        assertEquals("AUTH_INVALID", failureCode(ApiException(401, "AUTH_INVALID", "Invalid username or password")))
        assertEquals("VERSION_CONFLICT", failureCode(ApiException(409, "VERSION_CONFLICT", "arbitrary server details")))
    }

    @Test fun discoveryRejectionsKeepTheirSpecificRepairAction() {
        val invalid = assertFailsWith<DiscoveryException> { ServerDiscovery.normalizeRoot("http://console.example.com") }
        assertEquals("DISCOVERY_INVALID_ORIGIN", failureCode(invalid))
        assertEquals("DISCOVERY_CONFIG_INVALID", failureCode(DiscoveryException("Unparseable response")))
    }

    @Test fun networkFailuresDistinguishCertificateTimeoutAndConnectivity() {
        assertEquals("TLS_ERROR", failureCode(SSLHandshakeException("private certificate detail")))
        assertEquals("NETWORK_TIMEOUT", failureCode(SocketTimeoutException("private target")))
        assertEquals("NETWORK_ERROR", failureCode(IOException("private hostname")))
        assertEquals("REQUEST_FAILED", failureCode(IllegalStateException("internal diagnostic")))
    }
}
