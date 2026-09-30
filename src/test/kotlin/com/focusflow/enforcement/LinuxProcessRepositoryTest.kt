package com.focusflow.enforcement

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

interface LinuxPidfdLibC : Library {
    fun pidfd_open(pid: Int, flags: Int): Int
    fun close(fileDescriptor: Int): Int
}

class LinuxProcessRepositoryTest {

    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `proc stat parser handles whitespace and closing parentheses in comm`() {
        val parsed = LinuxProcessRepository.parseStat(
            expectedPid = 412,
            contents = statContents(
                pid = 412,
                comm = "worker ) pool (blue)",
                startTicks = 8912L
            )
        )

        assertNotNull(parsed)
        assertEquals("worker ) pool (blue)", parsed.comm)
        assertEquals(8912L, parsed.processStartTicks)
        assertEquals(111L, parsed.parentPid)
        assertEquals(222L, parsed.processGroupId)
        assertEquals(333L, parsed.sessionId)
    }

    @Test
    fun `cmdline parser preserves NUL separated argument boundaries`() {
        val argv = LinuxProcessRepository.parseCommandLine(
            "player\u0000--profile\u0000a profile with spaces\u0000--mode\u0000".toByteArray(
                StandardCharsets.UTF_8
            )
        )

        assertEquals(
            listOf("player", "--profile", "a profile with spaces", "--mode"),
            argv
        )
        assertEquals(
            emptyList(),
            LinuxProcessRepository.parseCommandLine(byteArrayOf())
        )
    }

    @Test
    fun `snapshot reads proc facts as one generation with stable instance identity`() {
        val procRoot = tempDirectory.resolve("proc")
        createProcessFixture(procRoot, pid = 412, startTicks = 8912L)
        val repository = LinuxProcessRepository(procRoot) { 1_700_000_000_000L }

        val first = repository.snapshot()
        val second = repository.snapshot()
        val process = first.processes.single()

        assertEquals(1L, first.generationId)
        assertEquals(2L, second.generationId)
        assertEquals(1_700_000_000_000L, first.observedAtEpochMs)
        assertEquals(LinuxProcessIoState.AVAILABLE, first.ioState)
        assertEquals(412L, process.pid)
        assertEquals(8912L, process.processStartTicks)
        assertEquals(1000L, process.uid)
        assertEquals(111L, process.parentPid)
        assertEquals(222L, process.processGroupId)
        assertEquals(333L, process.sessionId)
        assertEquals("worker ) pool (blue)", process.comm)
        assertEquals("/usr/bin/example-app", process.executablePath)
        assertEquals("example-app", process.executableBasename)
        assertEquals(
            listOf("example-app", "--config", "profile with spaces"),
            process.argv
        )
        assertEquals("/home/test-user", process.workingDirectory)
        assertEquals("/user.slice/test", process.cgroupPath)
        assertEquals(LinuxProcessIoState.AVAILABLE, process.ioState)
        assertTrue(process.fieldAvailability.containsAll(LinuxProcessField.values().toSet()))
        assertIs<ProcessInstanceIdentity.Known>(process.processInstanceIdentity)
    }

    @Test
    fun `unreadable or absent fields produce partial snapshots without dropping pid`() {
        val procRoot = tempDirectory.resolve("proc")
        createProcessFixture(
            procRoot = procRoot,
            pid = 527,
            startTicks = 7654L,
            includeCmdline = false,
            includeExecutableLink = false,
            includeWorkingDirectoryLink = false,
            includeCgroup = false
        )
        val generation = LinuxProcessRepository(procRoot).snapshot()
        val process = generation.processes.single()

        assertEquals(527L, process.pid)
        assertEquals(7654L, process.processStartTicks)
        assertEquals(LinuxProcessIoState.PARTIAL, process.ioState)
        assertEquals(LinuxProcessIoState.PARTIAL, generation.ioState)
        assertEquals(
            LinuxProcessFieldStatus.FIELD_UNAVAILABLE,
            process.fieldStatuses[LinuxProcessField.ARGV]
        )
        assertEquals(
            LinuxProcessFieldStatus.FIELD_UNAVAILABLE,
            process.fieldStatuses[LinuxProcessField.EXECUTABLE_PATH]
        )
        assertEquals(
            LinuxProcessFieldStatus.FIELD_UNAVAILABLE,
            process.fieldStatuses[LinuxProcessField.WORKING_DIRECTORY]
        )
        assertEquals(
            LinuxProcessFieldStatus.FIELD_UNAVAILABLE,
            process.fieldStatuses[LinuxProcessField.CGROUP_PATH]
        )
        assertIs<ProcessInstanceIdentity.Known>(process.processInstanceIdentity)
    }

    @Test
    fun `malformed stat remains observable but cannot become an identity key`() {
        val procRoot = tempDirectory.resolve("proc")
        val processDirectory = createProcessFixture(procRoot, pid = 528, startTicks = 7655L)
        Files.writeString(processDirectory.resolve("stat"), "malformed stat contents")

        val process = LinuxProcessRepository(procRoot).snapshot().processes.single()

        assertEquals(528L, process.pid)
        assertNull(process.processStartTicks)
        assertEquals(LinuxProcessIoState.PARTIAL, process.ioState)
        assertEquals(
            LinuxProcessFieldStatus.MALFORMED,
            process.fieldStatuses[LinuxProcessField.PROCESS_START_TICKS]
        )
        assertIs<ProcessInstanceIdentity.Unknown>(process.processInstanceIdentity)
    }

    @Test
    fun `process that disappears before observation is retained as exited and unknown`() {
        val repository = LinuxProcessRepository(tempDirectory.resolve("proc"))
        val observation = repository.readProcess(8301L)

        assertEquals(
            LinuxProcessIoState.PROCESS_EXITED_DURING_READ,
            observation.ioState
        )
        assertTrue(
            observation.fieldStatuses.values.all {
                it == LinuxProcessFieldStatus.PROCESS_EXITED_DURING_READ
            }
        )
        assertIs<ProcessInstanceIdentity.Unknown>(observation.processInstanceIdentity)
    }

    @Test
    fun `reused pid has a different process instance key`() {
        val first = sampleSnapshot(pid = 902, startTicks = 50L)
        val replacement = first.copy(processStartTicks = 51L)

        val firstIdentity = assertIs<ProcessInstanceIdentity.Known>(
            first.processInstanceIdentity
        )
        val replacementIdentity = assertIs<ProcessInstanceIdentity.Known>(
            replacement.processInstanceIdentity
        )

        assertNotEquals(firstIdentity.key, replacementIdentity.key)
        assertFalse(
            isSameKnownProcessInstance(
                first.processInstanceIdentity,
                replacement.processInstanceIdentity
            )
        )
        assertFalse(isSameKnownProcessObservation(first, replacement))
        assertEquals(first.fingerprint, replacement.fingerprint)
    }

    @Test
    fun `same process instance detects executable and argv changes in fingerprint`() {
        val before = sampleSnapshot(pid = 903, startTicks = 88L)
        val afterExec = before.copy(
            executablePath = "/opt/other/app",
            executableBasename = "app",
            argv = listOf("app", "--new-mode")
        )

        assertEquals(before.processInstanceIdentity, afterExec.processInstanceIdentity)
        assertTrue(
            isSameKnownProcessInstance(
                before.processInstanceIdentity,
                afterExec.processInstanceIdentity
            )
        )
        assertTrue(isSameKnownProcessObservation(before, before.copy()))
        assertFalse(isSameKnownProcessObservation(before, afterExec))
        assertNotEquals(before.fingerprint, afterExec.fingerprint)
        assertFalse(before.fingerprint.toString().contains("--password"))
    }

    @Test
    fun `live pidfd cannot promote missing start ticks into process identity`() {
        if (!isLinux) return

        val libc = runCatching {
            Native.load(Platform.C_LIBRARY_NAME, LinuxPidfdLibC::class.java)
        }.getOrNull()
        assumeTrue(libc != null, "libc pidfd bindings are unavailable")
        val nativeLibc = requireNotNull(libc)
        val pid = ProcessHandle.current().pid()
        val descriptor = try {
            nativeLibc.pidfd_open(pid.toInt(), 0)
        } catch (_: UnsatisfiedLinkError) {
            assumeTrue(false, "libc does not expose pidfd_open")
            return
        }
        assumeTrue(descriptor >= 0, "kernel does not support pidfd_open")

        try {
            val target = Files.readSymbolicLink(Path.of("/proc/self/fd/$descriptor"))
                .toString()
            assertTrue(target.contains("pidfd"), "Expected a live pidfd, got $target")

            val missingTicks = sampleSnapshot(pid = pid, startTicks = null)
            val identity = missingTicks.processInstanceIdentity
            assertIs<ProcessInstanceIdentity.Unknown>(identity)
            assertNull((identity as? ProcessInstanceIdentity.Known)?.key)
            assertFalse(isSameKnownProcessInstance(identity, identity))
            assertFalse(isSameKnownProcessObservation(missingTicks, missingTicks))
        } finally {
            nativeLibc.close(descriptor)
        }
    }

    private fun createProcessFixture(
        procRoot: Path,
        pid: Int,
        startTicks: Long,
        includeCmdline: Boolean = true,
        includeExecutableLink: Boolean = true,
        includeWorkingDirectoryLink: Boolean = true,
        includeCgroup: Boolean = true
    ): Path {
        val processDirectory = procRoot.resolve(pid.toString())
        Files.createDirectories(processDirectory)
        Files.writeString(
            processDirectory.resolve("stat"),
            statContents(pid.toLong(), "worker ) pool (blue)", startTicks)
        )
        Files.writeString(
            processDirectory.resolve("status"),
            "Name:\tworker\nUid:\t1000\t1000\t1000\t1000\n"
        )
        if (includeCmdline) {
            Files.write(
                processDirectory.resolve("cmdline"),
                "example-app\u0000--config\u0000profile with spaces\u0000"
                    .toByteArray(StandardCharsets.UTF_8)
            )
        }
        if (includeExecutableLink) {
            Files.createSymbolicLink(
                processDirectory.resolve("exe"),
                Path.of("/usr/bin/example-app")
            )
        }
        if (includeWorkingDirectoryLink) {
            Files.createSymbolicLink(
                processDirectory.resolve("cwd"),
                Path.of("/home/test-user")
            )
        }
        if (includeCgroup) {
            Files.writeString(
                processDirectory.resolve("cgroup"),
                "0::/user.slice/test\n"
            )
        }
        return processDirectory
    }

    private fun statContents(pid: Long, comm: String, startTicks: Long): String {
        val fieldsAfterComm =
            listOf("S", "111", "222", "333") +
                List(15) { "0" } +
                startTicks.toString()
        return "$pid ($comm) ${fieldsAfterComm.joinToString(" ")}"
    }

    private fun sampleSnapshot(pid: Long, startTicks: Long?): LinuxProcessSnapshot {
        val statuses = LinuxProcessField.values().associateWith { field ->
            if (
                startTicks == null &&
                field in setOf(
                    LinuxProcessField.PROCESS_START_TICKS,
                    LinuxProcessField.INSTANCE_STABILITY
                )
            ) {
                LinuxProcessFieldStatus.FIELD_UNAVAILABLE
            } else {
                LinuxProcessFieldStatus.AVAILABLE
            }
        }
        return LinuxProcessSnapshot(
            pid = pid,
            processStartTicks = startTicks,
            uid = 1000L,
            parentPid = 1L,
            processGroupId = 2L,
            sessionId = 3L,
            comm = "sample",
            executablePath = "/usr/bin/sample",
            executableBasename = "sample",
            argv = listOf("sample", "--password", "do-not-print"),
            workingDirectory = "/tmp",
            cgroupPath = "/user.slice/sample",
            ioState = if (startTicks == null) {
                LinuxProcessIoState.PARTIAL
            } else {
                LinuxProcessIoState.AVAILABLE
            },
            fieldStatuses = statuses
        )
    }
}