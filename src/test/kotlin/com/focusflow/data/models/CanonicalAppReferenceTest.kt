package com.focusflow.data.models

import kotlin.test.Test
import kotlin.test.assertEquals

class CanonicalAppReferenceTest {
    @Test
    fun `source and resolution statuses have stable wire values`() {
        assertEquals("catalog_native", AppReferenceSource.CATALOG_NATIVE.wireValue)
        assertEquals("catalog_flatpak", AppReferenceSource.CATALOG_FLATPAK.wireValue)
        assertEquals("catalog_snap", AppReferenceSource.CATALOG_SNAP.wireValue)
        assertEquals("windows_registry", AppReferenceSource.WINDOWS_REGISTRY.wireValue)
        assertEquals("running_only", AppReferenceSource.RUNNING_ONLY.wireValue)
        assertEquals("manual", AppReferenceSource.MANUAL.wireValue)
        assertEquals("legacy", AppReferenceSource.LEGACY.wireValue)

        assertEquals("resolved", AppResolutionStatus.RESOLVED.wireValue)
        assertEquals("stale", AppResolutionStatus.STALE.wireValue)
        assertEquals("unresolved", AppResolutionStatus.UNRESOLVED.wireValue)
        assertEquals("ambiguous", AppResolutionStatus.AMBIGUOUS.wireValue)
    }

    @Test
    fun `canonical reference retains process enforcement beside stable identity`() {
        val reference = CanonicalAppReference(
            stableId = "org.mozilla.firefox",
            displayName = "Firefox",
            primaryProcessName = "firefox",
            processAliases = listOf("firefox-bin"),
            source = AppReferenceSource.CATALOG_NATIVE,
            desktopFilePath = "/usr/share/applications/firefox.desktop",
            resolutionStatus = AppResolutionStatus.RESOLVED
        )

        assertEquals("org.mozilla.firefox", reference.stableId)
        assertEquals("firefox", reference.primaryProcessName)
        assertEquals(listOf("firefox-bin"), reference.processAliases)
        assertEquals(AppResolutionStatus.RESOLVED, reference.resolutionStatus)
    }
}