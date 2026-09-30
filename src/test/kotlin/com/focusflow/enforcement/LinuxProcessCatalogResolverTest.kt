package com.focusflow.enforcement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinuxProcessCatalogResolverTest {
    @Test
    fun `exact executable path is an exact catalog observation`() {
        val app = app(
            processName = "editor",
            desktopId = "org.example.Editor",
            exePath = "/opt/editor/bin/editor"
        )

        val result = LinuxProcessCatalogResolver.resolve(
            snapshot(executablePath = "/opt/editor/bin/editor", comm = "editor"),
            listOf(app)
        )

        assertEquals(CatalogMatchStatus.EXACT, result.status)
        assertEquals(app.desktopId, result.candidates.single().desktopId)
        assertEquals(
            ProcessCorrelationStatus.VERIFIED,
            result.asSelectorCorrelation().status
        )
    }

    @Test
    fun `process name evidence remains possible rather than exact`() {
        val app = app(processName = "editor", desktopId = "org.example.Editor")

        val result = LinuxProcessCatalogResolver.resolve(
            snapshot(
                executablePath = "/tmp/unrelated",
                comm = "editor"
            ),
            listOf(app)
        )

        assertEquals(CatalogMatchStatus.POSSIBLE, result.status)
        assertEquals(CatalogEvidenceKind.PROCESS_NAME, result.candidates.single().evidence.single().kind)
        assertEquals(
            ProcessCorrelationStatus.CANDIDATE,
            result.asSelectorCorrelation().status
        )
    }

    @Test
    fun `equally strong matches preserve ambiguity`() {
        val first = app(
            processName = "editor",
            desktopId = "org.example.First",
            exePath = "/opt/shared/editor"
        )
        val second = app(
            processName = "editor",
            desktopId = "org.example.Second",
            exePath = "/opt/shared/editor"
        )

        val result = LinuxProcessCatalogResolver.resolve(
            snapshot(executablePath = "/opt/shared/editor", comm = "editor"),
            listOf(first, second)
        )

        assertEquals(CatalogMatchStatus.AMBIGUOUS, result.status)
        assertEquals(2, result.candidates.size)
        assertEquals(
            ProcessCorrelationStatus.AMBIGUOUS,
            result.asSelectorCorrelation().status
        )
    }

    @Test
    fun `recognized flatpak run invocation matches package metadata`() {
        val app = app(
            processName = "chat-client",
            desktopId = "com.example.Chat",
            packageId = "com.example.Chat"
        )

        val result = LinuxProcessCatalogResolver.resolve(
            snapshot(
                executablePath = "/usr/bin/flatpak",
                comm = "flatpak",
                argv = listOf("/usr/bin/flatpak", "run", "com.example.Chat")
            ),
            listOf(app)
        )

        assertEquals(CatalogMatchStatus.EXACT, result.status)
        assertTrue(
            result.candidates.single().evidence.any {
                it.kind == CatalogEvidenceKind.VERIFIED_PACKAGE_INVOCATION
            }
        )
    }

    @Test
    fun `unavailable executable and command fields are not used as evidence`() {
        val app = app(
            processName = "editor",
            desktopId = "org.example.Editor",
            exePath = "/opt/editor/editor"
        )
        val process = snapshot(
            executablePath = "/opt/editor/editor",
            comm = "editor",
            argv = listOf("/opt/editor/editor"),
            availableFields = setOf(LinuxProcessField.UID)
        )

        val result = LinuxProcessCatalogResolver.resolve(process, listOf(app))

        assertEquals(CatalogMatchStatus.NONE, result.status)
        assertTrue(result.candidates.isEmpty())
    }

    private fun app(
        processName: String,
        desktopId: String,
        exePath: String? = null,
        packageId: String? = null
) = AppDescriptor(
        processName = processName,
        displayName = desktopId.substringAfterLast('.'),
        isRunning = false,
        exePath = exePath,
        desktopId = desktopId,
        packageId = packageId,
        source = AppSource.NATIVE_DESKTOP
    )

    private fun snapshot(
        executablePath: String?,
        comm: String?,
        argv: List<String> = listOfNotNull(executablePath),
        availableFields: Set<LinuxProcessField> = LinuxProcessField.entries.toSet()
    ): LinuxProcessSnapshot {
        val statuses = LinuxProcessField.entries.associateWith { field ->
            if (field in availableFields) {
                LinuxProcessFieldStatus.AVAILABLE
            } else {
                LinuxProcessFieldStatus.FIELD_UNAVAILABLE
            }
        }
        return LinuxProcessSnapshot(
            pid = 151L,
            processStartTicks = 551L,
            uid = 1000L,
            parentPid = 1L,
            processGroupId = 151L,
            sessionId = 151L,
            comm = comm,
            executablePath = executablePath,
            executableBasename = executablePath?.substringAfterLast('/'),
            argv = argv,
            workingDirectory = "/home/user",
            cgroupPath = "/user.slice",
            ioState = LinuxProcessIoState.AVAILABLE,
            fieldStatuses = statuses
        )
    }
}