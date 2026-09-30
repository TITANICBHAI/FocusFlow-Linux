package com.focusflow.enforcement

import com.focusflow.ProcessNameNormalizer
import com.focusflow.ProcessPlatform

enum class LinuxLegacyTargetStatus {
    MATCH,
    NO_MATCH,
    UNKNOWN
}

/**
 * Result of interpreting legacy process-name settings against the canonical
 * procfs observation. Selector evidence and process-instance identity stay
 * separate: a name match with unknown start ticks is still not killable.
 */
data class LinuxLegacyTargetEvaluation(
    val status: LinuxLegacyTargetStatus,
    val selectorStatus: SelectorMatchStatus,
    val processIdentityKnown: Boolean,
    val matchedTargetNames: Set<String> = emptySet(),
    val unresolvedTargetNames: Set<String> = emptySet()
) {
    val mayEnforce: Boolean
        get() = status == LinuxLegacyTargetStatus.MATCH && processIdentityKnown
}

/**
 * The single compatibility boundary for old process-name rules on Linux.
 *
 * Persisted names are normalized using the existing Linux compatibility rules,
 * then compared only through the typed selector matcher. This keeps legacy
 * process-name settings usable without creating another process matcher.
 */
object LinuxProcessCompatibilityAdapter {
    fun evaluate(
        process: LinuxProcessSnapshot,
        legacyTargetNames: Iterable<String>
    ): LinuxLegacyTargetEvaluation {
        val identityKnown =
            process.processInstanceIdentity is ProcessInstanceIdentity.Known
        val targets = ProcessNameNormalizer.normalizeStoredList(
            legacyTargetNames,
            ProcessPlatform.LINUX
        )
        if (targets.isEmpty()) {
            return LinuxLegacyTargetEvaluation(
                status = if (identityKnown) {
                    LinuxLegacyTargetStatus.NO_MATCH
                } else {
                    LinuxLegacyTargetStatus.UNKNOWN
                },
                selectorStatus = SelectorMatchStatus.NO_MATCH,
                processIdentityKnown = identityKnown
            )
        }

        val context = ProcessSelectorContext(process)
        val matched = linkedSetOf<String>()
        val unresolved = linkedSetOf<String>()
        targets.forEach { target ->
            val expression = SelectorExpression.Any(
                listOf(
                    SelectorExpression.Predicate(RuntimeSelector.ProcessName(target)),
                    SelectorExpression.Predicate(RuntimeSelector.ExecutableBasename(target))
                )
            )
            when (
                LinuxProcessSelectorMatcher.evaluate(
                    expression,
                    context,
                    includeAllEvidence = true
                ).status
            ) {
                SelectorMatchStatus.MATCH -> matched += target
                SelectorMatchStatus.INSUFFICIENT_DATA -> unresolved += target
                SelectorMatchStatus.NO_MATCH -> Unit
            }
        }

        val selectorStatus = when {
            matched.isNotEmpty() -> SelectorMatchStatus.MATCH
            unresolved.isNotEmpty() -> SelectorMatchStatus.INSUFFICIENT_DATA
            else -> SelectorMatchStatus.NO_MATCH
        }
        val status = when {
            !identityKnown || selectorStatus == SelectorMatchStatus.INSUFFICIENT_DATA ->
                LinuxLegacyTargetStatus.UNKNOWN
            selectorStatus == SelectorMatchStatus.MATCH -> LinuxLegacyTargetStatus.MATCH
            else -> LinuxLegacyTargetStatus.NO_MATCH
        }
        return LinuxLegacyTargetEvaluation(
            status = status,
            selectorStatus = selectorStatus,
            processIdentityKnown = identityKnown,
            matchedTargetNames = matched,
            unresolvedTargetNames = unresolved
        )
    }

    /**
     * A destructive action must still refer to the same fully observed process
     * instance and the legacy rule must match the fresh observation.
     */
    fun revalidateMatch(
        expected: LinuxProcessSnapshot,
        current: LinuxProcessSnapshot,
        legacyTargetNames: Iterable<String>
    ): Boolean =
        isSameKnownProcessObservation(expected, current) &&
            evaluate(current, legacyTargetNames).mayEnforce
}

/**
 * Emits bounded, deduplicated decision diagnostics without including argv,
 * selector values, window titles, or other command-line material.
 */
internal object LinuxProcessDecisionDiagnostics {
    private val lock = Any()
    private val lastMessageByIdentity = linkedMapOf<String, String>()
    private const val MAX_REMEMBERED_IDENTITIES = 256

    fun authorizationMessage(
        decision: ProcessAuthorizationDecision,
        process: LinuxProcessSnapshot
    ): String {
        val identity = identityLabel(process)
        return "outcome=${decision.outcome} reason=${decision.reason} " +
            "attribution=${decision.attribution.status} identity=$identity " +
            "candidateCount=${decision.attribution.evidence.size}"
    }

    fun legacyTargetMessage(
        evaluation: LinuxLegacyTargetEvaluation,
        process: LinuxProcessSnapshot
    ): String {
        val outcome = when {
            evaluation.status == LinuxLegacyTargetStatus.NO_MATCH -> "ALLOW"
            evaluation.mayEnforce -> "TARGET_MATCH"
            else -> "UNKNOWN"
        }
        val reason = when {
            !evaluation.processIdentityKnown -> "UNKNOWN_PROCESS_INSTANCE"
            evaluation.selectorStatus == SelectorMatchStatus.INSUFFICIENT_DATA ->
                "LEGACY_SELECTOR_DATA_INSUFFICIENT"
            evaluation.selectorStatus == SelectorMatchStatus.MATCH ->
                "LEGACY_TARGET_MATCH"
            else -> "NO_LEGACY_TARGET_MATCH"
        }
        return "outcome=$outcome reason=$reason selector=${evaluation.selectorStatus} " +
            "identity=${identityLabel(process)}"
    }

    fun recordAuthorization(
        component: String,
        decision: ProcessAuthorizationDecision,
        process: LinuxProcessSnapshot
    ) {
        recordOnce(component, process, authorizationMessage(decision, process))
    }

    fun recordLegacyTarget(
        component: String,
        evaluation: LinuxLegacyTargetEvaluation,
        process: LinuxProcessSnapshot
    ) {
        recordOnce(component, process, legacyTargetMessage(evaluation, process))
    }

    fun recordAggregate(component: String, key: String, message: String) {
        recordOnce("$component:$key", component, message)
    }

    private fun recordOnce(
        component: String,
        process: LinuxProcessSnapshot,
        message: String
    ) {
        val identity = when (val value = process.processInstanceIdentity) {
            is ProcessInstanceIdentity.Known -> value.key.toString()
            is ProcessInstanceIdentity.Unknown -> "unknown-pid:${value.pid}"
        }
        recordOnce("$component:$identity", component, message)
    }

    private fun recordOnce(cacheKey: String, component: String, message: String) {
        synchronized(lock) {
            if (lastMessageByIdentity[cacheKey] == message) return
            lastMessageByIdentity[cacheKey] = message
            while (lastMessageByIdentity.size > MAX_REMEMBERED_IDENTITIES) {
                val eldest = lastMessageByIdentity.keys.firstOrNull() ?: break
                lastMessageByIdentity.remove(eldest)
            }
        }
        EnforcementLog.info(component, message)
    }

    private fun identityLabel(process: LinuxProcessSnapshot): String =
        when (val identity = process.processInstanceIdentity) {
            is ProcessInstanceIdentity.Known ->
                "KNOWN(pid=${identity.key.pid},startTicks=${identity.key.processStartTicks})"
            is ProcessInstanceIdentity.Unknown ->
                "UNKNOWN(pid=${identity.pid},reason=${identity.reason})"
        }
}