package com.focusflow.enforcement

import com.focusflow.ProcessNameNormalizer
import com.focusflow.data.models.AppReferenceSource
import com.focusflow.data.models.AppResolutionStatus
import com.focusflow.data.models.CanonicalAppReference
import com.focusflow.data.models.RuntimeAuthorizationPurpose
import com.focusflow.data.models.RuntimeDefinition
import java.nio.file.Path
import java.util.UUID

enum class ManualLinuxTargetType(val label: String) {
    PROCESS_NAME("Process name"),
    EXECUTABLE_PATH("Executable path"),
    COMMAND_PREDICATE("Structured command"),
    DESKTOP_ID("Desktop ID"),
    PACKAGE_ID("Package ID")
}

data class ManualLinuxTargetInput(
    val type: ManualLinuxTargetType,
    val value: String = "",
    val executable: String = "",
    val argumentName: String = "",
    val argumentValue: String = ""
)

data class ManualLinuxAppTarget(
    val descriptor: AppDescriptor,
    val reference: CanonicalAppReference
)

data class ManualLinuxTargetParseResult(
    val target: ManualLinuxAppTarget? = null,
    val errorMessage: String? = null
)

/**
 * Builds explicit reference selectors from user-entered target fields. It
 * parses values only; it does not inspect the filesystem or process table.
 */
object ManualLinuxAppTargetParser {
    fun parse(
        input: ManualLinuxTargetInput,
        referenceId: String = "foc-${UUID.randomUUID()}"
    ): ManualLinuxTargetParseResult {
        val value = input.value.trim()
        val processName: String?
        val desktopId: String?
        val packageId: String?
        val executablePath: String?
        val selectorExpression: SelectorExpression
        val displayName: String

        when (input.type) {
            ManualLinuxTargetType.PROCESS_NAME -> {
                processName = ProcessNameNormalizer.normalizeManual(value)
                    ?: return invalid("Enter a process name.")
                val restriction = LinuxProcessSafety.manualTargetRestrictionReason(processName)
                if (restriction != null) return invalid(restriction)
                if (LinuxProcessSafety.isProtectedProcessName(processName)) {
                    return invalid("This system process is protected and cannot be selected.")
                }
                selectorExpression =
                    SelectorExpression.Predicate(RuntimeSelector.ProcessName(processName))
                desktopId = null
                packageId = null
                executablePath = null
                displayName = processName
            }
            ManualLinuxTargetType.EXECUTABLE_PATH -> {
                if (!isAbsolutePath(value)) {
                    return invalid("Enter an absolute executable path.")
                }
                processName = null
                desktopId = null
                packageId = null
                executablePath = value
                selectorExpression =
                    SelectorExpression.Predicate(RuntimeSelector.ExecutablePath(value))
                displayName = "Executable: ${Path.of(value).fileName ?: value}"
            }
            ManualLinuxTargetType.DESKTOP_ID -> {
                if (!isSafeIdentifier(value)) return invalid("Enter a desktop-entry ID.")
                processName = null
                desktopId = value
                packageId = null
                executablePath = null
                selectorExpression =
                    SelectorExpression.Predicate(RuntimeSelector.DesktopId(value))
                displayName = "Desktop: $value"
            }
            ManualLinuxTargetType.PACKAGE_ID -> {
                if (!SAFE_PACKAGE_ID.matches(value)) return invalid("Enter a valid package ID.")
                processName = null
                desktopId = null
                packageId = value
                executablePath = null
                selectorExpression =
                    SelectorExpression.Predicate(RuntimeSelector.PackageId(value))
                displayName = "Package: $value"
            }
            ManualLinuxTargetType.COMMAND_PREDICATE -> {
                val executableValue = input.executable.trim()
                val argumentName = input.argumentName.trim()
                if (executableValue.isBlank() && argumentName.isBlank()) {
                    return invalid("Add an executable or an argument predicate.")
                }
                if (executableValue.isNotBlank() && executableValue.contains('\u0000')) {
                    return invalid("Executable cannot contain a null character.")
                }
                if (
                    executableValue.isNotBlank() &&
                    !executableValue.startsWith("/") &&
                    !SAFE_EXECUTABLE_BASENAME.matches(executableValue)
                ) {
                    return invalid("Use one executable basename, not a shell command.")
                }
                if (argumentName.isNotBlank() && !SAFE_ARGUMENT_NAME.matches(argumentName)) {
                    return invalid("Use one argument name, such as --gameDir.")
                }

                val selectors = buildList {
                    if (executableValue.isNotBlank()) {
                        add(
                            SelectorExpression.Predicate(
                                if (executableValue.startsWith("/")) {
                                    if (!isAbsolutePath(executableValue)) {
                                        return invalid("Enter a valid absolute executable path.")
                                    }
                                    RuntimeSelector.ExecutablePath(executableValue)
                                } else {
                                    RuntimeSelector.ExecutableBasename(executableValue)
                                }
                            )
                        )
                    }
                    if (argumentName.isNotBlank()) {
                        val expected = input.argumentValue.takeIf { it.isNotEmpty() }
                        add(
                            SelectorExpression.Predicate(
                                if (expected == null) {
                                    RuntimeSelector.ArgumentExists(argumentName)
                                } else {
                                    RuntimeSelector.ArgumentEquals(
                                        name = argumentName,
                                        expectedValue = expected,
                                        valueKind = if (expected.startsWith("/")) {
                                            SelectorValueKind.PATH
                                        } else {
                                            SelectorValueKind.TEXT
                                        }
                                    )
                                }
                            )
                        )
                    }
                }
                processName = null
                desktopId = null
                packageId = null
                executablePath = executableValue.takeIf { it.startsWith("/") }
                selectorExpression = SelectorExpression.All(selectors)
                displayName = buildString {
                    append("Command target")
                    if (executableValue.isNotBlank()) append(": ${Path.of(executableValue).fileName ?: executableValue}")
                    if (argumentName.isNotBlank()) append(" · $argumentName")
                }
            }
        }

        val stableAppId = when {
            desktopId != null -> "desktop:$desktopId"
            packageId != null -> "package:$packageId"
            executablePath != null -> "path:$executablePath"
            processName != null -> "process:${processName.lowercase()}"
            else -> "target:$referenceId"
        }
        val reference = CanonicalAppReference(
            referenceId = referenceId,
            stableAppId = stableAppId,
            displayName = displayName,
            legacyProcessName = processName,
            primaryProcessName = processName,
            processAliases = listOfNotNull(processName),
            source = AppReferenceSource.MANUAL,
            resolutionStatus = AppResolutionStatus.RESOLVED,
            runtimeDefinitions = listOf(
                RuntimeDefinition(
                    id = "$referenceId:primary",
                    referenceId = referenceId,
                    role = RuntimeRole.PRIMARY,
                    selector = selectorExpression,
                    executionEnvironment = ExecutionEnvironment.UNKNOWN,
                    runtimeFamily = null,
                    authorizationPurpose = RuntimeAuthorizationPurpose.PRIMARY_RUNTIME
                )
            )
        )
        val descriptor = AppDescriptor(
            processName = processName.orEmpty(),
            displayName = displayName,
            isRunning = false,
            exePath = executablePath,
            desktopId = desktopId,
            packageId = packageId,
            processAliases = listOfNotNull(processName),
            source = AppSource.MANUAL,
            canonicalReference = reference
        )
        return ManualLinuxTargetParseResult(
            target = ManualLinuxAppTarget(descriptor, reference)
        )
    }

    private fun isAbsolutePath(value: String): Boolean =
        value.isNotBlank() &&
            !value.contains('\u0000') &&
            runCatching { Path.of(value).isAbsolute }.getOrDefault(false)

    private fun isSafeIdentifier(value: String): Boolean =
        value.isNotBlank() && !value.contains('\u0000') && value.none(Char::isWhitespace)

    private fun invalid(message: String) =
        ManualLinuxTargetParseResult(errorMessage = message)

    private val SAFE_PACKAGE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9_.+-]*$")
    private val SAFE_EXECUTABLE_BASENAME = Regex("^[A-Za-z0-9][A-Za-z0-9_.+@-]*$")
    private val SAFE_ARGUMENT_NAME = Regex("^-{1,2}[A-Za-z0-9][A-Za-z0-9_-]*$")
}

/**
 * Converts a catalog app into an additive canonical reference for picker
 * consumers. Each selection receives its own FocusFlow-owned ID; the stable
 * external app identity remains in stableAppId.
 */
fun AppDescriptor.toCanonicalAppReference(
    referenceId: String = "foc-${UUID.randomUUID()}"
): CanonicalAppReference {
    canonicalReference?.let { return it }
    val selector = when {
        packageId != null -> RuntimeSelector.PackageId(packageId)
        desktopId != null -> RuntimeSelector.DesktopId(desktopId)
        exePath?.startsWith("/") == true -> RuntimeSelector.ExecutablePath(exePath)
        processName.isNotBlank() -> RuntimeSelector.ProcessName(processName)
        else -> null
    }
    val sourceValue = when (source) {
        AppSource.NATIVE_DESKTOP -> AppReferenceSource.CATALOG_NATIVE
        AppSource.FLATPAK -> AppReferenceSource.CATALOG_FLATPAK
        AppSource.SNAP -> AppReferenceSource.CATALOG_SNAP
        AppSource.WINDOWS_REGISTRY -> AppReferenceSource.WINDOWS_REGISTRY
        AppSource.RUNNING_ONLY -> AppReferenceSource.RUNNING_ONLY
        AppSource.MANUAL -> AppReferenceSource.MANUAL
    }
    val environment = when (source) {
        AppSource.FLATPAK -> ExecutionEnvironment.FLATPAK
        AppSource.SNAP -> ExecutionEnvironment.SNAP
        else -> ExecutionEnvironment.UNKNOWN
    }
    return CanonicalAppReference(
        referenceId = referenceId,
        stableAppId = desktopId ?: packageId,
        displayName = displayName,
        legacyProcessName = processName.takeIf { it.isNotBlank() },
        primaryProcessName = processName.takeIf { it.isNotBlank() },
        processAliases = processAliases,
        source = sourceValue,
        resolutionStatus = if (source == AppSource.MANUAL) {
            AppResolutionStatus.UNRESOLVED
        } else {
            AppResolutionStatus.RESOLVED
        },
        runtimeDefinitions = selector?.let {
            listOf(
                RuntimeDefinition(
                    id = "$referenceId:primary",
                    referenceId = referenceId,
                    role = RuntimeRole.PRIMARY,
                    selector = SelectorExpression.Predicate(it),
                    executionEnvironment = environment,
                    runtimeFamily = null,
                    authorizationPurpose = RuntimeAuthorizationPurpose.PRIMARY_RUNTIME
                )
            )
        }.orEmpty()
    )
}