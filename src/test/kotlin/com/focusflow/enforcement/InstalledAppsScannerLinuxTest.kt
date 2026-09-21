package com.focusflow.enforcement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InstalledAppsScannerLinuxTest {

    @Test
    fun `normalizes quoted binary and desktop field codes`() {
        assertEquals(
            "google-chrome-stable",
            InstalledAppsScanner.normalizeLinuxExecForTesting(
                "\"/usr/bin/google-chrome-stable\" --profile-directory=Default %U"
            )
        )
    }

    @Test
    fun `skips env assignments before the real command`() {
        assertEquals(
            "firefox",
            InstalledAppsScanner.normalizeLinuxExecForTesting(
                "env MOZ_ENABLE_WAYLAND=1 /usr/bin/firefox %U"
            )
        )
    }

    @Test
    fun `derives stable names for flatpak app ids`() {
        assertEquals(
            "telegram-desktop",
            InstalledAppsScanner.normalizeLinuxExecForTesting(
                "flatpak run org.telegram.desktop %U"
            )
        )
        assertEquals(
            "nautilus",
            InstalledAppsScanner.normalizeLinuxExecForTesting(
                "flatpak run org.gnome.Nautilus"
            )
        )
    }

    @Test
    fun `rejects commands without an executable`() {
        assertNull(InstalledAppsScanner.normalizeLinuxExecForTesting("env FOO=bar"))
        assertNull(InstalledAppsScanner.normalizeLinuxExecForTesting("%U"))
    }
}