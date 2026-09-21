package com.focusflow.enforcement

import com.focusflow.services.HostsBlocker
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Linux release checks.
 *
 * The default suite is non-destructive. Tests that touch /etc/hosts or
 * iptables require FOCUSFLOW_RUN_PRIVILEGED_TESTS=1 and are intended for a
 * disposable VM/container with a real PolicyKit/firewall setup.
 */
class LinuxReleaseReadinessIntegrationTest {

    private val isLinux =
        System.getProperty("os.name", "").lowercase().contains("linux")

    @Test
    fun `resolver output variants accept loopback and reject unrelated addresses`() {
        assertTrue(
            HostsBlocker.resolverOutputConfirmsLoopbackForTesting(
                "Name: example.test\nAddress: 127.0.0.1\n"
            )
        )
        assertTrue(
            HostsBlocker.resolverOutputConfirmsLoopbackForTesting(
                "Name: example.test\nAddress: 127.0.0.1#53\n"
            )
        )
        assertFalse(
            HostsBlocker.resolverOutputConfirmsLoopbackForTesting(
                "Name: example.test\nAddress: 192.0.2.1\n"
            )
        )
    }

    @Test
    fun `watchdog and autostart lifecycle uses only disposable files`() {
        val root = createTempDirectory("focusflow-lifecycle-test")
        try {
            val systemdDir = File(root, "systemd/user")
            WatchdogInstaller.writeLinuxUnitsForTesting(
                systemdDir,
                "/opt/Focus Flow/bin/focusflow",
                listOf("--profile", "Profile Data")
            )
            assertTrue(File(systemdDir, "focusflow-watchdog.service").isFile)
            assertTrue(File(systemdDir, "focusflow-watchdog.timer").isFile)

            val autostart = File(root, "autostart/focusflow.desktop")
            WindowsStartupManager.writeLinuxAutostartFileForTesting(
                autostart,
                "\"/opt/Focus Flow/bin/focusflow\" --profile 'Profile Data'"
            )
            assertTrue(autostart.isFile)
            assertTrue(autostart.readText().contains("Exec=\"/opt/Focus Flow/bin/focusflow\""))

            autostart.delete()
            File(systemdDir, "focusflow-watchdog.service").delete()
            File(systemdDir, "focusflow-watchdog.timer").delete()
            assertFalse(autostart.exists())
            assertFalse(File(systemdDir, "focusflow-watchdog.service").exists())
            assertFalse(File(systemdDir, "focusflow-watchdog.timer").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `cancelled or hung authentication cannot hold enforcement`() {
        val result = BoundedProcess.run(
            listOf("/bin/sh", "-c", "sleep 10"),
            timeoutMs = 100
        )
        assertTrue(result.timedOut)
        assertFalse(result.succeeded)
    }

    @Test
    fun `real hosts blocking is opt in and always cleaned up`() {
        assumeTrue(isLinux, "Linux-only integration test")
        assumeTrue(
            System.getenv("FOCUSFLOW_RUN_PRIVILEGED_TESTS") == "1",
            "Set FOCUSFLOW_RUN_PRIVILEGED_TESTS=1 in a disposable Linux environment"
        )
        assumeTrue(
            HostsBlocker.canWriteHostsFile(),
            "The disposable environment must provide a writable /etc/hosts"
        )

        val domain = "focusflow-${ProcessHandle.current().pid()}.test"
        try {
            val result = HostsBlocker.blockDomain(domain)
            assertNotEquals(HostsBlocker.BlockResult.NoAdmin, result)
            assertTrue(HostsBlocker.integrityScore(domain) > 0.99f)
        } finally {
            HostsBlocker.unblockDomain(domain)
            assertEquals(0f, HostsBlocker.integrityScore(domain))
        }
    }

    @Test
    fun `real firewall rule is opt in and cleanup is verified`() {
        assumeTrue(isLinux, "Linux-only integration test")
        assumeTrue(
            System.getenv("FOCUSFLOW_RUN_PRIVILEGED_TESTS") == "1",
            "Set FOCUSFLOW_RUN_PRIVILEGED_TESTS=1 in a disposable Linux environment"
        )
        val processName = System.getenv("FOCUSFLOW_FIREWALL_TEST_PROCESS")
        assumeTrue(
            !processName.isNullOrBlank(),
            "Set FOCUSFLOW_FIREWALL_TEST_PROCESS to a running process with a real connection"
        )

        try {
            NetworkBlocker.addRule(processName!!)
            val deadline = System.currentTimeMillis() + 12_000L
            var status = NetworkBlocker.linuxRuleStatus(processName)
            while (status.state == NetworkBlocker.LinuxRuleState.PENDING &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(100)
                status = NetworkBlocker.linuxRuleStatus(processName)
            }
            assertEquals(NetworkBlocker.LinuxRuleState.ACTIVE, status.state, status.message)
        } finally {
            NetworkBlocker.removeRule(processName!!)
            val deadline = System.currentTimeMillis() + 12_000L
            while (NetworkBlocker.linuxRuleStatus(processName).state != NetworkBlocker.LinuxRuleState.IDLE &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(100)
            }
            assertEquals(
                NetworkBlocker.LinuxRuleState.IDLE,
                NetworkBlocker.linuxRuleStatus(processName).state
            )
        }
    }

    private fun createTempDirectory(prefix: String): File =
        kotlin.io.path.createTempDirectory(prefix).toFile()
}