package com.focusflow.enforcement

import java.io.File
import java.util.Locale

enum class LinuxProcessProtectionCategory(val rationale: String) {
    FOCUSFLOW_RUNTIME("This exact PID is the running FocusFlow process."),
    SYSTEM_CRITICAL(
        "This is a core Linux system, login, authorization, or connectivity service."
    ),
    DESKTOP_SESSION(
        "This process supports the active display, desktop, input, or user-session environment."
    )
}

data class LinuxProcessProtectionClassification(
    val category: LinuxProcessProtectionCategory,
    val reason: String = category.rationale
)

/**
 * Linux process-safety contract shared by discovery, pickers, and enforcement.
 *
 * Name-based protection is limited to classified system and desktop-session
 * infrastructure. FocusFlow itself is protected by its exact PID, not by a
 * generic runtime name or by protecting its descendants.
 */
object LinuxProcessSafety {

    private val focusFlowPid = ProcessHandle.current().pid()

    /**
     * Named global protections. Every entry is assigned to a category with an
     * explicit shared rationale. FocusFlow is intentionally absent: its own PID
     * is protected separately, and descendants are not implicitly protected.
     */
    private val protectedNamesByCategory: Map<LinuxProcessProtectionCategory, Set<String>> =
        linkedMapOf(
            LinuxProcessProtectionCategory.SYSTEM_CRITICAL to setOf(
                // Core boot, login, authorization, and service management.
                "systemd", "init", "systemd-logind", "systemd-user-session",
                "gdm", "gdm-session-worker", "sddm", "sddm-greeter",
                "lightdm", "lightdm-gtk-greeter", "lxdm",
                "polkitd", "polkit-gnome", "pk-launch",

                // System/session IPC and essential device/network services.
                "dbus-daemon", "dbus-broker",
                "udisks", "udisksd", "upower",
                "networkmanager", "wpa_supplicant", "sshd"
            ),
            LinuxProcessProtectionCategory.DESKTOP_SESSION to setOf(
                // Display server, compositor, window manager, and desktop shell.
                "xorg", "xwayland", "wayland",
                "gnome-shell", "kwin_x11", "kwin_wayland",
                "xfwm4", "mutter", "muffin", "compiz", "openbox",
                "plasmashell", "cinnamon", "mate-session", "mate-panel",
                "xfce4-session", "lxqt-session", "gnome-session-binary",

                // User-session IPC, portals, filesystems, and accessibility bus.
                "dbus-launch",
                "at-spi-bus-launcher", "at-spi2-registry",
                "gvfs", "gvfsd",
                "xdg-desktop-portal", "xdg-document-portal", "xdg-permission-store",
                "xdg-dbus-proxy",

                // Input, audio, accessibility, and KDE session services.
                "libinput-daemon", "input", "inputlock",
                "ibus-daemon", "fcitx", "fcitx5",
                "pulseaudio", "pipewire", "wireplumber",
                "alsa", "alsa-sink", "alsa-source", "jackd",
                "orca", "speech-dispatcher", "onboard", "xvkbd",
                "kded5", "kded6"
            )
        )

    /** Canonical name-to-category inventory used by safety and picker checks. */
    val protectedProcessNameClassifications: Map<String, LinuxProcessProtectionCategory> =
        protectedNamesByCategory.flatMap { (category, names) ->
            names.map { it.lowercase(Locale.ROOT) to category }
        }.toMap()

    /** Name-based protections used by Focus Launcher and other Linux safeguards. */
    val launcherSafeProcessNames: Set<String> = protectedProcessNameClassifications.keys

    /**
     * Compatibility name for consumers that need the classified system/session
     * inventory. It does not include generic runtimes or FocusFlow by name.
     */
    val protectedProcessNames: Set<String> = launcherSafeProcessNames

    /**
     * Runtime executables are not global protections, but a bare process-name
     * target is too broad to represent one application safely. Keep this
     * restriction separate so removing global authorization does not make a
     * generic runtime selectable as an application.
     */
    private val ambiguousRuntimeProcessNames = setOf(
        "java", "javaw", "python", "python2", "python3", "node", "nodejs",
        "ruby", "perl", "php", "dotnet", "mono", "bash", "sh", "dash",
        "zsh", "fish"
    )

    private val versionedPythonName = Regex("""python(?:2|3)(?:\.\d+)*""")

    fun normalizeProcessName(raw: String?): String? =
        raw?.trim()
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() }

    fun isGenericRuntimeProcessName(processName: String?): Boolean {
        val name = normalizeProcessName(processName) ?: return false
        return name in ambiguousRuntimeProcessNames || versionedPythonName.matches(name)
    }

    /** Explain why a generic runtime cannot be used as a bare manual app target. */
    fun manualTargetRestrictionReason(processName: String?): String? {
        if (!isLinux || !isGenericRuntimeProcessName(processName)) return null
        return "A bare runtime name can match unrelated applications. Select a specific app instead."
    }

    fun isProtectedProcessName(processName: String?): Boolean =
        isLinux && normalizeProcessName(processName) in protectedProcessNameClassifications

    fun protectionClassification(
        pid: Long,
        processName: String? = null
    ): LinuxProcessProtectionClassification? {
        if (!isLinux) return null
        if (pid > 0L && pid == focusFlowPid) {
            return LinuxProcessProtectionClassification(
                LinuxProcessProtectionCategory.FOCUSFLOW_RUNTIME
            )
        }
        val name = normalizeProcessName(processName) ?: return null
        val category = protectedProcessNameClassifications[name] ?: return null
        return LinuxProcessProtectionClassification(category)
    }

    fun protectedReason(processName: String?): String? {
        if (!isLinux) return null
        val name = normalizeProcessName(processName) ?: return null
        val category = protectedProcessNameClassifications[name] ?: return null
        return category.rationale
    }

    /**
     * A FocusFlow process is protected only by its exact PID. Other processes
     * are protected only when their own process name belongs to the classified
     * global system/session inventory; ancestry is never consulted.
     */
    fun isProtectedProcess(pid: Long, processName: String? = null): Boolean {
        return protectionClassification(pid, processName) != null
    }

    /**
     * Uses the actual executable basename when a PID is available. The exact
     * current FocusFlow PID remains protected even when its runtime name is
     * generic or unknown.
     */
    fun isProtectedProcess(pid: Long): Boolean {
        if (!isLinux || pid <= 0L) return false
        if (pid == focusFlowPid) return true
        val command = ProcessHandle.of(pid)
            .flatMap { it.info().command() }
            .orElse(null)
        return isProtectedProcess(pid, command?.let(::File)?.name)
    }
}