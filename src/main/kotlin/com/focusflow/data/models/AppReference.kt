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