package com.focusflow.enforcement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxProcessMatcherTest {
    @Test
    fun `all any nested semantics and diagnostic evaluation are explicit`() {
        val context = context(
            snapshot(),
            correlation = ProcessApplicationCorrelation(
                packageId = "org.example.Editor",
                status = ProcessCorrelationStatus.VERIFIED
            )
        )
        val expression = SelectorExpression.All(
            listOf(
                predicate(RuntimeSelector.ProcessName("JAVA")),
                SelectorExpression.Any(
                    listOf(
                        predicate(RuntimeSelector.ExecutablePath("/wrong/path")),
                        predicate(RuntimeSelector.PackageId("org.example.Editor"))
                    )
                )
            )
        )

        assertEquals(
            SelectorMatchStatus.MATCH,
            LinuxProcessSelectorMatcher.evaluate(expression, context).status
        )
        assertEquals(
            SelectorMatchStatus.MATCH,
            LinuxProcessSelectorMatcher.evaluate(SelectorExpression.All(emptyList()), context)
                .status
        )
        assertEquals(
            SelectorMatchStatus.NO_MATCH,
            LinuxProcessSelectorMatcher.evaluate(SelectorExpression.Any(emptyList()), context)
                .status
        )
    }

    @Test
    fun `matcher short circuits by default and can collect all diagnostic evidence`() {
        val process = snapshot(argvAvailable = false)
        val context = context(process)
        val all = SelectorExpression.All(
            listOf(
                predicate(RuntimeSelector.ProcessName("not-java")),
                predicate(RuntimeSelector.ArgumentExists("--secret"))
            )
        )
        val shortCircuit = LinuxProcessSelectorMatcher.evaluate(all, context)
        val diagnostic = LinuxProcessSelectorMatcher.evaluate(
            all,
            context,
            includeAllEvidence = true
        )

        assertEquals(SelectorMatchStatus.NO_MATCH, shortCircuit.status)
        assertTrue(shortCircuit.unresolvedSelectors.isEmpty())
        assertEquals(SelectorMatchStatus.NO_MATCH, diagnostic.status)
        assertEquals(
            listOf(RuntimeSelectorType.ARGUMENT_EXISTS),
            diagnostic.unresolvedSelectors
        )

        val any = SelectorExpression.Any(
            listOf(
                predicate(RuntimeSelector.ProcessName("java")),
                predicate(RuntimeSelector.ArgumentExists("--secret"))
            )
        )
        assertEquals(
            SelectorMatchStatus.MATCH,
            LinuxProcessSelectorMatcher.evaluate(any, context).status
        )
        assertEquals(
            listOf(RuntimeSelectorType.ARGUMENT_EXISTS),
            LinuxProcessSelectorMatcher.evaluate(
                any,
                context,
                includeAllEvidence = true
            ).unresolvedSelectors
        )
    }

    @Test
    fun `typed selectors enforce case rules and verified catalog correlation`() {
        val process = snapshot(
            comm = "Java",
            executablePath = "/usr/bin/Java"
        )
        val verified = context(
            process,
            correlation = ProcessApplicationCorrelation(
                desktopId = "org.example.Editor",
                packageId = "org.example.Editor",
                status = ProcessCorrelationStatus.VERIFIED
            )
        )

        assertEquals(
            SelectorMatchStatus.MATCH,
            evaluate(RuntimeSelector.ProcessName("java"), verified)
        )
        assertEquals(
            SelectorMatchStatus.MATCH,
            evaluate(RuntimeSelector.ExecutableBasename("java"), verified)
        )
        assertEquals(
            SelectorMatchStatus.MATCH,
            evaluate(RuntimeSelector.ExecutablePath("/usr/bin/Java"), verified)
        )
        assertEquals(
            SelectorMatchStatus.NO_MATCH,
            evaluate(RuntimeSelector.ExecutablePath("/usr/bin/java"), verified)
        )
        assertEquals(
            SelectorMatchStatus.MATCH,
            evaluate(RuntimeSelector.DesktopId("org.example.Editor"), verified)
        )
        assertEquals(
            SelectorMatchStatus.MATCH,
            evaluate(RuntimeSelector.PackageId("org.example.Editor"), verified)
        )

        val ambiguous = verified.copy(
            applicationCorrelation = verified.applicationCorrelation.copy(
                status = ProcessCorrelationStatus.AMBIGUOUS
            )
        )
        assertEquals(
            SelectorMatchStatus.INSUFFICIENT_DATA,
            evaluate(RuntimeSelector.PackageId("org.example.Editor"), ambiguous)
        )
    }

    @Test
    fun `argument selectors parse option values without retaining raw values in evidence`() {
        val process = snapshot(
            argv = listOf(
                "/usr/bin/java",
                "--gameDir",
                "/Home/Focus",
                "--profile=Work Profile",
                "--accessToken",
                "secret-token-value"
            )
        )
        val context = context(process)

        assertEquals(
            SelectorMatchStatus.MATCH,
            evaluate(
                RuntimeSelector.ArgumentEquals(
                    "--gameDir",
                    "/Home/Focus",
                    SelectorValueKind.PATH
                ),
                context
            )
        )
        assertEquals(
            SelectorMatchStatus.MATCH,
            evaluate(
                RuntimeSelector.ArgumentEquals("--profile", "Work Profile"),
                context
            )
        )
        assertEquals(
            SelectorMatchStatus.NO_MATCH,
            evaluate(
                RuntimeSelector.ArgumentEquals("--gameDir", "/home/focus"),
                context
            )
        )
        assertEquals(
            SelectorMatchStatus.MATCH,
            evaluate(RuntimeSelector.ArgumentExists("--profile"), context)
        )
        assertEquals(
            SelectorMatchStatus.NO_MATCH,
            evaluate(RuntimeSelector.ArgumentExists("profile"), context)
        )

        val evidence = LinuxProcessSelectorMatcher.evaluate(
            predicate(
                RuntimeSelector.ArgumentEquals("--accessToken", "secret-token-value")
            ),
            context
        ).toString()
        assertFalse(evidence.contains("secret-token-value"))
        assertFalse(process.toString().contains("secret-token-value"))
    }

    @Test
    fun `missing argv is insufficient instead of a negative match`() {
        val context = context(snapshot(argvAvailable = false))

        assertEquals(
            SelectorMatchStatus.INSUFFICIENT_DATA,
            evaluate(RuntimeSelector.ArgumentExists("--gameDir"), context)
        )
        assertEquals(
            SelectorMatchStatus.INSUFFICIENT_DATA,
            evaluate(
                RuntimeSelector.ArgumentEquals("--gameDir", "/home/user/game"),
                context
            )
        )
    }

    @Test
    fun `Java main class parsing recognizes launcher forms and refuses guesses`() {
        val classPathProcess = snapshot(
            executablePath = "/usr/bin/java",
            argv = listOf(
                "/usr/bin/java",
                "-Xmx1g",
                "-Dprofile=private",
                "-classpath",
                "/opt/game/*",
                "org.example.Game",
                "--gameDir",
                "/home/user/game"
            )
        )
        val classPathMetadata = ProcessRuntimeMetadata.from(classPathProcess)
        assertEquals("org.example.Game", classPathMetadata.javaLaunch.mainClass)
        assertEquals(
            SelectorMatchStatus.MATCH,
            evaluate(
                RuntimeSelector.MainClass("org.example.Game"),
                context(classPathProcess)
            )
        )

        val moduleProcess = snapshot(
            argv = listOf("/usr/bin/java", "-m", "game.module/org.example.Main")
        )
        assertEquals(
            "org.example.Main",
            ProcessRuntimeMetadata.from(moduleProcess).javaLaunch.mainClass
        )

        val jarProcess = snapshot(argv = listOf("/usr/bin/java", "-jar", "game.jar"))
        val jarMetadata = ProcessRuntimeMetadata.from(jarProcess)
        assertNull(jarMetadata.javaLaunch.mainClass)
        assertFalse(jarMetadata.toString().contains("game.jar"))
        assertEquals(
            SelectorMatchStatus.INSUFFICIENT_DATA,
            evaluate(RuntimeSelector.MainClass("org.example.Game"), context(jarProcess))
        )

        val ambiguousProcess = snapshot(
            argv = listOf("/usr/bin/java", "-unknown-option", "org.example.Guess")
        )
        assertEquals(
            JavaLaunchMetadataState.MALFORMED_OR_AMBIGUOUS,
            ProcessRuntimeMetadata.from(ambiguousProcess).javaLaunch.state
        )
        assertEquals(
            SelectorMatchStatus.INSUFFICIENT_DATA,
            evaluate(
                RuntimeSelector.MainClass("org.example.Guess"),
                context(ambiguousProcess)
            )
        )
    }

    @Test
    fun `runtime family and execution environment are independent selector facts`() {
        val process = snapshot()
        val metadata = ProcessRuntimeMetadata.from(
            process,
            executionEnvironment = ExecutionEnvironment.FLATPAK
        )
        val context = ProcessSelectorContext(process, runtimeMetadata = metadata)

        assertEquals(
            SelectorMatchStatus.MATCH,
            evaluate(RuntimeSelector.RuntimeFamilyIs(RuntimeFamily.JAVA), context)
        )
        assertEquals(
            SelectorMatchStatus.MATCH,
            evaluate(
                RuntimeSelector.ExecutionEnvironmentIs(ExecutionEnvironment.FLATPAK),
                context
            )
        )
        assertEquals(
            SelectorMatchStatus.NO_MATCH,
            evaluate(
                RuntimeSelector.ExecutionEnvironmentIs(ExecutionEnvironment.NATIVE),
                context
            )
        )
        assertEquals(
            SelectorMatchStatus.INSUFFICIENT_DATA,
            evaluate(
                RuntimeSelector.ExecutionEnvironmentIs(ExecutionEnvironment.FLATPAK),
                context(snapshot(), environment = null)
            )
        )
    }

    @Test
    fun `attribution reports zero one and multiple candidates without confidence ranking`() {
        val context = context(snapshot(executablePath = "/usr/bin/editor"))
        val matching = candidate(
            "app-editor",
            predicate(RuntimeSelector.ExecutablePath("/usr/bin/editor")),
            confidence = DiscoveryConfidence.LOW
        )
        val nonMatching = candidate(
            "app-other",
            predicate(RuntimeSelector.ExecutablePath("/usr/bin/other")),
            confidence = DiscoveryConfidence.HIGH
        )

        val zero = LinuxProcessAttributor.attribute(context, listOf(nonMatching))
        assertEquals(ProcessAttributionStatus.NOT_MATCHED, zero.status)

        val one = LinuxProcessAttributor.attribute(context, listOf(matching))
        assertEquals(ProcessAttributionStatus.MATCHED_PRIMARY, one.status)
        assertEquals("app-editor", one.applicationReferenceId)
        assertEquals(
            DiscoveryConfidence.LOW,
            one.evidence.single().discoveryConfidence
        )

        val multiple = LinuxProcessAttributor.attribute(
            context,
            listOf(matching, matching.copy(applicationReferenceId = "app-editor-copy"))
        )
        assertEquals(ProcessAttributionStatus.AMBIGUOUS, multiple.status)
        assertNull(multiple.applicationReferenceId)
    }

    @Test
    fun `candidate with unresolved evidence prevents a false unique attribution`() {
        val context = context(
            snapshot(executablePath = "/usr/bin/editor", argvAvailable = false)
        )
        val matching = candidate(
            "app-editor",
            predicate(RuntimeSelector.ExecutablePath("/usr/bin/editor"))
        )
        val unresolved = candidate(
            "app-unknown",
            predicate(RuntimeSelector.ArgumentExists("--runtime-mode"))
        )

        val attribution = LinuxProcessAttributor.attribute(
            context,
            listOf(matching, unresolved)
        )

        assertEquals(ProcessAttributionStatus.INSUFFICIENT_DATA, attribution.status)
        assertNull(attribution.applicationReferenceId)
    }

    @Test
    fun `authorization distinguishes allow safe denial and unknown`() {
        val process = snapshot(executablePath = "/usr/bin/editor")
        val context = context(process)
        val matching = candidate(
            "app-editor",
            predicate(RuntimeSelector.ExecutablePath("/usr/bin/editor"))
        )
        val different = candidate(
            "app-other",
            predicate(RuntimeSelector.ExecutablePath("/usr/bin/other"))
        )

        assertEquals(
            AuthorizationOutcome.ALLOW,
            LinuxProcessAuthorizer.decide(context, listOf(matching)).outcome
        )
        val denial = LinuxProcessAuthorizer.decide(context, listOf(different))
        assertEquals(
            AuthorizationOutcome.DENY_AND_SAFE_TO_TERMINATE,
            denial.outcome
        )
        assertTrue(
            LinuxProcessAuthorizer.revalidateForDestructiveAction(
                denial,
                context,
                listOf(different)
            )
        )
        assertFalse(
            LinuxProcessAuthorizer.revalidateForDestructiveAction(
                denial,
                context(snapshot(executablePath = "/usr/bin/other", startTicks = 301L)),
                listOf(different)
            )
        )
        assertFalse(
            LinuxProcessAuthorizer.revalidateForDestructiveAction(
                denial,
                context(snapshot(executablePath = "/usr/bin/other")),
                listOf(different)
            )
        )

        val unknownIdentityContext = context(snapshot(startTicks = null))
        assertEquals(
            AuthorizationOutcome.UNKNOWN,
            LinuxProcessAuthorizer.decide(
                unknownIdentityContext,
                listOf(matching)
            ).outcome
        )
        assertEquals(
            AuthorizationOutcome.UNKNOWN,
            LinuxProcessAuthorizer.decide(context, emptyList()).outcome
        )
    }

    @Test
    fun `generic runtime rules stay unknown while app-specific selectors can allow`() {
        val context = context(snapshot())
        val genericJava = candidate(
            "app-java",
            predicate(RuntimeSelector.RuntimeFamilyIs(RuntimeFamily.JAVA))
        )

        val unconfirmed = LinuxProcessAuthorizer.decide(context, listOf(genericJava))
        assertEquals(AuthorizationOutcome.UNKNOWN, unconfirmed.outcome)
        assertEquals(
            AuthorizationReason.BROAD_SELECTOR_NOT_APPLICATION_SPECIFIC,
            unconfirmed.reason
        )

        val environmentOnly = candidate(
            "native-apps",
            predicate(
                RuntimeSelector.ExecutionEnvironmentIs(ExecutionEnvironment.NATIVE)
            )
        )
        assertEquals(
            AuthorizationOutcome.UNKNOWN,
            LinuxProcessAuthorizer.decide(context, listOf(environmentOnly)).outcome
        )
        val otherFamilyContext = context(
            snapshot(comm = "editor", executablePath = "/usr/bin/editor")
        )
        val otherFamilyRule = candidate(
            "other-family",
            predicate(RuntimeSelector.RuntimeFamilyIs(RuntimeFamily.OTHER))
        )
        assertEquals(
            AuthorizationOutcome.UNKNOWN,
            LinuxProcessAuthorizer.decide(otherFamilyContext, listOf(otherFamilyRule)).outcome
        )

        val specificContext = context(
            snapshot(argv = listOf("/usr/bin/java", "org.example.Game"))
        )
        val specificJava = candidate(
            "app-game",
            SelectorExpression.All(
                listOf(
                    predicate(RuntimeSelector.RuntimeFamilyIs(RuntimeFamily.JAVA)),
                    predicate(RuntimeSelector.MainClass("org.example.Game"))
                )
            )
        )
        assertEquals(
            AuthorizationOutcome.ALLOW,
            LinuxProcessAuthorizer.decide(specificContext, listOf(specificJava)).outcome
        )
    }

    @Test
    fun `unresolved or ambiguous attribution remains unknown`() {
        val context = context(
            snapshot(executablePath = "/usr/bin/editor", argvAvailable = false)
        )
        val matching = candidate(
            "app-editor",
            predicate(RuntimeSelector.ExecutablePath("/usr/bin/editor"))
        )
        val ambiguous = LinuxProcessAuthorizer.decide(
            context,
            listOf(matching, matching.copy(applicationReferenceId = "app-copy"))
        )
        assertEquals(AuthorizationOutcome.UNKNOWN, ambiguous.outcome)
        assertEquals(
            AuthorizationReason.AMBIGUOUS_ATTRIBUTION,
            ambiguous.reason
        )

        val unresolvedContext = context(snapshot(argvAvailable = false))
        val argumentRule = candidate(
            "app-with-argument",
            predicate(RuntimeSelector.ArgumentExists("--mode"))
        )
        assertEquals(
            AuthorizationOutcome.UNKNOWN,
            LinuxProcessAuthorizer.decide(
                unresolvedContext,
                listOf(argumentRule)
            ).outcome
        )
    }

    @Test
    fun `attribution rejects invalid enabled expression definitions`() {
        val result = LinuxProcessAttributor.attribute(
            context(snapshot()),
            listOf(candidate("empty", SelectorExpression.All(emptyList())))
        )

        assertEquals(ProcessAttributionStatus.INSUFFICIENT_DATA, result.status)
        assertTrue(result.evidence.single().evaluation.validationIssues.isNotEmpty())
    }

    private fun evaluate(
        selector: RuntimeSelector,
        context: ProcessSelectorContext
    ): SelectorMatchStatus =
        LinuxProcessSelectorMatcher.evaluate(predicate(selector), context).status

    private fun predicate(selector: RuntimeSelector) =
        SelectorExpression.Predicate(selector)

    private fun candidate(
        id: String,
        expression: SelectorExpression,
        confidence: DiscoveryConfidence = DiscoveryConfidence.HIGH
    ) = ApplicationRuntimeCandidate(
        applicationReferenceId = id,
        runtimeDefinitionId = "$id-runtime",
        expression = expression,
        discoveryConfidence = confidence
    )

    private fun context(
        process: LinuxProcessSnapshot,
        environment: ExecutionEnvironment? = ExecutionEnvironment.NATIVE,
        correlation: ProcessApplicationCorrelation = ProcessApplicationCorrelation()
    ): ProcessSelectorContext =
        ProcessSelectorContext(
            process = process,
            runtimeMetadata = ProcessRuntimeMetadata.from(process, environment),
            applicationCorrelation = correlation
        )

    private fun snapshot(
        pid: Long = 201L,
        startTicks: Long? = 300L,
        comm: String = "java",
        executablePath: String = "/usr/bin/java",
        argv: List<String> = listOf("/usr/bin/java", "-version"),
        argvAvailable: Boolean = true,
        workingDirectory: String? = "/home/user"
    ): LinuxProcessSnapshot {
        val statuses = LinuxProcessField.values().associateWith {
            LinuxProcessFieldStatus.AVAILABLE
        }.toMutableMap()
        if (startTicks == null) {
            statuses[LinuxProcessField.PROCESS_START_TICKS] =
                LinuxProcessFieldStatus.FIELD_UNAVAILABLE
            statuses[LinuxProcessField.INSTANCE_STABILITY] =
                LinuxProcessFieldStatus.FIELD_UNAVAILABLE
        }
        if (!argvAvailable) {
            statuses[LinuxProcessField.ARGV] = LinuxProcessFieldStatus.FIELD_UNAVAILABLE
        }
        if (workingDirectory == null) {
            statuses[LinuxProcessField.WORKING_DIRECTORY] =
                LinuxProcessFieldStatus.FIELD_UNAVAILABLE
        }
        return LinuxProcessSnapshot(
            pid = pid,
            processStartTicks = startTicks,
            uid = 1000L,
            parentPid = 1L,
            processGroupId = pid,
            sessionId = pid,
            comm = comm,
            executablePath = executablePath,
            executableBasename = executablePath.substringAfterLast('/'),
            argv = if (argvAvailable) argv else emptyList(),
            workingDirectory = workingDirectory,
            cgroupPath = "/user.slice",
            ioState = if (statuses.values.all { it == LinuxProcessFieldStatus.AVAILABLE }) {
                LinuxProcessIoState.AVAILABLE
            } else {
                LinuxProcessIoState.PARTIAL
            },
            fieldStatuses = statuses
        )
    }
}