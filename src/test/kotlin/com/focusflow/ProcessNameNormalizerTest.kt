package com.focusflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProcessNameNormalizerTest {
    @Test
    fun `windows keeps compatibility exe suffix while linux does not add it`() {
        assertEquals(
            "firefox.exe",
            ProcessNameNormalizer.normalize("Firefox", ProcessPlatform.WINDOWS)
        )
        assertEquals(
            "firefox",
            ProcessNameNormalizer.normalize("Firefox", ProcessPlatform.LINUX)
        )
        assertEquals(
            "firefox.exe",
            ProcessNameNormalizer.normalizeManual("Firefox.exe", ProcessPlatform.LINUX)
        )
    }

    @Test
    fun `manual process names reject paths and shell text`() {
        assertNull(ProcessNameNormalizer.normalizeManual("/usr/bin/firefox", ProcessPlatform.LINUX))
        assertNull(ProcessNameNormalizer.normalizeManual("firefox --private-window", ProcessPlatform.LINUX))
        assertNull(ProcessNameNormalizer.normalizeManual("   ", ProcessPlatform.LINUX))
        assertEquals(
            "focus-helper",
            ProcessNameNormalizer.normalizeManual(" Focus-Helper ", ProcessPlatform.LINUX)
        )
    }

    @Test
    fun `stored linux normalization only changes known generated windows values`() {
        assertEquals(
            "chrome",
            ProcessNameNormalizer.normalizeStored("chrome.exe", ProcessPlatform.LINUX)
        )
        assertEquals(
            "legacy-tool.exe",
            ProcessNameNormalizer.normalizeStored("legacy-tool.exe", ProcessPlatform.LINUX)
        )
        assertEquals(
            "legacy-tool.exe",
            ProcessNameNormalizer.normalizeStored("legacy-tool.exe", ProcessPlatform.WINDOWS)
        )
    }
}