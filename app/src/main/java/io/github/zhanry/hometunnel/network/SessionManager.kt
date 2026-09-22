package io.github.zhanry.hometunnel.network

import io.github.zhanry.hometunnel.model.RefreshResponse
import io.github.zhanry.hometunnel.model.SessionResponse
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SessionManager(
    private val clock: Clock = Clock.systemUTC(),
    private val onRefresh: suspend (String, RefreshResponse) -> Unit = { _, _ -> },
) {
    private val refreshMutex = Mutex()

    @Volatile
    private var session: MemorySession? = null

    @Synchronized fun install(value: SessionResponse) {
        session = MemorySession(
            accessToken = value.accessToken,
            refreshToken = value.refreshToken,
            accessExpiresAt = Instant.parse(value.accessExpiresAt),
        )
    }

    @Synchronized fun clear() {
        session = null
    }

    fun hasSession(): Boolean = session != null

    suspend fun accessToken(refresher: suspend (String) -> RefreshResponse): String {
        val current = session ?: throw NoSessionException()
        if (current.accessExpiresAt.isAfter(clock.instant().plusSeconds(60))) return current.accessToken
        return refreshMutex.withLock {
            val afterLock = session ?: throw NoSessionException()
            if (afterLock.accessExpiresAt.isAfter(clock.instant().plusSeconds(60))) {
                return@withLock afterLock.accessToken
            }
            val refreshed = refresher(afterLock.refreshToken)
            val installed = installIfCurrent(afterLock, refreshed)
            onRefresh(afterLock.refreshToken, refreshed)
            if (session !== installed) throw NoSessionException()
            installed.accessToken
        }
    }

    suspend fun refreshAfterUnauthorized(
        rejectedAccessToken: String,
        refresher: suspend (String) -> RefreshResponse,
    ): String = refreshMutex.withLock {
        val current = session ?: throw NoSessionException()
        if (current.accessToken != rejectedAccessToken &&
            current.accessExpiresAt.isAfter(clock.instant().plusSeconds(5))
        ) {
            return@withLock current.accessToken
        }
        val refreshed = refresher(current.refreshToken)
        val installed = installIfCurrent(current, refreshed)
        onRefresh(current.refreshToken, refreshed)
        if (session !== installed) throw NoSessionException()
        installed.accessToken
    }

    @Synchronized private fun installIfCurrent(expected: MemorySession, value: RefreshResponse): MemorySession {
        // A network refresh finishing after logout/account replacement cannot resurrect that identity.
        if (session !== expected) throw NoSessionException()
        return MemorySession(
            accessToken = value.accessToken,
            refreshToken = value.refreshToken,
            accessExpiresAt = Instant.parse(value.accessExpiresAt),
        ).also { session = it }
    }

    private data class MemorySession(
        val accessToken: String,
        val refreshToken: String,
        val accessExpiresAt: Instant,
    )
}

class NoSessionException : IllegalStateException("No active control-center session")
