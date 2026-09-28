package com.focusflow.services

import com.focusflow.enforcement.InstallVariant
import com.focusflow.enforcement.WatchdogInstaller
import com.focusflow.enforcement.isLinux
import java.awt.GraphicsEnvironment
import java.io.File
import javax.swing.JOptionPane

/**
 * Guarded uninstall handoff.
 *
 * Windows delegates the final removal to the installer command retained by
 * [WindowsUninstallRegistration]. Linux delegates package removal to the
 * package manager that owns the installation. AppImages are intentionally
 * manual because a running executable cannot safely delete itself.
 */
object UninstallWizard {

    fun run(): Boolean {
        if (GraphicsEnvironment.isHeadless()) return false
        return when {
            isLinux -> runLinux()
            InstallVariant.isWindowsDirectInstall -> runWindows()
            else -> false
        }
    }

    private fun runLinux(): Boolean {
        val plan = LinuxUninstallPlan.detect()
        if (plan == null) {
            showMessage(
                "FocusFlow uninstall",
                "FocusFlow's installation type could not be detected safely.\n\n" +
                    "No files or packages were removed."
            )
            return false
        }
        if (!UninstallProtectionService.prepareForUninstallWizard()) {
            showMessage(
                "FocusFlow uninstall",
                "FocusFlow could not read its protection state safely.\n\n" +
                    "The application was not removed."
            )
            return false
        }
        val details = when (plan.kind) {
            LinuxUninstallKind.APP_IMAGE -> plan.description +
                "\n\nThe AppImage itself must be deleted manually after FocusFlow exits."
            else -> plan.description +
                "\n\nYour package manager will perform the final removal."
        }
        if (!confirm("FocusFlow uninstall", details + "\n\nContinue?")) return false
        if (!UninstallProtectionService.authorizeUninstallWizard()) return false
        if (!confirm("Confirm FocusFlow uninstall", plan.confirmation)) return false

        // Stop future relaunches before handing control to an external remover.
        WatchdogInstaller.uninstall()
        if (plan.kind == LinuxUninstallKind.APP_IMAGE) {
            showMessage(
                "FocusFlow uninstall",
                "FocusFlow is ready to close. Delete this AppImage after the window exits:\n\n" +
                    plan.path
            )
            return true
        }
        return try {
            ProcessBuilder(plan.command)
                .redirectErrorStream(true)
                .start()
            true
        } catch (e: Exception) {
            showMessage(
                "FocusFlow uninstall could not start",
                "${e.message ?: "The package manager could not be started."}\n\n" +
                    "No package was removed."
            )
            false
        }
    }

    private fun runWindows(): Boolean {
        val registered = WindowsUninstallRegistration.registeredCommand()
        if (registered == null) {
            showMessage(
                "FocusFlow uninstall",
                "FocusFlow could not find its Windows Installer entry.\n\n" +
                    "Open Windows Settings and try Apps → Installed apps again."
            )
            return false
        }
        if (!UninstallProtectionService.prepareForUninstallWizard()) return false
        if (!confirm(
                "FocusFlow uninstall wizard",
                "Windows Installer will remove this FocusFlow installation.\n\n" +
                    "Any active protection must finish or be authorized before uninstall can continue."
            )
        ) return false
        if (!UninstallProtectionService.authorizeUninstallWizard()) return false
        if (!confirm(
                "Confirm FocusFlow uninstall",
                "Windows Installer will remove the installed application.\n\nContinue?"
            )
        ) return false

        WatchdogInstaller.uninstall()
        return try {
            ProcessBuilder("cmd.exe", "/d", "/s", "/c", registered.command)
                .redirectErrorStream(true)
                .start()
            true
        } catch (e: Exception) {
            showMessage(
                "FocusFlow uninstall could not start",
                e.message ?: "Windows Installer could not be started."
            )
            false
        }
    }

    private fun confirm(title: String, message: String): Boolean =
        JOptionPane.showConfirmDialog(
            null, message, title, JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE
        ) == JOptionPane.OK_OPTION

    private fun showMessage(title: String, message: String) {
        if (!GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(null, message, title, JOptionPane.WARNING_MESSAGE)
        }
    }
}

private enum class LinuxUninstallKind {
    DEBIAN,
    RPM,
    FLATPAK,
    SNAP,
    APP_IMAGE
}

private data class LinuxUninstallPlan(
    val kind: LinuxUninstallKind,
    val description: String,
    val confirmation: String,
    val command: List<String> = emptyList(),
    val path: String = ""
) {
    companion object {
        fun detect(): LinuxUninstallPlan? {
            val executable = runCatching {
                ProcessHandle.current().info().command().orElse("")
            }.getOrDefault("")
            val normalized = executable.replace('\\', '/')
            val appImagePath = System.getenv("APPIMAGE")
                ?.takeIf { it.isNotBlank() }
                ?: normalized.takeIf { it.endsWith(".appimage", ignoreCase = true) }

            if (appImagePath != null) {
                return LinuxUninstallPlan(
                    LinuxUninstallKind.APP_IMAGE,
                    "FocusFlow is running as a portable AppImage.",
                    "No package-manager files will be changed.",
                    path = appImagePath
                )
            }
            if (commandExists("flatpak") && commandSucceeds("flatpak", "info", "com.focusflow.FocusFlow")) {
                return LinuxUninstallPlan(
                    LinuxUninstallKind.FLATPAK,
                    "FocusFlow is installed as a Flatpak (com.focusflow.FocusFlow).",
                    "Flatpak will remove the FocusFlow application for this user.",
                    listOf("flatpak", "uninstall", "--user", "-y", "com.focusflow.FocusFlow")
                )
            }
            if (commandSucceeds("dpkg-query", "-W", "-f", "\${Status}", "focusflow")) {
                return LinuxUninstallPlan(
                    LinuxUninstallKind.DEBIAN,
                    "FocusFlow is installed as the Debian package 'focusflow'.",
                    "apt will remove the FocusFlow package. User data is kept unless the package manager says otherwise.",
                    privilegedCommand("apt-get", "remove", "-y", "focusflow")
                )
            }
            if (commandSucceeds("rpm", "-q", "focusflow")) {
                val remover = when {
                    commandExists("dnf") -> "dnf"
                    commandExists("zypper") -> "zypper"
                    else -> "rpm"
                }
                val args = if (remover == "rpm") {
                    listOf(remover, "-e", "focusflow")
                } else {
                    listOf(remover, "remove", "-y", "focusflow")
                }
                return LinuxUninstallPlan(
                    LinuxUninstallKind.RPM,
                    "FocusFlow is installed as the RPM package 'focusflow'.",
                    "The system package manager will remove the FocusFlow package.",
                    privilegedCommand(*args.toTypedArray())
                )
            }
            if (commandSucceeds("snap", "list", "focusflow")) {
                return LinuxUninstallPlan(
                    LinuxUninstallKind.SNAP,
                    "FocusFlow is installed as the Snap package 'focusflow'.",
                    "Snap will remove the FocusFlow package.",
                    privilegedCommand("snap", "remove", "focusflow")
                )
            }

            // Do not guess based on arbitrary JVM paths. Unknown/manual installs
            // must be removed by the user rather than by a broad filesystem delete.
            return null
        }

        private fun commandExists(command: String): Boolean =
            runCatching {
                ProcessBuilder("sh", "-c", "command -v " + shellQuote(command))
                    .redirectErrorStream(true)
                    .start()
                    .waitFor() == 0
            }.getOrDefault(false)

        private fun commandSucceeds(vararg command: String): Boolean =
            runCatching {
                ProcessBuilder(*command)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor() == 0
            }.getOrDefault(false)

        private fun privilegedCommand(vararg command: String): List<String> =
            if (commandExists("pkexec")) listOf("pkexec") + command.toList()
            else command.toList()

        private fun shellQuote(value: String): String =
            "'" + value.replace("'", "'\\''") + "'"
    }
}