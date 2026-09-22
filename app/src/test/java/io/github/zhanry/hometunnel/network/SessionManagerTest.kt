package io.github.zhanry.hometunnel.network

import io.github.zhanry.hometunnel.model.RefreshResponse
import io.github.zhanry.hometunnel.model.SessionResponse
import io.github.zhanry.hometunnel.model.UserInfo
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.supervisorScope
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class SessionManagerTest {
    @Test
    fun `logout during an in flight refresh cannot restore the cleared session`() = runTest {
        val now = Instant.parse("2026-08-23T00:00:00Z")
        var persisted = false
        val manager = SessionManager(Clock.fixed(now, ZoneOffset.UTC)) { _, _ -> persisted = true }
        manager.install(SessionResponse(UserInfo("u", "user", "User", "user", "normal"), accessToken = "expired",
            refreshToken = "refresh", accessExpiresAt = now.minusSeconds(1).toString(), refreshExpiresAt = now.plusSeconds(3600).toString()))
        val started = CompletableDeferred<Unit>()
        val complete = CompletableDeferred<Unit>()
        supervisorScope {
            val request = async {
                manager.accessToken { started.complete(Unit); complete.await(); RefreshResponse("new", "rotated", now.plusSeconds(600).toString()) }
            }
            started.await(); manager.clear(); complete.complete(Unit)
            assertFailsWith<NoSessionException> { request.await() }
        }
        assertFalse(manager.hasSession()); assertFalse(persisted)
    }
    @Test
    fun `concurrent callers perform exactly one refresh`() = runTest {
        val now = Instant.parse("2026-08-23T00:00:00Z")
        val persisted = mutableListOf<Pair<String, RefreshResponse>>()
        val manager = SessionManager(Clock.fixed(now, ZoneOffset.UTC)) { previous, renewed -> persisted.add(previous to renewed) }
        manager.install(
            SessionResponse(
                user = UserInfo("u", "user", "User", "user", "normal"),
                accessToken = "expired-access",
                refreshToken = "refresh-a",
                accessExpiresAt = now.minusSeconds(1).toString(),
                refreshExpiresAt = now.plusSeconds(3600).toString(),
            ),
        )
        val refreshes = AtomicInteger()
        val values = List(32) {
            async {
                manager.accessToken { presented ->
                    assertEquals("refresh-a", presented)
                    refreshes.incrementAndGet()
                    RefreshResponse(
                        accessToken = "fresh-access",
                        refreshToken = "refresh-b",
                        accessExpiresAt = now.plusSeconds(900).toString(),
                    )
                }
            }
        }.awaitAll()
        assertEquals(1, refreshes.get())
        assertEquals(setOf("fresh-access"), values.toSet())
        assertEquals(1, persisted.size)
        assertEquals("refresh-a", persisted.single().first)
        assertEquals("refresh-b", persisted.single().second.refreshToken)
    }

    @Test
    fun `a late unauthorized response reuses the token already refreshed by another call`() = runTest {
        val now = Instant.parse("2026-08-23T00:00:00Z")
        val manager = SessionManager(Clock.fixed(now, ZoneOffset.UTC))
        manager.install(
            SessionResponse(
                user = UserInfo("u", "user", "User", "user", "normal"),
                accessToken = "access-a",
                refreshToken = "refresh-a",
                accessExpiresAt = now.plusSeconds(10).toString(),
                refreshExpiresAt = now.plusSeconds(3600).toString(),
            ),
        )
        val refreshes = AtomicInteger()
        val first = manager.refreshAfterUnauthorized("access-a") {
            refreshes.incrementAndGet()
            RefreshResponse("access-b", "refresh-b", now.plusSeconds(900).toString())
        }
        val late = manager.refreshAfterUnauthorized("access-a") {
            refreshes.incrementAndGet()
            error("must not reuse the rotated refresh token")
        }
        assertEquals("access-b", first)
        assertEquals("access-b", late)
        assertEquals(1, refreshes.get())
    }
}
