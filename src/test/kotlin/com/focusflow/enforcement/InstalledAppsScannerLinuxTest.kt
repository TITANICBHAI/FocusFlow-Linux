package com.focusflow.enforcement

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.io.File
import java.nio.file.Files

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

    @Test
    fun `parses localized desktop metadata and visibility fields`() {
        val app = InstalledAppsScanner.parseLinuxDesktopContentForTesting(
            content = """
                [Desktop Entry]
                Type=Application
                Name=Fallback Name
                Name[en_US]=Localized Editor
                Exec=/opt/editor --open %U
                Icon=editor-symbolic
                TryExec=/bin/sh
                Categories=Development;Utility;
            """.trimIndent(),
            desktopId = "org.example.Editor.desktop",
            localePreferences = listOf("en_US")
        )

        assertNotNull(app)
        assertEquals("Localized Editor", app.displayName)
        assertEquals("org.example.Editor.desktop", app.desktopId)
        assertEquals("editor-symbolic", app.iconName)
        assertEquals("/bin/sh", app.tryExec)
        assertEquals(listOf("Development", "Utility"), app.categories)
        assertEquals(AppSource.NATIVE_DESKTOP, app.source)
        assertEquals("editor", app.processName)
    }

    @Test
    fun `excludes hidden and no-display desktop entries`() {
        val root = Files.createTempDirectory("focusflow-desktop-test").toFile()
        try {
            File(root, "hidden.desktop").writeText(
                """
                    [Desktop Entry]
                    Type=Application
                    Name=Hidden
                    Hidden=true
                    Exec=/usr/bin/hidden
                """.trimIndent()
            )
            File(root, "helper.desktop").writeText(
                """
                    [Desktop Entry]
                    Type=Application
                    Name=Helper
                    NoDisplay=true
                    Exec=/usr/bin/helper
                """.trimIndent()
            )
            File(root, "visible.desktop").writeText(
                """
                    [Desktop Entry]
                    Type=Application
                    Name=Visible
                    Exec=/usr/bin/visible
                """.trimIndent()
            )

            val apps = InstalledAppsScanner.scanLinuxDesktopFilesForTesting(
                listOf(root to AppSource.NATIVE_DESKTOP)
            )
            assertEquals(listOf("visible"), apps.map { it.processName })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `discovers flatpak and snap sources without duplicate desktop ids`() {
        val nativeRoot = Files.createTempDirectory("focusflow-native-test").toFile()
        val exportRoot = Files.createTempDirectory("focusflow-flatpak-test").toFile()
        val snapRoot = Files.createTempDirectory("focusflow-snap-test").toFile()
        try {
            File(nativeRoot, "org.example.Editor.desktop").writeText(
                """
                    [Desktop Entry]
                    Type=Application
                    Name=Native Editor
                    Exec=/usr/bin/editor
                """.trimIndent()
            )
            File(exportRoot, "org.example.Editor.desktop").writeText(
                """
                    [Desktop Entry]
                    Type=Application
                    Name=Duplicate Export
                    Exec=flatpak run org.example.Editor %U
                    X-Flatpak=true
                """.trimIndent()
            )
            File(exportRoot, "org.example.Reader.desktop").writeText(
                """
                    [Desktop Entry]
                    Type=Application
                    Name=Flatpak Reader
                    Exec=flatpak run org.example.Reader %U
                """.trimIndent()
            )
            File(snapRoot, "snap-reader.desktop").writeText(
                """
                    [Desktop Entry]
                    Type=Application
                    Name=Snap Reader
                    Exec=snap run snap-reader
                    X-SnapInstanceName=snap-reader
                """.trimIndent()
            )

            val apps = InstalledAppsScanner.scanLinuxDesktopFilesForTesting(
                listOf(
                    nativeRoot to AppSource.NATIVE_DESKTOP,
                    exportRoot to AppSource.FLATPAK,
                    snapRoot to AppSource.SNAP
                )
            )

            assertEquals(3, apps.size)
            assertEquals("Native Editor", apps.first { it.desktopId == "org.example.Editor" }.displayName)
            assertEquals(AppSource.FLATPAK, apps.first { it.desktopId == "org.example.Reader" }.source)
            assertEquals("org.example.Reader", apps.first { it.desktopId == "org.example.Reader" }.packageId)
            assertEquals(AppSource.SNAP, apps.first { it.desktopId == "snap-reader" }.source)
            assertEquals("snap-reader", apps.first { it.desktopId == "snap-reader" }.packageId)
        } finally {
            nativeRoot.deleteRecursively()
            exportRoot.deleteRecursively()
            snapRoot.deleteRecursively()
        }
    }

    @Test
    fun `merges running processes through executable and package aliases`() {
        val installed = AppDescriptor(
            processName = "telegram-desktop",
            displayName = "Telegram",
            isRunning = false,
            exePath = "flatpak",
            packageId = "org.telegram.desktop",
            processAliases = listOf("org.telegram.desktop"),
            source = AppSource.FLATPAK
        )
        val running = AppDescriptor(
            processName = "telegram-desktop",
            displayName = "telegram-desktop",
            isRunning = true,
            exePath = "/usr/bin/flatpak",
            packageId = "org.telegram.desktop",
            processAliases = listOf("org.telegram.desktop"),
            source = AppSource.RUNNING_ONLY
        )
        val unrelated = AppDescriptor(
            processName = "custom-tool",
            displayName = "Custom Tool",
            isRunning = true,
            exePath = "/usr/bin/custom-tool",
            source = AppSource.RUNNING_ONLY
        )

        val catalog = InstalledAppsScanner.mergeInstalledAndRunningForTesting(
            installed = listOf(installed),
            running = listOf(running, unrelated)
        )

        assertTrue(catalog.first { it.processName == "telegram-desktop" }.isRunning)
        assertEquals(AppSource.FLATPAK, catalog.first { it.processName == "telegram-desktop" }.source)
        assertTrue(catalog.any { it.processName == "custom-tool" && it.source == AppSource.RUNNING_ONLY })
        assertFalse(catalog.any { it.processName == "telegram-desktop" && it.source == AppSource.RUNNING_ONLY })
    }
}