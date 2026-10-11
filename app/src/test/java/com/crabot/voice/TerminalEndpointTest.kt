package com.crabot.voice

import org.junit.Assert.*
import org.junit.Test

class TerminalEndpointTest {
    @Test fun acceptsExactChannelWithPrefixPortAndIpv6() {
        assertFalse(TerminalEndpoint.parse("ws://192.168.1.2:3000/ws/voice/%E6%89%8B%E6%9C%BA", "手机").secure)
        assertTrue(TerminalEndpoint.parse("wss://example.org/prefix/ws/voice/FuFu", "FuFu").secure)
        assertEquals("fd00::1", TerminalEndpoint.parse("ws://[fd00::1]:3000/ws/voice/phone", "phone").host)
        assertEquals("example.org", TerminalEndpoint.parse("wss://example.org:80/ws/voice/a+b", "a+b").host)
    }
    @Test fun rejectsOtherChannelOldPortRouteAndAmbiguousPaths() {
        listOf("wss://host/voice", "http://host/ws/voice/a", "ws://u:p@host/ws/voice/a", "ws://host/ws/voice/b", "ws://host/../ws/voice/a", "ws://host/%2e%2e/ws/voice/a", "ws://host/%2f/ws/voice/a", "ws://host/ws/voice/a?q=1", "ws://host/ws/voice/a#x", "ws://host:0/ws/voice/a", "ws://host:70000/ws/voice/a").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { TerminalEndpoint.parse(url, "a") }
        }
    }
}
