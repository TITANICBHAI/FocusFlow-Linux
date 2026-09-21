package com.focusflow.enforcement

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class NetworkBlockerLinuxTest {

    private val isLinux = System.getProperty("os.name", "").lowercase().contains("linux")

    @Test
    fun `syncFromFirewall does not throw on Linux`() {
        assumeTrue(isLinux, "Linux-only test")
        // Must silently return — no Windows Firewall available on Linux.
        // A throw here would crash the startup sequence.
        assertDoesNotThrow {
            NetworkBlocker.syncFromFirewall()
        }
    }

    @Test
    fun `addRule on Linux does not report unverified success`() {
        assumeTrue(isLinux, "Linux-only test")
        // Linux must not report success before an iptables rule is verified.
        val result = NetworkBlocker.addRule("test-process")
        assertFalse(result, "addRule must not report an unverified Linux rule as active")
        assertNotEquals(
            NetworkBlocker.LinuxRuleState.ACTIVE,
            NetworkBlocker.linuxRuleStatus("test-process").state
        )
        NetworkBlocker.removeRule("test-process")
    }

    @Test
    fun `removeRule on Linux does not throw`() {
        assumeTrue(isLinux, "Linux-only test")
        assertDoesNotThrow {
            NetworkBlocker.removeRule("test-process")
        }
    }

    @Test
    fun `removeAllRules on Linux does not throw`() {
        assumeTrue(isLinux, "Linux-only test")
        assertDoesNotThrow {
            NetworkBlocker.removeAllRules()
        }
    }

    @Test
    fun `bounded process terminates a hung command`() {
        val result = BoundedProcess.run(listOf("/bin/sh", "-c", "sleep 2"), 100)
        assertTrue(result.timedOut)
        assertFalse(result.succeeded)
    }
}
