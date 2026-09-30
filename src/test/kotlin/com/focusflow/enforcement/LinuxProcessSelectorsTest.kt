package com.focusflow.enforcement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxProcessSelectorsTest {
    @Test
    fun `selector expression codec round trips nested typed selectors`() {
        val expression = SelectorExpression.All(
            listOf(
                SelectorExpression.Predicate(RuntimeSelector.ProcessName("editor")),
                SelectorExpression.Any(
                    listOf(
                        SelectorExpression.Predicate(
                            RuntimeSelector.ExecutablePath("/opt/Editor/bin/editor")
                        ),
                        SelectorExpression.Predicate(
                            RuntimeSelector.ArgumentEquals(
                                "--profile",
                                "Work Profile",
                                SelectorValueKind.TEXT
                            )
                        ),
                        SelectorExpression.Predicate(
                            RuntimeSelector.RuntimeFamilyIs(RuntimeFamily.JAVA)
                        ),
                        SelectorExpression.Predicate(
                            RuntimeSelector.ExecutionEnvironmentIs(
                                ExecutionEnvironment.FLATPAK
                            )
                        )
                    )
                ),
                SelectorExpression.Predicate(RuntimeSelector.DesktopId("org.example.Editor")),
                SelectorExpression.Predicate(RuntimeSelector.PackageId("org.example.Editor")),
                SelectorExpression.Predicate(RuntimeSelector.ArgumentExists("--safe")),
                SelectorExpression.Predicate(RuntimeSelector.MainClass("org.example.Main")),
                SelectorExpression.Predicate(
                    RuntimeSelector.WorkingDirectory("/home/user/Work")
                ),
                SelectorExpression.Predicate(
                    RuntimeSelector.ExecutableBasename("editor")
                )
            )
        )

        val encoded = SelectorExpressionCodec.encode(expression)

        assertTrue(encoded.startsWith("focusflow-selector-v1:"))
        assertEquals(expression, SelectorExpressionCodec.decode(encoded))
    }

    @Test
    fun `selector codec rejects unknown schema versions and malformed input`() {
        val encoded = SelectorExpressionCodec.encode(
            SelectorExpression.Predicate(RuntimeSelector.ProcessName("editor"))
        )

        assertFailsWith<IllegalArgumentException> {
            SelectorExpressionCodec.decode(encoded.replace("v1:", "v2:"))
        }
        assertFailsWith<IllegalArgumentException> {
            SelectorExpressionCodec.decode("not-a-selector-expression")
        }
    }

    @Test
    fun `empty all and any have explicit semantics but enabled definitions reject them`() {
        val process = sampleProcess()
        val context = ProcessSelectorContext(process)

        assertEquals(
            SelectorMatchStatus.MATCH,
            LinuxProcessSelectorMatcher.evaluate(
                SelectorExpression.All(emptyList()),
                context
            ).status
        )
        assertEquals(
            SelectorMatchStatus.NO_MATCH,
            LinuxProcessSelectorMatcher.evaluate(
                SelectorExpression.Any(emptyList()),
                context
            ).status
        )
        assertTrue(
            RuntimeSelectorDefinition(SelectorExpression.All(emptyList()))
                .validationErrors().isNotEmpty()
        )
        assertTrue(
            RuntimeSelectorDefinition(
                SelectorExpression.Any(emptyList()),
                enabled = false
            ).validationErrors().isEmpty()
        )
        assertTrue(
            RuntimeSelectorDefinition(
                SelectorExpression.All(
                    listOf(SelectorExpression.Any(emptyList()))
                )
            ).validationErrors().isNotEmpty()
        )
    }

    @Test
    fun `selector codec preserves empty expression nodes without conflating them`() {
        val expression = SelectorExpression.All(
            listOf(
                SelectorExpression.All(emptyList()),
                SelectorExpression.Any(emptyList())
            )
        )

        assertEquals(
            expression,
            SelectorExpressionCodec.decode(SelectorExpressionCodec.encode(expression))
        )
        assertFalse(
            RuntimeSelectorDefinition(expression).validationErrors().isEmpty()
        )
    }

    private fun sampleProcess(): LinuxProcessSnapshot {
        val statuses = LinuxProcessField.values().associateWith {
            LinuxProcessFieldStatus.AVAILABLE
        }
        return LinuxProcessSnapshot(
            pid = 101L,
            processStartTicks = 200L,
            uid = 1000L,
            parentPid = 1L,
            processGroupId = 101L,
            sessionId = 101L,
            comm = "editor",
            executablePath = "/usr/bin/editor",
            executableBasename = "editor",
            argv = listOf("/usr/bin/editor"),
            workingDirectory = "/home/user",
            cgroupPath = "/user.slice",
            ioState = LinuxProcessIoState.AVAILABLE,
            fieldStatuses = statuses
        )
    }
}