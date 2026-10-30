package dev.dsh.mobile.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkPolicyTest {
    @Test
    fun addsDefaultPocketPortWhenMissing() {
        assertEquals("http://192.168.1.15:3081", LocalNetworkPolicy.normalize("192.168.1.15").baseUrl)
        assertEquals("http://192.168.1.15:3081", LocalNetworkPolicy.normalize("192.168.1.15:3081").baseUrl)
        assertEquals("http://192.168.1.15:3081", LocalNetworkPolicy.normalize(" http://192.168.1.15/ ").baseUrl)
    }

    @Test
    fun keepsExplicitPortsAndScheme() {
        assertEquals("https://10.0.0.5:3081", LocalNetworkPolicy.normalize("https://10.0.0.5").baseUrl)
        assertEquals("http://172.20.1.2:9000", LocalNetworkPolicy.normalize("http://172.20.1.2:9000").baseUrl)
        assertEquals("http://192.168.1.15:80", LocalNetworkPolicy.normalize("192.168.1.15:80").baseUrl)
    }

    @Test
    fun dropsPathQueryAndFragment() {
        assertEquals(
            "http://192.168.1.15:3081",
            LocalNetworkPolicy.normalize("192.168.1.15:3081/some/path?x=1#frag").baseUrl,
        )
    }

    @Test
    fun acceptsPrivateRangesAndLocalNames() {
        assertTrue(LocalNetworkPolicy.normalize("10.1.2.3").ok)
        assertTrue(LocalNetworkPolicy.normalize("172.16.0.9").ok)
        assertTrue(LocalNetworkPolicy.normalize("172.31.255.254").ok)
        assertTrue(LocalNetworkPolicy.normalize("192.168.1.1").ok)
        assertTrue(LocalNetworkPolicy.normalize("169.254.10.20").ok)
        assertTrue(LocalNetworkPolicy.normalize("dsh.local").ok)
        assertTrue(LocalNetworkPolicy.normalize("my-pc").ok)
        assertTrue(LocalNetworkPolicy.normalize("[fd00::1]").ok)
        assertTrue(LocalNetworkPolicy.normalize("[fe80::1]").ok)
        assertTrue(LocalNetworkPolicy.normalize("localhost").ok)
    }

    @Test
    fun rejectsPublicAndMalformedTargets() {
        assertFalse(LocalNetworkPolicy.normalize("https://example.com").ok)
        assertFalse(LocalNetworkPolicy.normalize("http://8.8.8.8:3081").ok)
        assertFalse(LocalNetworkPolicy.normalize("100.64.0.1").ok)
        assertFalse(LocalNetworkPolicy.normalize("172.32.0.1").ok)
        assertFalse(LocalNetworkPolicy.normalize("http://192.168.1.1:99999").ok)
        assertFalse(LocalNetworkPolicy.normalize("http://user:pass@192.168.1.1:3081").ok)
        assertFalse(LocalNetworkPolicy.normalize("ftp://192.168.1.1").ok)
        assertFalse(LocalNetworkPolicy.normalize("javascript:alert(1)").ok)
        assertFalse(LocalNetworkPolicy.normalize("").ok)
        assertFalse(LocalNetworkPolicy.normalize("http://192.168.1.15:3081/\nInjected").ok)
    }

    @Test
    fun hostClassification() {
        assertTrue(LocalNetworkPolicy.isPrivateHost("192.168.1.1"))
        assertTrue(LocalNetworkPolicy.isPrivateHost("::ffff:192.168.1.1"))
        assertTrue(LocalNetworkPolicy.isPrivateHost("fd12:3456::1"))
        assertTrue(LocalNetworkPolicy.isPrivateHost("fe80::1"))
        assertTrue(LocalNetworkPolicy.isPrivateHost("::1"))
        assertTrue(LocalNetworkPolicy.isPrivateHost("desktop.local"))
        assertFalse(LocalNetworkPolicy.isPrivateHost("fd00::1.example.com"))
        assertFalse(LocalNetworkPolicy.isPrivateHost("1.1.1.1"))
        assertFalse(LocalNetworkPolicy.isPrivateHost("dsh.example.com"))
        assertNull(LocalNetworkPolicy.normalize("192.168.1.15").error)
    }
}
