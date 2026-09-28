package com.focusflow.enforcement

/**
 * Identifies the distribution channel when the operating system owns removal.
 *
 * Windows Store/MSIX owns its uninstall flow and must not be wrapped. Direct
 * Windows launches can use the FocusFlow gate. Linux package removal remains
 * owned by dpkg/rpm/Flatpak/Snap; Linux only exposes a guarded handoff.
 */
object InstallVariant {
    private val windows = System.getProperty("os.name").lowercase().contains("windows")

    val isMsix: Boolean by lazy {
        if (!windows) return@lazy false
        val packageIdentityPresent = listOf(
            "PACKAGE_FAMILY_NAME",
            "APPX_PACKAGE_FAMILY_NAME",
            "APPX_PACKAGE_NAME"
        ).any { !System.getenv(it).isNullOrBlank() }
        val executablePath = runCatching {
            ProcessHandle.current().info().command().orElse("")
        }.getOrDefault("").replace('/', '\\')
        val resourcePath = System.getProperty("compose.application.resources.dir", "")
            .replace('/', '\\')
        packageIdentityPresent ||
            executablePath.contains("\\windowsapps\\", ignoreCase = true) ||
            resourcePath.contains("\\windowsapps\\", ignoreCase = true)
    }

    val isWindowsDirectInstall: Boolean
        get() = windows && !isMsix

    val isWindows: Boolean
        get() = windows
}