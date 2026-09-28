package com.focusflow.enforcement

import java.io.File
import java.util.Locale

/**
 * Linux process-safety contract shared by discovery, pickers, and enforcement.
 *
 * The launcher safe set is intentionally broader than Nuclear Mode's escape
 * blocklist. Launcher mode kills everything outside the selected app set, while
 * Nuclear Mode only targets explicitly known escape tools.
 */
object LinuxProcessSafety {

    /**
     * Processes that must survive Focus Launcher inverse enforcement.
     *
     * The names are a conservative fallback. [isProtectedProcess] also protects
     * the current FocusFlow process tree by PID, which covers packaged launchers
     * and helper processes whose executable name is not stable.
     */
    val launcherSafeProcessNames: Set<String> = setOf(
        // FocusFlow itself and the JVM fallback used by unpackaged launches.
        "java", "focusflow",

        // Display server, compositor, and window manager.
        "xorg", "xwayland", "wayland",
        "gnome-shell", "kwin_x11", "kwin_wayland",
        "xfwm4", "mutter", "muffin", "compiz", "openbox",
        "plasmashell", "cinnamon", "mate-session", "mate-panel",
        "xfce4-session", "lxqt-session", "gnome-session-binary",

        // Login, session, IPC, and authorization.
        "systemd", "init", "systemd-logind", "systemd-user-session",
        "dbus-daemon", "dbus-broker", "dbus-launch",
        "gdm", "gdm-session-worker", "sddm", "sddm-greeter",
        "lightdm", "lightdm-gtk-greeter", "lxdm",
        "polkitd", "polkit-gnome", "pk-launch",

        // Desktop services, portals, storage, power, and networking.
        "at-spi-bus-launcher", "at-spi2-registry",
        "gvfs", "gvfsd",
        "xdg-desktop-portal", "xdg-document-portal", "xdg-permission-store",
        "xdg-dbus-proxy",
        "udisks", "udisksd", "upower",
        "networkmanager", "wpa_supplicant",

        // Input methods and remote access.
        "libinput-daemon", "input", "inputlock",
        "ibus-daemon", "fcitx", "fcitx5",
        "sshd",

        // Audio.
        "pulseaudio", "pipewire", "wireplumber",
        "alsa", "alsa-sink", "alsa-source", "jackd",

        // Accessibility.
        "orca", "speech-dispatcher", "onboard", "xvkbd",

        // KDE desktop services commonly required by the active session.
        "kded5", "kded6"
    ).map { it.lowercase(Locale.ROOT) }.toSet()

    /**
     * Processes that the picker must never offer as a new blocking target.
     *
     * This is kept separate from the launcher set so a future platform can
     * preserve a session service without exposing it as a user-selectable app.
     */
    val protectedProcessNames: Set<String> = launcherSafeProcessNames

    fun normalizeProcessName(raw: String?): String? =
        raw?.trim()
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() }

    fun isProtectedProcessName(processName: String?): Boolean =
        isLinux && normalizeProcessName(processName) in protectedProcessNames

    fun protectedReason(processName: String?): String? {
        if (!isProtectedProcessName(processName)) return null
        val name = normalizeProcessName(processName) ?: return null
        return if (name == "java" || name == "focusflow") {
            "FocusFlow and its runtime are protected from termination."
        } else {
            "This Linux desktop/session process is protected from termination."
        }
    }

    /**
     * Returns true when a Linux PID is the current FocusFlow process, one of its
     * descendants, or a named protected process.
     *
     * Walking parent handles is intentionally bounded: a malformed or changing
     * process tree must never stall an enforcement tick.
     */
    fun isProtectedProcess(pid: Long, processName: String? = null): Boolean {
        if (!isLinux) return false
        if (isProtectedProcessName(processName)) return true
        if (pid <= 0L) return false

        val ownPid = ProcessHandle.current().pid()
        if (pid == ownPid) return true

        var current = ProcessHandle.of(pid).orElse(null) ?: return false
        repeat(64) {
            val parent = current.parent().orElse(null) ?: return@repeat
            if (parent.pid() == ownPid) return true
            if (parent.pid() == pid) return@repeat
            current = parent
        }
        return false
    }

    /**
     * Uses the actual executable basename when a PID is available. This catches
     * wrappers and protects the current FocusFlow process tree even when the
     * caller only has a PID.
     */
    fun isProtectedProcess(pid: Long): Boolean {
        val command = ProcessHandle.of(pid)
            .flatMap { it.info().command() }
            .orElse(null)
        return isProtectedProcess(pid, command?.let(::File)?.name)
    }
}