package io.github.zhanry.hometunnel.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseUpdatesTest {
    private fun release(tag: String, draft: Boolean = false, prerelease: Boolean = false) =
        """{"tag_name":"$tag","draft":$draft,"prerelease":$prerelease}"""

    @Test fun stableReleaseIsOfferedOnlyWhenNewer() {
        assertEquals("8.2.0", ReleaseUpdates.parse(release("v8.2.0"), "8.1.0-rc.1-debug")?.version)
        assertNull(ReleaseUpdates.parse(release("v8.0.0"), "8.1.0-rc.1-debug"))
        assertNull(ReleaseUpdates.parse(release("v8.1.0"), "8.1.0"))
    }

    @Test fun stableReleaseCanReplaceMatchingCandidate() {
        assertEquals("8.1.0", ReleaseUpdates.parse(release("v8.1.0"), "8.1.0-rc.1-debug")?.version)
    }

    @Test fun draftAndPrereleaseAreNotOffered() {
        assertNull(ReleaseUpdates.parse(release("v9.0.0", draft = true), "8.0.0"))
        assertNull(ReleaseUpdates.parse(release("v9.0.0", prerelease = true), "8.0.0"))
        assertNull(ReleaseUpdates.parse(release("v9.0.0-rc.1"), "8.0.0"))
    }
}
