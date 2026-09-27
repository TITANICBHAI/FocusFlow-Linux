package com.focusflow.enforcement

import com.focusflow.services.HostsBlocker
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Small, non-destructive fixtures for Linux enforcement tests.
 *
 * The fixture never points at /etc/hosts and never starts a firewall command.
 * Privileged integration tests remain in LinuxReleaseReadinessIntegrationTest
 * and require their explicit disposable-environment opt-in.
 */
private class DisposableLinuxEnforcementFixture : AutoCloseable {
    val root: File = Files.createTempDirectory("focusflow-enforcement-fixture").toFile()
    val hostsFile: File = File(root, "hosts")
    val commands = mutableListOf<List<String>>()
    val timeouts = mutableListOf<Long>()

    val executor = ProcessExecutor { command, timeoutMs ->
        commands += command
        timeouts += timeoutMs
        BoundedProcess.Result(exitCode = 0, output = "fixture-ok")
    }

    init {
        hostsFile.writeText("127.0.0.1 localhost\n")
    }

    override fun close() {
        root.deleteRecursively()
    }
}

internal data class LinuxTestEnvironmentMetadata(
    val osName: String,
    val distribution: String,
    val kernel: String,
    val javaVersion: String,
    val desktop: String?,
    val sessionType: String?,
    val display: String?,
    val waylandDisplay: String?,
    val optionalToolVersions: String?
) {
    fun asLines(): List<String> = listOf(
        "os.name=$osName",
        "distribution=$distribution",
        "kernel=$kernel",
        "java.version=$javaVersion",
        "desktop=${desktop.orEmpty()}",
        "session.type=${sessionType.orEmpty()}",
        "display=${display.orEmpty()}",
        "wayland.display=${waylandDisplay.orEmpty()}",
        "optional.tool.versions=${optionalToolVersions.orEmpty()}"
    )

    companion object {
        fun capture(): LinuxTestEnvironmentMetadata = LinuxTestEnvironmentMetadata(
            osName = System.getProperty("os.name", "unknown"),
            distribution = readDistribution(),
            kernel = readKernelVersion(),
            javaVersion = System.getProperty("java.version", "unknown"),
            desktop = System.getenv("XDG_CURRENT_DESKTOP")
                ?: System.getenv("DESKTOP_SESSION"),
            sessionType = System.getenv("XDG_SESSION_TYPE"),
            display = System.getenv("DISPLAY"),
            waylandDisplay = System.getenv("WAYLAND_DISPLAY"),
            optionalToolVersions = System.getenv("FOCUSFLOW_TEST_TOOL_VERSIONS")
        )

        private fun readKernelVersion(): String =
            try {
                File("/proc/sys/kernel/osrelease").readText().trim().ifBlank { "unknown" }
            } catch (_: Exception) {
                "unknown"
            }

        private fun readDistribution(): String =
            try {
                File("/etc/os-release").useLines { lines ->
                    lines.firstOrNull { it.startsWith("PRETTY_NAME=") }
                        ?.substringAfter('=')
                        ?.trim()
                        ?.removeSurrounding("\"")
                        ?.takeIf { it.isNotBlank() }
                } ?: "unknown"
            } catch (_: Exception) {
                "unknown"
            }
    }
}

class LinuxEnforcementFoundationTest {

    @Test
    fun `fake executor captures argv and timeout without starting a host command`() {
        DisposableLinuxEnforcementFixture().use { fixture ->
            val result = ProcessExecutorRegistry.withExecutorForTesting(fixture.executor) {
                ProcessExecutorRegistry.current.run(
                    listOf("iptables", "-C", "OUTPUT", "--comment", "focusflow-test;not-shell"),
                    timeoutMs = 1_250L
                )
            }

            assertTrue(result.succeeded)
            assertEquals(
                listOf("iptables", "-C", "OUTPUT", "--comment", "focusflow-test;not-shell"),
                fixture.commands.single()
            )
            assertEquals(listOf(1_250L), fixture.timeouts)
            assertSame(BoundedProcess, ProcessExecutorRegistry.current)
        }
    }

    @Test
    fun `bounded executor preserves output exit status and timeout state`() {
        val success = BoundedProcess.run(
            listOf("/bin/sh", "-c", "printf bounded-ok"),
            timeoutMs = 1_000L
        )
        assertTrue(success.succeeded)
        assertEquals(0, success.exitCode)
        assertEquals("bounded-ok", success.output)

        val failure = BoundedProcess.run(
            listOf("/bin/sh", "-c", "printf bounded-failed; exit 7"),
            timeoutMs = 1_000L
        )
        assertFalse(failure.succeeded)
        assertEquals(7, failure.exitCode)
        assertEquals("bounded-failed", failure.output)

        val timeout = BoundedProcess.run(
            listOf("/bin/sh", "-c", "sleep 2"),
            timeoutMs = 50L
        )
        assertFalse(timeout.succeeded)
        assertTrue(timeout.timedOut)
        assertNotSame(success, timeout)
    }

    @Test
    fun `fixture isolates hosts writes and firewall command traces`() {
        DisposableLinuxEnforcementFixture().use { fixture ->
            HostsBlocker.atomicWriteHostsForTesting(
                fixture.hostsFile,
                "127.0.0.1 localhost\n127.0.0.1 example.test # FocusFlow\n"
            )

            ProcessExecutorRegistry.withExecutorForTesting(fixture.executor) {
                ProcessExecutorRegistry.current.run(
                    listOf("iptables", "-A", "OUTPUT", "-d", "203.0.113.10"),
                    timeoutMs = 2_000L
                )
            }

            assertTrue(fixture.hostsFile.readText().contains("example.test"))
            assertEquals(
                listOf("iptables", "-A", "OUTPUT", "-d", "203.0.113.10"),
                fixture.commands.single()
            )
            assertTrue(
                fixture.root.listFiles()!!.none { it.name.startsWith(".focusflow-hosts-") },
                "fixture cleanup must remove atomic-write temporary files"
            )
        }
    }

    @Test
    fun `environment metadata captures only reproducible non-sensitive fields`() {
        val metadata = LinuxTestEnvironmentMetadata.capture()
        val lines = metadata.asLines()

        assertTrue(lines.any { it.startsWith("os.name=") })
        assertTrue(lines.any { it.startsWith("distribution=") })
        assertTrue(lines.any { it.startsWith("kernel=") })
        assertTrue(lines.any { it.startsWith("java.version=") })
        assertTrue(lines.any { it.startsWith("session.type=") })
        assertTrue(lines.any { it.startsWith("optional.tool.versions=") })
        assertTrue(lines.none { it.contains("TOKEN", ignoreCase = true) })
        assertTrue(lines.none { it.contains("PASSWORD", ignoreCase = true) })
    }
}