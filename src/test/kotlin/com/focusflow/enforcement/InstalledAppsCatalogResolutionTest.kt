package com.focusflow.enforcement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InstalledAppsCatalogResolutionTest {
    private val firefox = AppDescriptor(
        processName = "firefox",
        displayName = "Firefox",
        isRunning = false,
        desktopId = "org.mozilla.firefox",
        packageId = "org.mozilla.firefox",
        processAliases = listOf("firefox-bin"),
        source = AppSource.NATIVE_DESKTOP
    )

    @Test
    fun `resolves stable ids display names process names and aliases`() {
        assertEquals(firefox, InstalledAppsScanner.resolveAppReferenceForTesting("org.mozilla.firefox", listOf(firefox)))
        assertEquals(firefox, InstalledAppsScanner.resolveAppReferenceForTesting("Firefox", listOf(firefox)))
        assertEquals(firefox, InstalledAppsScanner.resolveAppReferenceForTesting("firefox-bin", listOf(firefox)))
    }

    @Test
    fun `resolves known stale windows process value using linux form`() {
        val chrome = firefox.copy(
            processName = "chrome",
            displayName = "Chrome",
            desktopId = "google-chrome",
            packageId = null,
            processAliases = emptyList()
        )

        assertEquals(
            chrome,
            InstalledAppsScanner.resolveAppReferenceForTesting("chrome.exe", listOf(chrome))
        )
    }

    @Test
    fun `does not map unknown stale exe value to a different process`() {
        val app = firefox.copy(processName = "legacy-tool", displayName = "Legacy Tool")

        assertNull(
            InstalledAppsScanner.resolveAppReferenceForTesting("legacy-tool.exe", listOf(app))
        )
    }
}