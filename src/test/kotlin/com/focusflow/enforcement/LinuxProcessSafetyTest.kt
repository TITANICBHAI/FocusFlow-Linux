package com.focusflow.enforcement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.concurrent.TimeUnit

class LinuxProcessSafetyTest {

    @Test
    fun `linux global protection inventory classifies system and desktop infrastructure`() {
        if (!isLinux) return

        val names = LinuxProcessSafety.launcherSafeProcessNames
        assertFalse("java" in names)
        assertFalse("python" in names)
        assertFalse("python3" in names)
        assertFalse("node" in names)
        assertFalse("bash" in names)
        assertFalse("focusflow" in names)
        assertTrue("gnome-shell" in names)
        assertTrue("plasmashell" in names)
        assertTrue("dbus-broker" in names)
        assertTrue("xdg-desktop-portal" in names)
        assertTrue("ibus-daemon" in names)
        assertEquals(names, LinuxProcessSafety.protectedProcessNameClassifications.keys)
        assertTrue(
            LinuxProcessSafety.protectedProcessNameClassifications.values.all {
                it.rationale.isNotBlank()
            }
        )
        assertEquals(
            LinuxProcessProtectionCategory.SYSTEM_CRITICAL,
            LinuxProcessSafety.protectionClassification(0L, "systemd")?.category
        )
        assertEquals(
            LinuxProcessProtectionCategory.DESKTOP_SESSION,
            LinuxProcessSafety.protectionClassification(0L, "gnome-shell")?.category
        )
    }

    @Test
    fun `only exact current focusflow pid receives runtime protection`() {
        if (!isLinux) return

        val ownPid = ProcessHandle.current().pid()
        assertTrue(LinuxProcessSafety.isProtectedProcess(ownPid, "unusual-focusflow-wrapper"))
        assertTrue(LinuxProcessSafety.isProtectedProcess(ownPid))
        assertEquals(
            LinuxProcessProtectionCategory.FOCUSFLOW_RUNTIME,
            LinuxProcessSafety.protectionClassification(ownPid, "java")?.category
        )
        assertFalse(LinuxProcessSafety.isProtectedProcess(Long.MAX_VALUE, "java"))
        assertFalse(LinuxProcessSafety.isProtectedProcess(Long.MAX_VALUE, "focusflow"))
    }

    @Test
    fun `ordinary child process is not protected by focusflow ancestry`() {
        if (!isLinux) return

        val child = ProcessBuilder("sleep", "30").start()
        try {
            assertTrue(child.isAlive)
            assertFalse(LinuxProcessSafety.isProtectedProcess(child.pid(), "sleep"))
            assertFalse(LinuxProcessSafety.isProtectedProcess(child.pid()))
        } finally {
            child.destroyForcibly()
            child.waitFor(2, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `generic runtimes are not global protections or bare manual app targets`() {
        if (!isLinux) return

        listOf("java", "python", "python3", "python3.12", "node", "bash").forEach { runtime ->
            assertFalse(LinuxProcessSafety.isProtectedProcessName(runtime), runtime)
            assertNull(InstalledAppsScanner.createManualProcessEntry(runtime), runtime)
            assertNotNull(
                LinuxProcessSafety.manualTargetRestrictionReason(runtime),
                runtime
            )
        }
    }

    @Test
    fun `manual linux entries reject classified system names but allow normal apps`() {
        if (!isLinux) return

        assertNull(InstalledAppsScanner.createManualProcessEntry("dbus-broker"))
        assertNotNull(InstalledAppsScanner.createManualProcessEntry("firefox"))
    }

    @Test
    fun `nuclear escape list remains separate from protected session list`() {
        if (!isLinux) return

        val overlap = NuclearMode.escapeProcessNames
            .intersect(LinuxProcessSafety.protectedProcessNames)
        assertEquals(emptySet(), overlap)
    }
}