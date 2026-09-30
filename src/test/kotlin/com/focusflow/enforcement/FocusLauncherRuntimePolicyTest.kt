package com.focusflow.enforcement

import com.focusflow.data.models.AppReferenceSource
import com.focusflow.data.models.AppResolutionStatus
import com.focusflow.data.models.CanonicalAppReference
import com.focusflow.data.models.RuntimeAuthorizationPurpose
import com.focusflow.data.models.RuntimeDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FocusLauncherRuntimePolicyTest {
    @Test
    fun `only selected resolved references and authorized runtime definitions become candidates`() {
        val selected = reference(
            "selected-editor",
            SelectorExpression.Predicate(RuntimeSelector.ExecutablePath("/usr/bin/editor"))
        )
        val stale = reference(
            "stale-app",
            SelectorExpression.Predicate(RuntimeSelector.ProcessName("stale")),
            resolutionStatus = AppResolutionStatus.STALE
        )
        val conflicting = reference(
            "conflicting-app",
            SelectorExpression.Predicate(RuntimeSelector.ProcessName("conflicting")),
            conflictStatus = "ambiguous"
        )
        val handoffOnly = reference(
            "handoff-app",
            SelectorExpression.Predicate(RuntimeSelector.ProcessName("launcher")),
            purpose = RuntimeAuthorizationPurpose.LAUNCH_HANDOFF_ONLY
        )

        val candidates = FocusLauncherRuntimePolicy.candidates(
            listOf(selected, stale, conflicting, handoffOnly)
        )

        assertEquals(listOf("selected-editor"), candidates.map { it.applicationReferenceId })
        assertEquals("selected-editor:runtime", candidates.single().runtimeDefinitionId)
    }

    @Test
    fun `known selected process associates while unselected and generic runtime do not`() {
        val selected = reference(
            "selected-editor",
            SelectorExpression.Predicate(RuntimeSelector.ExecutablePath("/usr/bin/editor"))
        )
        val candidates = FocusLauncherRuntimePolicy.candidates(listOf(selected))
        val editor = snapshot(101L, 1001L, "editor", "/usr/bin/editor")
        val editorDecision = LinuxProcessAuthorizer.decide(
            ProcessSelectorContext(editor),
            candidates
        )

        val association = FocusLauncherRuntimePolicy.association(editorDecision, editor)
        assertEquals("selected-editor", association?.applicationReferenceId)
        assertEquals(editor.processInstanceIdentity.let { (it as ProcessInstanceIdentity.Known).key },
            association?.processInstanceKey)

        val unselected = snapshot(102L, 1002L, "terminal", "/usr/bin/terminal")
        val unselectedDecision = LinuxProcessAuthorizer.decide(
            ProcessSelectorContext(unselected),
            candidates
        )
        assertEquals(
            AuthorizationOutcome.DENY_AND_SAFE_TO_TERMINATE,
            unselectedDecision.outcome
        )
        assertNull(FocusLauncherRuntimePolicy.association(unselectedDecision, unselected))

        val genericJava = reference(
            "generic-java",
            SelectorExpression.Predicate(RuntimeSelector.ProcessName("java"))
        )
        val javaProcess = snapshot(103L, 1003L, "java", "/usr/bin/java")
        val genericDecision = LinuxProcessAuthorizer.decide(
            ProcessSelectorContext(javaProcess),
            FocusLauncherRuntimePolicy.candidates(listOf(genericJava))
        )
        assertEquals(AuthorizationOutcome.UNKNOWN, genericDecision.outcome)
        assertNull(FocusLauncherRuntimePolicy.association(genericDecision, javaProcess))
    }

    @Test
    fun `ambiguous stale and unknown process observations never enter session authorization`() {
        val first = reference(
            "editor-one",
            SelectorExpression.Predicate(RuntimeSelector.ExecutablePath("/usr/bin/editor"))
        )
        val second = reference(
            "editor-two",
            SelectorExpression.Predicate(RuntimeSelector.ExecutablePath("/usr/bin/editor"))
        )
        val editor = snapshot(201L, 2001L, "editor", "/usr/bin/editor")
        val ambiguousCandidates = FocusLauncherRuntimePolicy.candidates(listOf(first, second))
        val ambiguousDecision = LinuxProcessAuthorizer.decide(
            ProcessSelectorContext(editor),
            ambiguousCandidates
        )
        assertEquals(AuthorizationOutcome.UNKNOWN, ambiguousDecision.outcome)
        assertNull(FocusLauncherRuntimePolicy.association(ambiguousDecision, editor))

        val stale = reference(
            "stale-editor",
            SelectorExpression.Predicate(RuntimeSelector.ExecutablePath("/usr/bin/editor")),
            resolutionStatus = AppResolutionStatus.STALE
        )
        val staleCandidates = FocusLauncherRuntimePolicy.candidates(listOf(stale))
        assertTrue(staleCandidates.isEmpty())
        val staleDecision = LinuxProcessAuthorizer.decide(
            ProcessSelectorContext(editor),
            staleCandidates
        )
        assertEquals(AuthorizationOutcome.UNKNOWN, staleDecision.outcome)
        assertNull(FocusLauncherRuntimePolicy.association(staleDecision, editor))

        val knownCandidate = FocusLauncherRuntimePolicy.candidates(listOf(first))
        val unknownProcess = snapshot(202L, null, "editor", "/usr/bin/editor")
        val unknownDecision = LinuxProcessAuthorizer.decide(
            ProcessSelectorContext(unknownProcess),
            knownCandidate
        )
        assertEquals(AuthorizationOutcome.UNKNOWN, unknownDecision.outcome)
        assertNull(FocusLauncherRuntimePolicy.association(unknownDecision, unknownProcess))
    }

    @Test
    fun `termination revalidation rejects changed instance and changed observation`() {
        val candidate = ApplicationRuntimeCandidate(
            applicationReferenceId = "selected-editor",
            runtimeDefinitionId = "selected-editor:runtime",
            expression = SelectorExpression.Predicate(
                RuntimeSelector.ExecutablePath("/usr/bin/editor")
            )
        )
        val candidates = listOf(candidate)
        val observed = snapshot(301L, 3001L, "terminal", "/usr/bin/terminal")
        val decision = LinuxProcessAuthorizer.decide(
            ProcessSelectorContext(observed),
            candidates
        )
        assertEquals(AuthorizationOutcome.DENY_AND_SAFE_TO_TERMINATE, decision.outcome)
        assertTrue(
            FocusLauncherRuntimePolicy.mayTerminate(
                decision,
                observed,
                observed,
                candidates
            )
        )

        val changedInstance = snapshot(301L, 3002L, "terminal", "/usr/bin/terminal")
        assertFalse(
            FocusLauncherRuntimePolicy.mayTerminate(
                decision,
                observed,
                changedInstance,
                candidates
            )
        )
        val changedImage = snapshot(301L, 3001L, "other", "/usr/bin/other")
        assertFalse(
            FocusLauncherRuntimePolicy.mayTerminate(
                decision,
                observed,
                changedImage,
                candidates
            )
        )
        val unknownNow = snapshot(301L, null, "terminal", "/usr/bin/terminal")
        assertFalse(
            FocusLauncherRuntimePolicy.mayTerminate(
                decision,
                observed,
                unknownNow,
                candidates
            )
        )
    }

    @Test
    fun `authorization teardown clears Linux associations and preserves the Windows adapter`() {
        val candidate = ApplicationRuntimeCandidate(
            applicationReferenceId = "selected-editor",
            runtimeDefinitionId = "selected-editor:runtime",
            expression = SelectorExpression.Predicate(
                RuntimeSelector.ExecutablePath("/usr/bin/editor")
            )
        )
        val process = snapshot(401L, 4001L, "editor", "/usr/bin/editor")
        val key = (process.processInstanceIdentity as ProcessInstanceIdentity.Known).key
        val association = LauncherSessionProcessAssociation(
            processInstanceKey = key,
            applicationReferenceId = "selected-editor",
            runtimeDefinitionId = "selected-editor:runtime",
            role = RuntimeRole.PRIMARY,
            observationFingerprint = process.fingerprint
        )

        try {
            ProcessMonitor.installLinuxLauncherAuthorization(listOf(candidate))
            ProcessMonitor.launcherAuthorizedInstances = mapOf(key to association)
            ProcessMonitor.clearLauncherAuthorization()
            assertTrue(ProcessMonitor.launcherRuntimeCandidates.isEmpty())
            assertTrue(ProcessMonitor.launcherAllowedProcesses.isEmpty())
            assertTrue(ProcessMonitor.launcherAuthorizedInstances.isEmpty())

            ProcessMonitor.installLegacyLauncherAuthorization(setOf("editor.exe"))
            assertEquals(setOf("editor.exe"), ProcessMonitor.launcherAllowedProcesses)
            assertTrue(ProcessMonitor.launcherRuntimeCandidates.isEmpty())
            assertTrue(ProcessMonitor.launcherAuthorizedInstances.isEmpty())
        } finally {
            ProcessMonitor.clearLauncherAuthorization()
        }
    }

    private fun reference(
        id: String,
        selector: SelectorExpression,
        resolutionStatus: AppResolutionStatus = AppResolutionStatus.RESOLVED,
        conflictStatus: String = "none",
        purpose: RuntimeAuthorizationPurpose =
            RuntimeAuthorizationPurpose.PRIMARY_RUNTIME
    ) = CanonicalAppReference(
        referenceId = id,
        stableAppId = id,
        displayName = id,
        source = AppReferenceSource.CATALOG_NATIVE,
        resolutionStatus = resolutionStatus,
        conflictStatus = conflictStatus,
        runtimeDefinitions = listOf(
            RuntimeDefinition(
                id = "$id:runtime",
                referenceId = id,
                role = RuntimeRole.PRIMARY,
                selector = selector,
                executionEnvironment = ExecutionEnvironment.NATIVE,
                runtimeFamily = null,
                authorizationPurpose = purpose
            )
        )
    )

    private fun snapshot(
        pid: Long,
        startTicks: Long?,
        comm: String,
        executablePath: String
    ) = LinuxProcessSnapshot(
        pid = pid,
        processStartTicks = startTicks,
        uid = 1000L,
        parentPid = 1L,
        processGroupId = pid,
        sessionId = pid,
        comm = comm,
        executablePath = executablePath,
        executableBasename = executablePath.substringAfterLast('/'),
        argv = listOf(executablePath),
        workingDirectory = "/home/test",
        cgroupPath = "/user.slice",
        ioState = LinuxProcessIoState.AVAILABLE,
        fieldStatuses = mapOf(
            LinuxProcessField.PROCESS_START_TICKS to if (startTicks == null) {
                LinuxProcessFieldStatus.FIELD_UNAVAILABLE
            } else {
                LinuxProcessFieldStatus.AVAILABLE
            },
            LinuxProcessField.COMM to LinuxProcessFieldStatus.AVAILABLE,
            LinuxProcessField.EXECUTABLE_PATH to LinuxProcessFieldStatus.AVAILABLE,
            LinuxProcessField.ARGV to LinuxProcessFieldStatus.AVAILABLE
        )
    )
}