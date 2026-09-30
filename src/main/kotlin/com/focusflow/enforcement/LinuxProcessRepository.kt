package com.focusflow.enforcement

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

enum class LinuxProcessField {
    PROCESS_START_TICKS,
    UID,
    PARENT_PID,
    PROCESS_GROUP_ID,
    SESSION_ID,
    COMM,
    EXECUTABLE_PATH,
    ARGV,
    WORKING_DIRECTORY,
    CGROUP_PATH,
    INSTANCE_STABILITY
}

enum class LinuxProcessFieldStatus {
    AVAILABLE,
    PERMISSION_DENIED,
    FIELD_UNAVAILABLE,
    PROCESS_EXITED_DURING_READ,
    MALFORMED
}

enum class LinuxProcessIoState {
    AVAILABLE,
    PARTIAL,
    PROCESS_EXITED_DURING_READ
}

enum class ProcessIdentityUnknownReason {
    INVALID_PID,
    INVALID_START_TICKS,
    START_TICKS_UNAVAILABLE
}

/**
 * Raw facts observed for one Linux PID. This contains no application,
 * runtime-family, package, catalog, or authorization decisions.
 */
data class LinuxProcessSnapshot(
    val pid: Long,
    val processStartTicks: Long?,
    val uid: Long?,
    val parentPid: Long?,
    val processGroupId: Long?,
    val sessionId: Long?,
    val comm: String?,
    val executablePath: String?,
    val executableBasename: String?,
    val argv: List<String>,
    val workingDirectory: String?,
    val cgroupPath: String?,
    val ioState: LinuxProcessIoState,
    val fieldStatuses: Map<LinuxProcessField, LinuxProcessFieldStatus>
) {
    /** Fields observed successfully in this snapshot. */
    val fieldAvailability: Set<LinuxProcessField>
        get() = fieldStatuses
            .filterValues { it == LinuxProcessFieldStatus.AVAILABLE }
            .keys

    /**
     * Identity is unknown unless both a positive PID and non-negative kernel
     * start ticks were observed. A pidfd is deliberately not an input here.
     */
    val processInstanceIdentity: ProcessInstanceIdentity
        get() = ProcessInstanceKey.from(pid, processStartTicks)

    /** A comparison value only; argv is hashed so it is not retained here. */
    val fingerprint: ProcessFingerprint
        get() = ProcessFingerprint.from(this)
}

/** One immutable result of a complete procfs directory sweep. */
data class LinuxProcessSnapshotGeneration(
    val generationId: Long,
    val observedAtEpochMs: Long,
    val processes: List<LinuxProcessSnapshot>,
    val ioState: LinuxProcessIoState
)

sealed interface ProcessInstanceIdentity {
    data class Known(val key: ProcessInstanceKey) : ProcessInstanceIdentity

    data class Unknown(
        val pid: Long,
        val reason: ProcessIdentityUnknownReason
    ) : ProcessInstanceIdentity
}

/** An unknown observation can never validate itself, even when a pidfd exists. */
internal fun isSameKnownProcessInstance(
    expected: ProcessInstanceIdentity,
    current: ProcessInstanceIdentity
): Boolean {
    val expectedKey = (expected as? ProcessInstanceIdentity.Known)?.key ?: return false
    val currentKey = (current as? ProcessInstanceIdentity.Known)?.key ?: return false
    return expectedKey == currentKey
}

/** Reject unknown identities and stale observations of same-instance execs. */
internal fun isSameKnownProcessObservation(
    expected: LinuxProcessSnapshot,
    current: LinuxProcessSnapshot
): Boolean =
    isSameKnownProcessInstance(
        expected.processInstanceIdentity,
        current.processInstanceIdentity
    ) && expected.fingerprint == current.fingerprint

/**
 * Stable identity for one Linux process instance. Construction is restricted
 * to observations containing both PID and start ticks.
 */
class ProcessInstanceKey private constructor(
    val pid: Long,
    val processStartTicks: Long
) {
    override fun equals(other: Any?): Boolean =
        other is ProcessInstanceKey &&
            pid == other.pid &&
            processStartTicks == other.processStartTicks

    override fun hashCode(): Int =
        31 * pid.hashCode() + processStartTicks.hashCode()

    override fun toString(): String =
        "ProcessInstanceKey(pid=$pid, processStartTicks=$processStartTicks)"

    companion object {
        internal fun from(pid: Long, processStartTicks: Long?): ProcessInstanceIdentity {
            if (pid <= 0L) {
                return ProcessInstanceIdentity.Unknown(
                    pid,
                    ProcessIdentityUnknownReason.INVALID_PID
                )
            }
            if (processStartTicks == null) {
                return ProcessInstanceIdentity.Unknown(
                    pid,
                    ProcessIdentityUnknownReason.START_TICKS_UNAVAILABLE
                )
            }
            if (processStartTicks < 0L) {
                return ProcessInstanceIdentity.Unknown(
                    pid,
                    ProcessIdentityUnknownReason.INVALID_START_TICKS
                )
            }
            return ProcessInstanceIdentity.Known(ProcessInstanceKey(pid, processStartTicks))
        }
    }
}

/**
 * In-memory change detector for a process instance. The argument vector is
 * represented by a digest rather than retained or emitted in fingerprint text.
 */
data class ProcessFingerprint(
    val executablePath: String?,
    val executableBasename: String?,
    val comm: String?,
    val argvDigest: String?,
    val workingDirectory: String?,
    val cgroupPath: String?
) {
    companion object {
        fun from(snapshot: LinuxProcessSnapshot): ProcessFingerprint =
            ProcessFingerprint(
                executablePath = snapshot.executablePath,
                executableBasename = snapshot.executableBasename,
                comm = snapshot.comm,
                argvDigest = if (
                    snapshot.fieldStatuses[LinuxProcessField.ARGV] ==
                    LinuxProcessFieldStatus.AVAILABLE
                ) {
                    digestArgv(snapshot.argv)
                } else {
                    null
                },
                workingDirectory = snapshot.workingDirectory,
                cgroupPath = snapshot.cgroupPath
            )

        private fun digestArgv(argv: List<String>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            argv.forEach { argument ->
                val bytes = argument.toByteArray(StandardCharsets.UTF_8)
                digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
                digest.update(bytes)
            }
            return buildString(64) {
                digest.digest().forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(HEX[value ushr 4])
                    append(HEX[value and 0x0f])
                }
            }
        }

        private const val HEX = "0123456789abcdef"
    }
}

internal data class ParsedLinuxProcStat(
    val pid: Long,
    val comm: String,
    val processStartTicks: Long?,
    val parentPid: Long?,
    val processGroupId: Long?,
    val sessionId: Long?,
    val fieldStatuses: Map<LinuxProcessField, LinuxProcessFieldStatus>
)

/**
 * The sole Linux process enumeration and procfs observation path. Each call to
 * [snapshot] returns one generation; unreadable fields remain explicit in
 * partial per-process snapshots instead of dropping those PIDs.
 */
class LinuxProcessRepository(
    private val procRoot: Path = Path.of("/proc"),
    private val epochClockMs: () -> Long = System::currentTimeMillis
) {
    private val generationSequence = AtomicLong(0L)

    fun snapshot(): LinuxProcessSnapshotGeneration {
        val pids = try {
            Files.newDirectoryStream(procRoot).use { entries ->
                entries.asSequence()
                    .mapNotNull { it.fileName?.toString()?.toLongOrNull() }
                    .filter { it > 0L }
                    .sorted()
                    .toList()
            }
        } catch (exception: IOException) {
            throw LinuxProcessRepositoryException(
                "Unable to enumerate Linux process directory $procRoot",
                exception
            )
        } catch (exception: SecurityException) {
            throw LinuxProcessRepositoryException(
                "Permission denied enumerating Linux process directory $procRoot",
                exception
            )
        }

        val processes = pids.map(::readProcess)
        val state = if (processes.all { it.ioState == LinuxProcessIoState.AVAILABLE }) {
            LinuxProcessIoState.AVAILABLE
        } else {
            LinuxProcessIoState.PARTIAL
        }
        return LinuxProcessSnapshotGeneration(
            generationId = generationSequence.incrementAndGet(),
            observedAtEpochMs = epochClockMs(),
            processes = processes.toList(),
            ioState = state
        )
    }

    /**
     * Read one PID. This is also used immediately before Linux launcher
     * enforcement to compare a fresh process-instance identity.
     */
    internal fun readProcess(pid: Long): LinuxProcessSnapshot {
        if (pid <= 0L) return malformedSnapshot(pid)

        val processDirectory = procRoot.resolve(pid.toString())

        val statuses = mutableMapOf<LinuxProcessField, LinuxProcessFieldStatus>()
        val statRead = readText(processDirectory.resolve("stat"), processDirectory)
        if (statRead.status == LinuxProcessFieldStatus.PROCESS_EXITED_DURING_READ) {
            return exitedSnapshot(pid)
        }
        val statBefore = if (statRead.status == LinuxProcessFieldStatus.AVAILABLE) {
            parseStat(pid, statRead.value.orEmpty())
        } else {
            null
        }
        if (statRead.status != LinuxProcessFieldStatus.AVAILABLE) {
            STAT_FIELDS.forEach { statuses[it] = statRead.status }
        } else if (statBefore == null) {
            STAT_FIELDS.forEach { statuses[it] = LinuxProcessFieldStatus.MALFORMED }
        } else {
            statuses.putAll(statBefore.fieldStatuses)
        }

        val uidRead = readText(processDirectory.resolve("status"), processDirectory)
        if (uidRead.status == LinuxProcessFieldStatus.PROCESS_EXITED_DURING_READ) {
            return exitedSnapshot(pid)
        }
        val uid = if (uidRead.status == LinuxProcessFieldStatus.AVAILABLE) {
            parseUid(uidRead.value.orEmpty()).also { value ->
                statuses[LinuxProcessField.UID] = if (value == null) {
                    LinuxProcessFieldStatus.MALFORMED
                } else {
                    LinuxProcessFieldStatus.AVAILABLE
                }
            }
        } else {
            statuses[LinuxProcessField.UID] = uidRead.status
            null
        }

        val argvRead = readBytes(processDirectory.resolve("cmdline"), processDirectory)
        if (argvRead.status == LinuxProcessFieldStatus.PROCESS_EXITED_DURING_READ) {
            return exitedSnapshot(pid)
        }
        val argv = if (argvRead.status == LinuxProcessFieldStatus.AVAILABLE) {
            statuses[LinuxProcessField.ARGV] = LinuxProcessFieldStatus.AVAILABLE
            parseCommandLine(argvRead.value ?: byteArrayOf())
        } else {
            statuses[LinuxProcessField.ARGV] = argvRead.status
            emptyList()
        }

        val executableRead = readLink(processDirectory.resolve("exe"), processDirectory)
        if (executableRead.status == LinuxProcessFieldStatus.PROCESS_EXITED_DURING_READ) {
            return exitedSnapshot(pid)
        }
        statuses[LinuxProcessField.EXECUTABLE_PATH] = executableRead.status

        val workingDirectoryRead = readLink(processDirectory.resolve("cwd"), processDirectory)
        if (workingDirectoryRead.status == LinuxProcessFieldStatus.PROCESS_EXITED_DURING_READ) {
            return exitedSnapshot(pid)
        }
        statuses[LinuxProcessField.WORKING_DIRECTORY] = workingDirectoryRead.status

        val cgroupRead = readText(processDirectory.resolve("cgroup"), processDirectory)
        if (cgroupRead.status == LinuxProcessFieldStatus.PROCESS_EXITED_DURING_READ) {
            return exitedSnapshot(pid)
        }
        val cgroupPath = if (cgroupRead.status == LinuxProcessFieldStatus.AVAILABLE) {
            statuses[LinuxProcessField.CGROUP_PATH] = LinuxProcessFieldStatus.AVAILABLE
            parseCgroupPath(cgroupRead.value.orEmpty())
        } else {
            statuses[LinuxProcessField.CGROUP_PATH] = cgroupRead.status
            null
        }

        val stabilityStatus = checkInstanceStability(
            pid = pid,
            processDirectory = processDirectory,
            statBefore = statBefore,
            initialStatus = statRead.status
        )
        if (stabilityStatus == LinuxProcessFieldStatus.PROCESS_EXITED_DURING_READ) {
            return exitedSnapshot(pid)
        }
        statuses[LinuxProcessField.INSTANCE_STABILITY] = stabilityStatus
        if (
            statBefore?.processStartTicks != null &&
            stabilityStatus != LinuxProcessFieldStatus.AVAILABLE
        ) {
            statuses[LinuxProcessField.PROCESS_START_TICKS] = stabilityStatus
        }

        val executablePath = executableRead.value
        val processTicks = statBefore?.processStartTicks.takeIf {
            stabilityStatus == LinuxProcessFieldStatus.AVAILABLE
        }
        val normalizedStatuses = completeStatusMap(statuses)
        val ioState = if (normalizedStatuses.values.all {
                it == LinuxProcessFieldStatus.AVAILABLE
            }
        ) {
            LinuxProcessIoState.AVAILABLE
        } else {
            LinuxProcessIoState.PARTIAL
        }
        return LinuxProcessSnapshot(
            pid = pid,
            processStartTicks = processTicks,
            uid = uid,
            parentPid = statBefore?.parentPid,
            processGroupId = statBefore?.processGroupId,
            sessionId = statBefore?.sessionId,
            comm = statBefore?.comm,
            executablePath = executablePath,
            executableBasename = executablePath?.let(::executableBasename),
            argv = argv.toList(),
            workingDirectory = workingDirectoryRead.value,
            cgroupPath = cgroupPath,
            ioState = ioState,
            fieldStatuses = normalizedStatuses
        )
    }

    private fun checkInstanceStability(
        pid: Long,
        processDirectory: Path,
        statBefore: ParsedLinuxProcStat?,
        initialStatus: LinuxProcessFieldStatus
    ): LinuxProcessFieldStatus {
        if (initialStatus != LinuxProcessFieldStatus.AVAILABLE) return initialStatus
        val initialTicks = statBefore?.processStartTicks
            ?: return statBefore?.fieldStatuses?.get(LinuxProcessField.PROCESS_START_TICKS)
                ?: LinuxProcessFieldStatus.MALFORMED

        val statAfterRead = readText(processDirectory.resolve("stat"), processDirectory)
        if (statAfterRead.status != LinuxProcessFieldStatus.AVAILABLE) {
            return statAfterRead.status
        }
        val statAfter = parseStat(pid, statAfterRead.value.orEmpty())
            ?: return LinuxProcessFieldStatus.MALFORMED
        val finalTicks = statAfter.processStartTicks
            ?: return statAfter.fieldStatuses[LinuxProcessField.PROCESS_START_TICKS]
                ?: LinuxProcessFieldStatus.MALFORMED
        return if (initialTicks == finalTicks) {
            LinuxProcessFieldStatus.AVAILABLE
        } else {
            LinuxProcessFieldStatus.PROCESS_EXITED_DURING_READ
        }
    }

    private fun readText(path: Path, processDirectory: Path): FieldRead<String> =
        try {
            FieldRead(Files.readString(path), LinuxProcessFieldStatus.AVAILABLE)
        } catch (exception: NoSuchFileException) {
            FieldRead(null, missingPathStatus(processDirectory))
        } catch (exception: AccessDeniedException) {
            FieldRead(null, LinuxProcessFieldStatus.PERMISSION_DENIED)
        } catch (exception: SecurityException) {
            FieldRead(null, LinuxProcessFieldStatus.PERMISSION_DENIED)
        } catch (exception: java.nio.file.DirectoryIteratorException) {
            throw LinuxProcessRepositoryException(
                "Unable to enumerate Linux process directory $procRoot",
                exception.cause ?: exception
            )
        } catch (exception: IOException) {
            FieldRead(null, LinuxProcessFieldStatus.FIELD_UNAVAILABLE)
        }

    private fun readBytes(path: Path, processDirectory: Path): FieldRead<ByteArray> =
        try {
            FieldRead(Files.readAllBytes(path), LinuxProcessFieldStatus.AVAILABLE)
        } catch (exception: NoSuchFileException) {
            FieldRead(null, missingPathStatus(processDirectory))
        } catch (exception: AccessDeniedException) {
            FieldRead(null, LinuxProcessFieldStatus.PERMISSION_DENIED)
        } catch (exception: SecurityException) {
            FieldRead(null, LinuxProcessFieldStatus.PERMISSION_DENIED)
        } catch (exception: IOException) {
            FieldRead(null, LinuxProcessFieldStatus.FIELD_UNAVAILABLE)
        }

    private fun readLink(path: Path, processDirectory: Path): FieldRead<String> =
        try {
            FieldRead(
                Files.readSymbolicLink(path).toString(),
                LinuxProcessFieldStatus.AVAILABLE
            )
        } catch (exception: NoSuchFileException) {
            FieldRead(null, missingPathStatus(processDirectory))
        } catch (exception: AccessDeniedException) {
            FieldRead(null, LinuxProcessFieldStatus.PERMISSION_DENIED)
        } catch (exception: SecurityException) {
            FieldRead(null, LinuxProcessFieldStatus.PERMISSION_DENIED)
        } catch (exception: IOException) {
            FieldRead(null, LinuxProcessFieldStatus.FIELD_UNAVAILABLE)
        }

    private fun missingPathStatus(processDirectory: Path): LinuxProcessFieldStatus =
        if (Files.exists(processDirectory)) {
            LinuxProcessFieldStatus.FIELD_UNAVAILABLE
        } else {
            LinuxProcessFieldStatus.PROCESS_EXITED_DURING_READ
        }

    private fun completeStatusMap(
        statuses: Map<LinuxProcessField, LinuxProcessFieldStatus>
    ): Map<LinuxProcessField, LinuxProcessFieldStatus> =
        LinuxProcessField.values().associateWith { field ->
            statuses[field] ?: LinuxProcessFieldStatus.FIELD_UNAVAILABLE
        }

    private fun exitedSnapshot(pid: Long): LinuxProcessSnapshot =
        LinuxProcessSnapshot(
            pid = pid,
            processStartTicks = null,
            uid = null,
            parentPid = null,
            processGroupId = null,
            sessionId = null,
            comm = null,
            executablePath = null,
            executableBasename = null,
            argv = emptyList(),
            workingDirectory = null,
            cgroupPath = null,
            ioState = LinuxProcessIoState.PROCESS_EXITED_DURING_READ,
            fieldStatuses = LinuxProcessField.values().associateWith {
                LinuxProcessFieldStatus.PROCESS_EXITED_DURING_READ
            }
        )

    private fun malformedSnapshot(pid: Long): LinuxProcessSnapshot =
        LinuxProcessSnapshot(
            pid = pid,
            processStartTicks = null,
            uid = null,
            parentPid = null,
            processGroupId = null,
            sessionId = null,
            comm = null,
            executablePath = null,
            executableBasename = null,
            argv = emptyList(),
            workingDirectory = null,
            cgroupPath = null,
            ioState = LinuxProcessIoState.PARTIAL,
            fieldStatuses = LinuxProcessField.values().associateWith {
                LinuxProcessFieldStatus.MALFORMED
            }
        )

    private fun executableBasename(rawPath: String): String? =
        runCatching { Path.of(rawPath).fileName?.toString() }.getOrNull()

    private fun parseUid(statusContents: String): Long? =
        statusContents.lineSequence()
            .firstOrNull { it.startsWith("Uid:") }
            ?.substringAfter(':')
            ?.trim()
            ?.split(WHITESPACE)
            ?.firstOrNull()
            ?.toLongOrNull()
            ?.takeIf { it >= 0L }

    private fun parseCgroupPath(contents: String): String? {
        val entries = contents.lineSequence().mapNotNull { line ->
            val fields = line.split(':', limit = 3)
            if (fields.size == 3) fields[0] to fields[2] else null
        }.toList()
        return entries.firstOrNull { it.first == "0" && it.second.isNotBlank() }?.second
            ?: entries.firstOrNull { it.second.isNotBlank() }?.second
    }

    private data class FieldRead<T>(
        val value: T?,
        val status: LinuxProcessFieldStatus
    )

    companion object {
        private val STAT_FIELDS = setOf(
            LinuxProcessField.PROCESS_START_TICKS,
            LinuxProcessField.PARENT_PID,
            LinuxProcessField.PROCESS_GROUP_ID,
            LinuxProcessField.SESSION_ID,
            LinuxProcessField.COMM
        )
        private val WHITESPACE = Regex("\\s+")

        /** Shared production repository. Tests inject an isolated proc root. */
        val system: LinuxProcessRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            LinuxProcessRepository()
        }

        /**
         * Parse procfs stat without splitting the parenthesized comm field.
         * `comm` may itself contain spaces and closing parentheses.
         */
        internal fun parseStat(expectedPid: Long, contents: String): ParsedLinuxProcStat? {
            val open = contents.indexOf('(')
            val close = contents.lastIndexOf(')')
            if (open <= 0 || close <= open) return null
            val parsedPid = contents.substring(0, open).trim().toLongOrNull()
                ?: return null
            if (parsedPid != expectedPid || parsedPid <= 0L) return null

            val afterComm = contents.substring(close + 1).trim()
            if (afterComm.isEmpty()) return null
            val fields = afterComm.split(WHITESPACE)
            if (fields.size <= START_TICKS_FIELD_INDEX) return null

            fun parseNonNegativeLong(index: Int): Long? =
                fields.getOrNull(index)
                    ?.toLongOrNull()
                    ?.takeIf { it >= 0L }

            val parentPid = parseNonNegativeLong(PARENT_PID_FIELD_INDEX)
            val processGroupId = parseNonNegativeLong(PROCESS_GROUP_FIELD_INDEX)
            val sessionId = parseNonNegativeLong(SESSION_ID_FIELD_INDEX)
            val processStartTicks = parseNonNegativeLong(START_TICKS_FIELD_INDEX)
            val statuses = mapOf(
                LinuxProcessField.COMM to LinuxProcessFieldStatus.AVAILABLE,
                LinuxProcessField.PARENT_PID to statusFor(parentPid),
                LinuxProcessField.PROCESS_GROUP_ID to statusFor(processGroupId),
                LinuxProcessField.SESSION_ID to statusFor(sessionId),
                LinuxProcessField.PROCESS_START_TICKS to statusFor(processStartTicks)
            )
            return ParsedLinuxProcStat(
                pid = parsedPid,
                comm = contents.substring(open + 1, close),
                processStartTicks = processStartTicks,
                parentPid = parentPid,
                processGroupId = processGroupId,
                sessionId = sessionId,
                fieldStatuses = statuses
            )
        }

        /** `/proc/<pid>/cmdline` is a NUL-delimited argv, not a shell string. */
        internal fun parseCommandLine(bytes: ByteArray): List<String> {
            if (bytes.isEmpty()) return emptyList()
            val tokens = String(bytes, StandardCharsets.UTF_8)
                .split('\u0000')
                .toMutableList()
            while (tokens.lastOrNull().isNullOrEmpty()) tokens.removeAt(tokens.lastIndex)
            return tokens
        }

        private fun statusFor(value: Long?): LinuxProcessFieldStatus =
            if (value == null) LinuxProcessFieldStatus.MALFORMED
            else LinuxProcessFieldStatus.AVAILABLE

        private const val PARENT_PID_FIELD_INDEX = 1
        private const val PROCESS_GROUP_FIELD_INDEX = 2
        private const val SESSION_ID_FIELD_INDEX = 3
        private const val START_TICKS_FIELD_INDEX = 19
    }
}

class LinuxProcessRepositoryException(
    message: String,
    cause: Throwable
) : IllegalStateException(message, cause)