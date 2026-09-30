package com.focusflow.data.models

import com.focusflow.enforcement.ExecutionEnvironment
import com.focusflow.enforcement.RuntimeFamily
import com.focusflow.enforcement.RuntimeRole
import com.focusflow.enforcement.SelectorExpression
import com.focusflow.enforcement.SelectorExpressionCodec

/**
 * Persisted app-reference provenance. Resolution status is separate because a
 * catalog reference can become stale while a manual reference can remain
 * usable without ever having a catalog record.
 */
enum class AppReferenceSource(val wireValue: String) {
    CATALOG_NATIVE("catalog_native"),
    CATALOG_FLATPAK("catalog_flatpak"),
    CATALOG_SNAP("catalog_snap"),
    WINDOWS_REGISTRY("windows_registry"),
    RUNNING_ONLY("running_only"),
    MANUAL("manual"),
    LEGACY("legacy")
}

enum class AppResolutionStatus(val wireValue: String) {
    RESOLVED("resolved"),
    STALE("stale"),
    UNRESOLVED("unresolved"),
    AMBIGUOUS("ambiguous")
}

enum class RuntimeAuthorizationPurpose(val wireValue: String) {
    PRIMARY_RUNTIME("primary_runtime"),
    RECOGNIZED_HELPER("recognized_helper"),
    LAUNCH_HANDOFF_ONLY("launch_handoff_only")
}

/**
 * A runtime selector is distinct from the way its application is launched.
 * The selector schema version travels with the recursive expression.
 */
data class RuntimeDefinition(
    val id: String,
    val referenceId: String,
    val role: RuntimeRole,
    val selector: SelectorExpression,
    val executionEnvironment: ExecutionEnvironment,
    val runtimeFamily: RuntimeFamily?,
    val authorizationPurpose: RuntimeAuthorizationPurpose,
    val enabled: Boolean = true,
    val schemaVersion: Int = SelectorExpressionCodec.CURRENT_SCHEMA_VERSION
)

/**
 * Launch metadata is structured and deliberately independent from runtime
 * selectors. argv is stored as ordered arguments, never as a shell command.
 */
data class LaunchDefinition(
    val id: String,
    val referenceId: String,
    val type: String,
    val executablePath: String? = null,
    val executable: String? = null,
    val argv: List<String> = emptyList(),
    val workingDirectory: String? = null,
    val desktopFilePath: String? = null,
    val desktopId: String? = null,
    val packageId: String? = null,
    val dbusActivatable: Boolean = false,
    val handoffPolicy: String = "none"
)

/**
 * Canonical metadata for one FocusFlow-owned reference. The internal ID is
 * always present; external identity and all process-name compatibility fields
 * are optional. Legacy process values remain separate from runtime selectors.
 */
data class CanonicalAppReference(
    val referenceId: String,
    val stableAppId: String? = null,
    val displayName: String? = null,
    val legacyProcessName: String? = null,
    val primaryProcessName: String? = null,
    val processAliases: List<String> = emptyList(),
    val source: AppReferenceSource,
    val resolutionStatus: AppResolutionStatus,
    val runtimeDefinitions: List<RuntimeDefinition> = emptyList(),
    val launchDefinitionId: String? = null,
    val conflictStatus: String = "none",
    val conflictGroupKey: String? = null,
    val lastResolvedAtMs: Long? = null
)

/**
 * Owner/position binding for a canonical reference. Existing process-bearing
 * owners continue to use StoredAppReference as their compatibility adapter.
 */
data class StoredCanonicalAppReference(
    val ownerType: String,
    val ownerId: String,
    val position: Int,
    val reference: CanonicalAppReference
)

/**
 * A row from the additive app-reference sidecar.
 *
 * The owner and position identify which legacy source value this reference
 * belongs to. Repositories use this shape to read new sidecar data while
 * retaining a fallback path for databases created before the sidecar existed.
 */
data class StoredAppReference(
    val id: String,
    val ownerType: String,
    val ownerId: String,
    val position: Int,
    val legacyProcessName: String,
    val stableAppId: String?,
    val displayName: String?,
    val primaryProcessName: String,
    val processAliases: List<String> = emptyList(),
    val source: AppReferenceSource,
    val resolutionStatus: AppResolutionStatus,
    val lastResolvedAtMs: Long?,
    val conflictStatus: String = "none",
    val conflictGroupKey: String? = null
) {
    /** Existing sidecar row IDs are FocusFlow-owned stable reference IDs. */
    val referenceId: String get() = id
}