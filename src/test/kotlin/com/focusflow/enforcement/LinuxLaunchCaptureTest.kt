package com.focusflow.enforcement

import com.focusflow.data.models.AppResolutionStatus
import com.focusflow.data.models.AppReferenceSource
import com.focusflow.data.models.CanonicalAppReference
import com.focusflow.data.models.LaunchDefinition
import com.focusflow.data.models.RuntimeAuthorizationPurpose
import com.focusflow.data.models.RuntimeDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LinuxLaunchCaptureTest {
    @Test
    fun `baseline processes are isolated and candidate attribution is explicit`() {
        val baselineProcess = snapshot(10, 100, "editor", "/usr/bin/editor")
        val launcher = snapshot(20, 200, "game-launcher", "/usr/bin/game-launcher")
        val child = snapshot(
            21,
            210,
            "game",
            "/opt/game/bin/game",
            parentPid = launcher.pid
        )
        val tracker = tracker(listOf(baselineProcess))
        tracker.bindLauncher(launcher.pid, generation(1, baselineProcess, launcher), 1_010)

        val observed = tracker.observe(
            generation(2, baselineProcess, launcher, child),
            1_020
        )
        assertEquals(setOf(launcher.key(), child.key()), observed.candidateInstances)
        assertTrue(observed.associatedInstances.isEmpty())
        assertFalse(baselineProcess.key() in observed.candidateInstances)

        val attributed = tracker.observe(
            generation(3, baselineProcess, launcher, child),
            1_030
        ) { process, _ ->
            if (process.pid == child.pid) attribution() else null
        }
        assertEquals(setOf(child.key()), attributed.associatedInstances)
        assertEquals(child.key(), attributed.primaryRuntimeInstance)
    }

    @Test
    fun `Minecraft launch session attributes a later JVM and survives launcher reparenting`() {
        val unrelatedJava = snapshot(
            19,
            190,
            "java",
            "/usr/bin/java",
            processGroupId = 19,
            sessionId = 19,
            cgroupPath = "/user.slice/unrelated.scope",
            argv = listOf("/usr/bin/java", "-cp", "tools/*", "org.example.Worker")
        )
        val tracker = LinuxLaunchCaptureTracker(
            applicationReferenceId = "selected-minecraft",
            baseline = generation(1, unrelatedJava),
            startedAtMs = 1_000
        )
        tracker.setLaunchDefinition(
            LaunchDefinition(
                id = "minecraft-launch",
                referenceId = "selected-minecraft",
                type = "executable",
                executable = "/usr/bin/minecraft-launcher"
            )
        )
        val launcher = snapshot(
            20,
            200,
            "minecraft-launcher",
            "/usr/bin/minecraft-launcher",
            processGroupId = 20,
            sessionId = 20,
            cgroupPath = "/user.slice/minecraft-launch.scope"
        )
        tracker.bindLauncher(launcher.pid, generation(2, unrelatedJava, launcher), 1_010)

        fun minecraftAttribution(
            process: LinuxProcessSnapshot,
            evidence: LaunchSessionProcessEvidence
        ): LaunchCaptureAttribution? {
            val result = MinecraftRuntimeAttributor.attribute(
                process,
                ProcessRuntimeMetadata.from(process),
                MinecraftLaunchSessionEvidence(
                    selectedMinecraftReference = true,
                    launchDefinitionArmed = tracker.view().launchDefinition != null,
                    activeLaunchSession = evidence.active,
                    processCreatedOrExecChanged = evidence.processCreatedOrExecChanged,
                    relatedToLauncher = evidence.relatedToLauncher,
                    previouslyAssociated = evidence.previouslyAssociated,
                    unchangedLauncherImage = evidence.unchangedLauncherImage
                )
            )
            if (!result.isAttributed) return null
            return LaunchCaptureAttribution(
                applicationReferenceId = "selected-minecraft",
                runtimeDefinitionId = "minecraft-primary",
                role = RuntimeRole.PRIMARY,
                minecraftAttributionMode = result.mode,
                explanation = result.explanation(),
                evidence = result.evidence
            )
        }

        val launcherOnly = tracker.observe(
            generation(3, unrelatedJava, launcher),
            1_020,
            ::minecraftAttribution
        )
        assertTrue(launcherOnly.associatedInstances.isEmpty())
        assertFalse(unrelatedJava.key() in launcherOnly.candidateInstances)

        val minecraftRuntime = snapshot(
            21,
            210,
            "java",
            "/usr/lib/jvm/java/bin/java",
            parentPid = launcher.pid,
            processGroupId = 20,
            sessionId = 20,
            cgroupPath = "/user.slice/minecraft-launch.scope",
            argv = listOf(
                "/usr/lib/jvm/java/bin/java",
                "-jar",
                "/opt/launcher/runtime.jar",
                "--gameDir",
                "/home/user/.minecraft",
                "--assetsDir=/home/user/.minecraft/assets",
                "--version=1.21"
            )
        )
        val runtimeState = tracker.observe(
            generation(4, unrelatedJava, launcher, minecraftRuntime),
            1_030,
            ::minecraftAttribution
        )
        val associated = runtimeState.candidates.single {
            it.processInstanceKey == minecraftRuntime.key()
        }
        assertEquals(LaunchCandidateStatus.ASSOCIATED, associated.status)
        assertEquals(
            MinecraftAttributionMode.LAUNCH_SESSION,
            associated.attribution?.minecraftAttributionMode
        )
        assertTrue(runtimeState.message.orEmpty().contains("active selected launch session"))

        val reparentedRuntime = minecraftRuntime.copy(parentPid = 1)
        val afterLauncherExit = tracker.observe(
            generation(5, unrelatedJava, reparentedRuntime),
            1_040,
            ::minecraftAttribution
        )
        assertEquals(setOf(minecraftRuntime.key()), afterLauncherExit.associatedInstances)
        assertEquals(
            MinecraftAttributionMode.LAUNCH_SESSION,
            afterLauncherExit.candidates
                .single { it.processInstanceKey == minecraftRuntime.key() }
                .attribution
                ?.minecraftAttributionMode
        )
    }

    @Test
    fun `same process exec creates a changed candidate without a new pid`() {
        val tracker = tracker(emptyList())
        val launcher = snapshot(30, 300, "game-launcher", "/usr/bin/game-launcher")
        tracker.bindLauncher(launcher.pid, generation(1, launcher), 2_010)
        val runtimeImage = snapshot(
            30,
            300,
            "java",
            "/usr/lib/jvm/java/bin/java",
            argv = listOf("java", "net.minecraft.client.main.Main")
        )

        val state = tracker.observe(generation(2, runtimeImage), 2_020)

        assertEquals(1, state.candidates.count { it.processInstanceKey.pid == launcher.pid })
        assertEquals(runtimeImage.fingerprint, state.candidates.single().fingerprint)
        assertEquals(runtimeImage.key(), state.candidateInstances.single())
        assertTrue(state.associatedInstances.isEmpty())
    }

    @Test
    fun `launcher exit and child reparenting preserve a valid association`() {
        val tracker = tracker(emptyList())
        val launcher = snapshot(40, 400, "game-launcher", "/usr/bin/game-launcher")
        val child = snapshot(41, 410, "game", "/opt/game/bin/game", parentPid = launcher.pid)
        tracker.bindLauncher(launcher.pid, generation(1, launcher), 3_010)
        tracker.observe(generation(2, launcher, child), 3_020)
        tracker.observe(generation(3, launcher, child), 3_030) { process, _ ->
            if (process.pid == child.pid) attribution() else null
        }

        val reparented = child.copy(parentPid = 1)
        val state = tracker.observe(generation(4, reparented), 3_040) { process, _ ->
            if (process.pid == child.pid) attribution() else null
        }

        assertEquals(3_040, state.launcherExitedAtMs)
        assertEquals(LaunchCaptureStatus.RUNTIME_ASSOCIATED, state.status)
        assertEquals(setOf(child.key()), state.associatedInstances)
    }

    @Test
    fun `launcher exit without runtime attribution has a bounded grace period`() {
        val tracker = LinuxLaunchCaptureTracker(
            applicationReferenceId = "selected-app",
            baseline = generation(1),
            startedAtMs = 100,
            timeoutMs = 5_000,
            postExitGraceMs = 500
        )
        val launcher = snapshot(50, 500, "launcher", "/usr/bin/launcher")
        tracker.bindLauncher(launcher.pid, generation(2, launcher), 110)

        val grace = tracker.observe(generation(3), 200)
        val expired = tracker.observe(generation(4), 701)

        assertEquals(LaunchCaptureStatus.POST_EXIT_GRACE, grace.status)
        assertEquals(LaunchCaptureStatus.FAILED, expired.status)
        assertTrue(expired.associatedInstances.isEmpty())
    }

    @Test
    fun `cancellation and overall timeout terminate capture state`() {
        val cancelled = tracker(emptyList()).cancel()
        val timedOutTracker = LinuxLaunchCaptureTracker(
            applicationReferenceId = "selected-app",
            baseline = generation(1),
            startedAtMs = 100,
            timeoutMs = 10,
            postExitGraceMs = 500
        )

        val timedOut = timedOutTracker.observe(generation(2), 110)

        assertEquals(LaunchCaptureStatus.CANCELLED, cancelled.status)
        assertEquals(LaunchCaptureStatus.TIMED_OUT, timedOut.status)
    }

    @Test
    fun `runtime exit removes its live association`() {
        val tracker = tracker(emptyList())
        val launcher = snapshot(60, 600, "launcher", "/usr/bin/launcher")
        val runtime = snapshot(61, 610, "game", "/opt/game/bin/game", parentPid = launcher.pid)
        tracker.bindLauncher(launcher.pid, generation(1, launcher), 4_010)
        tracker.observe(generation(2, launcher, runtime), 4_020)
        tracker.observe(generation(3, launcher, runtime), 4_030) { process, _ ->
            if (process.pid == runtime.pid) attribution() else null
        }

        val state = tracker.observe(generation(4, launcher), 4_040)
        val exited = state.candidates.single { it.processInstanceKey == runtime.key() }

        assertTrue(state.associatedInstances.isEmpty())
        assertFalse(exited.isRunning)
        assertNull(state.primaryRuntimeInstance)
    }

    @Test
    fun `pid reuse never inherits the old association`() {
        val tracker = tracker(emptyList())
        val launcher = snapshot(70, 700, "launcher", "/usr/bin/launcher")
        val oldRuntime = snapshot(71, 710, "game", "/opt/game/bin/game", parentPid = launcher.pid)
        tracker.bindLauncher(launcher.pid, generation(1, launcher), 5_010)
        tracker.observe(generation(2, launcher, oldRuntime), 5_020)
        tracker.observe(generation(3, launcher, oldRuntime), 5_030) { process, _ ->
            if (process.pid == oldRuntime.pid) attribution() else null
        }
        val reusedPid = snapshot(71, 711, "other", "/usr/bin/other", parentPid = launcher.pid)

        val state = tracker.observe(generation(4, launcher, reusedPid), 5_040)

        assertFalse(oldRuntime.key() in state.associatedInstances)
        assertFalse(reusedPid.key() in state.associatedInstances)
        assertTrue(
            state.candidates.any {
                it.processInstanceKey == oldRuntime.key() && !it.isRunning
            }
        )
        assertTrue(
            state.candidates.any {
                it.processInstanceKey == reusedPid.key() &&
                    it.status == LaunchCandidateStatus.CANDIDATE
            }
        )
    }

    @Test
    fun `launcher close requires fresh matching identity and fingerprint`() {
        val tracker = tracker(emptyList())
        val launcher = snapshot(80, 800, "launcher", "/usr/bin/launcher")
        tracker.bindLauncher(launcher.pid, generation(1, launcher), 6_010)
        val runtime = snapshot(81, 810, "game", "/opt/game/bin/game")
        val changedImage = snapshot(80, 800, "game", "/opt/game/bin/game")
        val unknownIdentity = snapshot(80, null, "launcher", "/usr/bin/launcher")

        assertTrue(tracker.canCloseLauncher(launcher, runtime.key()))
        assertFalse(tracker.canCloseLauncher(launcher, launcher.key()))
        assertFalse(tracker.canCloseLauncher(changedImage, runtime.key()))
        assertFalse(tracker.canCloseLauncher(unknownIdentity, runtime.key()))
    }

    @Test
    fun `unknown process identity remains non-authoritative`() {
        val tracker = tracker(emptyList())
        val launcher = snapshot(90, 900, "launcher", "/usr/bin/launcher")
        val unknownChild = snapshot(
            91,
            null,
            "game",
            "/opt/game/bin/game",
            parentPid = launcher.pid
        )
        tracker.bindLauncher(launcher.pid, generation(1, launcher), 7_010)

        val state = tracker.observe(generation(2, launcher, unknownChild), 7_020)

        assertEquals(1, state.unknownIdentityObservationCount)
        assertTrue(state.associatedInstances.isEmpty())
        assertFalse(state.candidates.any { it.processInstanceKey.pid == unknownChild.pid })
    }

    @Test
    fun `desktop launch definition does not persist raw Exec command text`() {
        val app = AppDescriptor(
            processName = "game",
            displayName = "Game",
            isRunning = false,
            execCommand = "game --token private-value",
            desktopFilePath = "/usr/share/applications/game.desktop",
            desktopId = "game.desktop",
            source = AppSource.NATIVE_DESKTOP
        )

        val definition = LinuxLaunchDefinitionFactory.create(app, "selected-app")

        assertNotNull(definition)
        assertEquals(listOf("launch", "/usr/share/applications/game.desktop"), definition!!.argv)
        assertFalse(definition.argv.any { it.contains("private-value") })
        assertEquals(
            listOf("gio", "launch", "/usr/share/applications/game.desktop"),
            LinuxLaunchDefinitionFactory.command(definition)
        )
    }

    private fun tracker(baseline: List<LinuxProcessSnapshot>) =
        LinuxLaunchCaptureTracker(
            applicationReferenceId = "selected-app",
            baseline = generation(1, *baseline.toTypedArray()),
            startedAtMs = 1_000
        )

    private fun attribution() = LaunchCaptureAttribution(
        applicationReferenceId = "selected-app",
        runtimeDefinitionId = "selected-app:primary",
        role = RuntimeRole.PRIMARY
    )

    private fun generation(
        id: Long,
        vararg processes: LinuxProcessSnapshot
    ) = LinuxProcessSnapshotGeneration(
        generationId = id,
        observedAtEpochMs = id * 1_000,
        processes = processes.toList(),
        ioState = if (processes.all { it.ioState == LinuxProcessIoState.AVAILABLE }) {
            LinuxProcessIoState.AVAILABLE
        } else {
            LinuxProcessIoState.PARTIAL
        }
    )

    private fun snapshot(
        pid: Long,
        startTicks: Long?,
        comm: String,
        executablePath: String,
        parentPid: Long? = null,
        processGroupId: Long? = 1,
        sessionId: Long? = 1,
        cgroupPath: String? = "/user.slice",
        argv: List<String> = listOf(executablePath)
    ): LinuxProcessSnapshot {
        val fields = LinuxProcessField.values().associateWith {
            LinuxProcessFieldStatus.AVAILABLE
        }
        return LinuxProcessSnapshot(
            pid = pid,
            processStartTicks = startTicks,
            uid = 1000,
            parentPid = parentPid,
            processGroupId = processGroupId,
            sessionId = sessionId,
            comm = comm,
            executablePath = executablePath,
            executableBasename = executablePath.substringAfterLast('/'),
            argv = argv,
            workingDirectory = "/tmp",
            cgroupPath = cgroupPath,
            ioState = LinuxProcessIoState.AVAILABLE,
            fieldStatuses = fields
        )
    }

    private fun LinuxProcessSnapshot.key(): ProcessInstanceKey =
        (processInstanceIdentity as ProcessInstanceIdentity.Known).key
}