package io.github.zhanry.hometunnel.remote

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class RemoteConnectionPolicyTest {
    @Test fun `only explicit UDP STUN destinations are accepted`() {
        listOf("stun:example.com:3478", "stun:192.168.1.5:3478", "stun:[2001:db8::1]:3478").forEach { assertTrue(RemoteConnectionPolicy.validStun(it), it) }
        listOf("turn:example.com:3478", "stuns:example.com:5349", "stun:user:pass@example.com:3478", "stun:example.com:3478?transport=tcp",
            "stun:example.com:0", "stun:example.com:65536", "stun:-example.com:3478", "stun:example.com:+3478", "stun:example..com:3478").forEach { assertFalse(RemoteConnectionPolicy.validStun(it), it) }
    }
}
