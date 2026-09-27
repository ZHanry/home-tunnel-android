package io.github.zhanry.hometunnel.repository

import io.github.zhanry.hometunnel.model.ApiException
import io.github.zhanry.hometunnel.network.DiscoveryException
import io.github.zhanry.hometunnel.storage.StateUnavailableException
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

/** Keep errors language-independent until the active UI resolves its resources. */
internal fun failureCode(error: Throwable): String = when (error) {
    is ApiException -> error.errorCode
    is DiscoveryException -> error.errorCode
    is StateUnavailableException -> "LOCAL_STATE_UNAVAILABLE"
    is SSLException -> "TLS_ERROR"
    is SocketTimeoutException -> "NETWORK_TIMEOUT"
    is IOException -> "NETWORK_ERROR"
    is IllegalArgumentException -> "VALIDATION_ERROR"
    else -> "REQUEST_FAILED"
}
