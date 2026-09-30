package com.focusflow.enforcement

import com.focusflow.data.models.AppResolutionStatus
import com.focusflow.data.models.CanonicalAppReference

data class LauncherSessionProcessAssociation(
    val processInstanceKey: ProcessInstanceKey,
    val applicationReferenceId: String,
    val observationFingerprint: String
)

/**
 * Converts selected canonical references into the only Linux launcher allow
 * inputs. Legacy process names are intentionally not adapted here.
 */
object FocusLauncherRuntimePolicy {
    fun selectorContext(process: LinuxProcessSnapshot): ProcessSelectorContext {
        val catalog = InstalledAppCatalog.state.value.apps
        val correlation = LinuxProcessCatalogResolver.resolve(process, catalog)
            .asSelectorCorrelation()
        return ProcessSelectorContext(
            process = process,
            applicationCorrelation = correlation
        )
    }

    fun candidates(
        selectedReferences: Collection<CanonicalAppReference>
    ): List<ApplicationRuntimeCandidate> =
        selectedReferences
            .asSequence()
            .filter { reference ->
                reference.referenceId.isNotBlank() &&
                    reference.resolutionStatus == AppResolutionStatus.RESOLVED &&
                    reference.conflictStatus == "none"
            }
            .flatMap { reference ->
                reference.runtimeDefinitions.asSequence()
                    .filter { definition ->
                        definition.enabled &&
                            definition.referenceId == reference.referenceId &&
                            definition.id.isNotBlank()
                    }
                    .map { definition ->
                        ApplicationRuntimeCandidate(
                            applicationReferenceId = reference.referenceId,
                            runtimeDefinitionId = definition.id,
                            expression = definition.selector,
                            role = definition.role,
                            schemaVersion = definition.schemaVersion
                        )
                    }
            }
            .distinctBy { it.applicationReferenceId to it.runtimeDefinitionId }
            .toList()

    /**
     * Process-instance associations are session-local and require both a known
     * PID/start-time key and one definite application attribution.
     */
    fun association(
        decision: ProcessAuthorizationDecision,
        process: LinuxProcessSnapshot
    ): LauncherSessionProcessAssociation? {
        if (decision.outcome != AuthorizationOutcome.ALLOW) return null
        val key = decision.processInstanceKey ?: return null
        val observedKey = (process.processInstanceIdentity as? ProcessInstanceIdentity.Known)
            ?.key
            ?: return null
        if (key != observedKey) return null
        val referenceId = decision.attribution.applicationReferenceId
            ?.takeIf(String::isNotBlank)
            ?: return null
        if (
            decision.attribution.status !in setOf(
                ProcessAttributionStatus.MATCHED_PRIMARY,
                ProcessAttributionStatus.MATCHED_HELPER,
                ProcessAttributionStatus.MATCHED_LAUNCHER
            )
        ) return null
        return LauncherSessionProcessAssociation(
            processInstanceKey = key,
            applicationReferenceId = referenceId,
            observationFingerprint = process.fingerprint
        )
    }
}