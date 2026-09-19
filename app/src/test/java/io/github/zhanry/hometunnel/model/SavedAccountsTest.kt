package io.github.zhanry.hometunnel.model

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SavedAccountsTest {
    private fun account(server: String, user: String) = PersistedState(
        profile=ServerProfile("https://$server", "https://$server/api/v1/",server,7000,server),
        username=user,accessToken="$server-access",refreshToken="$server-refresh",accessExpiresAt="2099-01-01T00:00:00Z")

    @Test fun `legacy login migrates and two servers survive encrypted state serialization`() {
        val first=account("first.example","owner").archiveActiveAccount()
        val next=account("second.example","owner").copy(savedAccounts=first.savedAccounts).archiveActiveAccount()
        val restored=Json.decodeFromString<PersistedState>(Json.encodeToString(PersistedState.serializer(),next))
        val switched=restored.switchAccount(first.activeAccountId!!)
        assertEquals("first.example-access",switched.accessToken)
        assertEquals("first.example-refresh",switched.refreshToken)
        assertEquals(2,switched.savedAccounts.size)
        assertTrue(switched.cachedConnections.isEmpty())
        assertNull(switched.deviceCredential)
    }

    @Test fun `signing out removes only the active account and add-server retains it`() {
        val first=account("first.example","one").archiveActiveAccount()
        val second=account("second.example","two").copy(savedAccounts=first.savedAccounts).archiveActiveAccount()
        assertEquals(2,second.withoutActiveAccount(remove=false).savedAccounts.size)
        val loggedOut=second.withoutActiveAccount(remove=true)
        assertEquals(first.activeAccountId,loggedOut.savedAccounts.single().id)
        assertNull(loggedOut.accessToken)
        assertFalse(loggedOut.signedIn)
        assertFalse(second.savedAccounts.last().toString().contains("second.example-refresh"))
    }

    @Test fun `capabilities fail closed for an older server`() {
        val old=Json.decodeFromString<ConnectionListResponse>("""{"items":[]}""")
        assertTrue(old.capabilities.permits(ProxyKind.HTTP))
        assertFalse(old.capabilities.permits(ProxyKind.TCP))
        assertFalse(old.capabilities.permits(ProxyKind.UDP))
    }

    @Test fun `refresh finishing after a switch updates only its saved session`() {
        val first = account("first.example", "owner").archiveActiveAccount()
        val second = account("second.example", "owner").copy(savedAccounts = first.savedAccounts).archiveActiveAccount()
        val renewed = RefreshResponse("renewed-access", "renewed-refresh", "2099-02-01T00:00:00Z")
        val updated = second.withRefreshedAccount(first.profile!!.apiBaseUrl, first.refreshToken!!, renewed)
        assertEquals(second.accessToken, updated.accessToken)
        assertEquals(second.activeAccountId, updated.activeAccountId)
        assertEquals("renewed-refresh", updated.switchAccount(first.activeAccountId!!).refreshToken)
        // A late response cannot recreate an account that the user removed.
        val removed = updated.copy(savedAccounts = updated.savedAccounts.filterNot { it.id == first.activeAccountId })
        assertEquals(removed, removed.withRefreshedAccount(first.profile.apiBaseUrl, first.refreshToken, renewed))
        // A duplicate response never overwrites a newer refresh token.
        assertEquals(updated, updated.withRefreshedAccount(first.profile.apiBaseUrl, first.refreshToken, renewed.copy(refreshToken = "stale")))
    }
}
