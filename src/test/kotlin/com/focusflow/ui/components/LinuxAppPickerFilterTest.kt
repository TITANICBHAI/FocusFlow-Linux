package com.focusflow.ui.components

import com.focusflow.enforcement.AppDescriptor
import com.focusflow.enforcement.AppSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinuxAppPickerFilterTest {
    private val nativeEditor = AppDescriptor(
        processName = "editor",
        displayName = "Workspace Editor",
        isRunning = false,
        desktopId = "org.example.Editor",
        source = AppSource.NATIVE_DESKTOP
    )

    private val flatpakTelegram = AppDescriptor(
        processName = "telegram-desktop",
        displayName = "Telegram",
        isRunning = true,
        desktopId = "org.telegram.desktop",
        packageId = "org.telegram.desktop",
        source = AppSource.FLATPAK
    )

    private val runningOnly = AppDescriptor(
        processName = "custom-tool",
        displayName = "Custom Tool",
        isRunning = true,
        source = AppSource.RUNNING_ONLY
    )

    private val manual = AppDescriptor(
        processName = "my-focus-helper",
        displayName = "My Focus Helper",
        isRunning = false,
        source = AppSource.MANUAL
    )

    private val catalog = listOf(nativeEditor, flatpakTelegram, runningOnly, manual)

    @Test
    fun `search matches display process desktop and package identity`() {
        assertEquals(
            listOf(nativeEditor),
            filterAppCatalog(catalog, "workspace", AppPickerPresenceFilter.ALL, AppPickerSourceFilter.ALL)
        )
        assertEquals(
            listOf(nativeEditor),
            filterAppCatalog(catalog, "editor", AppPickerPresenceFilter.ALL, AppPickerSourceFilter.ALL)
        )
        assertEquals(
            listOf(nativeEditor),
            filterAppCatalog(catalog, "org.example.editor", AppPickerPresenceFilter.ALL, AppPickerSourceFilter.ALL)
        )
        assertEquals(
            listOf(flatpakTelegram),
            filterAppCatalog(catalog, "org.telegram.desktop", AppPickerPresenceFilter.ALL, AppPickerSourceFilter.ALL)
        )
    }

    @Test
    fun `presence and source filters distinguish installed running and manual apps`() {
        assertEquals(
            listOf(nativeEditor, flatpakTelegram),
            filterAppCatalog(catalog, "", AppPickerPresenceFilter.INSTALLED, AppPickerSourceFilter.ALL)
        )
        assertEquals(
            listOf(flatpakTelegram, runningOnly),
            filterAppCatalog(catalog, "", AppPickerPresenceFilter.RUNNING, AppPickerSourceFilter.ALL)
        )
        assertEquals(
            listOf(flatpakTelegram),
            filterAppCatalog(catalog, "", AppPickerPresenceFilter.ALL, AppPickerSourceFilter.FLATPAK)
        )
        assertEquals(
            listOf(manual),
            filterAppCatalog(catalog, "", AppPickerPresenceFilter.ALL, AppPickerSourceFilter.MANUAL)
        )
        assertTrue(
            filterAppCatalog(catalog, "", AppPickerPresenceFilter.ALL, AppPickerSourceFilter.ALL)
                .containsAll(catalog)
        )
    }
}