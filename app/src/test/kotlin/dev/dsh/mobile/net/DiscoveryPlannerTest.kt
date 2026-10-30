package dev.dsh.mobile.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryPlannerTest {
    @Test
    fun scansThe24AroundAPhoneAddress() {
        val hosts = DiscoveryPlanner.hostsFor("192.168.1.37", 24)
        assertEquals(253, hosts.size)
        assertTrue(hosts.contains("192.168.1.15"))
        assertTrue(hosts.contains("192.168.1.1"))
        assertTrue(hosts.contains("192.168.1.254"))
        assertFalse(hosts.contains("192.168.1.37"))
        assertFalse(hosts.contains("192.168.2.1"))
    }

    @Test
    fun capsWidePrefixesToTheLocal24() {
        val hosts = DiscoveryPlanner.hostsFor("10.5.6.7", 16)
        assertEquals(253, hosts.size)
        assertTrue(hosts.contains("10.5.6.1"))
        assertFalse(hosts.contains("10.5.7.1"))
    }

    @Test
    fun handlesSmallAndInvalidPrefixes() {
        assertEquals(listOf("192.168.1.37"), DiscoveryPlanner.hostsFor("192.168.1.37", 32))
        assertTrue(DiscoveryPlanner.hostsFor("192.168.1.0", 16).contains("192.168.1.1"))
        assertTrue(DiscoveryPlanner.hostsFor("not-an-ip", 24).isEmpty())
        assertTrue(DiscoveryPlanner.hostsFor("192.168.1.1", 32).isNotEmpty())
    }
}
