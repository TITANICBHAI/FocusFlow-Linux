package com.focusflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `stored equivalence maps known windows values but preserves arbitrary exe values`() {
        assertEquals(
            true,
            ProcessNameNormalizer.equivalentStored(
                "Chrome.exe",
                "chrome",
                ProcessPlatform.LINUX
            )
        )
        assertEquals(
            false,
            ProcessNameNormalizer.equivalentStored(
                "legacy-tool.exe",
                "legacy-tool",
                ProcessPlatform.LINUX
            )
        )
    }

    @Test
    fun `executable basename handles trusted unix and windows paths`() {
        assertEquals(
            "firefox.exe",
            ProcessNameNormalizer.executableBasename(
                "\"C:\\Program Files\\Mozilla Firefox\\firefox.exe\""
            )
        )
        assertEquals(
            "firefox",
            ProcessNameNormalizer.executableBasename("/usr/bin/firefox")
        )
        assertNull(ProcessNameNormalizer.executableBasename("/usr/bin/firefox --private-window"))
        assertNull(ProcessNameNormalizer.executableBasename("/usr/bin/"))
    }

    @Test
    fun `aliases normalize with stable first-seen deduplication`() {
        assertEquals(
            listOf("discord", "discord-helper", "legacy-tool.exe"),
            ProcessNameNormalizer.normalizeAliases(
                listOf("Discord.exe", "discord", "DISCORD-HELPER", " ", "legacy-tool.exe"),
                ProcessPlatform.LINUX
            )
        )
    }

    @Test
    fun `malformed comma lists ignore empty values without throwing`() {
        assertEquals(
            listOf("chrome", "firefox", "legacy-tool.exe"),
            ProcessNameNormalizer.normalizeStoredList(
                " chrome.exe,, ,firefox.exe,legacy-tool.exe,chrome.exe,",
                ProcessPlatform.LINUX
            )
        )
        assertEquals(
            emptyList(),
            ProcessNameNormalizer.normalizeStoredList(",,,   ", ProcessPlatform.LINUX)
        )
        assertTrue(
            ProcessNameNormalizer.normalizeStoredList("one;two", ProcessPlatform.LINUX)
                .single() == "one;two"
        )
    }
}