package com.focusflow.data.models

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

/**
 * The future stable app-reference shape. Existing tables still store their
 * process compatibility value until a later schema migration; this contract
 * prevents desktop/application ID from becoming the only enforcement key.
 */
data class CanonicalAppReference(
    val stableId: String? = null,
    val displayName: String? = null,
    val primaryProcessName: String,
    val processAliases: List<String> = emptyList(),
    val source: AppReferenceSource,
    val desktopFilePath: String? = null,
    val packageId: String? = null,
    val lastResolvedAtMs: Long? = null,
    val resolutionStatus: AppResolutionStatus
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
)