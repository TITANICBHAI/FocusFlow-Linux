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
    fun `canonical reference has an internal ID and allows processless identity`() {
        val reference = CanonicalAppReference(
            referenceId = "foc-firefox",
            stableAppId = "org.mozilla.firefox",
            displayName = "Firefox",
            legacyProcessName = null,
            source = AppReferenceSource.CATALOG_NATIVE,
            resolutionStatus = AppResolutionStatus.RESOLVED
        )

        assertEquals("foc-firefox", reference.referenceId)
        assertEquals("org.mozilla.firefox", reference.stableAppId)
        assertEquals(null, reference.legacyProcessName)
        assertEquals(null, reference.primaryProcessName)
        assertEquals(emptyList(), reference.runtimeDefinitions)
        assertEquals(null, reference.launchDefinitionId)
        assertEquals(AppResolutionStatus.RESOLVED, reference.resolutionStatus)
    }
}