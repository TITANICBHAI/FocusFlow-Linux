package com.focusflow.enforcement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxProcessCompatibilityAdapterTest {
    @Test
    fun `legacy names use canonical process and executable selectors with known aliases`() {
        val process = snapshot(comm = "Discord", executableBasename = "discord")

        val result = LinuxProcessCompatibilityAdapter.evaluate(
            process,
            listOf("discord.exe")
        )

        assertEquals(LinuxLegacyTargetStatus.MATCH, result.status)
        assertEquals(SelectorMatchStatus.MATCH, result.selectorStatus)
        assertEquals(setOf("discord"), result.matchedTargetNames)
        assertTrue(result.mayEnforce)
    }

    @Test
    fun `unknown exe suffix is preserved instead of being treated as a Linux alias`() {
        val process = snapshot(comm = "legacy-tool", executableBasename = "legacy-tool")

        val result = LinuxProcessCompatibilityAdapter.evaluate(
            process,
            listOf("legacy-tool.exe")
        )

        assertEquals(LinuxLegacyTargetStatus.NO_MATCH, result.status)
        assertEquals(SelectorMatchStatus.NO_MATCH, result.selectorStatus)
    }

    @Test
    fun `missing selector observations remain unknown`() {
        val process = snapshot(
            comm = null,
            executableBasename = null,
            fieldStatuses = emptyMap()
        )

        val result = LinuxProcessCompatibilityAdapter.evaluate(process, listOf("editor"))

        assertEquals(LinuxLegacyTargetStatus.UNKNOWN, result.status)
        assertEquals(SelectorMatchStatus.INSUFFICIENT_DATA, result.selectorStatus)
        assertFalse(result.mayEnforce)
    }

    @Test
    fun `name match with missing start ticks is not destructive eligible`() {
        val process = snapshot(
            comm = "editor",
            executableBasename = "editor",
            startTicks = null
        )

        val result = LinuxProcessCompatibilityAdapter.evaluate(process, listOf("editor"))

        assertEquals(LinuxLegacyTargetStatus.UNKNOWN, result.status)
        assertEquals(SelectorMatchStatus.MATCH, result.selectorStatus)
        assertTrue(result.matchedTargetNames.contains("editor"))
        assertFalse(result.processIdentityKnown)
        assertFalse(result.mayEnforce)
    }

    @Test
    fun `revalidation rejects pid reuse and same instance exec changes`() {
        val expected = snapshot(
            pid = 413,
            startTicks = 9100L,
            comm = "editor",
            executableBasename = "editor"
        )
        val reusedPid = snapshot(
            pid = 413,
            startTicks = 9101L,
            comm = "editor",
            executableBasename = "editor"
        )
        val execChanged = snapshot(
            pid = 413,
            startTicks = 9100L,
            comm = "editor",
            executableBasename = "editor",
            argv = listOf("editor", "--new-image")
        )

        assertFalse(
            LinuxProcessCompatibilityAdapter.revalidateMatch(
                expected,
                reusedPid,
                listOf("editor")
            )
        )
        assertFalse(
            LinuxProcessCompatibilityAdapter.revalidateMatch(
                expected,
                execChanged,
                listOf("editor")
            )
        )
        assertTrue(
            LinuxProcessCompatibilityAdapter.revalidateMatch(
                expected,
                expected.copy(),
                listOf("editor")
            )
        )
    }

    @Test
    fun `authorization diagnostics explain outcome without exposing argv`() {
        val process = snapshot(
            comm = "java",
            executableBasename = "java",
            argv = listOf("java", "--token", "secret-value")
        )
        val decision = ProcessAuthorizationDecision(
            outcome = AuthorizationOutcome.UNKNOWN,
            reason = AuthorizationReason.AMBIGUOUS_ATTRIBUTION,
            attribution = ProcessAttributionResult(
                status = ProcessAttributionStatus.AMBIGUOUS
            )
        )

        val message = LinuxProcessDecisionDiagnostics.authorizationMessage(decision, process)

        assertTrue(message.contains("outcome=UNKNOWN"))
        assertTrue(message.contains("reason=AMBIGUOUS_ATTRIBUTION"))
        assertTrue(message.contains("attribution=AMBIGUOUS"))
        assertFalse(message.contains("--token"))
        assertFalse(message.contains("secret-value"))
    }

    @Test
    fun `diagnostics distinguish allow safe denial and unknown outcomes`() {
        val process = snapshot(comm = "editor", executableBasename = "editor")
        val attribution = ProcessAttributionResult(
            status = ProcessAttributionStatus.NOT_MATCHED
        )
        val decisions = listOf(
            ProcessAuthorizationDecision(
                AuthorizationOutcome.ALLOW,
                AuthorizationReason.SELECTED_APPLICATION_MATCH,
                attribution = attribution
            ),
            ProcessAuthorizationDecision(
                AuthorizationOutcome.DENY_AND_SAFE_TO_TERMINATE,
                AuthorizationReason.NO_SELECTED_APPLICATION_MATCH,
                process.processInstanceIdentity.let {
                    (it as ProcessInstanceIdentity.Known).key
                },
                attribution
            ),
            ProcessAuthorizationDecision(
                AuthorizationOutcome.UNKNOWN,
                AuthorizationReason.AMBIGUOUS_ATTRIBUTION,
                attribution = attribution.copy(status = ProcessAttributionStatus.AMBIGUOUS)
            )
        )

        val messages = decisions.map {
            LinuxProcessDecisionDiagnostics.authorizationMessage(it, process)
        }

        assertTrue(messages[0].contains("outcome=ALLOW"))
        assertTrue(messages[1].contains("outcome=DENY_AND_SAFE_TO_TERMINATE"))
        assertTrue(messages[2].contains("outcome=UNKNOWN"))
        assertTrue(messages.all { !it.contains("secret-value") })
    }

    private fun snapshot(
        pid: Long = 412L,
        startTicks: Long? = 8900L,
        comm: String?,
        executableBasename: String?,
        argv: List<String> = listOfNotNull(comm),
        fieldStatuses: Map<LinuxProcessField, LinuxProcessFieldStatus> = mapOf(
            LinuxProcessField.COMM to LinuxProcessFieldStatus.AVAILABLE,
            LinuxProcessField.EXECUTABLE_PATH to LinuxProcessFieldStatus.AVAILABLE,
            LinuxProcessField.ARGV to LinuxProcessFieldStatus.AVAILABLE
        )
    ) = LinuxProcessSnapshot(
        pid = pid,
        processStartTicks = startTicks,
        uid = 1000L,
        parentPid = 1L,
        processGroupId = pid,
        sessionId = pid,
        comm = comm,
        executablePath = executableBasename?.let { "/usr/bin/$it" },
        executableBasename = executableBasename,
        argv = argv,
        workingDirectory = "/home/test",
        cgroupPath = null,
        ioState = LinuxProcessIoState.AVAILABLE,
        fieldStatuses = fieldStatuses
    )
}