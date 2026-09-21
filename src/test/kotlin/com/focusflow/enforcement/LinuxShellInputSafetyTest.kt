package com.focusflow.enforcement

import com.focusflow.services.HostsBlocker
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.io.File

class LinuxShellInputSafetyTest {

    @Test
    fun `desktop Exec paths with spaces stay one process name`() {
        assertEquals(
            "focusflow",
            InstalledAppsScanner.normalizeLinuxExecForTesting(
                "\"/opt/Focus Flow/bin/focusflow\" --profile-directory=\"Work Profile\" %U"
            )
        )
    }

    @Test
    fun `desktop Exec executable names reject shell metacharacters`() {
        assertNull(
            InstalledAppsScanner.normalizeLinuxExecForTesting(
                "\"/opt/Focus Flow/bin/browser;touch /tmp/unsafe\" %U"
            )
        )
        assertNull(
            InstalledAppsScanner.normalizeLinuxExecForTesting(
                "flatpak run org.example.App\$(touch /tmp/unsafe)"
            )
        )
    }

    @Test
    fun `autostart Exec value quotes paths and metacharacters as data`() {
        val command = "\"/opt/Focus Flow/bin/focusflow\" --profile '/tmp/Profile Data' --label '\$(touch /tmp/unsafe)'"
        val encoded = WindowsStartupManager.desktopExecForTesting(command)

        assertTrue(encoded.startsWith("\"/opt/Focus Flow/bin/focusflow\""))
        assertTrue(encoded.contains("\"/tmp/Profile Data\""))
        assertTrue(encoded.contains("\"\$(touch /tmp/unsafe)\""))
        assertFalse(encoded.contains("' /tmp"))
    }

    @Test
    fun `watchdog keeps dynamic values out of its fixed shell guard`() {
        val service = WatchdogInstaller.linuxServiceUnitForTesting(
            "/opt/Focus Flow/bin/focusflow",
            listOf("--profile", "\$(touch /tmp/unsafe)", "name;touch /tmp/unsafe")
        )
        val preStart = service.lineSequence().first { it.startsWith("ExecStartPre=") }
        val execStart = service.lineSequence().first { it.startsWith("ExecStart=") }

        assertEquals(
            "ExecStartPre=/bin/sh -c '! pgrep -f focusflow > /dev/null'",
            preStart
        )
        assertTrue(execStart.contains("\$(touch /tmp/unsafe)"))
        assertTrue(execStart.contains("name;touch /tmp/unsafe"))
        assertFalse(preStart.contains("unsafe"))
    }

    @Test
    fun `hosts entries only render validated domains`() {
        assertEquals(
            5,
            HostsBlocker.expectedEntriesForTesting("www.Example.test")!!
                .lineSequence()
                .count { it.isNotBlank() }
        )
        assertNull(HostsBlocker.expectedEntriesForTesting("example.test;touch /tmp/unsafe"))
    }

    @Test
    fun `atomic hosts writer leaves no temporary files`() {
        val directory = createTempDirectory("focusflow-hosts-test").toFile()
        try {
            val hostsFile = File(directory, "hosts")
            hostsFile.writeText("127.0.0.1 localhost\n")
            HostsBlocker.atomicWriteHostsForTesting(hostsFile, "127.0.0.1 localhost\n# FocusFlow\n")

            assertTrue(hostsFile.readText().endsWith("# FocusFlow\n"))
            assertTrue(
                directory.listFiles()!!.none { it.name.startsWith(".focusflow-hosts-") },
                "atomic write must clean its temporary file"
            )
        } finally {
            directory.deleteRecursively()
        }
    }
}