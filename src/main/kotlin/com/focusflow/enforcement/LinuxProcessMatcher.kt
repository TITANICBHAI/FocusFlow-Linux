package com.focusflow.enforcement

import java.util.Locale

enum class SelectorMatchStatus {
    MATCH,
    NO_MATCH,
    INSUFFICIENT_DATA
}

enum class ProcessCorrelationStatus {
    VERIFIED,
    CANDIDATE,
    AMBIGUOUS,
    UNAVAILABLE
}

/**
 * Catalog facts are supplied by a resolver and remain separate from raw
 * procfs observations. Only VERIFIED values can satisfy catalog selectors.
 */
data class ProcessApplicationCorrelation(
    val desktopId: String? = null,
    val packageId: String? = null,
    val status: ProcessCorrelationStatus = ProcessCorrelationStatus.UNAVAILABLE
)

data class ProcessSelectorContext(
    val process: LinuxProcessSnapshot,
    val runtimeMetadata: ProcessRuntimeMetadata = ProcessRuntimeMetadata.from(process),
    val applicationCorrelation: ProcessApplicationCorrelation =
        ProcessApplicationCorrelation()
)

/**
 * Evidence is intentionally descriptive rather than a copy of observed argv.
 * In particular, ARGUMENT_EQUALS never retains or displays the observed value.
 */
data class SelectorMatchEvidence(
    val selectorType: RuntimeSelectorType,
    val description: String,
    val matched: Boolean
)

data class SelectorEvaluation(
    val status: SelectorMatchStatus,
    val evidence: List<SelectorMatchEvidence> = emptyList(),
    val unresolvedSelectors: List<RuntimeSelectorType> = emptyList(),
    val validationIssues: List<String> = emptyList()
)

object LinuxProcessSelectorMatcher {
    /**
     * Empty ALL evaluates true and empty ANY evaluates false. Persisted enabled
     * definitions should additionally pass RuntimeSelectorDefinition validation.
     */
    fun evaluate(
        expression: SelectorExpression,
        context: ProcessSelectorContext,
        includeAllEvidence: Boolean = false
    ): SelectorEvaluation =
        evaluateExpression(expression, context, includeAllEvidence)

    private fun evaluateExpression(
        expression: SelectorExpression,
        context: ProcessSelectorContext,
        includeAllEvidence: Boolean
    ): SelectorEvaluation {
        return when (expression) {
            is SelectorExpression.Predicate -> evaluateSelector(expression.selector, context)
            is SelectorExpression.All -> {
                var hasInsufficient = false
                var hasNoMatch = false
                val evidence = mutableListOf<SelectorMatchEvidence>()
                val unresolved = mutableListOf<RuntimeSelectorType>()
                for (child in expression.children) {
                    val result = evaluateExpression(child, context, includeAllEvidence)
                    evidence += result.evidence
                    unresolved += result.unresolvedSelectors
                    if (result.status == SelectorMatchStatus.INSUFFICIENT_DATA) {
                        hasInsufficient = true
                    }
                    if (result.status == SelectorMatchStatus.NO_MATCH) {
                        hasNoMatch = true
                        if (!includeAllEvidence) {
                            return SelectorEvaluation(
                                SelectorMatchStatus.NO_MATCH,
                                evidence,
                                unresolved
                            )
                        }
                    }
                }
                SelectorEvaluation(
                    if (hasNoMatch) {
                        SelectorMatchStatus.NO_MATCH
                    } else if (hasInsufficient) {
                        SelectorMatchStatus.INSUFFICIENT_DATA
                    } else {
                        SelectorMatchStatus.MATCH
                    },
                    evidence,
                    unresolved
                )
            }
            is SelectorExpression.Any -> {
                var hasInsufficient = false
                var hasMatch = false
                val evidence = mutableListOf<SelectorMatchEvidence>()
                val unresolved = mutableListOf<RuntimeSelectorType>()
                for (child in expression.children) {
                    val result = evaluateExpression(child, context, includeAllEvidence)
                    evidence += result.evidence
                    unresolved += result.unresolvedSelectors
                    if (result.status == SelectorMatchStatus.INSUFFICIENT_DATA) {
                        hasInsufficient = true
                    }
                    if (result.status == SelectorMatchStatus.MATCH) {
                        hasMatch = true
                        if (!includeAllEvidence) {
                            return SelectorEvaluation(
                                SelectorMatchStatus.MATCH,
                                evidence,
                                unresolved
                            )
                        }
                    }
                }
                SelectorEvaluation(
                    if (hasMatch) {
                        SelectorMatchStatus.MATCH
                    } else if (hasInsufficient) {
                        SelectorMatchStatus.INSUFFICIENT_DATA
                    } else {
                        SelectorMatchStatus.NO_MATCH
                    },
                    evidence,
                    unresolved
                )
            }
        }
    }

    private fun evaluateSelector(
        selector: RuntimeSelector,
        context: ProcessSelectorContext
    ): SelectorEvaluation {
        val process = context.process
        val outcome: SelectorMatchStatus = when (selector) {
            is RuntimeSelector.ProcessName ->
                compareObserved(
                    process.comm,
                    process.fieldStatuses[LinuxProcessField.COMM],
                    selector.value,
                    ignoreCase = true
                )
            is RuntimeSelector.ExecutableBasename ->
                compareObserved(
                    process.executableBasename,
                    process.fieldStatuses[LinuxProcessField.EXECUTABLE_PATH],
                    selector.value,
                    ignoreCase = true
                )
            is RuntimeSelector.ExecutablePath ->
                compareObserved(
                    process.executablePath,
                    process.fieldStatuses[LinuxProcessField.EXECUTABLE_PATH],
                    selector.value
                )
            is RuntimeSelector.DesktopId ->
                compareCorrelation(
                    context.applicationCorrelation.desktopId,
                    context.applicationCorrelation.status,
                    selector.value
                )
            is RuntimeSelector.PackageId ->
                compareCorrelation(
                    context.applicationCorrelation.packageId,
                    context.applicationCorrelation.status,
                    selector.value
                )
            is RuntimeSelector.ArgumentExists ->
                evaluateArgumentExists(selector.name, process)
            is RuntimeSelector.ArgumentEquals ->
                evaluateArgumentEquals(selector, process)
            is RuntimeSelector.MainClass -> {
                val metadata = context.runtimeMetadata
                when {
                    metadata.runtimeFamily == RuntimeFamily.UNKNOWN ->
                        SelectorMatchStatus.INSUFFICIENT_DATA
                    metadata.runtimeFamily != RuntimeFamily.JAVA ->
                        SelectorMatchStatus.NO_MATCH
                    metadata.javaLaunch.mainClass != null ->
                        if (metadata.javaLaunch.mainClass == selector.value) {
                            SelectorMatchStatus.MATCH
                        } else {
                            SelectorMatchStatus.NO_MATCH
                        }
                    metadata.javaLaunch.state ==
                        JavaLaunchMetadataState.MALFORMED_OR_AMBIGUOUS ||
                        metadata.javaLaunch.state ==
                        JavaLaunchMetadataState.UNAVAILABLE ||
                        metadata.javaLaunch.state ==
                        JavaLaunchMetadataState.JAR_LAUNCH_WITHOUT_KNOWN_MAIN_CLASS ||
                        metadata.javaLaunch.state ==
                        JavaLaunchMetadataState.MODULE_LAUNCH_WITHOUT_KNOWN_MAIN_CLASS ->
                        SelectorMatchStatus.INSUFFICIENT_DATA
                    else -> SelectorMatchStatus.NO_MATCH
                }
            }
            is RuntimeSelector.WorkingDirectory ->
                compareObserved(
                    process.workingDirectory,
                    process.fieldStatuses[LinuxProcessField.WORKING_DIRECTORY],
                    selector.value
                )
            is RuntimeSelector.RuntimeFamilyIs -> {
                if (context.runtimeMetadata.runtimeFamily == RuntimeFamily.UNKNOWN) {
                    SelectorMatchStatus.INSUFFICIENT_DATA
                } else if (context.runtimeMetadata.runtimeFamily == selector.value) {
                    SelectorMatchStatus.MATCH
                } else {
                    SelectorMatchStatus.NO_MATCH
                }
            }
            is RuntimeSelector.ExecutionEnvironmentIs -> {
                val observedEnvironment = context.runtimeMetadata.executionEnvironment
                when {
                    observedEnvironment == null ||
                        observedEnvironment == ExecutionEnvironment.UNKNOWN ->
                        SelectorMatchStatus.INSUFFICIENT_DATA
                    observedEnvironment == selector.value -> SelectorMatchStatus.MATCH
                    else -> SelectorMatchStatus.NO_MATCH
                }
            }
        }
        val description = selector.safeDescription()
        return SelectorEvaluation(
            status = outcome,
            evidence = listOf(
                SelectorMatchEvidence(
                    selectorType = selector.type,
                    description = description,
                    matched = outcome == SelectorMatchStatus.MATCH
                )
            ),
            unresolvedSelectors = if (outcome == SelectorMatchStatus.INSUFFICIENT_DATA) {
                listOf(selector.type)
            } else {
                emptyList()
            }
        )
    }

    private fun compareObserved(
        observed: String?,
        fieldStatus: LinuxProcessFieldStatus?,
        expected: String,
        ignoreCase: Boolean = false
    ): SelectorMatchStatus {
        if (fieldStatus != LinuxProcessFieldStatus.AVAILABLE || observed == null) {
            return if (observed == null && fieldStatus == LinuxProcessFieldStatus.AVAILABLE) {
                SelectorMatchStatus.NO_MATCH
            } else {
                SelectorMatchStatus.INSUFFICIENT_DATA
            }
        }
        return if (observed.equals(expected, ignoreCase = ignoreCase)) {
            SelectorMatchStatus.MATCH
        } else {
            SelectorMatchStatus.NO_MATCH
        }
    }

    private fun compareCorrelation(
        observed: String?,
        status: ProcessCorrelationStatus,
        expected: String
    ): SelectorMatchStatus {
        if (status != ProcessCorrelationStatus.VERIFIED) {
            return SelectorMatchStatus.INSUFFICIENT_DATA
        }
        if (observed == null) return SelectorMatchStatus.NO_MATCH
        return if (observed == expected) {
            SelectorMatchStatus.MATCH
        } else {
            SelectorMatchStatus.NO_MATCH
        }
    }

    private fun evaluateArgumentExists(
        name: String,
        process: LinuxProcessSnapshot
    ): SelectorMatchStatus {
        if (process.fieldStatuses[LinuxProcessField.ARGV] != LinuxProcessFieldStatus.AVAILABLE) {
            return SelectorMatchStatus.INSUFFICIENT_DATA
        }
        val exists = process.argv.any { token ->
            token == name || token.startsWith("$name=")
        }
        return if (exists) SelectorMatchStatus.MATCH else SelectorMatchStatus.NO_MATCH
    }

    private fun evaluateArgumentEquals(
        selector: RuntimeSelector.ArgumentEquals,
        process: LinuxProcessSnapshot
    ): SelectorMatchStatus {
        if (process.fieldStatuses[LinuxProcessField.ARGV] != LinuxProcessFieldStatus.AVAILABLE) {
            return SelectorMatchStatus.INSUFFICIENT_DATA
        }
        val observedValues = mutableListOf<String>()
        var missingValue = false
        process.argv.forEachIndexed { index, token ->
            when {
                token == selector.name -> {
                    val value = process.argv.getOrNull(index + 1)
                    if (value == null) {
                        missingValue = true
                    } else {
                        observedValues += value
                    }
                }
                token.startsWith("${selector.name}=") ->
                    observedValues += token.substring(selector.name.length + 1)
            }
        }
        if (observedValues.any { it == selector.expectedValue }) {
            return SelectorMatchStatus.MATCH
        }
        if (missingValue) return SelectorMatchStatus.INSUFFICIENT_DATA
        return SelectorMatchStatus.NO_MATCH
    }

    private fun RuntimeSelector.safeDescription(): String = when (this) {
        is RuntimeSelector.ProcessName -> "process name matches '$value'"
        is RuntimeSelector.ExecutableBasename -> "executable basename matches '$value'"
        is RuntimeSelector.ExecutablePath -> "executable path matches '$value'"
        is RuntimeSelector.DesktopId -> "verified desktop ID matches '$value'"
        is RuntimeSelector.PackageId -> "verified package ID matches '$value'"
        is RuntimeSelector.ArgumentExists -> "argv contains option '$name'"
        is RuntimeSelector.ArgumentEquals ->
            "argv option '$name' equals configured ${valueKind.name.lowercase()} value"
        is RuntimeSelector.MainClass -> "Java main class matches '$value'"
        is RuntimeSelector.WorkingDirectory -> "working directory matches '$value'"
        is RuntimeSelector.RuntimeFamilyIs -> "runtime family matches ${value.wireName}"
        is RuntimeSelector.ExecutionEnvironmentIs ->
            "execution environment matches ${value.wireName}"
    }
}

enum class RuntimeRole {
    PRIMARY,
    HELPER,
    LAUNCHER
}

enum class DiscoveryConfidence {
    HIGH,
    MEDIUM,
    LOW,
    UNKNOWN
}

data class ApplicationRuntimeCandidate(
    val applicationReferenceId: String,
    val runtimeDefinitionId: String,
    val expression: SelectorExpression,
    val role: RuntimeRole = RuntimeRole.PRIMARY,
    val discoveryConfidence: DiscoveryConfidence = DiscoveryConfidence.UNKNOWN,
    val enabled: Boolean = true,
    val schemaVersion: Int = SelectorExpressionCodec.CURRENT_SCHEMA_VERSION
)

enum class ProcessAttributionStatus {
    MATCHED_PRIMARY,
    MATCHED_HELPER,
    MATCHED_LAUNCHER,
    NOT_MATCHED,
    AMBIGUOUS,
    INSUFFICIENT_DATA
}

data class CandidateAttributionEvidence(
    val applicationReferenceId: String,
    val runtimeDefinitionId: String,
    val role: RuntimeRole,
    val discoveryConfidence: DiscoveryConfidence,
    val evaluation: SelectorEvaluation
)

data class ProcessAttributionResult(
    val status: ProcessAttributionStatus,
    val applicationReferenceId: String? = null,
    val runtimeDefinitionId: String? = null,
    val evidence: List<CandidateAttributionEvidence> = emptyList()
)

object LinuxProcessAttributor {
    fun attribute(
        context: ProcessSelectorContext,
        candidates: List<ApplicationRuntimeCandidate>
    ): ProcessAttributionResult {
        val evidence = candidates.filter { it.enabled }.map { candidate ->
            val validation = buildList {
                if (candidate.applicationReferenceId.isBlank()) {
                    add("applicationReferenceId cannot be blank")
                }
                if (candidate.runtimeDefinitionId.isBlank()) {
                    add("runtimeDefinitionId cannot be blank")
                }
                addAll(
                    RuntimeSelectorDefinition(
                        expression = candidate.expression,
                        enabled = true,
                        schemaVersion = candidate.schemaVersion
                    ).validationErrors()
                )
            }
            val evaluation = if (validation.isEmpty()) {
                LinuxProcessSelectorMatcher.evaluate(
                    candidate.expression,
                    context,
                    includeAllEvidence = true
                )
            } else {
                SelectorEvaluation(
                    status = SelectorMatchStatus.INSUFFICIENT_DATA,
                    validationIssues = validation
                )
            }
            CandidateAttributionEvidence(
                applicationReferenceId = candidate.applicationReferenceId,
                runtimeDefinitionId = candidate.runtimeDefinitionId,
                role = candidate.role,
                discoveryConfidence = candidate.discoveryConfidence,
                evaluation = evaluation
            )
        }
        val matches = evidence.filter {
            it.evaluation.status == SelectorMatchStatus.MATCH
        }
        val hasUnknownCandidate = evidence.any {
            it.evaluation.status == SelectorMatchStatus.INSUFFICIENT_DATA
        }
        if (matches.size > 1) {
            return ProcessAttributionResult(
                status = ProcessAttributionStatus.AMBIGUOUS,
                evidence = evidence
            )
        }
        if (matches.size == 1 && hasUnknownCandidate) {
            return ProcessAttributionResult(
                status = ProcessAttributionStatus.INSUFFICIENT_DATA,
                evidence = evidence
            )
        }
        if (matches.size == 1) {
            val match = matches.single()
            return ProcessAttributionResult(
                status = when (match.role) {
                    RuntimeRole.PRIMARY -> ProcessAttributionStatus.MATCHED_PRIMARY
                    RuntimeRole.HELPER -> ProcessAttributionStatus.MATCHED_HELPER
                    RuntimeRole.LAUNCHER -> ProcessAttributionStatus.MATCHED_LAUNCHER
                },
                applicationReferenceId = match.applicationReferenceId,
                runtimeDefinitionId = match.runtimeDefinitionId,
                evidence = evidence
            )
        }
        return ProcessAttributionResult(
            status = if (hasUnknownCandidate) {
                ProcessAttributionStatus.INSUFFICIENT_DATA
            } else {
                ProcessAttributionStatus.NOT_MATCHED
            },
            evidence = evidence
        )
    }
}

enum class AuthorizationOutcome {
    ALLOW,
    DENY_AND_SAFE_TO_TERMINATE,
    UNKNOWN
}

enum class AuthorizationReason {
    SELECTED_APPLICATION_MATCH,
    NO_SELECTED_APPLICATION_MATCH,
    NO_ENABLED_APPLICATION_RULES,
    AMBIGUOUS_ATTRIBUTION,
    INSUFFICIENT_ATTRIBUTION_DATA,
    UNKNOWN_PROCESS_INSTANCE,
    BROAD_SELECTOR_NOT_APPLICATION_SPECIFIC
}

data class ProcessAuthorizationDecision(
    val outcome: AuthorizationOutcome,
    val reason: AuthorizationReason,
    val processInstanceKey: ProcessInstanceKey? = null,
    val attribution: ProcessAttributionResult
)

object LinuxProcessAuthorizer {
    private val genericRuntimeNames = setOf(
        "java", "javaw", "kotlin", "python", "python2", "python3", "node",
        "nodejs", "bash", "sh", "zsh", "ruby", "dotnet", "mono"
    )

    fun decide(
        context: ProcessSelectorContext,
        candidates: List<ApplicationRuntimeCandidate>
    ): ProcessAuthorizationDecision {
        val attribution = LinuxProcessAttributor.attribute(context, candidates)
        val identity = context.process.processInstanceIdentity
            as? ProcessInstanceIdentity.Known
        if (identity == null) {
            return ProcessAuthorizationDecision(
                outcome = AuthorizationOutcome.UNKNOWN,
                reason = AuthorizationReason.UNKNOWN_PROCESS_INSTANCE,
                attribution = attribution
            )
        }

        return when (attribution.status) {
            ProcessAttributionStatus.MATCHED_PRIMARY,
            ProcessAttributionStatus.MATCHED_HELPER,
            ProcessAttributionStatus.MATCHED_LAUNCHER -> {
                val matchedCandidate = candidates.firstOrNull {
                    it.enabled &&
                        it.applicationReferenceId == attribution.applicationReferenceId &&
                        it.runtimeDefinitionId == attribution.runtimeDefinitionId
                }
                if (
                    matchedCandidate != null &&
                    containsBroadNonApplicationBranch(matchedCandidate.expression)
                ) {
                    ProcessAuthorizationDecision(
                        outcome = AuthorizationOutcome.UNKNOWN,
                        reason = AuthorizationReason.BROAD_SELECTOR_NOT_APPLICATION_SPECIFIC,
                        processInstanceKey = identity.key,
                        attribution = attribution
                    )
                } else {
                    ProcessAuthorizationDecision(
                        outcome = AuthorizationOutcome.ALLOW,
                        reason = AuthorizationReason.SELECTED_APPLICATION_MATCH,
                        processInstanceKey = identity.key,
                        attribution = attribution
                    )
                }
            }
            ProcessAttributionStatus.NOT_MATCHED -> {
                if (candidates.none { it.enabled }) {
                    ProcessAuthorizationDecision(
                        outcome = AuthorizationOutcome.UNKNOWN,
                        reason = AuthorizationReason.NO_ENABLED_APPLICATION_RULES,
                        processInstanceKey = identity.key,
                        attribution = attribution
                    )
                } else {
                    ProcessAuthorizationDecision(
                        outcome = AuthorizationOutcome.DENY_AND_SAFE_TO_TERMINATE,
                        reason = AuthorizationReason.NO_SELECTED_APPLICATION_MATCH,
                        processInstanceKey = identity.key,
                        attribution = attribution
                    )
                }
            }
            ProcessAttributionStatus.AMBIGUOUS ->
                ProcessAuthorizationDecision(
                    outcome = AuthorizationOutcome.UNKNOWN,
                    reason = AuthorizationReason.AMBIGUOUS_ATTRIBUTION,
                    processInstanceKey = identity.key,
                    attribution = attribution
                )
            ProcessAttributionStatus.INSUFFICIENT_DATA ->
                ProcessAuthorizationDecision(
                    outcome = AuthorizationOutcome.UNKNOWN,
                    reason = AuthorizationReason.INSUFFICIENT_ATTRIBUTION_DATA,
                    processInstanceKey = identity.key,
                    attribution = attribution
                )
        }
    }

    /**
     * Destructive enforcement is eligible only after a fresh known identity and
     * a second complete authorization decision against the current observation.
     */
    fun revalidateForDestructiveAction(
        priorDecision: ProcessAuthorizationDecision,
        currentContext: ProcessSelectorContext,
        candidates: List<ApplicationRuntimeCandidate>
    ): Boolean {
        if (priorDecision.outcome != AuthorizationOutcome.DENY_AND_SAFE_TO_TERMINATE) {
            return false
        }
        val expectedKey = priorDecision.processInstanceKey ?: return false
        val currentIdentity = currentContext.process.processInstanceIdentity
            as? ProcessInstanceIdentity.Known
            ?: return false
        if (currentIdentity.key != expectedKey) return false

        val currentDecision = decide(currentContext, candidates)
        return currentDecision.outcome == AuthorizationOutcome.DENY_AND_SAFE_TO_TERMINATE &&
            currentDecision.processInstanceKey == expectedKey
    }

    private fun containsBroadNonApplicationBranch(expression: SelectorExpression): Boolean =
        when (expression) {
            is SelectorExpression.Predicate -> when (val selector = expression.selector) {
                is RuntimeSelector.ProcessName -> isGenericRuntimeName(selector.value)
                is RuntimeSelector.ExecutableBasename -> isGenericRuntimeName(selector.value)
                is RuntimeSelector.RuntimeFamilyIs -> selector.value != RuntimeFamily.UNKNOWN
                is RuntimeSelector.ExecutionEnvironmentIs -> true
                else -> false
            }
            is SelectorExpression.All ->
                expression.children.isNotEmpty() &&
                    expression.children.all(::containsBroadNonApplicationBranch)
            is SelectorExpression.Any ->
                expression.children.any(::containsBroadNonApplicationBranch)
        }

    private fun isGenericRuntimeName(value: String): Boolean {
        val normalized = value.lowercase(Locale.ROOT).removeSuffix(".exe")
        return normalized in genericRuntimeNames ||
            normalized.matches(Regex("python[0-9.]*"))
    }
}