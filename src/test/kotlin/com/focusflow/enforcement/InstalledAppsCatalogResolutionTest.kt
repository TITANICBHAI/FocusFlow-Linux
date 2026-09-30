package com.focusflow.enforcement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `resolves known stale values through app aliases as well as process names`() {
        val chrome = firefox.copy(
            processName = "chrome",
            displayName = "Chrome",
            processAliases = listOf("chrome.exe")
        )

        assertEquals(
            chrome,
            InstalledAppsScanner.resolveAppReferenceForTesting("chrome.exe", listOf(chrome))
        )
    }

    @Test
    fun `ambiguous process identities are not resolved to the first catalog item`() {
        val first = firefox.copy(
            processName = "shared-runtime",
            displayName = "First App",
            desktopId = "org.example.first",
            packageId = null,
            processAliases = emptyList()
        )
        val second = firefox.copy(
            processName = "shared-runtime",
            displayName = "Second App",
            desktopId = "org.example.second",
            packageId = null,
            processAliases = emptyList()
        )

        val resolution = InstalledAppsScanner.resolveAppReferenceDetailedForTesting(
            "shared-runtime",
            listOf(first, second)
        )

        assertEquals(AppCatalogReferenceStatus.AMBIGUOUS, resolution.status)
        assertNull(resolution.app)
        assertEquals(setOf(first, second), resolution.candidates.toSet())
    }

    @Test
    fun `exact stable identity wins over a weaker process-name collision`() {
        val exact = firefox.copy(
            processName = "other-process",
            desktopId = "org.example.target",
            packageId = null
        )
        val weak = firefox.copy(
            processName = "org.example.target",
            displayName = "Different App",
            desktopId = "org.example.different",
            packageId = null
        )

        val resolution = InstalledAppsScanner.resolveAppReferenceDetailedForTesting(
            "org.example.target",
            listOf(weak, exact)
        )

        assertEquals(AppCatalogReferenceStatus.RESOLVED, resolution.status)
        assertEquals(exact, resolution.app)
    }

    @Test
    fun `catalog selection model reports all ambiguity candidates`() {
        val first = firefox.copy(
            processName = "shared",
            desktopId = "org.example.one",
            packageId = null
        )
        val second = firefox.copy(
            processName = "shared",
            desktopId = "org.example.two",
            packageId = null
        )

        val result = InstalledAppsScanner.resolveAppReferenceDetailedForTesting(
            "shared",
            listOf(first, second)
        )

        assertTrue(result.candidates.size == 2)
        assertEquals(AppCatalogReferenceStatus.AMBIGUOUS, result.status)
    }
}