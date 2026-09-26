package com.focusflow

import java.util.Locale

enum class ProcessPlatform {
    WINDOWS,
    LINUX,
    OTHER
}

/**
 * Canonicalizes values that represent executable/process identities.
 *
 * This deliberately does not interpret arbitrary text as a process name. The
 * permissive [normalizeStored] function is for existing persisted values and
 * keeps unknown values intact; [normalizeManual] is the stricter API for a new
 * manual process entry.
 */
object ProcessNameNormalizer {
    private val manualProcessPattern = Regex("^[a-z0-9][a-z0-9_.+\\-]*$")

    /**
     * These are the Windows-shaped values FocusFlow has historically generated
     * for its built-in app choices. Only these known values are converted on
     * Linux; an arbitrary user value such as `legacy-tool.exe` is preserved.
     */
    private val knownWindowsExecutableNames = setOf(
        "chrome.exe",
        "firefox.exe",
        "msedge.exe",
        "opera.exe",
        "brave.exe",
        "discord.exe",
        "slack.exe",
        "teams.exe",
        "zoom.exe",
        "telegram.exe",
        "whatsapp.exe",
        "signal.exe",
        "spotify.exe",
        "steam.exe",
        "epicgameslauncher.exe",
        "origin.exe",
        "battle.net.exe",
        "leagueclient.exe",
        "twitch.exe",
        "obs64.exe",
        "tiktok.exe",
        "netflix.exe",
        "vlc.exe",
        "wmplayer.exe",
        "itunes.exe",
        "outlook.exe",
        "winword.exe",
        "excel.exe",
        "powerpnt.exe",
        "notepad.exe",
        "notepad++.exe",
        "code.exe",
        "devenv.exe",
        "idea64.exe",
        "pycharm64.exe",
        "webstorm64.exe",
        "clion64.exe",
        "studio64.exe",
        "nordvpn.exe",
        "expressvpn.exe",
        "protonvpn.exe",
        "surfshark.exe",
        "cyberghost.exe",
        "windscribe.exe",
        "mullvad.exe",
        "pia.exe",
        "ipvanish.exe",
        "tunnelbear.exe",
        "vyprvpn.exe",
        "torguard.exe",
        "openvpn.exe",
        "wireguard.exe",
        "tor.exe",
        "anyconnect.exe",
        "globalprotect.exe",
        "psiphon3.exe"
    )

    fun currentPlatform(): ProcessPlatform = when {
        IS_WINDOWS -> ProcessPlatform.WINDOWS
        IS_LINUX -> ProcessPlatform.LINUX
        else -> ProcessPlatform.OTHER
    }

    /**
     * Normalizes a new process value for the target platform. Windows keeps its
     * compatibility `.exe` convention; Linux never receives a suffix.
     */
    fun normalize(value: String, platform: ProcessPlatform = currentPlatform()): String? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        val lower = trimmed.lowercase(Locale.ROOT)
        return if (platform == ProcessPlatform.WINDOWS && !lower.endsWith(".exe")) {
            "$lower.exe"
        } else {
            lower
        }
    }

    /**
     * Strict normalization for a user-entered manual process name.
     * Paths, whitespace, shell metacharacters, and empty values are rejected.
     */
    fun normalizeManual(
        value: String,
        platform: ProcessPlatform = currentPlatform()
    ): String? {
        val normalized = normalize(value, platform) ?: return null
        return normalized.takeIf { manualProcessPattern.matches(it) }
    }

    /**
     * Reads an existing stored process reference without dropping it. On Linux,
     * only known Windows-generated values lose their invalid `.exe` suffix.
     */
    fun normalizeStored(
        value: String,
        platform: ProcessPlatform = currentPlatform()
    ): String? {
        val normalized = normalize(value, platform) ?: return null
        if (platform != ProcessPlatform.LINUX) return normalized
        return if (normalized in knownWindowsExecutableNames) {
            normalized.removeSuffix(".exe")
        } else {
            normalized
        }
    }

    fun isKnownWindowsExecutable(value: String): Boolean =
        value.trim().lowercase(Locale.ROOT) in knownWindowsExecutableNames
}