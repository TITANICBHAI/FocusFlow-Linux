package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.enforcement.InstallVariant
import com.focusflow.enforcement.NuclearMode
import com.focusflow.enforcement.ProcessMonitor
import com.focusflow.enforcement.isLinux
import java.awt.GraphicsEnvironment
import javax.swing.JOptionPane
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared authorization for intentional quit and uninstall actions.
 *
 * Crash cleanup stays fail-open. Only user-requested actions use this gate.
 * Linux cannot prevent an administrator or package manager from removing a
 * package externally, but it can protect the app-owned quit and handoff paths.
 */
object UninstallProtectionService {

    private const val AUTHORIZATION_WINDOW_MS = 30_000L
    private val authorizedUninstallUntilMs = AtomicLong(0L)
    private val uninstallPromptLock = Any()

    private sealed interface Requirement {
        data class WaitForStandalone(val remainingMs: Long) : Requirement
        data class Blocked(val message: String) : Requirement
        data class Pin(
            val feature: String,
            val prompt: String,
            val verify: (String) -> Boolean
        ) : Requirement
    }

    fun prepareForUninstallWizard(): Boolean {
        if (!isLinux && !InstallVariant.isWindowsDirectInstall) return true
        return runCatching {
            if (!Database.isReady) Database.init()
            if (!Database.isReady) return false
            ProcessMonitor.alwaysOnEnabled =
                Database.getSetting("always_on_enforcement") == "true"
            true
        }.getOrDefault(false)
    }

    fun authorizeQuit(): Boolean {
        if (!isLinux && !InstallVariant.isWindowsDirectInstall) return true
        return authorizeRequirements(activeRequirements())
    }

    fun authorizeUninstallWizard(): Boolean {
        if (!isLinux && !InstallVariant.isWindowsDirectInstall) return true
        if (!Database.isReady) {
            showMessage(
                "Uninstall protection unavailable",
                "FocusFlow could not read its protection state safely. The application was not removed."
            )
            return false
        }
        return authorizeRequirements(activeRequirements())
    }

    /**
     * Used by Nuclear Mode when an installer process appears. MSI may spawn
     * several helper processes, so a successful check opens a short window
     * rather than prompting once per 500 ms scan.
     */
    fun authorizeUninstallAttempt(): Boolean {
        if (!InstallVariant.isWindowsDirectInstall) return true
        val now = System.currentTimeMillis()
        if (authorizedUninstallUntilMs.get() > now) return true
        synchronized(uninstallPromptLock) {
            val refreshed = System.currentTimeMillis()
            if (authorizedUninstallUntilMs.get() > refreshed) return true
            val allowed = authorizeRequirements(activeRequirements())
            if (allowed) {
                authorizedUninstallUntilMs.set(
                    System.currentTimeMillis() + AUTHORIZATION_WINDOW_MS
                )
            }
            return allowed
        }
    }

    private fun activeRequirements(): List<Requirement> {
        val result = mutableListOf<Requirement>()
        runCatching {
            val until = Database.getSetting("standalone_block_until")?.toLongOrNull() ?: 0L
            val start = Database.getSetting("standalone_block_start")?.toLongOrNull() ?: 0L
            val processes = Database.getSetting("standalone_block_processes").orEmpty()
            val persistedActive = processes.isNotBlank() &&
                until > System.currentTimeMillis() &&
                (start == 0L || start <= System.currentTimeMillis())
            if (StandaloneBlockService.isActive || persistedActive) {
                val remaining = if (StandaloneBlockService.isActive) {
                    StandaloneBlockService.remainingMs()
                } else {
                    (until - System.currentTimeMillis()).coerceAtLeast(0L)
                }
                result += Requirement.WaitForStandalone(remaining)
            }
        }
        runCatching {
            val persistedAlwaysOn = Database.getSetting("always_on_enforcement") == "true"
            if ((ProcessMonitor.alwaysOnEnabled || persistedAlwaysOn) && GlobalPin.isSet()) {
                result += Requirement.Pin(
                    "Always-On enforcement",
                    "Enter the Global PIN to quit or uninstall while Always-On enforcement is active:",
                    GlobalPin::verify
                )
            }
        }
        runCatching {
            val focusActive = Database.hasUnfinishedFocusSession() ||
                FocusSessionService.state.value.isActive ||
                FocusLauncherService.isActive.value
            if (focusActive) {
                if (SessionPin.isSet()) {
                    result += Requirement.Pin(
                        "Focus session",
                        "Enter the Session PIN to quit or uninstall during the active focus session:",
                        SessionPin::verify
                    )
                } else {
                    result += Requirement.Blocked(
                        "FocusFlow cannot quit or uninstall during an active focus session without a Session PIN."
                    )
                }
            }
        }
        runCatching {
            val persistedNuclear = Database.getSetting("nuclear_mode") == "true"
            if (NuclearMode.isActive || persistedNuclear) {
                if (NuclearPin.isSet()) {
                    result += Requirement.Pin(
                        "Nuclear Mode",
                        "Enter the Nuclear Mode PIN to quit or uninstall while Nuclear Mode is active:",
                        NuclearPin::verify
                    )
                } else {
                    result += Requirement.Blocked(
                        "Disable Nuclear Mode from within FocusFlow before quitting or uninstalling."
                    )
                }
            }
        }
        return result
    }

    private fun authorizeRequirements(requirements: List<Requirement>): Boolean {
        if (requirements.isEmpty()) return true
        requirements.filterIsInstance<Requirement.WaitForStandalone>().firstOrNull()?.let {
            showMessage(
                "Protection is active",
                "A Standalone Block is active. FocusFlow must remain running until it ends.\n\n" +
                    "Time remaining: ${formatRemaining(it.remainingMs)}"
            )
            return false
        }
        requirements.filterIsInstance<Requirement.Blocked>().firstOrNull()?.let {
            showMessage("Action blocked", it.message)
            return false
        }
        requirements.filterIsInstance<Requirement.Pin>().forEach {
            if (!promptForPin(it)) return false
        }
        return true
    }

    private fun promptForPin(requirement: Requirement.Pin): Boolean {
        if (GraphicsEnvironment.isHeadless()) return false
        val entered = JOptionPane.showInputDialog(
            null, requirement.prompt, "${requirement.feature} protection",
            JOptionPane.WARNING_MESSAGE
        ) ?: return false
        if (requirement.verify(entered)) return true
        showMessage("Incorrect PIN", "The ${requirement.feature} PIN was incorrect. FocusFlow will keep running.")
        return false
    }

    private fun showMessage(title: String, message: String) {
        if (!GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(null, message, title, JOptionPane.WARNING_MESSAGE)
        }
    }

    private fun formatRemaining(remainingMs: Long): String {
        val totalSeconds = remainingMs.coerceAtLeast(0L) / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) "${hours}h ${minutes}m ${seconds}s"
        else "${minutes}m ${seconds.toString().padStart(2, '0')}s"
    }
}