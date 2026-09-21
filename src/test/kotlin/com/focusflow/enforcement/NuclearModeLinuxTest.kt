package com.focusflow.enforcement

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NuclearModeLinuxTest {

    @Test
    fun `linux escape list includes current desktop escape tools`() {
        val names = NuclearMode.escapeProcessNames

        assertTrue("gnome-system-monitor" in names)
        assertTrue("plasma-systemmonitor" in names)
        assertTrue("xfce4-taskmanager" in names)
        assertTrue("kgx" in names)
        assertTrue("ptyxis" in names)
        assertTrue("foot" in names)
        assertTrue("rofi" in names)
        assertTrue("kde-systemmonitor" in names)
        assertTrue("gnome-usage" in names)
        assertTrue("lxqt-taskmanager" in names)
    }

    @Test
    fun `linux escape list excludes system critical desktop infrastructure`() {
        val names = NuclearMode.escapeProcessNames

        assertFalse("gnome-shell" in names)
        assertFalse("kwin_wayland" in names)
        assertFalse("Xwayland" in names)
        assertFalse("systemd" in names)
        assertFalse("dbus-daemon" in names)
    }
}