package com.focusflow.enforcement

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.util.concurrent.ConcurrentHashMap

data class ScannedApp(
    val processName: String,
    val displayName: String,
    val isRunning: Boolean,
    val exePath: String? = null,
    /** Original desktop-file Exec= value, retained for diagnostics and launchers. */
    val execCommand: String? = null,
    /** Source .desktop file used to discover this Linux application. */
    val desktopFilePath: String? = null
)

object InstalledAppsScanner {

    private val windowsCurated = mapOf(
        "chrome.exe"            to "Google Chrome",
        "firefox.exe"           to "Mozilla Firefox",
        "msedge.exe"            to "Microsoft Edge",
        "opera.exe"             to "Opera",
        "brave.exe"             to "Brave Browser",
        "discord.exe"           to "Discord",
        "slack.exe"             to "Slack",
        "teams.exe"             to "Microsoft Teams",
        "zoom.exe"              to "Zoom",
        "telegram.exe"          to "Telegram",
        "whatsapp.exe"          to "WhatsApp",
        "signal.exe"            to "Signal",
        "spotify.exe"           to "Spotify",
        "steam.exe"             to "Steam",
        "epicgameslauncher.exe" to "Epic Games Launcher",
        "origin.exe"            to "EA Origin",
        "battle.net.exe"        to "Battle.net",
        "leagueclient.exe"      to "League of Legends",
        "twitch.exe"            to "Twitch",
        "obs64.exe"             to "OBS Studio",
        "tiktok.exe"            to "TikTok",
        "netflix.exe"           to "Netflix",
        "vlc.exe"               to "VLC Media Player",
        "wmplayer.exe"          to "Windows Media Player",
        "itunes.exe"            to "iTunes",
        "outlook.exe"           to "Microsoft Outlook",
        "winword.exe"           to "Microsoft Word",
        "excel.exe"             to "Microsoft Excel",
        "powerpnt.exe"          to "Microsoft PowerPoint",
        "notepad.exe"           to "Notepad",
        "notepad++.exe"         to "Notepad++",
        "code.exe"              to "Visual Studio Code",
        "devenv.exe"            to "Visual Studio",
        "idea64.exe"            to "IntelliJ IDEA",
        "pycharm64.exe"         to "PyCharm",
        "webstorm64.exe"        to "WebStorm",
        "clion64.exe"           to "CLion",
        "studio64.exe"          to "Android Studio"
    )

    private val linuxCurated = mapOf(
        "chrome"       to "Google Chrome",
        "firefox"      to "Mozilla Firefox",
        "discord"      to "Discord",
        "slack"        to "Slack",
        "teams"        to "Microsoft Teams",
        "zoom"         to "Zoom",
        "telegram-desktop" to "Telegram",
        "whatsapp"     to "WhatsApp",
        "signal"       to "Signal",
        "spotify"      to "Spotify",
        "steam"        to "Steam",
        "vlc"          to "VLC Media Player",
        "obs"          to "OBS Studio",
        "code"         to "Visual Studio Code",
        "notion"       to "Notion",
        "thunderbird"  to "Thunderbird"
    )

    private val curated: Map<String, String> get() = when {
        isWindows -> windowsCurated
        isLinux   -> linuxCurated
        else      -> emptyMap()
    }

    private val windowsSystemIgnore = setOf(
        "system", "system idle process", "registry", "smss.exe", "csrss.exe",
        "wininit.exe", "winlogon.exe", "lsass.exe", "svchost.exe", "services.exe",
        "spoolsv.exe", "searchindexer.exe", "audiodg.exe", "dwm.exe", "conhost.exe",
        "dllhost.exe", "rundll32.exe", "wermgr.exe", "wmiprvse.exe", "msiexec.exe",
        "fontdrvhost.exe", "sihost.exe", "taskhostw.exe", "explorer.exe",
        "securityhealthsystray.exe", "runtimebroker.exe", "applicationframehost.exe",
        "shellexperiencehost.exe", "startmenuexperiencehost.exe", "searchhost.exe",
        "ctfmon.exe", "textinputhost.exe", "lockapp.exe", "logonui.exe",
        "userinit.exe", "wlanext.exe", "dashost.exe", "igfxem.exe", "igfxhk.exe",
        "nvdisplay.container.exe", "amdow.exe", "focusflow.exe"
    )

    // Linux system processes to ignore — daemons and infrastructure
    private val linuxSystemIgnore = setOf(
        "systemd", "init", "kthreadd", "ksoftirqd", "kworker", "migration",
        "kdevtmpfs", "netns", "rcu_sched", "rcu_bh", "rcu_par", "watchdog",
        "jbd2", "ext4", "kauditd", "khungtaskd", "oom_reaper",
        "writeback", "kiperf", "ksmd", "khugepaged", "kcompac", "kernfsd",
        "kthrotld", "kirqd", "kintegrityd", "kblockd", "ata_sff",
        "edac-poll", "pwolf",
        "dbus-daemon", "dbus-broker", "pipewire", "pulseaudio", "wireplumber",
        // Desktop managers
        "gdm", "sddm", "lightdm", "lxdm", "xsession",
        // Compositors / window managers
        "Xorg", "Xwayland", "mutter", "kwin_x11", "kwin_wayland",
        "compositor", "weston", "xfwm4", "openbox", "fluxbox",
        // Shell panel components (not user-launched shells)
        "panel", "lxpanel", "fbpanel", "picom", "compton",
        // File managers (not user)
        "nautilus", "nemo", "thunar", "dolphin", "pcmanfm",
        // Network
        "NetworkManager", "wpa_supplicant", "dhcpcd", "dhclient",
        // Polkit
        "polkitd", "polkit", "pk-launch",
        // FocusFlow itself
        "focusflow", "java", "kotlin"
    )

    private val systemIgnore: Set<String> get() = when {
        isWindows -> windowsSystemIgnore
        isLinux   -> linuxSystemIgnore
        else       -> emptySet()
    }

    /**
     * Exe-path lookup cache.
     * Populated by getRunningApps() and getInstalledApps().
     * Used by AppIcon to resolve real icons without re-scanning.
     */
    private val exePathCache = ConcurrentHashMap<String, String>()
    private val desktopFileCache = ConcurrentHashMap<String, String>()
    private val execCommandCache = ConcurrentHashMap<String, String>()

    /** Installed-apps registry scan — lazily populated, cached for the session. */
    private val installedCache = mutableListOf<ScannedApp>()
    private var installedScanned = false
    private val installLock = Any()

    // ── Public API ────────────────────────────────────────────────────────────

    fun getRunningApps(): List<ScannedApp> {
        val running: List<ScannedApp> = try {
            ProcessHandle.allProcesses().toList()
                .mapNotNull { ph ->
                    // Use orElse(null) on a single call to avoid the TOCTOU race where
                    // isPresent() returns true but the process exits before get() is called,
                    // causing NoSuchElementException on the second command() invocation.
                    val info = ph.info()
                    val cmd = info.command().orElse(null) ?: return@mapNotNull null
                    val commandLine = info.commandLine().orElse("")
                    val commandName = java.io.File(cmd).name.lowercase()
                    // Flatpak's host process is reported as "flatpak" by
                    // ProcessHandle.command(), while its command line contains
                    // the app ID. Use the same normalization as desktop files
                    // so installed and running entries share a process key.
                    val exe = if (isLinux && commandName == "flatpak") {
                        normalizeLinuxExec(commandLine)?.processName ?: commandName
                    } else {
                        commandName
                    }
                    val display = curated[exe] ?: friendlyName(exe)
                    ScannedApp(
                        processName = exe,
                        displayName = display,
                        isRunning = true,
                        exePath = cmd,
                        execCommand = commandLine.takeIf { it.isNotBlank() }
                    )
                }
                .filter { app ->
                    app.processName.isNotBlank() &&
                    app.processName !in systemIgnore &&
                    (isLinux || app.processName.endsWith(".exe"))
                }
                .distinctBy { it.processName }
        } catch (e: Exception) { emptyList() }

        // Populate path cache from running processes (most accurate paths)
        running.forEach { app ->
            if (app.exePath != null) exePathCache[app.processName] = app.exePath
        }

        return running.sortedBy { it.displayName }
    }

    /**
     * Scans the Windows Registry for installed applications and returns them
     * with real exe paths. Results are cached for the life of the process;
     * the first call may take 200–400 ms on a typical machine.
     */
    fun getInstalledApps(): List<ScannedApp> {
        synchronized(installLock) {
            if (!installedScanned) {
                installedCache.clear()
                installedCache.addAll(scanRegistry())
                installedScanned = true
            }
            return installedCache.toList()
        }
    }

    /**
     * Returns apps that are verifiably on this machine — registry-installed apps
     * (with a real, existing .exe) plus any currently-running user processes not
     * already in the registry results.  The old hardcoded fallback list is NOT
     * included because it contained entries (TikTok, iTunes, Netflix, etc.) that
     * are rarely installed on a given PC and confused users.
     */
    fun getCuratedApps(): List<ScannedApp> {
        val installed   = getInstalledApps()
        val installedEx = installed.map { it.processName }.toSet()
        val running     = getRunningApps()
            .filter { it.processName !in installedEx }
        return (installed + running).sortedBy { it.displayName }
    }

    /** Look up the exe path for a process name using the cache built by any prior scan. */
    fun getExePathFor(processName: String): String? =
        exePathCache[processName.lowercase()]

    /** Returns the Linux desktop file associated with a normalized process name. */
    fun getDesktopFileForProcess(processName: String): String? =
        desktopFileCache[processName.trim().lowercase()]

    /**
     * Resolve a desktop file from the executable path passed to the icon loader.
     * The optional process name is useful for launchers such as Flatpak, whose
     * Exec= binary is "flatpak" while the actual app process has another name.
     */
    fun getDesktopFileForExecutable(
        exePath: String,
        processName: String? = null
    ): String? {
        if (isLinux && !installedScanned) {
            try { getInstalledApps() } catch (_: Exception) {}
        }
        val processKey = processName?.trim()?.lowercase()
        if (!processKey.isNullOrBlank()) {
            desktopFileCache[processKey]?.let { return it }
        }
        val executableKey = java.io.File(exePath).name.lowercase()
        return desktopFileCache[executableKey]
    }

    /** Returns the original Linux Exec= command for diagnostics or launchers. */
    fun getExecCommandFor(processName: String): String? =
        execCommandCache[processName.trim().lowercase()]

    fun friendlyNameFor(processName: String): String =
        curated[processName.lowercase()] ?: friendlyName(processName.lowercase())

    // ── Registry scan ─────────────────────────────────────────────────────────

    private fun scanRegistry(): List<ScannedApp> {
        if (isLinux) return scanLinuxDesktopFiles()
        if (!isWindows) return emptyList()

        val result  = mutableMapOf<String, ScannedApp>() // key = processName
        val regKeys = listOf(
            WinReg.HKEY_LOCAL_MACHINE to "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            WinReg.HKEY_LOCAL_MACHINE to "SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            WinReg.HKEY_CURRENT_USER  to "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall"
        )

        for ((hive, key) in regKeys) {
            val subkeys = try {
                Advapi32Util.registryGetKeys(hive, key)
            } catch (e: Exception) { continue }

            for (sub in subkeys) {
                try {
                    val vals = Advapi32Util.registryGetValues(hive, "$key\\$sub")

                    val displayName = (vals["DisplayName"] as? String)?.trim()
                        ?.takeIf { it.isNotBlank() } ?: continue

                    // DisplayIcon is usually "C:\path\to\app.exe,0" or just the path
                    val displayIcon = (vals["DisplayIcon"] as? String)?.trim()
                        ?.takeIf { it.isNotBlank() } ?: continue

                    // Strip icon index suffix (e.g. ",0") and surrounding quotes
                    val rawPath = displayIcon
                        .substringBefore(",")
                        .trim()
                        .removeSurrounding("\"")

                    if (!rawPath.endsWith(".exe", ignoreCase = true)) continue

                    val exeFile = java.io.File(rawPath)
                    if (!exeFile.exists()) continue

                    val processName = exeFile.name.lowercase()
                    if (processName in systemIgnore) continue
                    if (!processName.endsWith(".exe")) continue

                    // Earlier results (HKLM 64-bit) take priority; don't overwrite
                    if (processName in result) continue

                    val friendlyDisplay = curated[processName] ?: displayName

                    val app = ScannedApp(
                        processName = processName,
                        displayName = friendlyDisplay,
                        isRunning   = false,
                        exePath     = rawPath
                    )
                    result[processName] = app
                    exePathCache.putIfAbsent(processName, rawPath)

                } catch (e: Exception) { /* malformed key — skip */ }
            }
        }

        return result.values
            .filter { it.processName !in systemIgnore }
            .sortedBy { it.displayName }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    // ── Linux desktop-file scan ─────────────────────────────────────────────

    /**
     * Scans .desktop files from /usr/share/applications and ~/.local/share/applications
     * for installed applications, parsing Name= and Exec= fields.
     * Note: DE-specific; may not find all Flatpak/Snap apps.
     */
    private fun scanLinuxDesktopFiles(): List<ScannedApp> {
        val result = mutableMapOf<String, ScannedApp>()
        val home = System.getProperty("user.home")
        val dirs = listOf(
            java.io.File("/usr/share/applications"),
            java.io.File("$home/.local/share/applications"),
            java.io.File("$home/.local/share/flatpak/exports/share/applications"),
            java.io.File("/var/lib/flatpak/exports/share/applications"),
            java.io.File("/var/lib/snapd/desktop/applications")
        ).filter { it.isDirectory }
        for (dir in dirs) {
            val files = try {
                dir.listFiles()
                    ?.filter { it.isFile && it.extension.equals("desktop", ignoreCase = true) }
                    ?.sortedBy { it.name }
            } catch (_: Exception) { null } ?: continue
            for (f in files) {
                try {
                    val entry = parseLinuxDesktopFile(f) ?: continue
                    val normalized = normalizeLinuxExec(entry.exec) ?: continue
                    val procName = normalized.processName
                    if (procName in systemIgnore) continue
                    if (procName in result) continue

                    val display = curated[procName] ?: entry.name
                    val app = ScannedApp(
                        processName    = procName,
                        displayName    = display,
                        isRunning      = false,
                        exePath        = normalized.command,
                        execCommand    = normalized.fullCommand,
                        desktopFilePath = f.absolutePath
                    )
                    result[procName] = app
                    exePathCache.putIfAbsent(procName, normalized.command)
                    desktopFileCache[procName] = f.absolutePath
                    execCommandCache[procName] = normalized.fullCommand

                    // A normal binary can be looked up by its executable basename.
                    // Do not cache "flatpak" itself because many desktop files use
                    // that launcher and would overwrite one another.
                    val commandName = java.io.File(normalized.command).name.lowercase()
                    if (commandName != "flatpak" && commandName != "env") {
                        desktopFileCache.putIfAbsent(commandName, f.absolutePath)
                    }
                } catch (e2: Exception) { /* skip malformed desktop file */ }
            }
        }
        return result.values.sortedBy { it.displayName }
    }

    private data class LinuxDesktopEntry(
        val name: String,
        val exec: String
    )

    private data class NormalizedLinuxExec(
        val processName: String,
        val command: String,
        val fullCommand: String
    )

    private fun parseLinuxDesktopFile(file: java.io.File): LinuxDesktopEntry? {
        var name: String? = null
        var exec: String? = null
        var inDesktopEntry = true
        file.forEachLine { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inDesktopEntry = trimmed == "[Desktop Entry]"
            } else if (inDesktopEntry && !trimmed.startsWith("#")) {
                when {
                    trimmed.startsWith("Name=") && name == null ->
                        name = trimmed.removePrefix("Name=").trim()
                    trimmed.startsWith("Exec=") && exec == null ->
                        exec = trimmed.removePrefix("Exec=").trim()
                }
            }
        }
        val displayName = name?.takeIf { it.isNotBlank() } ?: return null
        val command = exec?.takeIf { it.isNotBlank() } ?: return null
        return LinuxDesktopEntry(displayName, command)
    }

    /**
     * Normalize a desktop-file Exec= command to the process name seen by
     * ProcessHandle and the Linux foreground poller, while retaining the
     * original command in ScannedApp.execCommand.
     */
    private fun normalizeLinuxExec(exec: String): NormalizedLinuxExec? {
        val tokens = tokenizeDesktopExec(exec)
            .filterNot { it.startsWith("%") }
        if (tokens.isEmpty()) return null

        var index = 0
        var command = tokens[index]
        var commandName = java.io.File(command).name.lowercase()

        if (commandName == "env") {
            index++
            while (index < tokens.size) {
                val token = tokens[index]
                if (token == "--" || token == "-i") {
                    index++
                    continue
                }
                if (token.contains("=") && !token.startsWith("=")) {
                    index++
                    continue
                }
                break
            }
            if (index >= tokens.size) return null
            command = tokens[index]
            commandName = java.io.File(command).name.lowercase()
        }

        if (commandName == "flatpak") {
            val appId = tokens.drop(index + 1)
                .dropWhile { it == "run" || it.startsWith("-") }
                .firstOrNull()
                ?.takeIf { it.isNotBlank() }
            val flatpakName = appId?.let(::flatpakProcessName) ?: return null
            return NormalizedLinuxExec(flatpakName, command, exec)
        }

        val processName = commandName.takeIf { it.isNotBlank() } ?: return null
        return NormalizedLinuxExec(processName, command, exec)
    }

    private fun flatpakProcessName(appId: String): String {
        val parts = appId.split('.').filter { it.isNotBlank() }
        if (parts.isEmpty()) return appId.lowercase()
        return if (parts.size >= 3 && parts.last().equals("desktop", ignoreCase = true)) {
            parts.drop(1).joinToString("-")
        } else {
            parts.last()
        }.lowercase()
    }

    /** Tokenize the quoted/escaped command grammar used by desktop Exec= values. */
    private fun tokenizeDesktopExec(value: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false

        fun flush() {
            if (current.isNotEmpty()) {
                tokens += current.toString()
                current.clear()
            }
        }

        for (char in value) {
            when {
                escaped -> {
                    current.append(char)
                    escaped = false
                }
                char == '\\' && quote != '\'' -> escaped = true
                quote != null && char == quote -> quote = null
                quote == null && (char == '\'' || char == '"') -> quote = char
                quote == null && char.isWhitespace() -> flush()
                else -> current.append(char)
            }
        }
        if (escaped) current.append('\\')
        flush()
        return tokens
    }

    /** Exposed to the Linux unit tests without making parser internals public. */
    internal fun normalizeLinuxExecForTesting(exec: String): String? =
        normalizeLinuxExec(exec)?.processName

    private fun friendlyName(exe: String): String =
        exe.substringBeforeLast(".")
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace(Regex("\\d+$"), "")
            .trim()
            .replaceFirstChar { c -> c.uppercaseChar() }
}
