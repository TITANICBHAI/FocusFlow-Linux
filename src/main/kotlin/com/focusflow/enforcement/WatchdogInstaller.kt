package com.focusflow.enforcement

import java.io.File

/**
 * WatchdogInstaller — self-restart guardian for FocusFlow.
 *
 * Registers two Windows Scheduled Tasks:
 *
 *   1. FocusFlowWatchdog — fires every 2 minutes. Checks whether FocusFlow.exe
 *      is running and, if not, relaunches it. Handles force-kills, crashes, and
 *      OOM terminations. Uses /IT (interactive token) — no admin rights needed.
 *
 *   2. FocusFlowTaskbarGuard — fires at every user logon. Unconditionally restores
 *      the Windows taskbar (primary + all secondary monitors) using a Win32
 *      ShowWindow call. This is the safety net for the "hard kill while kiosk is
 *      active" scenario: if FocusFlow is killed at the OS level and never relaunched,
 *      the user's taskbar is guaranteed to reappear on their next login, even if
 *      FocusFlow is uninstalled.
 */
object WatchdogInstaller {

    private const val TASK_NAME         = "FocusFlowWatchdog"
    private const val GUARD_TASK_NAME   = "FocusFlowTaskbarGuard"

    fun isInstalled(): Boolean {
        if (isLinux) {
            // Check if systemd user service exists
            return try {
                val proc = ProcessBuilder("systemctl", "--user", "is-enabled", "focusflow-watchdog.timer")
                    .redirectErrorStream(true).start()
                proc.inputStream.bufferedReader().readText().trim() == "enabled"
            } catch (_: Exception) { false }
        }
        if (!isWindows) return false
        return try {
            val proc = ProcessBuilder("schtasks", "/query", "/tn", TASK_NAME)
                .redirectErrorStream(true)
                .start()
            proc.waitFor() == 0
        } catch (_: Exception) { false }
    }

    /**
     * Creates (or overwrites) both the watchdog task and the taskbar-guard task.
     * Safe to call on every launch — /f overwrites silently.
     */
    fun install() {
        if (isLinux) {
            installLinuxWatchdog()
            return
        }
        if (!isWindows) return
        installWatchdog()
        installTaskbarGuard()
    }

    // ── Linux systemd user timer ──────────────────────────────────────────

    /**
     * Installs a systemd user timer that checks every 2 minutes whether
     * FocusFlow is running and relaunches if needed.
     * // Linux kiosk: systemd user units may not be available on all DEs.
     */
    private fun installLinuxWatchdog() {
        try {
            val home = System.getProperty("user.home")
            val systemdDir = File("$home/.config/systemd/user")
            systemdDir.mkdirs()

            // Resolve the full command line used to launch this JVM process so the
            // watchdog relaunches FocusFlow exactly the same way (same classpath, flags, etc.)
            val info = ProcessHandle.current().info()
            val cmd = info.command().orElse(null)
                ?: runCatching {
                    java.nio.file.Files.readSymbolicLink(java.nio.file.Path.of("/proc/self/exe")).toString()
                }.getOrNull()
                ?: return  // can't determine exe path — skip watchdog silently

            // Reconstruct the full ExecStart line using systemd's direct-exec
            // syntax. No user-controlled value is passed through a shell.
            val args = info.arguments().map { it.toList() }.orElse(emptyList())
            writeLinuxUnits(systemdDir, cmd, args)
            val execStartLine = linuxExecStartLine(cmd, args)

            ProcessBuilder("systemctl", "--user", "daemon-reload").start().waitFor()
            ProcessBuilder("systemctl", "--user", "enable", "focusflow-watchdog.timer").start().waitFor()
            ProcessBuilder("systemctl", "--user", "start", "focusflow-watchdog.timer").start().waitFor()
            EnforcementLog.info("WatchdogInstaller", "Linux watchdog timer installed (cmd=$execStartLine)")
        } catch (_: Exception) {
            EnforcementLog.warn("WatchdogInstaller", "Failed to install Linux watchdog timer")
        }
    }

    private fun installWatchdog() {
        val exePath = WindowsStartupManager.resolveExePath()
        val safePath = exePath.replace("'", "''")
        val psScript = "if (-not (Get-Process -Name 'FocusFlow' -ErrorAction SilentlyContinue))" +
                " { Start-Process '$safePath' }"
        try {
            val exit = ProcessBuilder(
                "schtasks", "/create",
                "/tn", TASK_NAME,
                "/tr", "powershell -WindowStyle Hidden -NonInteractive -Command \"$psScript\"",
                "/sc", "MINUTE",
                "/mo", "2",
                "/it",
                "/f"
            ).redirectErrorStream(true).start().waitFor()
            if (exit != 0) EnforcementLog.warn("WatchdogInstaller", "schtasks /create $TASK_NAME exited with code $exit — watchdog may not self-restart")
            else EnforcementLog.info("WatchdogInstaller", "$TASK_NAME registered (exe=$exePath)")
        } catch (e: Exception) {
            EnforcementLog.warn("WatchdogInstaller", "Failed to register $TASK_NAME", e)
        }
    }

    private fun quoteSystemdExecArg(value: String): String {
        if (value.isNotEmpty() && value.all {
                it.isLetterOrDigit() || it in "._+-%/:=@,"
            }) {
            return value
        }
        return "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    }

    private fun linuxExecStartLine(command: String, args: List<String>): String =
        (listOf(command) + args).joinToString(" ", transform = ::quoteSystemdExecArg)

    private fun linuxServiceUnit(command: String, args: List<String>): String =
        "[Unit]\n" +
            "Description=FocusFlow Watchdog\n" +
            "[Service]\n" +
            "Type=oneshot\n" +
            // Exit 0 (proceed to start) only when FocusFlow is NOT running.
            // The command is fixed; the generated ExecStart uses direct argv.
            "ExecStartPre=/bin/sh -c '! pgrep -f focusflow > /dev/null'\n" +
            "ExecStart=${linuxExecStartLine(command, args)}\n" +
            "Restart=no\n"

    private fun linuxTimerUnit(): String =
        "[Unit]\n" +
            "Description=FocusFlow Watchdog Timer\n" +
            "[Timer]\n" +
            "OnBootSec=1min\n" +
            "OnUnitActiveSec=2min\n" +
            "[Install]\n" +
            "WantedBy=default.target\n"

    private fun writeLinuxUnits(directory: File, command: String, args: List<String>) {
        directory.mkdirs()
        File(directory, "focusflow-watchdog.service").writeText(linuxServiceUnit(command, args))
        File(directory, "focusflow-watchdog.timer").writeText(linuxTimerUnit())
    }

    /** Test-only hooks keep watchdog lifecycle tests on disposable paths. */
    internal fun writeLinuxUnitsForTesting(
        directory: File,
        command: String,
        args: List<String> = emptyList()
    ) = writeLinuxUnits(directory, command, args)

    internal fun linuxServiceUnitForTesting(command: String, args: List<String> = emptyList()): String =
        linuxServiceUnit(command, args)

    /**
     * Installs a logon-triggered scheduled task that restores the Windows taskbar.
     *
     * Writes a small .ps1 script to %APPDATA%\FocusFlow\ and creates a task that
     * runs it at every logon. The script uses Win32 ShowWindow to un-hide both the
     * primary taskbar (Shell_TrayWnd) and all secondary taskbars
     * (Shell_SecondaryTrayWnd), then deletes itself from the scheduled tasks once
     * the taskbar was already visible (indicating no crash guard was needed).
     *
     * This task is INDEPENDENT of FocusFlow — it runs even if FocusFlow is
     * uninstalled, ensuring users never get stuck with a permanently hidden taskbar.
     */
    private fun installTaskbarGuard() {
        try {
            val appDataDir = File(System.getenv("APPDATA") ?: return, "FocusFlow")
            appDataDir.mkdirs()
            val scriptFile = File(appDataDir, "taskbar-guard.ps1")

            // PowerShell script: show primary + ALL secondary taskbar windows via Win32.
            // Uses a loop with FindWindowEx to handle 3+ monitor setups.
            val D = '$'
            scriptFile.writeText("""
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public class FocusFlowTaskbarGuard {
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    public static extern IntPtr FindWindow(string lpClassName, string lpWindowName);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    public static extern IntPtr FindWindowEx(IntPtr hwndParent, IntPtr hwndChildAfter, string lpszClass, string lpszWindow);
    [DllImport("user32.dll")]
    public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")]
    public static extern bool IsWindowVisible(IntPtr hWnd);
}
'@

${D}SW_SHOW = 5

# Restore primary taskbar
${D}primary = [FocusFlowTaskbarGuard]::FindWindow("Shell_TrayWnd", ${D}null)
if (${D}primary -ne [IntPtr]::Zero) {
    [FocusFlowTaskbarGuard]::ShowWindow(${D}primary, ${D}SW_SHOW) | Out-Null
}

# Restore ALL secondary taskbars (handles 3+ monitor setups)
${D}prev = [IntPtr]::Zero
while (${D}true) {
    ${D}sec = [FocusFlowTaskbarGuard]::FindWindowEx([IntPtr]::Zero, ${D}prev, "Shell_SecondaryTrayWnd", ${D}null)
    if (${D}sec -eq [IntPtr]::Zero) { break }
    [FocusFlowTaskbarGuard]::ShowWindow(${D}sec, ${D}SW_SHOW) | Out-Null
    ${D}prev = ${D}sec
}
""".trimIndent())

            val safePath = scriptFile.absolutePath.replace("'", "''")
            val exit = ProcessBuilder(
                "schtasks", "/create",
                "/tn", GUARD_TASK_NAME,
                "/tr", "powershell -ExecutionPolicy Bypass -NonInteractive -WindowStyle Hidden -File \"$safePath\"",
                "/sc", "ONLOGON",
                "/it",
                "/f"
            ).redirectErrorStream(true).start().waitFor()
            if (exit != 0) EnforcementLog.warn("WatchdogInstaller", "schtasks /create $GUARD_TASK_NAME exited with code $exit — taskbar may not restore on next logon")
            else EnforcementLog.info("WatchdogInstaller", "$GUARD_TASK_NAME registered (script=${scriptFile.absolutePath})")
        } catch (e: Exception) {
            EnforcementLog.warn("WatchdogInstaller", "Failed to register $GUARD_TASK_NAME", e)
        }
    }

    fun uninstall() {
        if (isLinux) {
            try {
                ProcessBuilder("systemctl", "--user", "stop", "focusflow-watchdog.timer").start().waitFor()
                ProcessBuilder("systemctl", "--user", "disable", "focusflow-watchdog.timer").start().waitFor()
                val home = System.getProperty("user.home")
                File("$home/.config/systemd/user/focusflow-watchdog.service").delete()
                File("$home/.config/systemd/user/focusflow-watchdog.timer").delete()
            } catch (_: Exception) {}
            return
        }
        if (!isWindows) return
        try {
            ProcessBuilder("schtasks", "/delete", "/tn", TASK_NAME, "/f")
                .redirectErrorStream(true).start().waitFor()
        } catch (_: Exception) { }
        try {
            ProcessBuilder("schtasks", "/delete", "/tn", GUARD_TASK_NAME, "/f")
                .redirectErrorStream(true).start().waitFor()
        } catch (_: Exception) { }
    }
}
