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
     * Extracts only the executable basename from an explicit filesystem path.
     *
     * This is intentionally separate from [normalizeStored]. A persisted
     * process value is not rewritten just because it contains a slash; callers
     * must opt into path interpretation at a trusted catalog/desktop-entry
     * boundary.
     */
    fun executableBasename(value: String): String? {
        val trimmed = value.trim().removeSurrounding("\"").removeSurrounding("'")
        if (trimmed.isBlank()) return null
        val basename = trimmed.substringAfterLast('/').substringAfterLast('\\')
        if (basename.isBlank() || basename.any(Char::isWhitespace)) return null
        return basename.takeIf { manualProcessPattern.matches(it.lowercase(Locale.ROOT)) }
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

    /**
     * Compares two persisted process references using the same compatibility
     * normalization used by database reads and catalog resolution.
     *
     * Unknown values are intentionally not rewritten, so an arbitrary
     * user-entered `legacy-tool.exe` remains distinct from `legacy-tool`.
     */
    fun equivalentStored(
        first: String,
        second: String,
        platform: ProcessPlatform = currentPlatform()
    ): Boolean {
        val normalizedFirst = normalizeStored(first, platform) ?: return false
        val normalizedSecond = normalizeStored(second, platform) ?: return false
        return normalizedFirst.equals(normalizedSecond, ignoreCase = true)
    }

    /**
     * Normalizes a comma-separated process list without throwing on empty or
     * malformed elements. The comma is the only supported storage delimiter;
     * arbitrary text is not split or reinterpreted.
     */
    fun normalizeStoredList(
        raw: String?,
        platform: ProcessPlatform = currentPlatform()
    ): List<String> =
        raw.orEmpty()
            .split(',')
            .mapNotNull { normalizeStored(it, platform) }
            .distinct()

    /** Same list contract for values that have already been split by a caller. */
    fun normalizeStoredList(
        values: Iterable<String>,
        platform: ProcessPlatform = currentPlatform()
    ): List<String> =
        values
            .mapNotNull { normalizeStored(it, platform) }
            .distinct()

    /**
     * Canonicalizes catalog-provided aliases. Alias meaning comes from the
     * catalog; this helper only applies process syntax and stable ordering.
     */
    fun normalizeAliases(
        aliases: Iterable<String>,
        platform: ProcessPlatform = currentPlatform()
    ): List<String> = normalizeStoredList(aliases, platform)

    fun isKnownWindowsExecutable(value: String): Boolean =
        value.trim().lowercase(Locale.ROOT) in knownWindowsExecutableNames
}