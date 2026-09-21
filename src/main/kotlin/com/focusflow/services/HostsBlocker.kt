package com.focusflow.services

import com.focusflow.enforcement.isWindows
import com.focusflow.enforcement.isLinux
import com.focusflow.enforcement.EnforcementLog
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * HostsBlocker — three-layer hosts-file website blocking
 *
 * Layer 1 — Write robustly:
 *   Blocks bare domain + www + m + mobile + app subdomains. Normalises Windows
 *   line endings (CRLF → LF) before parsing to prevent duplicate entries.
 *   Appends only lines that are not already present.
 *
 * Layer 2 — Verify:
 *   After each write, calls [verifyBlock] which runs `nslookup` against the
 *   domain and confirms the resolved IP is 127.0.0.1. Returns a [BlockResult]
 *   that callers can surface to the user (blocked / not-blocked / no-admin).
 *
 * Layer 3 — Monitor:
 *   A background coroutine ([startMonitor] / [stopMonitor]) watches the hosts
 *   file for external modification (e.g. antivirus or another app removing
 *   entries). If blocks are missing they are silently re-applied.
 */
object HostsBlocker {

    private val HOSTS_PATH: String
        get() = if (isLinux) "/etc/hosts" else "C:\\Windows\\System32\\drivers\\etc\\hosts"
    private const val MARKER     = "# FocusFlow"
    private const val PRIVILEGED_HELPER_CLASS = "com.focusflow.services.HostsPrivilegedHelper"
    private val SAFE_DOMAIN = Regex(
        "^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*$"
    )

    /** Subdomains written for every blocked root domain. */
    private val SUBDOMAINS = listOf("", "www.", "m.", "mobile.", "app.")

    private val monitorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // @Volatile: startMonitor() writes on the Compose application thread; stopMonitor()
    // may be called from the "FocusFlow-Shutdown" daemon thread. Without @Volatile the
    // shutdown thread may see a stale null and leave the hosts-file monitor running.
    @Volatile private var monitorJob: Job? = null

    /**
     * Serialises all read-modify-write operations on the hosts file.
     * Without this, concurrent blockDomain / unblockDomain calls each read the file,
     * build their own modified version, and the last writer silently overwrites the other's
     * changes — leaving domains unblocked or duplicate lines in the file.
     */
    private val writeLock = Any()

    /** Tracks the last-known good set of blocked domains for the monitor loop. */
    @Volatile private var monitoredDomains: Set<String> = emptySet()

    // ── Layer 1: Write ────────────────────────────────────────────────────────

    sealed class BlockResult {
        object Success          : BlockResult()
        object AlreadyBlocked   : BlockResult()
        object NoAdmin          : BlockResult()
        object NotWindows       : BlockResult()
        object VerificationFail : BlockResult()
        data class Error(val reason: String) : BlockResult()
    }

    /**
     * Block [domain] by adding hosts entries for all common subdomains.
     * Returns a [BlockResult] describing outcome.
     */
    fun blockDomain(domain: String): BlockResult {
        if (!isWindows && !isLinux) return BlockResult.NotWindows
        val root = normalizeDomain(domain) ?: return BlockResult.Error("Invalid domain")

        // Linux normally runs unprivileged. The helper accepts only the
        // allowlisted operation and domain, and performs the read/modify/write
        // itself as root so user-controlled content never reaches a shell.
        if (isLinux && !canWriteHostsFile()) {
            if (!runLinuxPrivileged("block", root)) return BlockResult.NoAdmin
            flushDnsCache()
            monitoredDomains = monitoredDomains + root
            return if (verifyBlock(root)) BlockResult.Success else BlockResult.VerificationFail
        }

        return try {
            synchronized(writeLock) {
                val hostsFile = File(HOSTS_PATH)
                val existing  = normalizeContent(hostsFile.readText())
                val sb        = StringBuilder(existing)
                if (!existing.endsWith("\n")) sb.append("\n")

                var anyAdded = false
                SUBDOMAINS.forEach { prefix ->
                    val fqdn = "$prefix$root"
                    val line = "127.0.0.1  $fqdn  $MARKER\n"
                    if (!existing.contains("127.0.0.1  $fqdn  $MARKER")) {
                        sb.append(line)
                        anyAdded = true
                    }
                }

                if (!anyAdded) return BlockResult.AlreadyBlocked

                atomicWriteHosts(hostsFile, sb.toString())
                flushDnsCache()
                monitoredDomains = monitoredDomains + root
            }
            // Layer 2: verify (outside the lock — nslookup is slow and blocks nothing)
            if (verifyBlock(root)) BlockResult.Success else BlockResult.VerificationFail
        } catch (e: Exception) {
            // A writable check can race with permissions changing. Retry through
            // the constrained helper rather than reporting a silent partial path.
            if (isLinux && runLinuxPrivileged("block", root)) {
                flushDnsCache()
                monitoredDomains = monitoredDomains + root
                if (verifyBlock(root)) BlockResult.Success else BlockResult.VerificationFail
            } else {
                BlockResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun unblockDomain(domain: String): Boolean {
        if (!isWindows && !isLinux) return false
        val root = normalizeDomain(domain) ?: return false
        if (isLinux && !canWriteHostsFile()) {
            val removed = runLinuxPrivileged("unblock", root)
            if (removed) {
                flushDnsCache()
                monitoredDomains = monitoredDomains - root
            }
            return removed
        }

        return try {
            synchronized(writeLock) {
                val hostsFile = File(HOSTS_PATH)
                val lines     = normalizeContent(hostsFile.readText()).lines()
                val exactEntries = SUBDOMAINS.map { prefix ->
                    "127.0.0.1  $prefix$root  $MARKER"
                }.toSet()
                val filtered = lines.filter { it.trim() !in exactEntries }
                atomicWriteHosts(hostsFile, filtered.joinToString("\n") + "\n")
                flushDnsCache()
                monitoredDomains = monitoredDomains - root
            }
            true
        } catch (_: Exception) {
            if (isLinux) {
                val removed = runLinuxPrivileged("unblock", root)
                if (removed) {
                    flushDnsCache()
                    monitoredDomains = monitoredDomains - root
                }
                removed
            } else false
        }
    }

    fun unblockAll(): Boolean {
        if (!isWindows && !isLinux) return false
        if (isLinux && !canWriteHostsFile()) {
            val removed = runLinuxPrivileged("unblock-all")
            if (removed) {
                flushDnsCache()
                monitoredDomains = emptySet()
            }
            return removed
        }

        return try {
            synchronized(writeLock) {
                val hostsFile = File(HOSTS_PATH)
                val lines     = normalizeContent(hostsFile.readText()).lines()
                val filtered  = lines.filter { !it.contains(MARKER) }
                atomicWriteHosts(hostsFile, filtered.joinToString("\n") + "\n")
                flushDnsCache()
                monitoredDomains = emptySet()
            }
            true
        } catch (_: Exception) {
            if (isLinux) {
                val removed = runLinuxPrivileged("unblock-all")
                if (removed) {
                    flushDnsCache()
                    monitoredDomains = emptySet()
                }
                removed
            } else false
        }
    }

    fun getBlockedDomains(): List<String> {
        if (!isWindows && !isLinux) return emptyList()
        return try {
            normalizeContent(File(HOSTS_PATH).readText())
                .lines()
                .filter { it.contains(MARKER) }
                .mapNotNull { line ->
                    line.trim()
                        .removePrefix("127.0.0.1").trim()
                        .substringBefore(MARKER).trim()
                        .takeIf { it.isNotBlank() }
                }
                // Deduplicate: return only the root (no www./m./mobile./app. prefixes)
                .filter { d -> SUBDOMAINS.none { prefix -> prefix.isNotEmpty() && d.startsWith(prefix) } }
                .distinct()
        } catch (_: Exception) { emptyList() }
    }

    // ── Layer 2: Verify ───────────────────────────────────────────────────────

    /**
     * Returns true if [domain] resolves to 127.0.0.1 in a fresh nslookup.
     * Uses `nslookup` against 127.0.0.1 (localhost DNS) to bypass any cached
     * upstream result. A false return means the OS DNS cache may still serve the
     * real IP — the caller should surface this as a warning.
     */
    fun verifyBlock(domain: String): Boolean {
        if (!isWindows && !isLinux) return false
        return try {
            val safeDomain = normalizeDomain(domain) ?: return false
            val proc = ProcessBuilder("nslookup", safeDomain, "127.0.0.1")
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(10, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return false
            }
            // nslookup output contains "Address:  127.0.0.1" when the hosts entry is active
            output.contains("127.0.0.1")
        } catch (_: Exception) { false }
    }

    /**
     * Checks how many of the expected hosts entries are currently in the file.
     * Returns a fraction 0.0–1.0. A value < 1.0 means some entries are missing.
     */
    fun integrityScore(domain: String): Float {
        if (!isWindows && !isLinux) return 0f
        return try {
            val root    = normalizeDomain(domain) ?: return 0f
            val content = normalizeContent(File(HOSTS_PATH).readText())
            val present = SUBDOMAINS.count { prefix ->
                content.contains("127.0.0.1  $prefix$root  $MARKER")
            }
            present.toFloat() / SUBDOMAINS.size.toFloat()
        } catch (_: Exception) { 0f }
    }

    // ── Layer 3: Monitor ──────────────────────────────────────────────────────

    /**
     * Start a background coroutine that checks every [intervalMs] ms whether
     * the hosts file still contains all FocusFlow entries. Missing entries are
     * silently re-applied. Call this after the first [blockDomain] succeeds.
     */
    fun startMonitor(intervalMs: Long = 30_000L) {
        if (monitoredDomains.isEmpty()) {
            monitoredDomains = getBlockedDomains().toSet()
        }
        if (monitorJob?.isActive == true) return
        monitorJob = monitorScope.launch {
            while (isActive) {
                delay(intervalMs)
                reapplyMissingBlocks()
            }
        }
    }

    fun stopMonitor() {
        monitorJob?.cancel()
        monitorJob = null
    }

    /**
     * For every domain in [monitoredDomains], check if all expected entries are
     * present in the hosts file. Re-apply the full block if any are missing.
     * This silently corrects tampering by antivirus or other tools.
     */
    private fun reapplyMissingBlocks() {
        val domains = monitoredDomains
        if (domains.isEmpty()) return

        try {
            val content = normalizeContent(File(HOSTS_PATH).readText())
            val needsRepair = domains.any { root ->
                SUBDOMAINS.any { prefix -> !content.contains("127.0.0.1  $prefix$root  $MARKER") }
            }
            if (needsRepair) {
                domains.forEach { root -> blockDomain(root) }
            }
        } catch (_: Exception) {}
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Write [content] to [hostsFile] atomically.
     * Writes to a sibling temp file first, then replaces the real file with a
     * single move — so a crash mid-write can never leave a partial/empty hosts
     * file. REPLACE_EXISTING ensures the move succeeds even when the target exists.
     */
    private fun atomicWriteHosts(hostsFile: File, content: String) {
        val tmp = File(hostsFile.parent, "hosts.focusflow.tmp")
        tmp.writeText(content)
        Files.move(tmp.toPath(), hostsFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /** Strip Windows CRLF so line parsing is consistent across encodings. */
    private fun normalizeContent(content: String): String =
        content.replace("\r\n", "\n").replace("\r", "\n")

    private fun flushDnsCache() {
        try {
            if (isLinux) {
                // systemd-resolved may be present; nscd is a common alternative
                val proc = ProcessBuilder("resolvectl", "flush-caches")
                    .redirectErrorStream(true).start()
                val finished = proc.waitFor(10, TimeUnit.SECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    return
                }
                val exit = proc.exitValue()
                if (exit != 0) {
                    // fallback: restart nscd
                    try {
                        val fallback = ProcessBuilder("pkexec", "systemctl", "restart", "nscd")
                            .redirectErrorStream(true).start()
                        if (!fallback.waitFor(10, TimeUnit.SECONDS)) fallback.destroyForcibly()
                    } catch (_: Exception) {}
                }
            } else {
                val proc = ProcessBuilder("ipconfig", "/flushdns")
                    .redirectErrorStream(true).start()
                if (!proc.waitFor(10, TimeUnit.SECONDS)) proc.destroyForcibly()
            }
        } catch (_: Exception) {}
    }

    val isAdminRequired: Boolean get() = canWriteHostsFile()

    fun canWriteHostsFile(): Boolean {
        if (!isWindows && !isLinux) return false
        return try { File(HOSTS_PATH).canWrite() } catch (_: Exception) { false }
    }

    internal fun isSafeDomain(domain: String): Boolean = normalizeDomain(domain) != null

    private fun normalizeDomain(domain: String): String? {
        val root = domain.trim().lowercase().removePrefix("www.")
        return root.takeIf {
            it.isNotEmpty() && it.length <= 253 && SAFE_DOMAIN.matches(it)
        }
    }

    /**
     * Invoke the constrained helper without a shell. The helper is passed only
     * an allowlisted operation and a validated domain; it always targets
     * /etc/hosts itself and never accepts a target path or arbitrary file body.
     */
    private fun runLinuxPrivileged(operation: String, domain: String? = null): Boolean {
        if (!isLinux || operation !in setOf("block", "unblock", "unblock-all")) return false
        if (operation != "unblock-all" && (domain == null || !isSafeDomain(domain))) return false

        return try {
            val javaBin = File(System.getProperty("java.home"), "bin/java")
            // Use only the code source that contains the Java-only helper. Do
            // not elevate the caller's complete development classpath.
            val helperClasspath = File(
                Class.forName(PRIVILEGED_HELPER_CLASS).protectionDomain.codeSource.location.toURI()
            )
            val pkexec = listOf("/usr/bin/pkexec", "/bin/pkexec", "/usr/local/bin/pkexec")
                .map(::File)
                .firstOrNull { it.isFile && it.canExecute() }
            val classpath = helperClasspath.absolutePath
            if (!javaBin.isFile || !helperClasspath.exists() || pkexec == null) {
                EnforcementLog.warn("HostsBlocker", "Cannot locate the JVM/classpath for the privileged hosts helper")
                return false
            }

            val args = mutableListOf(
                pkexec.absolutePath,
                javaBin.absolutePath,
                "-cp",
                classpath,
                PRIVILEGED_HELPER_CLASS,
                operation
            )
            if (domain != null) args += domain

            val proc = ProcessBuilder(args).redirectErrorStream(true).start()
            if (!proc.waitFor(15, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                EnforcementLog.warn("HostsBlocker", "Privileged hosts helper timed out for operation=$operation")
                return false
            }
            val output = proc.inputStream.bufferedReader().readText().trim()
            if (proc.exitValue() == 0) {
                true
            } else {
                EnforcementLog.warn(
                    "HostsBlocker",
                    "Privileged hosts helper failed for operation=$operation exit=${proc.exitValue()}${if (output.isNotBlank()) ": $output" else ""}"
                )
                false
            }
        } catch (e: Exception) {
            EnforcementLog.warn("HostsBlocker", "Unable to start privileged hosts helper for operation=$operation", e)
            false
        }
    }
}
