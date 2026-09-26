package com.focusflow.enforcement

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.file.Files
import java.nio.file.Path
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

enum class AppSource {
    NATIVE_DESKTOP,
    FLATPAK,
    SNAP,
    WINDOWS_REGISTRY,
    RUNNING_ONLY,
    MANUAL
}

enum class AppDetectionConfidence {
    UNKNOWN,
    LOW,
    MEDIUM,
    HIGH
}

/**
 * Shared application identity used by the installed-app catalog and all
 * future pickers. [processName] remains the primary enforcement key for
 * backwards compatibility, while the additional fields identify the
 * installed application more reliably on Linux.
 */
data class AppDescriptor(
    val processName: String,
    val displayName: String,
    val isRunning: Boolean,
    val exePath: String? = null,
    /** Original desktop-file Exec= value, retained for diagnostics and launchers. */
    val execCommand: String? = null,
    /** Source .desktop file used to discover this Linux application. */
    val desktopFilePath: String? = null,
    /** Stable desktop-entry ID, normally the relative .desktop filename. */
    val desktopId: String? = null,
    /** Flatpak application ID, Snap name, or another package identifier. */
    val packageId: String? = null,
    /** Additional process/application aliases used during catalog matching. */
    val processAliases: List<String> = emptyList(),
    /** Parsed Icon= value from the desktop entry. */
    val iconName: String? = null,
    /** Parsed TryExec= value, if the desktop entry provided one. */
    val tryExec: String? = null,
    /** Parsed Categories= values, useful for future picker filtering. */
    val categories: List<String> = emptyList(),
    val source: AppSource = AppSource.MANUAL,
    /** PIDs currently matched to this catalog entry. */
    val runningPids: List<Long> = emptyList(),
    val detectionConfidence: AppDetectionConfidence = AppDetectionConfidence.UNKNOWN
)

data class AppCatalogState(
    val apps: List<AppDescriptor> = emptyList(),
    val isRefreshing: Boolean = false,
    val lastRefreshedAtMs: Long? = null,
    val isPartial: Boolean = false,
    val errorMessage: String? = null
)

data class AppCatalogScanStatus(
    val isPartial: Boolean = false,
    val errorMessage: String? = null
)

/** Compatibility name retained for current callers while the catalog adopts AppDescriptor. */
typealias ScannedApp = AppDescriptor

interface AppCatalogRepository {
    fun read(): List<AppDescriptor>
    fun readRunning(): List<AppDescriptor>
    fun refresh(): List<AppDescriptor>
    fun resolve(reference: String): AppDescriptor?
    fun createManualProcessEntry(
        processName: String,
        displayName: String? = null
    ): AppDescriptor?
}

/** Shared catalog entry point for future pickers; scanner APIs remain compatible. */
object InstalledAppCatalog : AppCatalogRepository {
    private val refreshing = AtomicBoolean(false)
    private val _state = MutableStateFlow(AppCatalogState())
    val state: StateFlow<AppCatalogState> = _state.asStateFlow()

    override fun read(): List<AppDescriptor> {
        return try {
            val apps = InstalledAppsScanner.getAppCatalog()
            val scanStatus = InstalledAppsScanner.lastScanStatus()
            _state.update { current ->
                current.copy(
                    apps = apps,
                    isPartial = scanStatus.isPartial,
                    errorMessage = scanStatus.errorMessage
                )
            }
            apps
        } catch (error: Exception) {
            _state.update { current ->
                current.copy(
                    isPartial = current.apps.isNotEmpty(),
                    errorMessage = error.message ?: "Unable to read installed applications"
                )
            }
            _state.value.apps
        }
    }

    override fun readRunning(): List<AppDescriptor> = InstalledAppsScanner.getRunningApps()

    override fun refresh(): List<AppDescriptor> {
        // A second refresh request must not clear or replace a scan already in
        // progress. The caller can observe state.isRefreshing and retry later.
        if (!refreshing.compareAndSet(false, true)) return _state.value.apps

        _state.update {
            it.copy(isRefreshing = true, isPartial = false, errorMessage = null)
        }

        return try {
            val apps = InstalledAppsScanner.refreshAppCatalog()
            val scanStatus = InstalledAppsScanner.lastScanStatus()
            _state.update {
                it.copy(
                    apps = apps,
                    isRefreshing = false,
                    lastRefreshedAtMs = System.currentTimeMillis(),
                    isPartial = scanStatus.isPartial,
                    errorMessage = scanStatus.errorMessage
                )
            }
            apps
        } catch (error: Exception) {
            _state.update {
                it.copy(
                    isRefreshing = false,
                    isPartial = it.apps.isNotEmpty(),
                    errorMessage = error.message ?: "Unable to refresh installed applications"
                )
            }
            _state.value.apps
        } finally {
            refreshing.set(false)
        }
    }

    override fun resolve(reference: String): AppDescriptor? =
        InstalledAppsScanner.resolveApp(reference)
    override fun createManualProcessEntry(
        processName: String,
        displayName: String?
    ): AppDescriptor? = InstalledAppsScanner.createManualProcessEntry(processName, displayName)
}

object InstalledAppsScanner {

    /**
     * Linux desktop files are input, not trusted command lines. Keep the
     * normalized executable name within the character set used by ordinary
     * process names before it can be passed to a launcher or matcher.
     */
    private val SAFE_LINUX_PROCESS_NAME = Regex("^[a-z0-9][a-z0-9_.+-]*$")

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
        // Sandbox/runtime helpers. A real application identity is recovered from
        // their command line when possible; otherwise these are infrastructure.
        "bwrap", "xdg-dbus-proxy",
        // FocusFlow itself
        "focusflow", "java", "kotlin"
    ).map { it.lowercase(Locale.ROOT) }.toSet()

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
    private val lastScan = AtomicReference(AppCatalogScanStatus())

    fun lastScanStatus(): AppCatalogScanStatus = lastScan.get()

    // ── Public API ────────────────────────────────────────────────────────────

    fun getRunningApps(): List<ScannedApp> {
        val running: List<ScannedApp> = try {
            ProcessHandle.allProcesses().toList()
                .mapNotNull { ph ->
                    // Use orElse(null) on a single call to avoid the TOCTOU race where
                    // isPresent() returns true but the process exits before get() is called,
                    // causing NoSuchElementException on the second command() invocation.
                    val info = ph.info()
                    val pid = ph.pid()
                    val command = info.command().orElse(null)
                    val procExe = if (isLinux) readLinuxProcExe(pid) else null
                    val cmd = procExe ?: command
                    val commandLine = info.commandLine().orElse("")
                        .ifBlank { if (isLinux) readLinuxProcFile(pid, "cmdline").orEmpty() else "" }
                    val comm = if (isLinux) readLinuxProcFile(pid, "comm") else null
                    val commandName = java.io.File(cmd ?: comm ?: return@mapNotNull null)
                        .name
                        .lowercase(Locale.ROOT)
                    // Flatpak's host process is reported as "flatpak" by
                    // ProcessHandle.command(), while its command line contains
                    // the app ID. Use the same normalization as desktop files
                    // so installed and running entries share a process key.
                    val normalized = if (isLinux) normalizeLinuxExec(commandLine) else null
                    val sandboxPackage = if (
                        isLinux && commandName in setOf("bwrap", "xdg-dbus-proxy")
                    ) {
                        linuxPackageIdFromCommandLine(commandLine)
                    } else {
                        null
                    }
                    val exe = normalized?.processName
                        ?: sandboxPackage?.let(::flatpakProcessName)
                        ?: comm?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
                        ?: commandName
                    val display = curated[exe] ?: friendlyName(exe)
                    val aliases = (
                        listOf(exe, commandName, comm.orEmpty()) +
                            normalized?.aliases.orEmpty() +
                            listOfNotNull(normalized?.packageId, sandboxPackage) +
                            linuxCommandLineAliases(commandLine)
                        )
                        .map { it.trim().lowercase(Locale.ROOT) }
                        .filter { it.isNotBlank() && it !in setOf("flatpak", "snap", "env") }
                        .distinct()
                    ScannedApp(
                        processName = exe,
                        displayName = display,
                        isRunning = true,
                        exePath = cmd,
                        execCommand = commandLine.takeIf { it.isNotBlank() },
                        processAliases = aliases,
                        packageId = normalized?.packageId ?: sandboxPackage,
                        source = AppSource.RUNNING_ONLY,
                        runningPids = listOf(pid),
                        detectionConfidence = when {
                            normalized != null || sandboxPackage != null -> AppDetectionConfidence.HIGH
                            comm != null && comm.equals(commandName, ignoreCase = true) ->
                                AppDetectionConfidence.HIGH
                            else -> AppDetectionConfidence.MEDIUM
                        }
                    )
                }
                .filter { app ->
                    app.processName.isNotBlank() &&
                    app.processName !in systemIgnore &&
                    (isLinux || app.processName.endsWith(".exe"))
                }
                .fold(LinkedHashMap<String, ScannedApp>()) { grouped, app ->
                    val key = app.processName.lowercase(Locale.ROOT)
                    val previous = grouped[key]
                    grouped[key] = if (previous == null) {
                        app
                    } else {
                        previous.copy(
                            processAliases = (previous.processAliases + app.processAliases).distinct(),
                            runningPids = (previous.runningPids + app.runningPids).distinct(),
                            exePath = previous.exePath ?: app.exePath,
                            execCommand = previous.execCommand ?: app.execCommand,
                            packageId = previous.packageId ?: app.packageId,
                            detectionConfidence = strongerConfidence(
                                previous.detectionConfidence,
                                app.detectionConfidence
                            )
                        )
                    }
                    grouped
                }
                .values
                .toList()
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
     * Returns the installed-app catalog with running state merged onto
     * desktop-file entries. Running processes without a desktop entry are
     * retained as RUNNING_ONLY records.
     */
    fun getAppCatalog(): List<AppDescriptor> {
        val installed = getInstalledApps()
        val running = getRunningApps()
        return mergeInstalledAndRunning(installed, running)
    }

    /**
     * Clears the process-lifetime installed-app cache and performs a fresh
     * catalog read. Running processes are always sampled again by
     * [getAppCatalog], so this is primarily for newly installed desktop files.
     */
    fun refreshAppCatalog(): List<AppDescriptor> {
        synchronized(installLock) {
            installedCache.clear()
            installedScanned = false
            exePathCache.clear()
            desktopFileCache.clear()
            execCommandCache.clear()
        }
        return getAppCatalog()
    }

    /**
     * Resolves a saved app reference by stable ID, package ID, process alias,
     * process name, or display name. The comparison is intentionally
     * case-insensitive because Linux process matching is normalized this way.
     */
    fun resolveApp(reference: String): AppDescriptor? {
        val key = reference.trim().lowercase(Locale.ROOT)
        if (key.isBlank()) return null
        return getAppCatalog().firstOrNull { app ->
            sequenceOf(
                app.desktopId,
                app.packageId,
                app.processName,
                app.displayName
            ).filterNotNull().any { it.equals(key, ignoreCase = true) } ||
                app.processAliases.any { it.equals(key, ignoreCase = true) }
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
        return getAppCatalog()
    }

    /**
     * Creates a descriptor for a user-entered process without inventing a
     * platform-specific suffix. In particular, Linux values remain exactly the
     * process name the user entered; callers can decide how to persist it.
     */
    fun createManualProcessEntry(
        processName: String,
        displayName: String? = null
    ): AppDescriptor? {
        val normalized = processName.trim().takeIf { it.isNotBlank() } ?: return null
        return AppDescriptor(
            processName = normalized,
            displayName = displayName?.trim().takeIf { !it.isNullOrBlank() }
                ?: friendlyNameFor(normalized),
            isRunning = false,
            processAliases = listOf(normalized.lowercase(Locale.ROOT)),
            source = AppSource.MANUAL,
            detectionConfidence = AppDetectionConfidence.UNKNOWN
        )
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
                        exePath     = rawPath,
                        source      = AppSource.WINDOWS_REGISTRY
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

    private data class LinuxApplicationRoot(
        val directory: java.io.File,
        val source: AppSource
    )

    private data class LinuxDesktopEntry(
        val name: String,
        val exec: String,
        val desktopId: String,
        val packageId: String?,
        val iconName: String?,
        val tryExec: String?,
        val categories: List<String>,
        val startupWmClass: String?
    )

    private data class NormalizedLinuxExec(
        val processName: String,
        val command: String,
        val fullCommand: String,
        val aliases: List<String> = emptyList(),
        val packageId: String? = null
    )

    /**
     * Reads the user XDG application directory first, then system XDG
     * directories, followed by Flatpak exports and Snap's exported desktop
     * files. The order is also the precedence order for duplicate desktop IDs.
     */
    private fun linuxApplicationRoots(): List<LinuxApplicationRoot> {
        val home = System.getProperty("user.home", "")
        val dataHome = System.getenv("XDG_DATA_HOME")
            ?.takeIf { it.isNotBlank() }
            ?.let { java.io.File(it) }
            ?: java.io.File(home, ".local/share")
        val dataDirs = (System.getenv("XDG_DATA_DIRS")
            ?.takeIf { it.isNotBlank() }
            ?: "/usr/local/share:/usr/share")
            .split(':')
            .filter { it.isNotBlank() }
            .map { java.io.File(it) }

        val roots = buildList {
            add(LinuxApplicationRoot(java.io.File(dataHome, "applications"), AppSource.NATIVE_DESKTOP))
            add(
                LinuxApplicationRoot(
                    java.io.File(dataHome, "flatpak/exports/share/applications"),
                    AppSource.FLATPAK
                )
            )
            dataDirs.forEach { dataDir ->
                add(LinuxApplicationRoot(java.io.File(dataDir, "applications"), AppSource.NATIVE_DESKTOP))
                add(
                    LinuxApplicationRoot(
                        java.io.File(dataDir, "flatpak/exports/share/applications"),
                        AppSource.FLATPAK
                    )
                )
            }
            add(
                LinuxApplicationRoot(
                    java.io.File("/var/lib/flatpak/exports/share/applications"),
                    AppSource.FLATPAK
                )
            )
            add(
                LinuxApplicationRoot(
                    java.io.File("/var/lib/snapd/desktop/applications"),
                    AppSource.SNAP
                )
            )
            add(
                LinuxApplicationRoot(
                    java.io.File("/snap/desktop/applications"),
                    AppSource.SNAP
                )
            )
            add(
                LinuxApplicationRoot(
                    java.io.File(home, "snap/desktop/applications"),
                    AppSource.SNAP
                )
            )
            // These are part of the Linux application search path even when a
            // user has customized XDG_DATA_DIRS.
            add(
                LinuxApplicationRoot(
                    java.io.File("/usr/local/share/applications"),
                    AppSource.NATIVE_DESKTOP
                )
            )
            add(
                LinuxApplicationRoot(
                    java.io.File("/usr/share/applications"),
                    AppSource.NATIVE_DESKTOP
                )
            )
        }
        return roots.distinctBy { it.directory.absoluteFile.normalize().path }
    }

    /**
     * Scans desktop files without collapsing separate desktop IDs that happen
     * to launch the same process. User/system duplicates are collapsed by
     * desktop ID, with earlier XDG roots taking precedence.
     */
    private fun scanLinuxDesktopFiles(
        roots: List<LinuxApplicationRoot> = linuxApplicationRoots()
    ): List<ScannedApp> {
        val result = LinkedHashMap<String, ScannedApp>()
        var skippedEntries = 0
        var firstError: String? = null
        for (root in roots) {
            val files = try {
                if (!root.directory.isDirectory) {
                    emptyList()
                } else {
                    root.directory.walkTopDown()
                        .filter { it.isFile && it.extension.equals("desktop", ignoreCase = true) }
                        .sortedBy { it.absolutePath }
                        .toList()
                }
            } catch (error: Exception) {
                skippedEntries++
                if (firstError == null) {
                    firstError = error.message ?: "Unable to read an application directory"
                }
                emptyList()
            }

            for (file in files) {
                try {
                    val entry = parseLinuxDesktopFile(file, root.directory) ?: continue
                    val app = buildLinuxDescriptor(
                        entry = entry,
                        source = root.source,
                        desktopFilePath = file.absolutePath
                    ) ?: continue
                    if (app.processName in systemIgnore) continue

                    val identity = (app.desktopId ?: file.nameWithoutExtension)
                        .lowercase(Locale.ROOT)
                    if (result.containsKey(identity)) continue
                    result[identity] = app
                    cacheLinuxDescriptor(app)
                } catch (error: Exception) {
                    skippedEntries++
                    if (firstError == null) {
                        firstError = error.message ?: "Unable to read a desktop entry"
                    }
                    // A single malformed desktop file must not hide other apps.
                }
            }
        }
        lastScan.set(
            AppCatalogScanStatus(
                isPartial = skippedEntries > 0,
                errorMessage = firstError
            )
        )
        return result.values.sortedBy { it.displayName.lowercase(Locale.ROOT) }
    }

    private fun parseLinuxDesktopFile(
        file: java.io.File,
        applicationRoot: java.io.File
    ): LinuxDesktopEntry? {
        return parseLinuxDesktopContent(
            content = file.readText(),
            desktopId = file.relativeToOrNull(applicationRoot)
                ?.path
                ?.removeSuffix(".desktop")
                ?.replace(java.io.File.separatorChar, '/')
                ?: file.nameWithoutExtension
        )
    }

    private fun parseLinuxDesktopContent(
        content: String,
        desktopId: String,
        localePreferences: List<String> = preferredLocaleTags()
    ): LinuxDesktopEntry? {
        val values = LinkedHashMap<String, String>()
        val localizedNames = LinkedHashMap<String, String>()
        var inDesktopEntry = false

        content.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inDesktopEntry = trimmed == "[Desktop Entry]"
                return@forEach
            }
            if (!inDesktopEntry || trimmed.isBlank() || trimmed.startsWith("#")) return@forEach

            val separator = trimmed.indexOf('=')
            if (separator <= 0) return@forEach
            val key = trimmed.substring(0, separator).trim()
            val value = unescapeDesktopValue(trimmed.substring(separator + 1).trim())
            if (key.startsWith("Name[") && key.endsWith("]")) {
                localizedNames[key.substringAfter("Name[").removeSuffix("]")] = value
            } else if (!values.containsKey(key)) {
                values[key] = value
            }
        }

        if (values["Type"]?.equals("Application", ignoreCase = true) == false) return null
        if (values["Hidden"].toBoolean() || values["NoDisplay"].toBoolean()) return null

        val onlyShowIn = splitDesktopList(values["OnlyShowIn"])
        val notShowIn = splitDesktopList(values["NotShowIn"])
        if (!isVisibleOnCurrentDesktop(onlyShowIn, notShowIn)) return null

        val name = chooseLocalizedName(
            fallback = values["Name"],
            localizedNames = localizedNames,
            localePreferences = localePreferences
        ) ?: return null
        val exec = values["Exec"]?.takeIf { it.isNotBlank() } ?: return null
        val packageId = values["X-Flatpak"]
            ?.takeIf { it.isNotBlank() && !it.equals("true", ignoreCase = true) }
            ?: values["X-SnapInstanceName"]?.takeIf { it.isNotBlank() }
            ?: values["X-Snap-Instance"]?.takeIf { it.isNotBlank() }

        return LinuxDesktopEntry(
            name = name,
            exec = exec,
            desktopId = desktopId,
            packageId = packageId,
            iconName = values["Icon"]?.takeIf { it.isNotBlank() },
            tryExec = values["TryExec"]?.takeIf { it.isNotBlank() },
            categories = splitDesktopList(values["Categories"]),
            startupWmClass = values["StartupWMClass"]?.takeIf { it.isNotBlank() }
        )
    }

    private fun buildLinuxDescriptor(
        entry: LinuxDesktopEntry,
        source: AppSource,
        desktopFilePath: String?
    ): ScannedApp? {
        val normalized = normalizeLinuxExec(entry.exec) ?: return null
        if (entry.tryExec != null && !isExecutableAvailable(entry.tryExec)) return null
        val packageId = entry.packageId ?: normalized.packageId
        val aliases = (
            normalized.aliases +
                listOfNotNull(packageId, entry.startupWmClass)
            )
            .map { it.lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .distinct()
        val effectiveSource = when {
            normalized.packageId != null &&
                java.io.File(normalized.command).name.equals("flatpak", ignoreCase = true) ->
                AppSource.FLATPAK
            normalized.packageId != null &&
                java.io.File(normalized.command).name.equals("snap", ignoreCase = true) ->
                AppSource.SNAP
            else -> source
        }
        return ScannedApp(
            processName = normalized.processName,
            displayName = entry.name,
            isRunning = false,
            exePath = normalized.command,
            execCommand = normalized.fullCommand,
            desktopFilePath = desktopFilePath,
            desktopId = entry.desktopId,
            packageId = packageId,
            processAliases = aliases,
            iconName = entry.iconName,
            tryExec = entry.tryExec,
            categories = entry.categories,
            source = effectiveSource
        )
    }

    private fun cacheLinuxDescriptor(app: ScannedApp) {
        val desktopPath = app.desktopFilePath ?: return
        val names = (listOf(app.processName) + app.processAliases)
            .map { it.lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .distinct()
        names.forEach { name ->
            desktopFileCache.putIfAbsent(name, desktopPath)
            app.execCommand?.let { execCommandCache.putIfAbsent(name, it) }
        }

        val commandName = java.io.File(app.exePath.orEmpty()).name.lowercase(Locale.ROOT)
        if (commandName != "flatpak" && commandName != "snap" && commandName != "env") {
            desktopFileCache.putIfAbsent(commandName, desktopPath)
        }
        app.exePath?.let { exePathCache.putIfAbsent(app.processName, it) }
    }

    private fun splitDesktopList(value: String?): List<String> =
        value.orEmpty()
            .split(';')
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun preferredLocaleTags(): List<String> {
        val configured = listOf(
            System.getenv("LANGUAGE"),
            System.getenv("LC_ALL"),
            System.getenv("LC_MESSAGES"),
            System.getenv("LANG")
        ).filterNotNull().flatMap { it.split(':') }
        return (configured + Locale.getDefault().toLanguageTag() + "C")
            .flatMap { listOf(it, it.substringBefore('.'), it.substringBefore('_')) }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun chooseLocalizedName(
        fallback: String?,
        localizedNames: Map<String, String>,
        localePreferences: List<String>
    ): String? {
        val preferred = localePreferences.asSequence()
            .map { it.replace('-', '_') }
            .flatMap { locale ->
                sequenceOf(locale, locale.substringBefore('_'))
            }
            .mapNotNull { localizedNames[it] }
            .firstOrNull { it.isNotBlank() }
        return preferred ?: fallback?.takeIf { it.isNotBlank() }
    }

    private fun isVisibleOnCurrentDesktop(
        onlyShowIn: List<String>,
        notShowIn: List<String>
    ): Boolean {
        val current = listOf(
            System.getenv("XDG_CURRENT_DESKTOP"),
            System.getenv("XDG_SESSION_DESKTOP")
        ).filterNotNull()
            .flatMap { it.split(':', ';') }
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .toSet()
        if (onlyShowIn.isNotEmpty() && current.isNotEmpty() &&
            onlyShowIn.none { it.lowercase(Locale.ROOT) in current }
        ) {
            return false
        }
        if (notShowIn.any { it.lowercase(Locale.ROOT) in current }) return false
        return true
    }

    private fun unescapeDesktopValue(value: String): String {
        val result = StringBuilder()
        var escaped = false
        value.forEach { char ->
            if (escaped) {
                result.append(
                    when (char) {
                        'n' -> '\n'
                        's' -> ' '
                        't' -> '\t'
                        'r' -> '\r'
                        '\\' -> '\\'
                        ';' -> ';'
                        else -> char
                    }
                )
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else {
                result.append(char)
            }
        }
        if (escaped) result.append('\\')
        return result.toString()
    }

    private fun isExecutableAvailable(value: String): Boolean {
        val candidate = java.io.File(value)
        if (candidate.isAbsolute) return candidate.isFile && candidate.canExecute()
        val path = System.getenv("PATH").orEmpty()
        return path.split(java.io.File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { java.io.File(it, value) }
            .any { it.isFile && it.canExecute() }
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

        // A desktop Exec= file is parsed without a shell, but reject shell
        // metacharacters in the executable token before it is used for process
        // matching or launching. Arguments remain data and are not normalized
        // into executable names.
        if (command.any { it in ";|&$`()<>!\n\r" }) return null

        if (commandName == "flatpak") {
            val appId = tokens.drop(index + 1)
                .dropWhile { it == "run" || it.startsWith("-") }
                .firstOrNull()
                ?.takeIf { it.isNotBlank() }
            val flatpakName = appId?.let(::flatpakProcessName)
                ?.takeIf { SAFE_LINUX_PROCESS_NAME.matches(it) }
                ?: return null
            return NormalizedLinuxExec(
                processName = flatpakName,
                command = command,
                fullCommand = exec,
                aliases = listOfNotNull(appId),
                packageId = appId
            )
        }

        if (commandName == "snap") {
            val snapId = tokens.drop(index + 1)
                .dropWhile { it == "run" || it.startsWith("-") }
                .firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.lowercase(Locale.ROOT)
                ?.takeIf { SAFE_LINUX_PROCESS_NAME.matches(it) }
                ?: return null
            return NormalizedLinuxExec(
                processName = snapId,
                command = command,
                fullCommand = exec,
                aliases = listOf(snapId),
                packageId = snapId
            )
        }

        val processName = commandName
            .takeIf { SAFE_LINUX_PROCESS_NAME.matches(it) }
            ?: return null
        return NormalizedLinuxExec(
            processName = processName,
            command = command,
            fullCommand = exec,
            aliases = listOf(processName)
        )
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

    internal fun parseLinuxDesktopContentForTesting(
        content: String,
        desktopId: String = "test.desktop",
        source: AppSource = AppSource.NATIVE_DESKTOP,
        localePreferences: List<String> = listOf("en_US")
    ): AppDescriptor? {
        val entry = parseLinuxDesktopContent(content, desktopId, localePreferences) ?: return null
        return buildLinuxDescriptor(entry, source, desktopFilePath = null)
    }

    internal fun scanLinuxDesktopFilesForTesting(
        roots: List<Pair<java.io.File, AppSource>>
    ): List<AppDescriptor> {
        return scanLinuxDesktopFiles(
            roots.map { (directory, source) -> LinuxApplicationRoot(directory, source) }
        )
    }

    internal fun mergeInstalledAndRunningForTesting(
        installed: List<AppDescriptor>,
        running: List<AppDescriptor>
    ): List<AppDescriptor> = mergeInstalledAndRunning(installed, running)

    private fun mergeInstalledAndRunning(
        installed: List<AppDescriptor>,
        running: List<AppDescriptor>
    ): List<AppDescriptor> {
        val usedRunning = BooleanArray(running.size)
        val mergedInstalled = installed.map { app ->
            val runningIndex = running.indices.firstOrNull { index ->
                !usedRunning[index] && appsMatch(app, running[index])
            } ?: -1
            if (runningIndex < 0) {
                app
            } else {
                usedRunning[runningIndex] = true
                val live = running[runningIndex]
                app.copy(
                    isRunning = true,
                    exePath = live.exePath ?: app.exePath,
                    processAliases = (app.processAliases + live.processAliases).distinct(),
                    runningPids = (app.runningPids + live.runningPids).distinct(),
                    detectionConfidence = strongerConfidence(
                        app.detectionConfidence,
                        live.detectionConfidence
                    )
                )
            }
        }
        val runningOnly = running.filterIndexed { index, _ -> !usedRunning[index] }
            .map { it.copy(source = AppSource.RUNNING_ONLY) }
        return (mergedInstalled + runningOnly)
            .sortedWith(
                compareBy<AppDescriptor> { it.displayName.lowercase(Locale.ROOT) }
                    .thenBy { it.desktopId.orEmpty().lowercase(Locale.ROOT) }
                    .thenBy { it.processName }
            )
    }

    private fun appsMatch(installed: AppDescriptor, running: AppDescriptor): Boolean {
        val installedCandidates = appMatchCandidates(installed)
        val runningCandidates = appMatchCandidates(running)
        if (installedCandidates.intersect(runningCandidates).isNotEmpty()) return true

        val installedExecutable = installed.exePath
            ?.let { java.io.File(it).name.lowercase(Locale.ROOT) }
        val runningExecutable = running.exePath
            ?.let { java.io.File(it).name.lowercase(Locale.ROOT) }
        return installedExecutable != null &&
            installedExecutable == runningExecutable &&
            installedExecutable !in setOf("flatpak", "snap", "env")
    }

    private fun appMatchCandidates(app: AppDescriptor): Set<String> =
        (listOf(app.processName, app.packageId) + app.processAliases)
            .filterNotNull()
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .toSet()

    private fun readLinuxProcExe(pid: Long): String? = try {
        Files.readSymbolicLink(Path.of("/proc", pid.toString(), "exe")).toString()
    } catch (_: Exception) {
        null
    }

    private fun readLinuxProcFile(pid: Long, name: String): String? = try {
        Files.readString(Path.of("/proc", pid.toString(), name))
            .replace('\u0000', ' ')
            .trim()
            .takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    private fun linuxPackageIdFromCommandLine(commandLine: String): String? =
        tokenizeDesktopExec(commandLine)
            .firstOrNull {
                it.matches(Regex("^[A-Za-z0-9][A-Za-z0-9_.-]*\\.[A-Za-z0-9_.-]+$"))
            }
            ?.takeIf { it.contains('.') }

    private fun linuxCommandLineAliases(commandLine: String): List<String> =
        tokenizeDesktopExec(commandLine)
            .filter { it.matches(Regex("^[a-zA-Z0-9][a-zA-Z0-9_.+-]*$")) }
            .map { it.lowercase(Locale.ROOT) }
            .filter { it.length > 1 }
            .distinct()

    private fun strongerConfidence(
        first: AppDetectionConfidence,
        second: AppDetectionConfidence
    ): AppDetectionConfidence = if (confidenceRank(first) >= confidenceRank(second)) first else second

    private fun confidenceRank(confidence: AppDetectionConfidence): Int = when (confidence) {
        AppDetectionConfidence.UNKNOWN -> 0
        AppDetectionConfidence.LOW -> 1
        AppDetectionConfidence.MEDIUM -> 2
        AppDetectionConfidence.HIGH -> 3
    }

    private fun friendlyName(exe: String): String =
        exe.substringBeforeLast(".")
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace(Regex("\\d+$"), "")
            .trim()
            .replaceFirstChar { c -> c.uppercaseChar() }
}
