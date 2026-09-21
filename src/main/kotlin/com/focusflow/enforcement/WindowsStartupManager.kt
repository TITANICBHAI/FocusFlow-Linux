package com.focusflow.enforcement

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.io.File

/**
 * WindowsStartupManager
 *
 * Adds / removes a HKCU\Software\Microsoft\Windows\CurrentVersion\Run registry
 * entry so FocusFlow JVM launches automatically on Windows login.
 *
 * Uses JNA Advapi32Util for registry access — no admin rights required for HKCU.
 */
object WindowsStartupManager {

    private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val APP_NAME = "FocusFlow"

    // Linux autostart path — ~/.config/autostart/focusflow.desktop
    private val linuxDesktopFile: File
        get() = File(
            System.getProperty("user.home") + "/.config/autostart/focusflow.desktop"
        )

    fun isEnabled(): Boolean {
        if (isLinux) return linuxDesktopFile.exists()
        if (!isWindows) return false
        return try {
            Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, APP_NAME)
        } catch (_: Exception) {
            false
        }
    }

    fun enable() {
        if (isLinux) {
            try {
                writeLinuxAutostartFile(linuxDesktopFile, resolveExePath())
            } catch (_: Exception) { /* skip silently if cannot write */ }
            return
        }
        if (!isWindows) return
        val exePath = resolveExePath()
        try {
            Advapi32Util.registrySetStringValue(
                WinReg.HKEY_CURRENT_USER, RUN_KEY, APP_NAME, "\"$exePath\""
            )
        } catch (_: Exception) {
            // Fallback: reg.exe CLI
            ProcessBuilder(
                "reg", "add", "HKCU\\$RUN_KEY",
                "/v", APP_NAME, "/t", "REG_SZ", "/d", "\"$exePath\"", "/f"
            ).start()
        }
    }

    fun disable() {
        if (isLinux) {
            try { linuxDesktopFile.delete() } catch (_: Exception) {}
            return
        }
        if (!isWindows) return
        try {
            if (isEnabled()) {
                Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, APP_NAME)
            }
        } catch (_: Exception) {
            ProcessBuilder(
                "reg", "delete", "HKCU\\$RUN_KEY", "/v", APP_NAME, "/f"
            ).start()
        }
    }

    /**
     * Exposed so the onboarding relaunch-as-admin button can find the exe path.
     */
    internal fun resolveExePath(): String {
        // Linux: return the current process command or script path
        if (isLinux) {
            val cmd = ProcessHandle.current().info().command().orElse("")
            if (cmd.isNotBlank() && File(cmd).exists() &&
                !File(cmd).name.equals("java", ignoreCase = true) &&
                !File(cmd).name.startsWith("java", ignoreCase = true)
            ) return cmd

            val candidates = listOf(
                File(System.getProperty("user.dir", ""), "focusflow"),
                File("/usr/bin/focusflow"),
                File("/usr/local/bin/focusflow"),
                File("/opt/focusflow/bin/focusflow"),
                File(System.getProperty("user.home", ""), "Applications/FocusFlow.AppImage")
            )
            candidates.firstOrNull { it.isFile && it.canExecute() }?.let { return it.absolutePath }
            return "java -jar /usr/share/focusflow/focusflow.jar"
        }

        val processCmd = ProcessHandle.current().info().command().orElse("")

        // Case 1: already running as FocusFlow.exe (rare — JVM usually shows java.exe)
        if (processCmd.endsWith("FocusFlow.exe", ignoreCase = true) && File(processCmd).exists())
            return processCmd

        // Case 2: Compose Desktop distributable layout
        //   <install>/app/runtime/bin/java.exe  →  go up 4 levels → <install>/FocusFlow.exe
        if (processCmd.isNotBlank()) {
            var dir: File? = File(processCmd)
            repeat(4) { dir = dir?.parentFile }
            val candidate = dir?.let { File(it, "FocusFlow.exe") }
            if (candidate?.exists() == true) return candidate.absolutePath
        }

        // Case 3: working directory is the install root
        val fromUserDir = File(System.getProperty("user.dir", ""), "FocusFlow.exe")
        if (fromUserDir.exists()) return fromUserDir.absolutePath

        // Case 4: one level up from working directory
        val fromParent = File(System.getProperty("user.dir", "")).parentFile
            ?.let { File(it, "FocusFlow.exe") }
        if (fromParent?.exists() == true) return fromParent.absolutePath

        // Fallback
        return "FocusFlow.exe"
    }

    private fun writeLinuxAutostartFile(file: File, command: String) {
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }
        file.writeText(
            "[Desktop Entry]\n" +
                "Type=Application\n" +
                "Name=FocusFlow\n" +
                "Exec=${desktopExec(command)}\n" +
                "StartupWMClass=focusflow\n" +
                "Terminal=false\n" +
                "X-GNOME-Autostart-enabled=true\n"
        )
    }

    /**
     * Tokenize and quote a Linux desktop Exec= value without invoking a shell.
     * The old implementation split on spaces before checking for whitespace,
     * so an executable path such as "/opt/Focus Flow/focusflow" was emitted as
     * two unrelated tokens.
     */
    private fun desktopExec(command: String): String {
        // resolveExePath() normally returns a single existing executable path,
        // which may itself contain spaces. Treat that as one token before
        // falling back to parsing a multi-token command such as `java -jar`.
        val tokens = if (File(command).isFile) listOf(command) else tokenizeDesktopCommand(command)
        return tokens.joinToString(" ") { token ->
            if (token.isNotEmpty() && token.all { it.isLetterOrDigit() || it in "._+-%/:=@," }) {
                token
            } else {
                "\"${token.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            }
        }
    }

    private fun tokenizeDesktopCommand(value: String): List<String> {
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

        value.forEach { char ->
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

    /** Test-only hooks keep lifecycle tests on disposable paths. */
    internal fun writeLinuxAutostartFileForTesting(file: File, command: String) =
        writeLinuxAutostartFile(file, command)

    internal fun desktopExecForTesting(command: String): String = desktopExec(command)
}
