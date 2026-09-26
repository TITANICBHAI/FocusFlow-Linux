package com.focusflow.enforcement

import com.focusflow.ProcessPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlockPresetsTest {
    @Test
    fun `linux preset resolution uses catalog process names and keeps missing entries stale`() {
        val firefox = AppDescriptor(
            processName = "firefox",
            displayName = "Firefox",
            desktopId = "org.mozilla.firefox",
            isRunning = false,
            source = AppSource.NATIVE_DESKTOP
        )

        val resolution = resolvePresetProcessNames(
            selectedPresetIds = setOf("browsers"),
            catalog = listOf(firefox),
            platform = ProcessPlatform.LINUX
        )

        assertTrue("firefox" in resolution.processNames)
        assertTrue("chrome" in resolution.processNames)
        assertTrue("chrome.exe" in resolution.missingReferences)
        assertTrue(resolution.processNames.none { it.endsWith(".exe") })
    }

    @Test
    fun `windows preset resolution keeps executable compatibility`() {
        val resolution = resolvePresetProcessNames(
            selectedPresetIds = setOf("gaming"),
            catalog = emptyList(),
            platform = ProcessPlatform.WINDOWS
        )

        assertEquals(
            listOf(
                "steam.exe",
                "epicgameslauncher.exe",
                "battle.net.exe",
                "leagueclient.exe",
                "origin.exe"
            ),
            resolution.processNames
        )
        assertTrue(resolution.missingReferences.isEmpty())
    }
}