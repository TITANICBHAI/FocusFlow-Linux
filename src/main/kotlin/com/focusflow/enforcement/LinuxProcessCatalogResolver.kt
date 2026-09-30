package com.focusflow.enforcement

import java.nio.file.Path
import java.util.Locale

enum class CatalogMatchStatus {
    EXACT,
    POSSIBLE,
    NONE,
    AMBIGUOUS
}

enum class CatalogEvidenceKind {
    VERIFIED_PACKAGE_INVOCATION,
    EXACT_EXECUTABLE_PATH,
    EXECUTABLE_BASENAME,
    PROCESS_NAME,
    PROCESS_ALIAS
}

data class CatalogMatchEvidence(
    val kind: CatalogEvidenceKind,
    val description: String
)

data class LinuxProcessCatalogCandidate(
    val catalogKey: String,
    val displayName: String,
    val desktopId: String?,
    val packageId: String?,
    val evidence: List<CatalogMatchEvidence>
)

/**
 * A display/discovery result only. It is deliberately not an authorization
 * decision and must never be used to decide whether a process can be killed.
 */
data class LinuxProcessCatalogResolution(
    val pid: Long,
    val status: CatalogMatchStatus,
    val candidates: List<LinuxProcessCatalogCandidate>
) {
    fun asSelectorCorrelation(): ProcessApplicationCorrelation {
        val candidate = candidates.singleOrNull()
        return when (status) {
            CatalogMatchStatus.EXACT -> ProcessApplicationCorrelation(
                desktopId = candidate?.desktopId,
                packageId = candidate?.packageId,
                status = ProcessCorrelationStatus.VERIFIED
            )
            CatalogMatchStatus.POSSIBLE -> ProcessApplicationCorrelation(
                desktopId = candidate?.desktopId,
                packageId = candidate?.packageId,
                status = ProcessCorrelationStatus.CANDIDATE
            )
            CatalogMatchStatus.AMBIGUOUS ->
                ProcessApplicationCorrelation(status = ProcessCorrelationStatus.AMBIGUOUS)
            CatalogMatchStatus.NONE ->
                ProcessApplicationCorrelation(status = ProcessCorrelationStatus.UNAVAILABLE)
        }
    }
}

/**
 * Correlates immutable procfs observations with the installed-app catalog.
 * Results preserve ambiguity and uncertainty; even EXACT is only catalog
 * evidence, not authorization to allow or terminate a process.
 */
object LinuxProcessCatalogResolver {
    private data class RankedCandidate(
        val app: AppDescriptor,
        val score: Int,
        val evidence: List<CatalogMatchEvidence>
    )

    fun resolve(
        process: LinuxProcessSnapshot,
        catalog: List<AppDescriptor>
    ): LinuxProcessCatalogResolution {
        val packageId = trustedPackageInvocation(process)
        val executablePath = process.executablePath.takeIf {
            process.fieldStatuses[LinuxProcessField.EXECUTABLE_PATH] ==
                LinuxProcessFieldStatus.AVAILABLE
        }
        val executableBasename = process.executableBasename
            ?.takeIf {
                process.fieldStatuses[LinuxProcessField.EXECUTABLE_PATH] ==
                    LinuxProcessFieldStatus.AVAILABLE
            }
            ?: executablePath?.let(::basename)
        val processName = process.comm
            ?.takeIf {
                process.fieldStatuses[LinuxProcessField.COMM] ==
                    LinuxProcessFieldStatus.AVAILABLE
            }

        val ranked = catalog.mapNotNull { app ->
            val evidence = buildList {
                if (packageId != null && app.packageId == packageId) {
                    add(
                        CatalogMatchEvidence(
                            CatalogEvidenceKind.VERIFIED_PACKAGE_INVOCATION,
                            "Recognized Flatpak or Snap run invocation matches package metadata"
                        )
                    )
                }
                if (
                    executablePath != null &&
                    executablePath.startsWith("/") &&
                    app.exePath?.startsWith("/") == true &&
                    executablePath == app.exePath
                ) {
                    add(
                        CatalogMatchEvidence(
                            CatalogEvidenceKind.EXACT_EXECUTABLE_PATH,
                            "Executable path matches exactly"
                        )
                    )
                }

                val catalogExecutable = app.exePath?.let(::basename)
                if (
                    executableBasename != null &&
                    catalogExecutable != null &&
                    executableBasename.equals(catalogExecutable, ignoreCase = true) &&
                    !isLauncherOrGenericRuntime(executableBasename)
                ) {
                    add(
                        CatalogMatchEvidence(
                            CatalogEvidenceKind.EXECUTABLE_BASENAME,
                            "Executable basename matches"
                        )
                    )
                }

                if (
                    processName != null &&
                    !LinuxProcessSafety.isGenericRuntimeProcessName(processName) &&
                    processName.equals(app.processName, ignoreCase = true)
                ) {
                    add(
                        CatalogMatchEvidence(
                            CatalogEvidenceKind.PROCESS_NAME,
                            "Observed process name matches"
                        )
                    )
                }

                if (
                    processName != null &&
                    !LinuxProcessSafety.isGenericRuntimeProcessName(processName) &&
                    app.processAliases.any { it.equals(processName, ignoreCase = true) }
                ) {
                    add(
                        CatalogMatchEvidence(
                            CatalogEvidenceKind.PROCESS_ALIAS,
                            "Observed process name matches a catalog alias"
                        )
                    )
                }
            }
            if (evidence.isEmpty()) {
                null
            } else {
                val score = when {
                    evidence.any {
                        it.kind == CatalogEvidenceKind.VERIFIED_PACKAGE_INVOCATION ||
                            it.kind == CatalogEvidenceKind.EXACT_EXECUTABLE_PATH
                    } -> 100
                    evidence.any { it.kind == CatalogEvidenceKind.EXECUTABLE_BASENAME } -> 60
                    evidence.any { it.kind == CatalogEvidenceKind.PROCESS_NAME } -> 50
                    else -> 40
                }
                RankedCandidate(app, score, evidence)
            }
        }

        if (ranked.isEmpty()) {
            return LinuxProcessCatalogResolution(process.pid, CatalogMatchStatus.NONE, emptyList())
        }

        val bestScore = ranked.maxOf { it.score }
        val best = ranked.filter { it.score == bestScore }
            .sortedWith(
                compareBy<RankedCandidate> { it.app.displayName.lowercase(Locale.ROOT) }
                    .thenBy { it.app.desktopId.orEmpty() }
                    .thenBy { it.app.packageId.orEmpty() }
            )
        val status = when {
            best.size > 1 -> CatalogMatchStatus.AMBIGUOUS
            bestScore >= 100 -> CatalogMatchStatus.EXACT
            else -> CatalogMatchStatus.POSSIBLE
        }
        return LinuxProcessCatalogResolution(
            pid = process.pid,
            status = status,
            candidates = best.map { candidate ->
                LinuxProcessCatalogCandidate(
                    catalogKey = catalogKey(candidate.app),
                    displayName = candidate.app.displayName,
                    desktopId = candidate.app.desktopId,
                    packageId = candidate.app.packageId,
                    evidence = candidate.evidence
                )
            }
        )
    }

    private fun trustedPackageInvocation(process: LinuxProcessSnapshot): String? {
        if (
            process.fieldStatuses[LinuxProcessField.ARGV] != LinuxProcessFieldStatus.AVAILABLE ||
            process.fieldStatuses[LinuxProcessField.EXECUTABLE_PATH] !=
                LinuxProcessFieldStatus.AVAILABLE
        ) {
            return null
        }
        val executable = process.executableBasename
            ?: process.executablePath?.let(::basename)
            ?: return null
        val argvExecutable = process.argv.firstOrNull()?.let(::basename) ?: return null
        if (!argvExecutable.equals(executable, ignoreCase = true)) return null
        if (executable.lowercase(Locale.ROOT) !in setOf("flatpak", "snap")) return null
        if (process.argv.getOrNull(1) != "run") return null
        return process.argv.getOrNull(2)
            ?.takeIf { SAFE_PACKAGE_ID.matches(it) }
    }

    private fun isLauncherOrGenericRuntime(name: String): Boolean {
        val normalized = name.substringAfterLast('/').lowercase(Locale.ROOT)
        return normalized in setOf("flatpak", "snap", "env") ||
            LinuxProcessSafety.isGenericRuntimeProcessName(normalized)
    }

    private fun basename(path: String): String? =
        runCatching { Path.of(path).fileName?.toString() }.getOrNull()

    private fun catalogKey(app: AppDescriptor): String =
        (app.desktopId ?: app.packageId ?: app.processName)
            .trim()
            .lowercase(Locale.ROOT)

    private val SAFE_PACKAGE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9_.+-]*$")
}