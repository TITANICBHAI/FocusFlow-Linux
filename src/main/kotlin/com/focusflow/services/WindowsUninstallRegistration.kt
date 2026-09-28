package com.focusflow.services

import com.focusflow.enforcement.InstallVariant
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.io.File

/**
 * Wraps the direct Windows uninstall entry with FocusFlow's --uninstall gate.
 *
 * MSIX/Store removal stays owned by Windows. Registry access is best effort:
 * permission failures leave the stock entry intact rather than changing an
 * unrelated uninstall record.
 */
object WindowsUninstallRegistration {
    private const val ROOT = "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall"
    private const val DISPLAY_NAME = "DisplayName"
    private const val UNINSTALL_STRING = "UninstallString"
    private const val QUIET_UNINSTALL_STRING = "QuietUninstallString"
    private const val ORIGINAL = "FocusFlowOriginalUninstallString"
    private const val MARKER = "FocusFlowUninstallWizard"

    data class RegisteredCommand(
        val registryPath: String,
        val command: String
    )

    fun ensureRegistered() {
        if (!InstallVariant.isWindowsDirectInstall) return
        val executable = currentExecutable() ?: return
        if (!executable.isFile || !executable.name.endsWith(".exe", ignoreCase = true)) return

        for (hive in listOf(WinReg.HKEY_CURRENT_USER, WinReg.HKEY_LOCAL_MACHINE)) {
            val subkeys = runCatching { Advapi32Util.registryGetKeys(hive, ROOT) }
                .getOrElse { continue }
            for (subkey in subkeys) {
                val path = "$ROOT\\$subkey"
                val values = runCatching { Advapi32Util.registryGetValues(hive, path) }
                    .getOrElse { continue }
                val displayName = (values[DISPLAY_NAME] as? String)?.trim() ?: continue
                if (!displayName.equals("FocusFlow", ignoreCase = true) &&
                    !displayName.startsWith("FocusFlow ", ignoreCase = true)
                ) continue

                val existing = (values[UNINSTALL_STRING] as? String)?.trim().orEmpty()
                val original = (values[ORIGINAL] as? String)?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: existing.takeIf { it.isNotBlank() }
                    ?: continue
                try {
                    Advapi32Util.registrySetStringValue(hive, path, ORIGINAL, normalizeRemovalCommand(original))
                    Advapi32Util.registrySetStringValue(hive, path, MARKER, "1")
                    val command = "\"${executable.absolutePath}\" --uninstall"
                    Advapi32Util.registrySetStringValue(hive, path, UNINSTALL_STRING, command)
                    Advapi32Util.registrySetStringValue(hive, path, QUIET_UNINSTALL_STRING, command)
                    return
                } catch (_: Throwable) {
                    // HKLM can be readable but not writable; try HKCU next.
                }
            }
        }
    }

    fun registeredCommand(): RegisteredCommand? {
        if (!InstallVariant.isWindowsDirectInstall) return null
        for (hive in listOf(WinReg.HKEY_CURRENT_USER, WinReg.HKEY_LOCAL_MACHINE)) {
            val subkeys = runCatching { Advapi32Util.registryGetKeys(hive, ROOT) }
                .getOrElse { continue }
            for (subkey in subkeys) {
                val path = "$ROOT\\$subkey"
                val values = runCatching { Advapi32Util.registryGetValues(hive, path) }
                    .getOrElse { continue }
                if ((values[MARKER] as? String) != "1") continue
                val command = (values[ORIGINAL] as? String)?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::normalizeRemovalCommand)
                    ?: continue
                return RegisteredCommand(path, command)
            }
        }
        return null
    }

    private fun normalizeRemovalCommand(command: String): String =
        command.replace(
            Regex("""(?i)(\bmsiexec(?:\.exe)?\s+)/i(?=[\s{])""")
        ) { "${it.groupValues[1]}/x" }

    private fun currentExecutable(): File? = runCatching {
        val command = ProcessHandle.current().info().command().orElse(null)?.let(::File)
        if (command?.isFile == true &&
            command.name.equals("FocusFlow.exe", ignoreCase = true)
        ) return@runCatching command

        sequenceOf(
            command,
            System.getProperty("compose.application.resources.dir")
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
        )
            .filterNotNull()
            .flatMap { file -> generateSequence(file) { it.parentFile }.take(8) }
            .distinctBy { it.absolutePath.lowercase() }
            .map { File(it, "FocusFlow.exe") }
            .firstOrNull { it.isFile }
    }.getOrNull()
}