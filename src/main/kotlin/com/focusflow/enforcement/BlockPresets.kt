package com.focusflow.enforcement

import com.focusflow.ProcessNameNormalizer
import com.focusflow.ProcessPlatform

data class BlockPreset(
    val id: String,
    val name: String,
    val description: String,
    val emoji: String,
    val processNames: List<String>
) {
    /** Returns the process names appropriate for the current OS. */
    fun resolvedProcessNames(): List<String> = processNames
}

object BlockPresets {

    val all = listOf(
        BlockPreset(
            id = "social_media",
            name = "Social Media",
            description = "Discord, Telegram, WhatsApp, TikTok, Signal",
            emoji = "📱",
            processNames = listOf(
                "discord.exe", "telegram.exe", "whatsapp.exe", "signal.exe", "tiktok.exe"
            )
        ),
        BlockPreset(
            id = "browsers",
            name = "Web Browsers",
            description = "Chrome, Firefox, Edge, Opera, Brave",
            emoji = "🌐",
            processNames = listOf(
                "chrome.exe", "firefox.exe", "msedge.exe", "opera.exe", "brave.exe"
            )
        ),
        BlockPreset(
            id = "gaming",
            name = "Gaming",
            description = "Steam, Epic Games, Battle.net, League of Legends, Origin",
            emoji = "🎮",
            processNames = listOf(
                "steam.exe", "epicgameslauncher.exe", "battle.net.exe",
                "leagueclient.exe", "origin.exe"
            )
        ),
        BlockPreset(
            id = "entertainment",
            name = "Entertainment",
            description = "Spotify, Netflix, VLC, Twitch, Windows Media Player",
            emoji = "🎬",
            processNames = listOf(
                "spotify.exe", "netflix.exe", "vlc.exe", "twitch.exe", "wmplayer.exe"
            )
        ),
        BlockPreset(
            id = "messaging",
            name = "Messaging & Chat",
            description = "Discord, Slack, Teams, Telegram, WhatsApp, Zoom",
            emoji = "💬",
            processNames = listOf(
                "discord.exe", "slack.exe", "teams.exe", "telegram.exe",
                "whatsapp.exe", "signal.exe", "zoom.exe"
            )
        ),
        BlockPreset(
            id = "work_mode",
            name = "Work Mode",
            description = "Block all games & social — keep work tools free",
            emoji = "💼",
            processNames = listOf(
                "steam.exe", "epicgameslauncher.exe", "battle.net.exe",
                "discord.exe", "spotify.exe", "tiktok.exe",
                "twitch.exe", "leagueclient.exe", "origin.exe"
            )
        ),
        BlockPreset(
            id = "deep_focus",
            name = "Deep Focus",
            description = "Block browsers, social, games & entertainment",
            emoji = "🧠",
            processNames = listOf(
                "chrome.exe", "firefox.exe", "msedge.exe",
                "discord.exe", "slack.exe", "steam.exe",
                "epicgameslauncher.exe", "spotify.exe", "tiktok.exe",
                "twitch.exe", "telegram.exe", "whatsapp.exe",
                "netflix.exe", "leagueclient.exe"
            )
        )
    )

    val goalSuggestions: Map<String, List<String>> = mapOf(
        "social"  to listOf("social_media", "messaging"),
        "gaming"  to listOf("gaming", "entertainment"),
        "web"     to listOf("browsers", "entertainment"),
        "deep"    to listOf("deep_focus", "work_mode")
    )

    fun findById(id: String): BlockPreset? = all.find { it.id == id }

    fun presetForProcessName(processName: String): BlockPreset? =
        all.find { it.processNames.contains(processName.lowercase()) }
}

/**
 * Resolves the process references contributed by onboarding presets.
 *
 * Linux presets historically contain Windows-shaped names. When the catalog
 * knows the application, persist its canonical process name; when it does
 * not, retain a normalized stale reference so the user's choice is visible
 * and can resolve after a later catalog refresh.
 */
data class PresetProcessResolution(
    val processNames: List<String>,
    val missingReferences: List<String>
)

fun resolvePresetProcessNames(
    selectedPresetIds: Set<String>,
    catalog: List<AppDescriptor>,
    platform: ProcessPlatform = ProcessNameNormalizer.currentPlatform()
): PresetProcessResolution {
    val references = selectedPresetIds
        .mapNotNull { BlockPresets.findById(it) }
        .flatMap { it.processNames }
        .distinct()

    val missing = mutableListOf<String>()
    val resolved = references.mapNotNull { reference ->
        if (platform == ProcessPlatform.LINUX) {
            val app = InstalledAppsScanner.resolveAppReference(reference, catalog)
            if (app != null) {
                app.processName.lowercase()
            } else {
                missing += reference
                ProcessNameNormalizer.normalizeStored(reference, platform)
            }
        } else {
            ProcessNameNormalizer.normalizeStored(reference, platform)
        }
    }.distinct()

    return PresetProcessResolution(
        processNames = resolved,
        missingReferences = missing.distinct()
    )
}
