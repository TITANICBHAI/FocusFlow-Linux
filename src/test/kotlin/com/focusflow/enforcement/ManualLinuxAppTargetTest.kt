package com.focusflow.enforcement

import com.focusflow.data.models.AppReferenceSource
import com.focusflow.data.models.AppResolutionStatus
import com.focusflow.data.models.RuntimeAuthorizationPurpose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManualLinuxAppTargetTest {
    @Test
    fun `process name creates a compatibility name and explicit selector`() {
        val result = ManualLinuxAppTargetParser.parse(
            ManualLinuxTargetInput(
                type = ManualLinuxTargetType.PROCESS_NAME,
                value = "my-focus-tool"
            ),
            referenceId = "foc-process"
        )
        val target = requireNotNull(result.target)

        assertEquals("my-focus-tool", target.descriptor.processName)
        assertEquals(AppReferenceSource.MANUAL, target.reference.source)
        assertEquals(AppResolutionStatus.RESOLVED, target.reference.resolutionStatus)
        assertEquals(
            RuntimeSelector.ProcessName("my-focus-tool"),
            primaryPredicate(target.reference.runtimeDefinitions.single().selector)
        )
        assertEquals(
            RuntimeAuthorizationPurpose.PRIMARY_RUNTIME,
            target.reference.runtimeDefinitions.single().authorizationPurpose
        )
    }

    @Test
    fun `path desktop id and package id become typed exact selectors`() {
        val executable = parse(ManualLinuxTargetType.EXECUTABLE_PATH, "/opt/tool/bin/tool")
        assertEquals(
            RuntimeSelector.ExecutablePath("/opt/tool/bin/tool"),
            primaryPredicate(executable.reference.runtimeDefinitions.single().selector)
        )

        val desktop = parse(ManualLinuxTargetType.DESKTOP_ID, "org.example.Tool")
        assertEquals(
            RuntimeSelector.DesktopId("org.example.Tool"),
            primaryPredicate(desktop.reference.runtimeDefinitions.single().selector)
        )

        val packageTarget = parse(ManualLinuxTargetType.PACKAGE_ID, "org.example.Tool")
        assertEquals(
            RuntimeSelector.PackageId("org.example.Tool"),
            primaryPredicate(packageTarget.reference.runtimeDefinitions.single().selector)
        )
    }

    @Test
    fun `structured command uses separate executable and argument predicates`() {
        val result = ManualLinuxAppTargetParser.parse(
            ManualLinuxTargetInput(
                type = ManualLinuxTargetType.COMMAND_PREDICATE,
                executable = "java",
                argumentName = "--gameDir",
                argumentValue = "/home/user/game"
            ),
            referenceId = "foc-command"
        )
        val target = requireNotNull(result.target)
        val expression = target.reference.runtimeDefinitions.single().selector

        assertIs<SelectorExpression.All>(expression)
        assertEquals(2, expression.children.size)
        assertEquals(
            RuntimeSelector.ExecutableBasename("java"),
            primaryPredicate(expression.children[0])
        )
        val argument = primaryPredicate(expression.children[1])
        assertIs<RuntimeSelector.ArgumentEquals>(argument)
        assertEquals("--gameDir", argument.name)
        assertEquals("/home/user/game", argument.expectedValue)
        assertEquals(SelectorValueKind.PATH, argument.valueKind)
    }

    @Test
    fun `command text is not interpreted as a shell command`() {
        val result = ManualLinuxAppTargetParser.parse(
            ManualLinuxTargetInput(
                type = ManualLinuxTargetType.COMMAND_PREDICATE,
                executable = "java -jar app.jar"
            ),
            referenceId = "foc-shell-like"
        )

        assertNull(result.target)
        assertTrue(result.errorMessage.orEmpty().contains("shell command"))
    }

    @Test
    fun `invalid paths identifiers and protected process names are rejected`() {
        assertNull(parseResult(ManualLinuxTargetType.EXECUTABLE_PATH, "relative/tool").target)
        assertNull(parseResult(ManualLinuxTargetType.PACKAGE_ID, "org/example").target)
        assertNull(parseResult(ManualLinuxTargetType.PROCESS_NAME, "systemd").target)
    }

    private fun parse(type: ManualLinuxTargetType, value: String) =
        requireNotNull(parseResult(type, value).target)

    private fun parseResult(type: ManualLinuxTargetType, value: String) =
        ManualLinuxAppTargetParser.parse(
            ManualLinuxTargetInput(type = type, value = value),
            referenceId = "foc-test"
        )

    private fun primaryPredicate(expression: SelectorExpression): RuntimeSelector =
        assertIs<SelectorExpression.Predicate>(expression).selector
}