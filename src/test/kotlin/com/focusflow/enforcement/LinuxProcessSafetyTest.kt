package com.focusflow.enforcement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxProcessSafetyTest {

    @Test
    fun `linux launcher safety list includes focusflow and session infrastructure`() {
        if (!isLinux) return

        val names = LinuxProcessSafety.launcherSafeProcessNames
        assertTrue("java" in names)
        assertTrue("focusflow" in names)
        assertTrue("gnome-shell" in names)
        assertTrue("plasmashell" in names)
        assertTrue("dbus-broker" in names)
        assertTrue("xdg-desktop-portal" in names)
        assertTrue("ibus-daemon" in names)
    }

    @Test
    fun `current focusflow process is protected by pid`() {
        if (!isLinux) return

        val ownPid = ProcessHandle.current().pid()
        assertTrue(LinuxProcessSafety.isProtectedProcess(ownPid, "unusual-focusflow-wrapper"))
        assertTrue(LinuxProcessSafety.isProtectedProcess(ownPid))
    }

    @Test
    fun `manual linux entries reject protected process names`() {
        if (!isLinux) return

        assertNull(InstalledAppsScanner.createManualProcessEntry("focusflow"))
        assertNull(InstalledAppsScanner.createManualProcessEntry("java"))
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